package net.civmc.shards.paper.mirror;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.nbt.NBT;
import com.github.retrooper.packetevents.protocol.nbt.NBTLimiter;
import com.github.retrooper.packetevents.protocol.nbt.serializer.DefaultNBTSerializer;
import com.github.retrooper.packetevents.protocol.particle.Particle;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Base64;

/**
 * Writing a particle down and reading it back.
 *
 * <p>A particle is a type and its data, and the data is a different shape for every type: a block
 * particle carries a block state, a dust carries a colour, a vibration carries where it is going. So
 * there is no short list of fields that would carry all of them, and picking out the few that could be
 * described by hand would mean a border where some particles cross and the rest quietly do not.</p>
 *
 * <p>It is written out in the game's own encoding instead, which is the only description guaranteed to
 * hold everything a particle can be. Both shards run the same version - the mirror could not draw a
 * block otherwise - so what one writes the other reads.</p>
 *
 * <p>Nothing here throws. A particle that cannot be written is not announced and one that cannot be
 * read is not drawn, which is the same as what happened before any of this existed: nothing.</p>
 */
final class Particles {

    private Particles() {
    }

    /**
     * @return the particle as Base64 of the game's own encoding, or null if it cannot be written
     */
    static String encode(final Particle<?> particle) {
        try {
            final NBT nbt = Particle.encode(particle, version());
            final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                // Unnamed: this is one tag on its own rather than a tag in a compound, and the far
                // side reads it the same way
                DefaultNBTSerializer.INSTANCE.serializeTag(out, nbt, false);
            }
            return Base64.getEncoder().encodeToString(bytes.toByteArray());
        } catch (final IOException | RuntimeException cannotWriteIt) {
            return null;
        }
    }

    /**
     * @return the particle again, or null if this build cannot make sense of it
     */
    static Particle<?> decode(final String encoded) {
        try (DataInputStream in = new DataInputStream(
            new ByteArrayInputStream(Base64.getDecoder().decode(encoded)))) {
            final NBT nbt = DefaultNBTSerializer.INSTANCE.deserializeTag(NBTLimiter.noop(), in, false);
            return Particle.decode(nbt, version());
        } catch (final IOException | RuntimeException cannotReadIt) {
            return null;
        }
    }

    private static ClientVersion version() {
        return PacketEvents.getAPI().getServerManager().getVersion().toClientVersion();
    }
}
