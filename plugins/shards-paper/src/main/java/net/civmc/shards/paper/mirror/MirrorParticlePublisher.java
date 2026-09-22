package net.civmc.shards.paper.mirror;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerParticle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import net.civmc.shards.api.ParticleMessage;
import net.civmc.shards.api.mirror.MirrorParticle;
import net.civmc.shards.paper.border.ShardBorder;
import net.civmc.shards.paper.rabbitmq.ShardsClient;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * Tells the other shards about the particles this one has just shown.
 *
 * <p>Almost everything that happens in a place and is neither a block nor a mob is a particle, and
 * none of it crossed a border: a neighbour mining, brewing, fighting or setting off dynamite did all of
 * it in a silent, motionless photograph of their town. This is the channel for the whole class of them
 * rather than one for each, because they are all the same packet.</p>
 *
 * <p><strong>Read off the wire, because nothing else says.</strong> There is no event for "a particle
 * was shown": a plugin, a block, a mob and the game itself all produce them and none of them announce
 * it. What they have in common is the packet, so the packet is where this listens - the same trick the
 * entity field numbers are read with, and for the same reason.</p>
 *
 * <p><strong>Three filters, in this order, because the first is the cheap one.</strong> The server
 * sends a particle packet per person who can see it, so a burst near a crowded seam arrives here once
 * per pair of eyes and most of them are for people nowhere near a border:</p>
 *
 * <ul>
 *   <li>Is this viewer near a border at all? Answered from a set the main thread keeps up to date, so
 *       a packet to somebody a thousand blocks inland costs one lookup and is not even parsed.</li>
 *   <li>Is the burst itself near this shard's outline, and on ground this shard owns? Ours to announce
 *       only if both - a particle this server showed over a neighbour's ground is its own business,
 *       exactly as its cows wandering there are.</li>
 *   <li>Has this same burst already been announced this tick? The copies sent to everybody else are
 *       dropped here, which is what keeps a crowd from multiplying the traffic.</li>
 * </ul>
 *
 * <p>Capped per tick as well. A shard is allowed to spend a little of its bandwidth making a
 * neighbour's town look alive; it is not allowed to spend a tick of everybody's on an explosion.</p>
 */
public final class MirrorParticlePublisher extends PacketListenerAbstract {

    // The same as the players' and the mobs', so what is announced is what a neighbour might draw
    private static final int PUBLISH_RADIUS = 192;
    // Per tick, across every world. Deliberately not the whole of what a busy seam produces: the point
    // is that a border looks alive, not that it is exact
    private static final int MOST_PER_TICK = 64;
    // What the network thread may hold before it starts dropping. A tick's worth many times over, and
    // it can only fill up if the main thread has stopped draining it
    private static final int PENDING_CAP = 1024;
    // One complaint a minute at most, however many bursts are being dropped
    private static final long WARN_EVERY_NANOS = TimeUnit.MINUTES.toNanos(1L);

    private final ShardBorder border;
    private final ShardsClient client;
    private final String serverName;
    private final Logger logger;
    // Handed from the network threads to the main thread, which is the only place the world, the shard
    // map and the players may be read
    private final Queue<Seen> pending = new ConcurrentLinkedQueue<>();
    // Who is close enough to a border for what they are shown to be worth announcing. Written by the
    // main thread once a tick, read by the network threads, and the whole reason this is affordable
    private final Set<UUID> nearABorder = ConcurrentHashMap.newKeySet();
    private long lastWarnedAt;

    public MirrorParticlePublisher(final ShardBorder border, final ShardsClient client,
                                   final String serverName, final Logger logger) {
        this.border = border;
        this.client = client;
        this.serverName = serverName;
        this.logger = logger;
    }

    /**
     * Starts listening. Here rather than at the call site so nothing outside this package has to name
     * the packet library to switch it on.
     */
    public void watch() {
        PacketEvents.getAPI().getEventManager().registerListener(this);
    }

    /**
     * Network thread. Does as little as it possibly can, and nothing at all for most packets.
     */
    @Override
    public void onPacketSend(final PacketSendEvent event) {
        if (!PacketType.Play.Server.PARTICLE.equals(event.getPacketType())) {
            return;
        }
        final UUID viewer = event.getUser() == null ? null : event.getUser().getUUID();
        if (viewer == null || !this.nearABorder.contains(viewer)) {
            // Nowhere near a seam, so nothing they are shown is anybody else's business. Not parsed
            return;
        }
        if (this.pending.size() >= PENDING_CAP) {
            return;
        }
        try {
            final WrapperPlayServerParticle packet = new WrapperPlayServerParticle(event);
            // Written out here rather than queued as a packet. A wrapper is a view over the buffer the
            // packet is being sent from, and that buffer is gone by the time the main thread runs -
            // so everything wanted from it is taken now, while it is still there to take. Encoding a
            // particle is arithmetic and touches nothing of the server's
            final String encoded = Particles.encode(packet.getParticle());
            if (encoded == null) {
                return;
            }
            this.pending.add(new Seen(viewer, encoded, packet.getPosition().getX(),
                packet.getPosition().getY(), packet.getPosition().getZ(), packet.getOffset().getX(),
                packet.getOffset().getY(), packet.getOffset().getZ(), packet.getParticleCount(),
                packet.getMaxSpeed(), packet.isLongDistance()));
        } catch (final RuntimeException unreadable) {
            // Never a line each: this sees every particle on the server and the only consequence of
            // failing to read one is that it is not announced
            this.logger.finest(() -> "Could not read a particle packet: " + unreadable);
        }
    }

