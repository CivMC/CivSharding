package net.civmc.shards.paper.border;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import net.civmc.shards.api.region.ShardPoint;
import net.civmc.shards.api.region.ShardRegion;
import org.bukkit.Location;

/**
 * The areas this server owns, as the proxy reported them at startup.
 *
 * <p>Held here rather than read from this server's own config so there is one copy of the shard map.
 * Two copies would disagree the moment one was edited, and a disagreement at a border is a block that
 * either belongs to nobody or to both.</p>
 */
public final class ShardBorder {

    // The four ways off a block. Edges run along an axis, so nothing diagonal can be reached without
    // first crossing one of these
    private static final int[][] STEPS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private final AtomicReference<List<ShardRegion>> regions = new AtomicReference<>(List.of());

    public void set(final List<ShardRegion> regions) {
        this.regions.set(regions == null ? List.of() : List.copyOf(regions));
    }

    public boolean isConfigured() {
        return !this.regions.get().isEmpty();
    }

    /**
     * The areas themselves, for the one caller that has to walk the ground around them rather than
     * ask about a place: bringing the band just past this shard's edges up to date needs somewhere to
     * start looking, and the box around these is it.
     */
    public List<ShardRegion> regions() {
        return this.regions.get();
    }

    /**
     * Whether a position is outside every area this server owns.
     *
     * <p>A server with no areas owns everywhere as far as this is concerned. It is not a shard - the
     * holding server is the usual case - and answering otherwise would wall its players in at the
     * first block they walked to.</p>
     */
    public boolean isOutside(final Location location) {
        final List<ShardRegion> owned = this.regions.get();
        if (owned.isEmpty()) {
            return false;
        }
        // Floored, not cast: a cast truncates towards zero, which would put someone standing at
        // x = -0.5 on block 0 instead of block -1, and so on the wrong side of a border at zero
        final int blockX = (int) Math.floor(location.getX());
        final int blockZ = (int) Math.floor(location.getZ());
        return isOutside(blockX, blockZ);
    }


    /**
     * Whether any of this server's areas has an edge running within {@code radius} blocks.
     *
     * <p>The cheap half of {@link #facesWithin}: it walks the corners of each area rather than the
     * blocks around the player, so it costs the same handful of comparisons wherever they stand.</p>
     *
     * <p>Measured against the outline itself rather than by looking outward along the four axes from
     * the player, which is the same mistake as drawing the border from one straight edge: a notch or
     * a corner off to one side is within sight and on none of those four lines, so a player walking
     * past one would be shown nothing at all.</p>
     *
     * <p>May say yes where the real answer is no - two of this server's own areas meeting have an
     * edge with no face on it - which costs one wasted scan and never a missing border.</p>
     */
    public boolean outlineWithin(final int blockX, final int blockZ, final int radius) {
        return distanceToOutline(blockX, blockZ) <= radius;
    }

    /**
     * How far this block is from the nearest edge of this shard's outline, in blocks.
     *
     * <p>The same measurement {@link #outlineWithin} answers yes or no about, for the callers that
     * have to put one place before another rather than merely include it. Costs the same handful of
     * comparisons per edge wherever it is asked.</p>
     *
     * @return {@link Integer#MAX_VALUE} for a server with no areas, which has no outline to be near
     */
    public int distanceToOutline(final int blockX, final int blockZ) {
        int nearest = Integer.MAX_VALUE;
        for (final ShardRegion region : this.regions.get()) {
            final List<ShardPoint> corners = region.vertices();
            for (int index = 0; index < corners.size(); index++) {
                nearest = Math.min(nearest, edgeDistance(corners.get(index),
                    corners.get((index + 1) % corners.size()), blockX, blockZ));
            }
        }
        return nearest;
    }

    /**
     * How far one edge of an area passes from a block.
     *
     * <p>Corners sit on the grid lines between blocks, so an edge at coordinate {@code c} separates
     * the blocks {@code c - 1} and {@code c}: the nearer of those two is what the distance is
     * measured to. Along the edge, anywhere between its ends is a distance of nothing, and past
     * either end it is the distance to the last block the edge actually runs beside - the upper end
     * exclusive, matching the rule that decides ownership.</p>
     */
    private static int edgeDistance(final ShardPoint from, final ShardPoint to, final int blockX,
                                    final int blockZ) {
        final boolean runsAlongZ = from.x() == to.x();
        final int across = runsAlongZ ? from.x() : from.z();
        final int alongLow = Math.min(runsAlongZ ? from.z() : from.x(), runsAlongZ ? to.z() : to.x());
        final int alongHigh = Math.max(runsAlongZ ? from.z() : from.x(), runsAlongZ ? to.z() : to.x());
        final int blockAcross = runsAlongZ ? blockX : blockZ;
        final int blockAlong = runsAlongZ ? blockZ : blockX;

        final int distanceAcross = Math.min(Math.abs(blockAcross - across), Math.abs(blockAcross - (across - 1)));
        final int distanceAlong;
        if (blockAlong < alongLow) {
            distanceAlong = alongLow - blockAlong;
        } else if (blockAlong >= alongHigh) {
            distanceAlong = blockAlong - (alongHigh - 1);
        } else {
            distanceAlong = 0;
        }
        return Math.max(distanceAcross, distanceAlong);
    }

    /**
     * Whether a whole chunk lies outside every area this server owns.
     *
     * <p>One block decides it. Shard edges are required to fall on chunk boundaries, so no chunk is
     * ever split between two shards and every block in it gives the same answer. Everything about the
     * mirror rests on that: it has to ask one shard for a chunk, and a chunk with two owners has no
     * one shard to ask.</p>
     */
    public boolean isChunkOutside(final int chunkX, final int chunkZ) {
        return isOutside(chunkX << 4, chunkZ << 4);
    }

    public boolean isOutside(final int blockX, final int blockZ) {
        final List<ShardRegion> owned = this.regions.get();
        if (owned.isEmpty()) {
            return false;
        }
        for (final ShardRegion region : owned) {
            if (region.containsBlock(blockX, blockZ)) {
                return false;
            }
        }
        return true;
    }
}
