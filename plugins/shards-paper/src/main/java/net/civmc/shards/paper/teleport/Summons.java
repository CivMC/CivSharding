package net.civmc.shards.paper.teleport;

import java.util.logging.Logger;
import net.civmc.shards.api.PlayerLocation;
import net.civmc.shards.api.PlayerSummonMessage;
import net.civmc.shards.paper.border.ShardBorder;
import net.civmc.shards.paper.border.TransferService;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Sending somebody who is not here somewhere else, because another shard asked.
 *
 * <p>The other half of {@link NetworkTeleport}. Sending yourself to somebody far away is a handover,
 * and a handover is something a server does to a player standing in front of it. Sending
 * <em>somebody else</em> who is on another shard is not: they are being played by that server, which
 * alone can read them, write them back and let them go. So the command works by asking, and this is
 * the asking arriving.</p>
 *
 * <p>Every shard hears every summons and almost all of them find it is about nobody here, which costs
 * a lookup. The one that has them does one of three things: steps them onto the person they were sent
 * to if that person is standing here, puts them down on this shard's own ground if that is where they
 * were sent, or hands them over exactly as walking over a border would.</p>
 *
 * <p>Nothing is refused on the grounds of who asked. A summons can only come from another shard of
 * this network, and whether the person who typed it was allowed to was settled where they typed it -
 * duplicating that check here would mean this server having an opinion about another shard's
 * permissions, which it cannot hold correctly.</p>
 */
public final class Summons {

    private final JavaPlugin plugin;
    private final ShardBorder border;
    private final TransferService transfers;
    private final FollowingSomebody following;
    private final Logger logger;

    public Summons(final JavaPlugin plugin, final ShardBorder border, final TransferService transfers,
                   final FollowingSomebody following, final Logger logger) {
        this.plugin = plugin;
        this.border = border;
        this.transfers = transfers;
        this.following = following;
        this.logger = logger;
    }

    /**
     * Arrives on a broker thread, so nothing is looked at until the next tick.
     */
    public void receive(final PlayerSummonMessage summons) {
        Bukkit.getScheduler().runTask(this.plugin, () -> act(summons));
    }

    private void act(final PlayerSummonMessage summons) {
        final Player player = Bukkit.getPlayer(summons.playerUuid());
        if (player == null) {
            // Not ours. This is the ordinary answer for every shard but one
            return;
        }
        if (player.isDead()) {
            // Mid-death, with no position worth reading and a respawn of their own about to decide
            // where they are. Moving them now would be moving a corpse
            this.logger.info("Ignoring a summons for " + player.getName() + ": they are dead");
            return;
        }
        if (this.transfers.isInTransit(player.getUniqueId())) {
            // Already on their way somewhere. Starting another handover for a player this server has
            // given up is the one thing a crossing must never do
            this.logger.info("Ignoring a summons for " + player.getName() + ": they are already crossing");
            return;
        }
        final Player standingHere = summons.follow() == null ? null : Bukkit.getPlayer(summons.follow());
        if (standingHere != null) {
            // The person they were sent to is on this shard, so where they are standing right now is
            // a better answer than the coordinates that travelled with the summons
            player.teleport(standingHere.getLocation(), PlayerTeleportEvent.TeleportCause.PLUGIN);
            said(player, summons);
            return;
        }
        final Location here = asLocal(summons.to());
        if (here != null && !this.border.isOutside(here)) {
            player.teleport(here, PlayerTeleportEvent.TeleportCause.PLUGIN);
            said(player, summons);
            return;
        }
        if (summons.follow() != null) {
            this.following.intend(player, summons.follow(), summons.followName());
        }
        this.logger.info(player.getName() + " is being sent to " + summons.to().world() + " "
            + Math.round(summons.to().x()) + "," + Math.round(summons.to().z()) + " at "
            + summons.serverName() + "'s asking");
        if (!this.transfers.transferTo(player, summons.to())) {
            this.following.forget(player);
            return;
        }
        said(player, summons);
        // The handover can still be refused after it starts, and a player who is refused keeps
        // playing here with an intention written on them
        this.following.forgetIfTheyStay(this.plugin, player,
            () -> this.transfers.isInTransit(player.getUniqueId()));
    }

    /**
     * The same place, as a location in this server's world, or null when it has no such world.
     *
     * <p>A neighbour's world may not exist here at all, which is not a fault: the handover path takes
     * the place by name and coordinates for exactly that reason. It only means this server cannot
     * decide whether the destination is its own ground, and something that is not its own ground is
     * handed over.</p>
     */
    private static Location asLocal(final PlayerLocation to) {
        final World world = Bukkit.getWorld(to.world());
        return world == null ? null : new Location(world, to.x(), to.y(), to.z());
    }

    /**
     * Tells somebody they have been moved, and by whom.
     *
     * <p>Said because being picked up and put down with no explanation is indistinguishable from the
     * server misbehaving - the shard they are on is not the shard the command was typed on, so they
     * did not see it typed and there is nothing else anywhere that would tell them.</p>
     */
    private static void said(final Player player, final PlayerSummonMessage summons) {
        if (!summons.askedBy().isEmpty()) {
            player.sendMessage(summons.askedBy() + " has teleported you");
        }
    }
}
