package net.civmc.shards.paper.mirror;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import net.civmc.shards.api.MobPositionMessage;
import net.civmc.shards.api.mirror.MirrorMob;
import net.civmc.shards.paper.border.ShardBorder;
import net.civmc.shards.paper.rabbitmq.ShardsClient;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;

/**
 * Says where this shard's minecarts, animals and dropped items are, for the shards that can see that
 * ground.
 *
 * <p>The mirror draws the buildings, the shop frames, the signs and the people. What was left is
 * everything else that moves, which turns out to be most of what makes a place look lived in: a
 * minecart running the rail beside a seam, a farm's animals, an item somebody dropped. A border with
 * every building drawn and none of this reads as a diorama - and a rail line along a seam reads as
 * one nobody uses.</p>
 *
 * <p>Announced every tick on a fanout, exactly as players are, and for the same reasons written down
 * in {@link MirrorPlayerPublisher}: the latest state rather than a change, so nothing is missed by
 * dropping one, and no need to know which neighbour is looking.</p>
 *
 * <p><strong>Only what this shard owns.</strong> Near its own outline, and standing on ground inside
 * it. The second half is the one that is easy to leave out and cannot be: this server's world does
 * not stop at its border, so it has cows of its own wandering a neighbour's fields - ones that
 * neighbour cannot see and that this server's own players are not shown either, because
 * {@code hide-unowned-entities} hides them. Announcing those would put this server's private herd
 * inside somebody's town, which is the exact fault that setting exists to fix, sent over the wire.</p>
 *
 * <p><strong>What is deliberately not described.</strong> Players, which have machinery of their own
 * that carries a skin and a name. Frames and stands, which do not move and travel with their chunk.
 * Paintings, for the same reason. And displays - the block displays this very plugin uses to draw a
 * border - because a shard publishing those would have every neighbour drawing its border panes
 * alongside their own.</p>
 */
public final class MirrorMobPublisher {

    // The same as the players', and for the same reason: their tracking range is the widest of any
    // entity and this costs only bandwidth, since a receiver shows only what is near its own players
    private static final int PUBLISH_RADIUS = 192;
    // Per world, per tick. A border along a mob farm or a rail yard could otherwise describe hundreds
    // of things sixty times a second; past this the far side is missing some of them, which is worse
    // than nothing to look at but very much better than a shard spending its tick on somebody else's
    // scenery. Deliberately not the whole of what could be sent
    private static final int MOST_PER_WORLD = 128;
    // One complaint a minute at most, so a border beside a farm does not fill the log
    private static final long WARN_EVERY_NANOS = 60L * 1_000_000_000L;

    private final ShardBorder border;
    private final ShardsClient client;
    private final String serverName;
    private final Logger logger;
    // Which chunks have a border near enough to matter, worked out once each
    private final Map<Long, Boolean> nearABorder = new HashMap<>();
    private long lastWarnedAt;

    public MirrorMobPublisher(final ShardBorder border, final ShardsClient client, final String serverName,
                              final Logger logger) {
        this.border = border;
        this.client = client;
        this.serverName = serverName;
        this.logger = logger;
    }

    /**
     * Main thread, once a tick.
     */
    public void publish() {
        if (!this.border.isConfigured()) {
            return;
        }
        for (final World world : Bukkit.getWorlds()) {
            final List<MirrorMob> mobs = new ArrayList<>();
            boolean tooMany = false;
            // The loaded chunks near a border rather than every entity in the world. A world's entity
            // list is every mob on the shard, and walking it sixty times a second to find the handful
            // beside a seam is most of the cost of this feature for none of its value
            for (final Chunk chunk : world.getLoadedChunks()) {
                if (!nearTheOutline(chunk)) {
                    continue;
                }
                for (final Entity entity : chunk.getEntities()) {
                    if (!worthSending(entity)) {
                        continue;
                    }
                    if (mobs.size() >= MOST_PER_WORLD) {
                        tooMany = true;
                        break;
                    }
                    mobs.add(describe(entity));
                }
                if (tooMany) {
                    break;
                }
            }
            if (tooMany) {
                warnAboutTooMany(world);
            }
            if (!mobs.isEmpty()) {
                this.client.publishMobPositions(
                    MobPositionMessage.create(this.serverName, world.getName(), mobs));
            }
        }
    }