    /**
     * Announces what was seen, and works out who is near a border for the next tick. Main thread, once
     * a tick.
     */
    public void publish() {
        refreshWhoIsNearABorder();
        if (!this.border.isConfigured()) {
            this.pending.clear();
            return;
        }
        final Map<String, List<MirrorParticle>> byWorld = new HashMap<>();
        // One burst, however many people were shown it. Kept only for this drain: the next tick's
        // copies of a repeating particle are a new burst and should be announced again
        final Set<String> alreadyAnnounced = new HashSet<>();
        int announced = 0;
        boolean dropped = false;
        Seen seen;
        while ((seen = this.pending.poll()) != null) {
            final Player viewer = Bukkit.getPlayer(seen.viewer());
            if (viewer == null) {
                continue;
            }
            if (announced >= MOST_PER_TICK) {
                dropped = true;
                continue;
            }
            final int blockX = (int) Math.floor(seen.x());
            final int blockZ = (int) Math.floor(seen.z());
            if (this.border.isOutside(blockX, blockZ)
                || this.border.distanceToOutline(blockX, blockZ) > PUBLISH_RADIUS) {
                // Over a neighbour's ground, or too far inside this shard for anybody else to see it
                continue;
            }
            // The world the packet was on the way to, taken from whose connection it was on: a
            // position on its own does not say which world it is in
            final String world = viewer.getWorld().getName();
            if (!alreadyAnnounced.add(key(world, seen))) {
                continue;
            }
            byWorld.computeIfAbsent(world, ignored -> new ArrayList<>()).add(new MirrorParticle(
                seen.encoded(), seen.x(), seen.y(), seen.z(), seen.offsetX(), seen.offsetY(),
                seen.offsetZ(), seen.count(), seen.speed(), seen.longDistance()));
            announced++;
        }
        if (dropped) {
            warnAboutTooMany();
        }
        for (final Map.Entry<String, List<MirrorParticle>> world : byWorld.entrySet()) {
            this.client.publishParticles(
                ParticleMessage.create(this.serverName, world.getKey(), world.getValue()));
        }
    }

    /**
     * Who the network threads should bother reading packets for.
     *
     * <p>Worked out here because it is the main thread that may ask a player where they are. Being one
     * tick out of date is harmless: somebody who has just walked into range has their first burst or
     * two not announced, and somebody who has walked out has one announced that nobody will draw.</p>
     */
    private void refreshWhoIsNearABorder() {
        if (!this.border.isConfigured()) {
            this.nearABorder.clear();
            return;
        }
        final Set<UUID> near = new HashSet<>();
        for (final Player player : Bukkit.getOnlinePlayers()) {
            final Location at = player.getLocation();
            if (this.border.outlineWithin(at.getBlockX(), at.getBlockZ(), PUBLISH_RADIUS)) {
                near.add(player.getUniqueId());
            }
        }
        this.nearABorder.retainAll(near);
        this.nearABorder.addAll(near);
    }

    /**
     * What makes two packets the same burst: the same particle, in the same place, of the same size.
     *
     * <p>The position to the block rather than exactly, because the game aims a burst slightly
     * differently for each person it sends it to.</p>
     */
    private static String key(final String world, final Seen seen) {
        return world + ':' + Math.round(seen.x() * 4.0) + ':' + Math.round(seen.y() * 4.0)
            + ':' + Math.round(seen.z() * 4.0) + ':' + seen.count() + ':' + seen.encoded();
    }

    private void warnAboutTooMany() {
        final long now = System.nanoTime();
        if (now - this.lastWarnedAt < WARN_EVERY_NANOS) {
            return;
        }
        this.lastWarnedAt = now;
        this.logger.info("More than " + MOST_PER_TICK + " bursts of particles in one tick near a "
            + "border, so the neighbouring shards are being shown some of them and not all");
    }

    /**
     * One burst as it was read off the wire: no packet, no buffer, nothing that stops being valid.
     *
     * @param viewer whose connection it was on, which is how the world it was in is found later. A
     *     uuid rather than anything live, because this crosses from a network thread
     */
    private record Seen(UUID viewer, String encoded, double x, double y, double z, float offsetX,
                        float offsetY, float offsetZ, int count, float speed, boolean longDistance) {
    }
}
