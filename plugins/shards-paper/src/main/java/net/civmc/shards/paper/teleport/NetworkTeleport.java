package net.civmc.shards.paper.teleport;

import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.UUID;
import java.util.function.Consumer;
import net.civmc.shards.api.PlayerLocateRequest;
import net.civmc.shards.api.PlayerLocateResponse;
import net.civmc.shards.api.PlayerLocation;
import net.civmc.shards.api.PlayerSummonMessage;
import net.civmc.shards.paper.border.TransferService;
import net.civmc.shards.paper.rabbitmq.ShardsClient;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Teleporting to somebody who is not on this shard.
 *
 * <p>A shard sees only the people connected to it, so {@code /tp somebody} on a sharded network is
 * not refused - it fails to find anybody to refuse. From in the game a player who is plainly online,
 * and whose name is in the tab list, simply does not exist. That is what this fixes, and it is the
 * only way of crossing a border that a player asks for by name rather than by walking.</p>
 *
 * <p>Two steps, because two servers are involved. The proxy is asked which shard the name is on,
 * since it holds every connection and this server holds one shard's worth. Then the player is handed
 * over by the same machinery a walk uses, carrying an intention for the far side to finish - see
 * {@link FollowingSomebody} for why the last step cannot be done from here.</p>
 *
 * <p>Where they are put down is the proxy's hint about where the other player was when their data was
 * last written back, which is up to a checkpoint out of date. That is deliberately not the whole
 * answer: it lands the arrival on the right shard and roughly the right place, and the arrival
 * itself then steps onto the person, who by then is a player standing in the same world.</p>
 */
public final class NetworkTeleport {

    private static final Component COULD_NOT_ASK =
        Component.text("Could not find out where they are right now", NamedTextColor.RED);
    private static final Component COULD_NOT_GO =
        Component.text("You could not be sent to them just now", NamedTextColor.RED);
    // The game's own words for a name nobody answers to, because that is what this is: the proxy has
    // looked at every connection on the network and found no such person
    private static final Component NOT_FOUND =
        Component.text("No player was found", NamedTextColor.RED);

    private final JavaPlugin plugin;
    private final ShardsClient client;
    private final TransferService transfers;
    private final FollowingSomebody following;
    private final String serverName;
    private final Logger logger;

    public NetworkTeleport(final JavaPlugin plugin, final ShardsClient client, final TransferService transfers,
                           final FollowingSomebody following, final String serverName, final Logger logger) {
        this.plugin = plugin;
        this.client = client;
        this.transfers = transfers;
        this.following = following;
        this.serverName = serverName;
        this.logger = logger;
    }

    /**
     * Sends {@code traveller} to whoever is called {@code name}, wherever on the network that is.
     *
     * <p>Answers nothing here: the proxy has to be asked first, so everything this says to the player
     * is said a fraction of a second later, back on the main thread.</p>
     */
    public void sendTo(final Player traveller, final String name) {
        this.client.locate(PlayerLocateRequest.create(this.serverName, name))
            // Back onto the main thread: what happens next reads players and starts a handover
            .whenComplete((response, error) -> Bukkit.getScheduler().runTask(this.plugin,
                () -> go(traveller, name, response, error)));
    }

    /**
     * Sends whoever is called {@code whoName} to whoever is called {@code destinationName}.
     *
     * <p>Four shapes of the same command, decided by where the two of them turn out to be. Only one of
     * them - both of them here - is left to the game, and this is never asked about that one.</p>
     */
    public void sendTo(final Player asker, final String whoName, final String destinationName) {
        locate(destinationName, destination -> {
            if (badAnswer(asker, destination)) {
                return;
            }
            locate(whoName, who -> {
                if (badAnswer(asker, who)) {
                    return;
                }
                sendToPerson(asker, who, destination);
            });
        });
    }

    /**
     * Sends whoever is called {@code whoName} to a place, wherever on the network they are.
     *
     * <p>Only reached for somebody who is not on this shard: one who is can be teleported by the game
     * itself, and a destination past the edge is then a crossing like any other, which the border
     * already handles.</p>
     */
    public void sendTo(final Player asker, final String whoName, final Location to) {
        locate(whoName, who -> {
            if (badAnswer(asker, who)) {
                return;
            }
            final Player here = Bukkit.getPlayer(who.playerUuid());
            if (here != null) {
                here.teleport(to);
                return;
            }
            summon(asker, who, place(to), null, "");
        });
    }