    /**
     * Whether a chunk is close enough to a border for anything in it to be visible from another shard.
     *
     * <p>Remembered rather than worked out again every tick. The shard map does not change while the
     * network is up, so a chunk's answer never does - and the alternative is an outline test for every
     * loaded chunk in the world, sixty times a second.</p>
     */
    private boolean nearTheOutline(final Chunk chunk) {
        return this.nearABorder.computeIfAbsent(packed(chunk.getX(), chunk.getZ()),
            ignored -> this.border.outlineWithin((chunk.getX() << 4) + 8, (chunk.getZ() << 4) + 8,
                PUBLISH_RADIUS));
    }

    private static long packed(final int chunkX, final int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
    }

    /**
     * Whether this is one of the things a neighbour should see, standing somewhere it is theirs to
     * see.
     *
     * <p>How far from the border it is has already been answered by the chunk it is in, to the
     * nearest chunk. Asking again per entity would be the same question to sixteen blocks' more
     * precision, on something that is about to be described in full anyway.</p>
     */
    private boolean worthSending(final Entity entity) {
        if (entity instanceof Player || entity instanceof Hanging || entity instanceof ArmorStand
            || entity instanceof Display) {
            return false;
        }
        final Location at = entity.getLocation();
        // Ours, but standing on somebody else's ground. Nobody is shown this - not the shard that
        // owns the ground, and not this server's own players either
        return !this.border.isOutside(at.getBlockX(), at.getBlockZ());
    }

    private MirrorMob describe(final Entity entity) {
        final Location at = entity.getLocation();
        return new MirrorMob(entity.getUniqueId(), entity.getType().name(), at.getX(), at.getY(),
            at.getZ(), at.getYaw(), at.getPitch(), headYaw(entity, at), entity.isOnGround(),
            worn(entity), carried(entity));
    }

    /**
     * Where the head is turned. Only a living thing has one apart from its body; for a minecart the
     * body's own facing is the whole of it.
     */
    private static float headYaw(final Entity entity, final Location at) {
        return entity instanceof LivingEntity living ? living.getEyeLocation().getYaw() : at.getYaw();
    }

    /**
     * What it is wearing and holding: a skeleton's bow, the helmet a zombie picked up.
     *
     * <p>Worked out every tick rather than only when it changes, which is what the player publisher
     * takes care to avoid. The difference is what else is in the message: a player's carries their
     * skin, so there was a comparison worth making to keep the items out of it, and one of these
     * carries almost nothing else. Mobs that wear anything at all are also a small minority of what
     * this sends, where every player wears something.</p>
     */
    private static Map<String, String> worn(final Entity entity) {
        if (!(entity instanceof LivingEntity living)) {
            return Map.of();
        }
        final EntityEquipment equipment = living.getEquipment();
        if (equipment == null) {
            return Map.of();
        }
        return StillEntities.worn(living);
    }

    /**
     * The stack a dropped item is. Not what it is carrying - it is the stack, and without this it
     * draws as nothing at all.
     */
    private static String carried(final Entity entity) {
        return entity instanceof Item item ? StillEntities.encode(item.getItemStack()) : "";
    }

    private void warnAboutTooMany(final World world) {
        final long now = System.nanoTime();
        if (now - this.lastWarnedAt < WARN_EVERY_NANOS) {
            return;
        }
        this.lastWarnedAt = now;
        this.logger.warning("More than " + MOST_PER_WORLD + " moving things near a border in "
            + world.getName() + ", so the neighbouring shards are being shown some of them and not "
            + "all. A mob farm or a rail yard on a seam is the usual reason");
    }
}
