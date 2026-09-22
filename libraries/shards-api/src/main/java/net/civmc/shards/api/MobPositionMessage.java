package net.civmc.shards.api;

import java.util.List;
import java.util.Objects;
import net.civmc.shards.api.mirror.MirrorMob;

/**
 * Where a shard's moving things are, announced every tick to whoever can see that ground.
 *
 * <p>The same shape and the same reasoning as {@link PlayerPositionMessage}: a fanout, because a
 * shard would otherwise have to know which neighbour is looking at which of its minecarts; the latest
 * state rather than a change, so a dropped message is replaced rather than missed; and a short expiry
 * with no durability, because a position that is a second old would drag something backwards.</p>
 *
 * <p>Its own exchange rather than a list inside the player one. They carry the same kind of thing at
 * the same rate, but a shard with a rail line along a seam and nobody near it sends one of these and
 * no players at all - and the two rates are worth being able to tell apart in the broker's own
 * statistics, which is the only place they will ever be visible.</p>
 *
 * <p>Only things near this shard's own outline and standing on ground it owns. The second half
 * matters more than it looks: a shard's world does not stop at its border, so it has cows of its own
 * wandering the neighbour's fields that nobody else can see and that its own players are not shown
 * either. Announcing those would put this server's private herd inside somebody's town.</p>
 */
public record MobPositionMessage(String serverName, String world, List<MirrorMob> mobs,
                                 long createdAtEpochMillis) {

    public MobPositionMessage {
        serverName = Messages.requireNonBlank(serverName, "serverName");
        world = Messages.requireNonBlank(world, "world");
        Objects.requireNonNull(mobs, "mobs");
        mobs = List.copyOf(mobs);
        Messages.requirePositive(createdAtEpochMillis, "createdAtEpochMillis");
    }

    public static MobPositionMessage create(final String serverName, final String world,
                                            final List<MirrorMob> mobs) {
        return new MobPositionMessage(serverName, world, mobs, System.currentTimeMillis());
    }
}
