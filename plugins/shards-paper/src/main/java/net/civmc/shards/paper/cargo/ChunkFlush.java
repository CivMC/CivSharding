package net.civmc.shards.paper.cargo;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import org.bukkit.World;

/**
 * Writes part of a world to disk now, rather than whenever the next autosave comes round.
 *
 * <p>Needed because a parcel is reported landed on the strength of it being in the world, and "in
 * the world" ordinarily means in memory. A server killed between pasting a rocket and its next
 * autosave would come back with the rocket gone and the proxy no longer counting it as owed, which
 * is the one outcome the cargo store exists to prevent.</p>
 *
 * <p>Paper has no public way to save one chunk. What it has is unloading one, which saves it on the
 * way out - so that is what this does, chunk by chunk over the area that changed. Unloading is
 * refused for a chunk something is holding on to, a player standing nearby most often, and for those
 * the only remaining option is to save the whole world. That is a real stall, so it is done once for
 * all of them rather than per chunk, and said out loud when it happens. A rocket landing next to
 * somebody is rare; a rocket landing where nobody is standing is the ordinary case and costs a few
 * chunk writes.</p>
 */
public final class ChunkFlush {

    private ChunkFlush() {
    }

    /**
     * Makes everything in the given block area durable.
     *
     * <p>Must run on the main thread.</p>
     *
     * @return whether it had to save the whole world to do it
     */
    public static boolean blocks(final World world, final int minBlockX, final int minBlockZ,
                                 final int maxBlockX, final int maxBlockZ, final Logger logger) {
        final List<int[]> stubborn = new ArrayList<>();
        for (int chunkX = minBlockX >> 4; chunkX <= (maxBlockX >> 4); chunkX++) {
            for (int chunkZ = minBlockZ >> 4; chunkZ <= (maxBlockZ >> 4); chunkZ++) {
                // Saves on the way out. It comes straight back the moment anybody looks at it, read
                // from what was just written, which is the same blocks
                if (!world.unloadChunk(chunkX, chunkZ, true)) {
                    stubborn.add(new int[] {chunkX, chunkZ});
                }
            }
        }
        if (stubborn.isEmpty()) {
            return false;
        }
        logger.info("Saving " + world.getName() + " because " + stubborn.size() + " chunk(s) holding cargo "
            + "could not be unloaded - something is keeping them loaded, most likely somebody standing "
            + "near where it landed");
        world.save();
        return true;
    }
}
