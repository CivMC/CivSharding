package net.civmc.shards.api;

import java.util.List;
import java.util.Objects;
import net.civmc.shards.api.mirror.MirrorParticle;

/**
 * The particles a shard has just shown, announced to whoever can see that ground.
 *
 * <p>A fanout like the rest of the mirror, and events rather than state: unlike a position, a particle
 * is not replaced by the next one - it happened once, and a message that is lost is a burst nobody
 * across the border saw. That is why these are sent as they happen rather than once a tick per thing,
 * and why an old one is dropped rather than applied: a puff of smoke a second late is worse than one
 * missed, because it is smoke from nothing.</p>
 *
 * <p>Only bursts near this shard's own outline, and only for what its own players are being shown.
 * The server sends a particle packet per person watching, so the same burst arrives here many times
 * over; one of them is announced and the rest are dropped, or a crowded seam would be announced once
 * per pair of eyes.</p>
 */
public record ParticleMessage(String serverName, String world, List<MirrorParticle> particles,
                              long createdAtEpochMillis) {

    public ParticleMessage {
        serverName = Messages.requireNonBlank(serverName, "serverName");
        world = Messages.requireNonBlank(world, "world");
        Objects.requireNonNull(particles, "particles");
        particles = List.copyOf(particles);
        Messages.requirePositive(createdAtEpochMillis, "createdAtEpochMillis");
    }

    public static ParticleMessage create(final String serverName, final String world,
                                         final List<MirrorParticle> particles) {
        return new ParticleMessage(serverName, world, particles, System.currentTimeMillis());
    }
}
