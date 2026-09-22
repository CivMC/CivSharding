package net.civmc.shards.paper.teleport;

import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * The half of a teleport that has to survive the journey.
 *
 * <p>Sending somebody to a person on another shard is two separate things happening on two servers:
 * this one hands them over, and the shard they land on is the only one that can put them next to
 * somebody standing in its world. Nothing in a handover carries an instruction - a transfer says
 * where to put a player down and nothing about what they were trying to do - so the intention travels
 * with the player, in the persistent data that is captured into their snapshot and restored on
 * arrival.</p>
 *
 * <p>Written just before the handover starts so it is in the snapshot, taken and cleared the moment
 * they arrive, and ignored if it is stale: an intention left on somebody by a crossing that then
 * failed must not fire the next time they walk over a border half an hour later.</p>
 */
public final class FollowingSomebody {

    // Long enough to cover a handover and a slow login several times over, short enough that an
    // intention left behind by a crossing that never happened is dead before anybody crosses again
    private static final long GOOD_FOR_MILLIS = 30_000L;
    // Well past a handover, which answers in a fraction of a second, and well short of the time an
    // intention stays good for
    private static final long SETTLED_AFTER_TICKS = 100L;

    private final NamespacedKey who;
    private final NamespacedKey name;
    private final NamespacedKey when;

    public FollowingSomebody(final JavaPlugin plugin) {
        this.who = new NamespacedKey(plugin, "following-player");
        this.name = new NamespacedKey(plugin, "following-name");
        this.when = new NamespacedKey(plugin, "following-since");
    }

    /**
     * Writes down who this player is on their way to. Must be called before the snapshot is taken,
     * which is to say before the handover starts.
     */
    public void intend(final Player player, final UUID target, final String targetName) {
        final PersistentDataContainer data = player.getPersistentDataContainer();
        data.set(this.who, PersistentDataType.STRING, target.toString());
        data.set(this.name, PersistentDataType.STRING, targetName);
        data.set(this.when, PersistentDataType.LONG, System.currentTimeMillis());
    }

    /**
     * Forgets an intention, for a handover that never started. The player is still standing here, so
     * leaving it on them would fire it the next time they crossed a border by any other means.
     */
    public void forget(final Player player) {
        final PersistentDataContainer data = player.getPersistentDataContainer();
        data.remove(this.who);
        data.remove(this.name);
        data.remove(this.when);
    }

    /**
     * Takes an intention back off somebody who is still standing here a moment later.
     *
     * <p>A handover that starts can still be refused - ground nobody owns, a shard that is not
     * answering, a save the proxy would not make - and a player who is refused keeps playing here
     * with an intention written on them. Left there, the next border they walked over within the half
     * minute would step them onto somebody they had stopped trying to reach.</p>
     *
     * <p>Only for one who is still here and not still crossing. Somebody genuinely mid-handover is
     * left alone: their snapshot was taken long ago and what is on the player now no longer travels
     * anywhere.</p>
     *
     * @param crossing whether they are still in the middle of a handover, asked at the time rather
     *     than now
     */
    public void forgetIfTheyStay(final JavaPlugin plugin, final Player player,
                                 final BooleanSupplier crossing) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline() && !crossing.getAsBoolean()) {
                forget(player);
            }
        }, SETTLED_AFTER_TICKS);
    }

    /**
     * What this arrival was on their way to do, clearing it as it is read.
     *
     * <p>Always clears, including when the intention is too old to act on. An intention that is read
     * but left behind is one that fires again on the next crossing.</p>
     *
     * @return null when they were not following anybody, or set out too long ago to still mean it
     */
    public Intent take(final Player player) {
        final PersistentDataContainer data = player.getPersistentDataContainer();
        final String target = data.get(this.who, PersistentDataType.STRING);
        final String targetName = data.get(this.name, PersistentDataType.STRING);
        final Long since = data.get(this.when, PersistentDataType.LONG);
        forget(player);
        if (target == null || since == null) {
            return null;
        }
        if (System.currentTimeMillis() - since > GOOD_FOR_MILLIS) {
            return null;
        }
        try {
            return new Intent(UUID.fromString(target), targetName == null ? "" : targetName);
        } catch (final IllegalArgumentException notAUuid) {
            // Somebody else's key, or one written by a version that meant something else by it
            return null;
        }
    }

    public record Intent(UUID target, String targetName) {
    }
}
