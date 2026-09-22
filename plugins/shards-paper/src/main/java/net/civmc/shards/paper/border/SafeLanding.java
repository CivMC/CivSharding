package net.civmc.shards.paper.border;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * Lifting somebody who has arrived inside a block.
 *
 * <p>A crossing is aimed by the shard being left, and it aims with the only copy of the far side it
 * has: its own, which is the world as generated, without whatever the neighbour has built or dug
 * there since. So the place it picks is a place it believes is open, and the shard that actually owns
 * the ground is the only one that knows whether it is. Where it is not, the player arrives standing
 * inside something and begins to suffocate, with nothing on their screen to explain it - the blocks
 * they are drawn are the ones that are really there, and those are clear.</p>
 *
 * <p>This is the destination answering that question for itself rather than trusting the aim. It is
 * not a repair of the disagreement - the two copies still differ, and walking at a border still means
 * walking into blocks only the other server believes in - but it is the one place where the
 * disagreement costs a player health rather than smoothness.</p>
 *
 * <p>Deliberately small: it moves them straight up or straight down to the nearest place they fit, and
 * never sideways. A player who crossed a border going north expects to be slightly north, and being
 * quietly shifted along the seam to a nicer spot is worse than being lifted a block.</p>
 */
public final class SafeLanding {

    // A build over a seam is a floor and a ceiling, so the space wanted is nearly always within a
    // block or two. Past this, the far side is not a surface with something on it, it is solid - and
    // moving somebody a long way from where they aimed is its own kind of wrong
    private static final int SEARCH = 4;

    private SafeLanding() {
    }

    /**
     * Where this player can stand, nearest to where they arrived.
     *
     * <p>Main thread: it reads the world.</p>
     *
     * @return the place to put them, or null if they are already somewhere they fit - which is almost
     *     everybody, almost always
     */
    public static Location clearOf(final Player player) {
        final Location arrived = player.getLocation();
        if (fits(arrived.getWorld(), arrived.getX(), arrived.getY(), arrived.getZ())) {
            return null;
        }
        final World world = arrived.getWorld();
        // Up first. Ground that has been built on has gained height rather than lost it, so the open
        // space is above far more often than below, and looking down first would drop somebody through
        // a floor into a cellar they were never headed for
        for (int step = 1; step <= SEARCH; step++) {
            final double above = arrived.getBlockY() + step;
            if (fits(world, arrived.getX(), above, arrived.getZ())) {
                return standing(arrived, above);
            }
            final double below = arrived.getBlockY() - step;
            if (fits(world, arrived.getX(), below, arrived.getZ())) {
                return standing(arrived, below);
            }
        }
        return null;
    }

    /**
     * Whether a player standing here has room for the rest of themselves.
     *
     * <p>{@link StandingRoom} against the blocks that are really there, rather than two whole blocks
     * asked whether they are passable. A slab, a bed, a carpet or a path block is not passable and is
     * exactly what a player arriving normally is standing on, so the old test called an ordinary
     * landing a suffocation and lifted them a block into the air for it.</p>
     */
    private static boolean fits(final World world, final double x, final double y, final double z) {
        if (y < world.getMinHeight() || y + 2 > world.getMaxHeight()) {
            return false;
        }
        return !StandingRoom.blocked(world, x, y, z,
            (blockX, blockY, blockZ) -> world.getBlockAt(blockX, blockY, blockZ).getBlockData());
    }

    /**
     * The same spot at a new height, keeping where within the block they were and which way they face.
     */
    private static Location standing(final Location arrived, final double y) {
        final Location clear = arrived.clone();
        clear.setY(y);
        return clear;
    }
}
