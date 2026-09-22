package net.civmc.shards.paper.mirror;

import io.papermc.paper.math.Position;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;
import net.civmc.shards.api.ChunkUpdateMessage;
import net.civmc.shards.api.mirror.BlockUpdate;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Keeps the ground just past this server's borders up to date for as long as it is running, and not
 * only at the moment it started.
 *
 * <p>{@link BorderBandSync} reads that strip from its owners once, at startup, which leaves the
 * problem it was built for solved for exactly as long as nobody changes anything. A neighbour digging
 * out their side of a seam at noon puts the two copies back into disagreement, and from then on this
 * server is simulating against ground that has not existed since the morning - a player walks into a
 * wall that was demolished hours ago, with a clear view through where it used to be. On a network
 * that restarts daily that is most of a day of it.</p>
 *
 * <p>The announcements that fix it are already flying. {@link MirrorUpdatePublisher} tells every
 * shard about blocks changing near its own outline, and {@link MirrorView#applyUpdate} draws them -
 * for anybody looking at a chunk it has fetched, which is the picture and nothing else. This takes
 * the same announcement and writes the part of it that falls in the band into this server's own
 * world, exactly as the startup pass would have.</p>
 *
 * <p><strong>The mirror's rule is untouched.</strong> What is drawn for a client is still never
 * written. What is written here is the ground, from its owner, for the same reason and under the same
 * limits as at startup: terrain only, so no chest's contents and no sign's words, and only past a
 * border where this server is not authoritative for anything anyway.</p>
 *
 * <p><strong>Only from the shard that owns the chunk.</strong> The announcement says who sent it and
 * the band knows who owns each column; anything else is a shard describing ground that is not its
 * own, which would be its stale copy written into ours as though it were the truth.</p>
 */
public final class BorderBandUpdates {

    // Chunks loaded and written per tick, for the ones that are not already in memory. The same
    // gentleness as the startup pass, for a different reason: this runs while people are playing
    private static final int CHUNKS_PER_TICK = 2;
    // One complaint a minute at most about a shard describing ground it does not own. It would
    // otherwise be one per announcement, which is a misconfigured neighbour filling the log
    private static final long WARN_EVERY_NANOS = 60L * 1_000_000_000L;

    private final JavaPlugin plugin;
    private final BorderBandSync band;
    private final MirrorView mirror;
    private final Logger logger;

    // Chunks that were not loaded when their announcement arrived, with everything announced about
    // each while it waited. Merged rather than queued twice: two announcements about one chunk are
    // one chunk to load, and the later block wins because it is put in second
    private final Map<ChunkKey, Map<Position, BlockData>> waitingForTheChunk = new HashMap<>();
    private final Deque<ChunkKey> toLoad = new ArrayDeque<>();
    private long lastWarnedAt;

    public BorderBandUpdates(final JavaPlugin plugin, final BorderBandSync band, final MirrorView mirror,
                             final Logger logger) {
        this.plugin = plugin;
        this.band = band;
        this.mirror = mirror;
        this.logger = logger;
    }

    /**
     * Writes whatever a neighbour has just announced that falls in the band. Main thread, before the
     * mirror is told the same thing.
     *
     * <p>Before, and not after, so that a chunk somebody is looking at is reconciled for free: the
     * mirror compares what the neighbour says against this server's own block, and by the time it
     * looks that block is already the one being announced. The difference stops being a difference
     * and is dropped, instead of being cached as a picture of ground that now really is there.</p>
     */
    public void apply(final ChunkUpdateMessage update) {
        if (update.updates().isEmpty()) {
            // An announcement about entities or signs. Neither is ground, and neither is ever written
            return;
        }
        final ChunkKey key = new ChunkKey(update.world(), update.chunkX(), update.chunkZ());
        final String owner = this.band.ownerOf(update.world(), update.chunkX(), update.chunkZ());
        if (owner == null || owner.isBlank()) {
            // Outside the band, or ground nobody owns. Either way there is nothing better than this
            // server's own copy to write, and beyond the band a player cannot reach it to be stopped
            // by it - that is the whole reason the band is a strip rather than the view
            return;
        }
        if (!owner.equals(update.serverName())) {
            warnAboutSomebodyElsesGround(update, owner);
            return;
        }
        if (this.band.isReading(key)) {
            // The startup pass has asked for this chunk and the answer is older than what has just
            // arrived. Applying both in the order they land would put the old block back
            this.band.readAgainNow(key);
        }
        final World world = Bukkit.getWorld(update.world());
        if (world == null) {
            return;
        }
        final Map<Position, BlockData> blocks = read(update);
        if (blocks.isEmpty()) {
            return;
        }
        if (world.isChunkLoaded(update.chunkX(), update.chunkZ())) {
            write(world, blocks);
            return;
        }
        // Not in memory, so nobody is standing near it and nothing is waiting on this. Loaded and
        // written a couple of chunks a tick, because the alternative is leaving it: a chunk written
        // while it is unloaded is a chunk that loads with the old block in it, and the next time
        // anybody walks there it is the wall that was demolished hours ago all over again
        if (this.waitingForTheChunk.put(key, merged(key, blocks)) == null) {
            this.toLoad.add(key);
        }
    }

    /**
     * Loads and writes the chunks that were not in memory, a couple at a time.
     */
    public void drain() {
        for (int loaded = 0; loaded < CHUNKS_PER_TICK && !this.toLoad.isEmpty(); loaded++) {
            final ChunkKey key = this.toLoad.poll();
            final Map<Position, BlockData> blocks = this.waitingForTheChunk.remove(key);
            if (blocks == null || blocks.isEmpty()) {
                continue;
            }
            final World world = Bukkit.getWorld(key.world());
            if (world == null) {
                continue;
            }
            world.getChunkAtAsync(key.x(), key.z()).thenAccept(chunk ->
                Bukkit.getScheduler().runTask(this.plugin, () -> {
                    write(world, blocks);
                    // Nobody was looking at this one - it was not even loaded - but the mirror keeps
                    // its picture of a chunk for a while after the last person walks away, and that
                    // picture is a difference from ground that has just moved underneath it
                    this.mirror.forgetChunk(key);
                }));
        }
    }

    private Map<Position, BlockData> merged(final ChunkKey key, final Map<Position, BlockData> blocks) {
        final Map<Position, BlockData> already = this.waitingForTheChunk.get(key);
        if (already == null) {
            return blocks;
        }
        already.putAll(blocks);
        return already;
    }

    /**
     * Writes the neighbour's blocks into this server's own world.
     *
     * <p>Without physics, for the reason the startup pass gives: with it on, applying a neighbour's
     * ground would have sand fall, water flow and redstone fire on ground this server is not
     * authoritative for - the border seal broken by the very thing meant to keep the two copies
     * agreeing.</p>
     */
    private void write(final World world, final Map<Position, BlockData> blocks) {
        for (final Map.Entry<Position, BlockData> block : blocks.entrySet()) {
            world.getBlockAt(block.getKey().blockX(), block.getKey().blockY(), block.getKey().blockZ())
                .setBlockData(block.getValue(), false);
        }
    }

    /**
     * What the announcement describes, in the order it was sent, so a position named twice ends as
     * whatever it was said to be last.
     */
    private Map<Position, BlockData> read(final ChunkUpdateMessage update) {
        final Map<Position, BlockData> blocks = new LinkedHashMap<>();
        for (final BlockUpdate block : update.updates()) {
            final BlockData data;
            try {
                data = Bukkit.createBlockData(block.blockData());
            } catch (final IllegalArgumentException unknown) {
                // A block this server cannot make: a neighbour on a newer version, or a plugin block.
                // Its own copy stays, which is the best it has
                continue;
            }
            blocks.put(Position.block(block.x(), block.y(), block.z()), data);
        }
        return blocks;
    }

    private void warnAboutSomebodyElsesGround(final ChunkUpdateMessage update, final String owner) {
        final long now = System.nanoTime();
        if (now - this.lastWarnedAt < WARN_EVERY_NANOS) {
            return;
        }
        this.lastWarnedAt = now;
        this.logger.warning(update.serverName() + " announced blocks in " + update.world() + " chunk "
            + update.chunkX() + ", " + update.chunkZ() + ", which " + owner + " owns. Nothing was "
            + "written: a shard describing ground that is not its own is describing its own stale copy "
            + "of it. The two disagree about the shard map, so check the proxy's config.yml");
    }
}