    private void sendToPerson(final Player asker, final PlayerLocateResponse who,
                              final PlayerLocateResponse destination) {
        final Player whoIsHere = Bukkit.getPlayer(who.playerUuid());
        final Player destinationIsHere = Bukkit.getPlayer(destination.playerUuid());
        if (whoIsHere != null && destinationIsHere != null) {
            // Both on this shard after all: somebody arrived between the asking and the answer
            whoIsHere.teleport(destinationIsHere.getLocation());
            return;
        }
        if (whoIsHere != null) {
            // Here, and going elsewhere: an ordinary handover, with the last step left to the shard
            // they land on
            cross(whoIsHere, destination);
            return;
        }
        if (destinationIsHere != null) {
            // The destination is standing in front of us, so where they are now is the answer - and
            // the person being moved is somebody else's to move
            summon(asker, who, place(destinationIsHere.getLocation()), destination.playerUuid(),
                destination.playerName());
            return;
        }
        if (destination.location() == null) {
            // Neither of them is here, and nothing can say where the destination is standing closely
            // enough to aim at. It fixes itself within a checkpoint, and guessing would put somebody
            // down in the wrong place on the wrong shard
            asker.sendMessage(Component.text("Cannot work out where " + destination.playerName()
                + " is standing just now; try again in a moment", NamedTextColor.RED));
            return;
        }
        // Neither is here. The summons carries the place and the person both, so if the two of them
        // are on one shard that shard steps one onto the other exactly
        summon(asker, who, destination.location(), destination.playerUuid(), destination.playerName());
    }

    /**
     * Asks whoever has this player to send them somewhere.
     */
    private void summon(final Player asker, final PlayerLocateResponse who, final PlayerLocation to,
                        final UUID follow, final String followName) {
        this.logger.info(asker.getName() + " is having " + who.playerName() + " on " + who.shardName()
            + " sent to " + to.world() + " " + Math.round(to.x()) + "," + Math.round(to.z()));
        this.client.publishSummon(PlayerSummonMessage.create(this.serverName, who.playerUuid(), to,
            follow, followName, asker.getName()));
        asker.sendMessage(Component.text("Asked " + who.shardName() + " to send " + who.playerName()
            + " on their way", NamedTextColor.GRAY));
    }

    private static PlayerLocation place(final Location location) {
        return new PlayerLocation(location.getWorld().getName(), location.getX(), location.getY(),
            location.getZ());
    }

    /**
     * Asks the proxy where somebody is, and carries on with the answer on the main thread.
     */
    private void locate(final String name, final Consumer<PlayerLocateResponse> then) {
        this.client.locate(PlayerLocateRequest.create(this.serverName, name))
            .whenComplete((response, error) -> Bukkit.getScheduler().runTask(this.plugin, () -> {
                if (error != null || response == null || response.failed()) {
                    this.logger.log(Level.WARNING, "Could not ask the proxy where " + name + " is", error);
                    then.accept(null);
                    return;
                }
                then.accept(response);
            }));
    }

    /**
     * Whether that answer is one to stop on, saying so to whoever asked.
     */
    private boolean badAnswer(final Player asker, final PlayerLocateResponse answer) {
        if (!asker.isOnline()) {
            return true;
        }
        if (answer == null) {
            asker.sendMessage(COULD_NOT_ASK);
            return true;
        }
        if (!answer.isOnAShard()) {
            asker.sendMessage(NOT_FOUND);
            return true;
        }
        return false;
    }

    private void go(final Player traveller, final String name, final PlayerLocateResponse response,
                    final Throwable error) {
        if (!traveller.isOnline()) {
            return;
        }
        if (error != null || response == null || response.failed()) {
            this.logger.log(Level.WARNING, "Could not ask the proxy where " + name + " is", error);
            traveller.sendMessage(COULD_NOT_ASK);
            return;
        }
        if (!response.isOnAShard()) {
            traveller.sendMessage(NOT_FOUND);
            return;
        }
        final Player here = Bukkit.getPlayer(response.playerUuid());
        if (here != null) {
            // They were on this shard all along, or have just arrived on it. Nothing to cross
            traveller.teleport(here.getLocation());
            return;
        }
        if (this.serverName.equals(response.shardName())) {
            // This shard according to the proxy, and not here according to this server. They are
            // between two servers - handed over a moment ago, or arriving now
            traveller.sendMessage(Component.text(response.playerName() + " is on the move; try again",
                NamedTextColor.GRAY));
            return;
        }
        cross(traveller, response);
    }

    /**
     * Hands somebody who is standing here over to the shard another person is on, to be stepped onto
     * them when they land.
     */
    private void cross(final Player traveller, final PlayerLocateResponse destination) {
        this.following.intend(traveller, destination.playerUuid(), destination.playerName());
        this.logger.info(traveller.getName() + " is being sent to " + destination.playerName() + " on "
            + destination.shardName());
        final boolean started = destination.location() == null
            // No usable landing spot, so that shard's own arrival logic decides where they appear and
            // the step onto the player is the whole of the teleport
            ? this.transfers.transferToShard(traveller, destination.shardName())
            : this.transfers.transferTo(traveller, destination.location());
        if (!started) {
            // Said out loud and taken back off them: the handover did not start, so nothing will ever
            // read the intention, and it would otherwise fire on their next crossing
            this.following.forget(traveller);
            traveller.sendMessage(COULD_NOT_GO);
            return;
        }
        // And taken back off them if it started and then came to nothing: a refused crossing leaves
        // them standing here with an intention written on them
        this.following.forgetIfTheyStay(this.plugin, traveller,
            () -> this.transfers.isInTransit(traveller.getUniqueId()));
    }
}
