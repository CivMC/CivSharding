package net.civmc.shards.paper.mirror;

import com.github.retrooper.packetevents.protocol.entity.pose.EntityPose;
import java.util.Locale;

/**
 * Turns the name of a pose into the one the protocol knows.
 *
 * <p>A pose travels as the name the sending server calls it, not as a number: the number is the
 * receiver's to read off its own classes, and a number sent from a shard on a different version would
 * be the kind of guess this whole part of the mirror is built to avoid.</p>
 *
 * <p>The two sides spell one of them differently - the game's own name for a crouch is
 * {@code SNEAKING} and the protocol's is {@code CROUCHING} - and a version that adds a pose this one
 * has never heard of is drawn standing rather than not drawn at all. Standing is what an entity in an
 * unknown pose looked like before any of this was carried, so it is the honest fallback.</p>
 */
final class Poses {

    private Poses() {
    }

    /**
     * @param name the Bukkit {@code Pose} name, or blank from a sender that does not send one
     */
    static EntityPose of(final String name) {
        if (name == null || name.isEmpty()) {
            return EntityPose.STANDING;
        }
        if ("SNEAKING".equals(name)) {
            return EntityPose.CROUCHING;
        }
        try {
            return EntityPose.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (final IllegalArgumentException notAPoseHere) {
            return EntityPose.STANDING;
        }
    }
}
