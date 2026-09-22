package net.civmc.shards.paper.border;

import org.bukkit.World;

/**
 * Where a border stands at a column, for the renderers that put something in the world.
 *
 * <p>Shared rather than copied because getting it wrong is subtle and was got wrong several times
 * over: every mistake here places the border somewhere a player cannot see it, and an invisible
 * border is the same thing as no border at all.</p>
 */
final class BorderFooting {

    // How far above the player a marker will look for open space before giving up on the column, and
    // how far below it will follow that space down to a floor
    private static final int OPEN_ABOVE = 4;
    private static final int FLOOR_BELOW = 8;
    // A marker stands on the floor while the player is within this much of it. Higher than that - up
    // a pillar, or flying - the floor is not where they are looking, so it comes up to meet them, in
    // steps of FOLLOW_STEP so that climbing does not re-place everything on every block
    private static final int FOLLOW_ABOVE = 3;
    private static final int FOLLOW_STEP = 3;

    /**
     * No floor was found, because the column is solid all the way up.
     */
    static final int SOLID = Integer.MIN_VALUE;

    private BorderFooting() {
    }

    /**
     * The floor of the open space the player themselves is in, at this column.
     *
     * <p>Found by looking out from the player's own level rather than by asking for the highest
     * block. The highest block is wrong three ways over: it counts water, so a border across a lake
     * stood on the surface and disappeared the moment you swam under it; it counts leaves, so one
     * under a tree hung at canopy height; and it looks from the sky, so in a cave or a building it
     * reported the hillside or the roof overhead and the border was placed in the ceiling. Clamping
     * the result back towards the player then buried what it had put too high, and left the rest
     * hanging at whatever height the player happened to be.</p>
     *
     * <p>Water and air are both open here, so the search comes to rest on the lake bed rather than on
     * the lake, and a border crossing one is drawn where somebody swimming can see it.</p>
     *
     * @return the y of the lowest open block, or {@link #SOLID} where there is no open space to
     *     stand a border in - a border running into a hillside, or through a wall built on it
     */
    static int floorUnder(final World world, final int x, final int z, final int playerY) {
        final int ceiling = Math.min(world.getMaxHeight() - 1, playerY + OPEN_ABOVE);
        int open = SOLID;
        for (int y = Math.max(playerY, world.getMinHeight()); y <= ceiling; y++) {
            if (!world.getBlockAt(x, y, z).getType().isSolid()) {
                open = y;
                break;
            }
        }
        if (open == SOLID) {
            return SOLID;
        }
        final int lowest = Math.max(world.getMinHeight(), playerY - FLOOR_BELOW);
        int floor = open;
        while (floor > lowest && !world.getBlockAt(x, floor - 1, z).getType().isSolid()) {
            floor--;
        }
        return floor;
    }

    /**
     * Lifts a marker off the floor once the player is well above it.
     *
     * <p>A border is a thing you are about to cross, so it belongs where you are about to cross it;
     * left on the floor it hangs several blocks below anybody in the air, which is both useless and
     * the wrong answer about where the border is.</p>
     *
     * <p>In steps rather than continuously. Following exactly would mean everything in sight being
     * taken down and raised again on every block of a climb, and a jump would do it too - the step is
     * what makes something that already covers the player be left alone.</p>
     */
    static int follow(final int floorBase, final int playerY) {
        final int above = playerY - floorBase;
        if (above <= FOLLOW_ABOVE) {
            return floorBase;
        }
        // Snapped against the floor rather than against the player, so two players at slightly
        // different heights over the same ground are shown the border in the same place
        return floorBase + Math.floorDiv(above, FOLLOW_STEP) * FOLLOW_STEP;
    }
}
