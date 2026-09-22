package net.civmc.shards.paper.mirror;

import net.civmc.shards.api.ParticleMessage;

/**
 * Showing the particles another shard has shown, or not.
 *
 * <p>An interface with nothing from a packet library in it, for the same reason as {@link MirrorMobs}:
 * drawing something that is not really here can only be done with that library, the library is a soft
 * dependency, and a soft dependency that takes the plugin down when it is missing is not soft. So the
 * type that uses it is never named unless it has loaded.</p>
 */
public interface MirrorParticles {

    /**
     * Does nothing, for a server with no packet library. Everything else about the mirror works.
     */
    MirrorParticles NONE = message -> {
    };

    void apply(ParticleMessage message);
}
