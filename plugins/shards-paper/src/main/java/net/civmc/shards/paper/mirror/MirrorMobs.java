package net.civmc.shards.paper.mirror;

import net.civmc.shards.api.MobPositionMessage;

/**
 * Showing the minecarts, animals and dropped items on another shard, or not.
 *
 * <p>An interface with nothing from a packet library in it, for the same reason as
 * {@link MirrorPlayers}: drawing something that is not really here can only be done with that
 * library, the library is a soft dependency, and a soft dependency that takes the plugin down when it
 * is missing is not soft. So the type that uses it is never named unless it has loaded.</p>
 */
public interface MirrorMobs {

    /**
     * Does nothing, for a server with no packet library. Everything else about the mirror works.
     */
    MirrorMobs NONE = new MirrorMobs() {
        @Override
        public void apply(final MobPositionMessage message) {
        }

        @Override
        public void expire() {
        }
    };

    void apply(MobPositionMessage message);

    void expire();
}
