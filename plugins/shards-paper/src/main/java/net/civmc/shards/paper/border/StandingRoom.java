package net.civmc.shards.paper.border;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.util.BoundingBox;

/**
 * Whether a player's body fits where they are about to be put.
 *
 * <p>The question a border asks twice: once by the shard being left, of the ground it is handing
 * somebody onto, and once by the shard they arrive at, of the ground they landed on. Both used to ask
 * it of whole blocks - is the block at their feet solid, is the block at their head passable - and a
 * whole block is the wrong unit for it.</p>
 *
 * <p>Everything that is not a full cube came out wrong. A door standing open at a seam has a solid
 * <em>material</em> and three inches of actual collision hinged out of the way, so walking through an
 * open doorway was refused as walking into a wall. A slab, a bed, a carpet or a path block under a
 * player's feet is likewise solid as a material: standing on one at a border meant the block at their
 * feet was solid, so they could not leave, and arriving on one meant they did not fit, so they were
 * lifted a block into the air. All of those were reported from one afternoon at a seam.</p>
 *
 * <p>So the test is the player's own box against what is really in the way - the collision shapes of
 * the blocks it passes through, which is the same thing the server itself moves a player against.
 * Touching is not overlapping, so feet resting exactly on the top face of a slab fit, and a doorway
 * with the door swung aside is a doorway.</p>
 */
public final class StandingRoom {

    // What a player is. Not read from the entity, because the departing shard asks this about
    // somebody who is still standing on its own side and the arriving one asks before they exist
    // there; both want the same body
    private static final double HALF_WIDTH = 0.3D;
    private static final double HEIGHT = 1.8D;

    /**
     * What is at a block, or null where the caller has not been told.
     */
    @FunctionalInterface
    public interface Blocks {

        BlockData at(int x, int y, int z);
    }

    private StandingRoom() {
    }

    /**
     * Whether something is standing where a player put here would be.
     *
     * <p>Unknown blocks do not block. A shard that has not read a chunk yet would otherwise wall
     * players in at a border they could cross a moment later, and guessing the other way is the
     * answer this had before there was anything better.</p>
     *
     * @param world the world the position is in, needed only to place the collision shapes
     * @param x exact position, not a block
     */
    public static boolean blocked(final World world, final double x, final double y, final double z,
                                  final Blocks blocks) {
        final BoundingBox body = new BoundingBox(x - HALF_WIDTH, y, z - HALF_WIDTH,
            x + HALF_WIDTH, y + HEIGHT, z + HALF_WIDTH);
        final int fromX = (int) Math.floor(body.getMinX());
        final int toX = (int) Math.floor(body.getMaxX());
        final int fromY = (int) Math.floor(body.getMinY());
        final int toY = (int) Math.floor(body.getMaxY());
        final int fromZ = (int) Math.floor(body.getMinZ());
        final int toZ = (int) Math.floor(body.getMaxZ());
        for (int blockX = fromX; blockX <= toX; blockX++) {
            for (int blockY = fromY; blockY <= toY; blockY++) {
                for (int blockZ = fromZ; blockZ <= toZ; blockZ++) {
                    if (inTheWay(world, blockX, blockY, blockZ, blocks, body)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean inTheWay(final World world, final int x, final int y, final int z,
                                    final Blocks blocks, final BoundingBox body) {
        if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
            return false;
        }
        final BlockData data = blocks.at(x, y, z);
        if (data == null) {
            return false;
        }
        return data.getCollisionShape(new Location(world, x, y, z)).overlaps(body);
    }
}
