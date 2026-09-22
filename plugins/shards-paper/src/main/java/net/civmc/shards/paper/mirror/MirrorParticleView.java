package net.civmc.shards.paper.mirror;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.particle.Particle;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.util.Vector3f;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerParticle;
import net.civmc.shards.api.ParticleMessage;
import net.civmc.shards.api.mirror.MirrorParticle;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * Shows this shard's players the particles a neighbouring shard has just shown its own.
 *
 * <p>The last of the things a border was a photograph of. With the ground, the frames, the signs, the
 * people and the animals drawn, what was still missing was every sign of anything <em>happening</em>:
 * a neighbour mining a wall did it silently and without dust, a brewing stand bubbled nothing, an
 * explosion on the far side of a seam was a wall changing shape.</p>
 *
 * <p>Nothing exists here. A particle is a packet and nothing else - there is no entity, nothing in the
 * world, nothing any plugin can find, and nothing that can be stood in, breathed or caught. That is
 * the whole of what makes this safe to draw from another server's word.</p>
 *
 * <p>Sent only to the people close enough to have been sent it had it happened here. A burst drawn to
 * everybody on the server would be a packet each for something happening several hundred blocks
 * away, and the game's own rule - a short distance, or a long one for the few kinds that are meant to
 * be seen from across a valley - is the right one to copy.</p>
 */
public final class MirrorParticleView implements MirrorParticles {

    // What the game uses: a particle is sent to players within this of it, and the few kinds that are
    // meant to be seen from far off - an explosion, a firework - carry a flag that says so
    private static final double SHOW_WITHIN = 32.0;
    private static final double SHOW_FAR_WITHIN = 512.0;

    /**
     * Applies one announcement. Main thread.
     */
    @Override
    public void apply(final ParticleMessage message) {
        if (!available()) {
            return;
        }
        for (final MirrorParticle burst : message.particles()) {
            final Particle<?> particle = Particles.decode(burst.particle());
            if (particle == null) {
                // A particle this build cannot make: a neighbour on a newer game, or one made by a
                // plugin of theirs. Nothing is drawn rather than something else being drawn
                continue;
            }
            final WrapperPlayServerParticle packet = new WrapperPlayServerParticle(particle,
                burst.longDistance(),
                new Vector3d(burst.x(), burst.y(), burst.z()),
                new Vector3f(burst.offsetX(), burst.offsetY(), burst.offsetZ()),
                burst.speed(), burst.count());
            final double within = burst.longDistance() ? SHOW_FAR_WITHIN : SHOW_WITHIN;
            for (final Player viewer : Bukkit.getOnlinePlayers()) {
                if (!viewer.getWorld().getName().equals(message.world())) {
                    continue;
                }
                if (viewer.getLocation().distanceSquared(
                    new Location(viewer.getWorld(), burst.x(), burst.y(), burst.z())) > within * within) {
                    continue;
                }
                PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, packet);
            }
        }
    }

    /**
     * Whether packets can be sent at all. PacketEvents is a soft dependency: everything else about the
     * mirror works without it, and a shard that will not start is worse than one that cannot show the
     * dust off a neighbour's pick.
     */
    private static boolean available() {
        try {
            return PacketEvents.getAPI() != null && PacketEvents.getAPI().isLoaded();
        } catch (final RuntimeException | NoClassDefFoundError exception) {
            return false;
        }
    }
}
