package net.civmc.shards.paper.teleport;

import java.util.Locale;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Makes {@code /tp somebody} mean somebody on the network, and finishes the ones that arrive here.
 *
 * <p>The command is caught before it runs rather than replaced. A shard is a Minecraft server with
 * all of its own commands, and taking over {@code /tp} outright would mean reimplementing every form
 * of it to gain the few that do not work. So only the forms with somebody in them that this server
 * has never heard of are taken - which is the case the game answers with "no player was found"
 * however plainly online that person is:</p>
 *
 * <ul>
 *   <li>{@code /tp them} - the asker to a person elsewhere. A handover.</li>
 *   <li>{@code /tp them somebody} - a person to a person, with either or both of them elsewhere.</li>
 *   <li>{@code /tp them x y z} - a person elsewhere to a place.</li>
 * </ul>
 *
 * <p>Everything else falls through untouched: coordinates for the asker themselves, which the border
 * already turns into a crossing wherever they land; selectors, which mean what is on this server;
 * anybody moving somebody who is standing here, which the game can do perfectly well; and all of
 * those typed by somebody without the permission for it, which the command refuses.</p>
 */
public final class NetworkTeleportListener implements Listener {

    private static final String TELEPORT_PERMISSION = "minecraft.command.teleport";

    private final JavaPlugin plugin;
    private final NetworkTeleport teleports;
    private final FollowingSomebody following;
    private final Logger logger;

    public NetworkTeleportListener(final JavaPlugin plugin, final NetworkTeleport teleports,
                                   final FollowingSomebody following, final Logger logger) {
        this.plugin = plugin;
        this.teleports = teleports;
        this.following = following;
        this.logger = logger;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(final PlayerCommandPreprocessEvent event) {
        final String[] words = event.getMessage().trim().split("\\s+");
        if (words.length < 2 || !isTeleport(words[0])) {
            return;
        }
        if (!event.getPlayer().hasPermission(TELEPORT_PERMISSION)) {
            // Left to the command to refuse, so there is one answer to "may I" rather than this
            // having an opinion of its own
            return;
        }
        switch (words.length) {
            case 2 -> toSomebody(event, words[1]);
            case 3 -> takeSomebody(event, words[1], words[2]);
            case 5 -> takeSomebodyTo(event, words[1], words[2], words[3], words[4]);
            // Four is a place for the player themselves, and the border already turns that into a
            // crossing wherever it lands. Anything longer is a form of the command this does not
            // know, and a form it does not know is one the game should answer
            default -> {
            }
        }
    }

    /**
     * {@code /tp somebody} - the asker to a person.
     */
    private void toSomebody(final PlayerCommandPreprocessEvent event, final String name) {
        if (!isAName(name) || Bukkit.getPlayerExact(name) != null) {
            // A selector, a coordinate, or somebody standing right here, which the ordinary command
            // does - and does without a broker round trip
            return;
        }
        event.setCancelled(true);
        this.teleports.sendTo(event.getPlayer(), name);
    }

    /**
     * {@code /tp somebody somebody-else} - a person to a person, neither of them necessarily here.
     */
    private void takeSomebody(final PlayerCommandPreprocessEvent event, final String whoName,
                              final String destinationName) {
        if (!isAName(whoName) || !isAName(destinationName)) {
            return;
        }
        if (Bukkit.getPlayerExact(whoName) != null && Bukkit.getPlayerExact(destinationName) != null) {
            // Both on this shard, so this is an ordinary teleport and none of it needs a network
            return;
        }
        event.setCancelled(true);
        this.teleports.sendTo(event.getPlayer(), whoName, destinationName);
    }

    /**
     * {@code /tp somebody x y z} - a person to a place.
     *
     * <p>Left entirely alone for somebody who is on this shard, even when the place is not: the game
     * teleports them, and the border turns a destination past the edge into a crossing. It is only
     * somebody who is <em>not</em> here that the game cannot move at all.</p>
     */
    private void takeSomebodyTo(final PlayerCommandPreprocessEvent event, final String whoName,
                                final String x, final String y, final String z) {
        if (!isAName(whoName) || Bukkit.getPlayerExact(whoName) != null) {
            return;
        }
        final Location to = place(event.getPlayer(), x, y, z);
        if (to == null) {
            // Relative coordinates, or not coordinates at all. Relative to whom is the question this
            // cannot answer: the player they are relative to is not on this server
            return;
        }
        event.setCancelled(true);
        this.teleports.sendTo(event.getPlayer(), whoName, to);
    }

    /**
     * Three numbers in the asker's own world, or null if any of them is not a plain number.
     *
     * <p>The asker's world because that is the one the command was typed in, which is what the game
     * itself does with a place that names no dimension.</p>
     */
    private static Location place(final Player asker, final String x, final String y, final String z) {
        try {
            return new Location(asker.getWorld(), Double.parseDouble(x), Double.parseDouble(y),
                Double.parseDouble(z));
        } catch (final NumberFormatException notAPlace) {
            return null;
        }
    }

    /**
     * Steps an arrival onto whoever they came here for.
     *
     * <p>A tick after they join rather than during it: the join tick is where they are put in the
     * world and their snapshot is restored, and the motion from that restore is applied on the tick
     * after. Teleporting in the middle of that sequence is moving a player who is still being
     * assembled.</p>
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(final PlayerJoinEvent event) {
        final FollowingSomebody.Intent intent = this.following.take(event.getPlayer());
        if (intent == null) {
            return;
        }
        Bukkit.getScheduler().runTask(this.plugin, () -> step(event.getPlayer(), intent));
    }

    private void step(final Player traveller, final FollowingSomebody.Intent intent) {
        if (!traveller.isOnline()) {
            return;
        }
        final Player target = Bukkit.getPlayer(intent.target());
        if (target == null) {
            // They have moved on while the handover ran. Deliberately not chased: a second hop would
            // be a second chance to miss them, and a pair of players crossing back and forth would
            // have somebody following them around the network
            this.logger.info(traveller.getName() + " arrived following " + intent.targetName()
                + ", who is no longer on this shard");
            traveller.sendMessage(Component.text(
                intent.targetName() + " has moved on; you have arrived where they were",
                NamedTextColor.GRAY));
            return;
        }
        traveller.teleport(target.getLocation());
    }

    private static boolean isTeleport(final String word) {
        final String label = word.toLowerCase(Locale.ROOT);
        return "/tp".equals(label) || "/teleport".equals(label)
            || "/minecraft:tp".equals(label) || "/minecraft:teleport".equals(label);
    }

    /**
     * Whether this argument is a player's name rather than a selector or part of a coordinate.
     */
    private static boolean isAName(final String word) {
        if (word.isEmpty() || word.length() > 16) {
            return false;
        }
        for (int index = 0; index < word.length(); index++) {
            final char character = word.charAt(index);
            if (character != '_' && !Character.isLetterOrDigit(character)) {
                return false;
            }
        }
        return true;
    }
}
