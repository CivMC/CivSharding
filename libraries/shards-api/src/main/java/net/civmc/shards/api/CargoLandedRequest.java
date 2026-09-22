package net.civmc.shards.api;

import java.util.Objects;
import java.util.UUID;

/**
 * Says a parcel is in this shard's world and on its disk.
 *
 * <p>Sent <strong>after</strong> the chunks holding it have been flushed, never before. That order is
 * the other half of what makes a parcel safe: the sender marks the handover done before destroying
 * its copy, and the receiver persists its copy before marking the parcel consumed. Both ends err the
 * same way, towards the parcel being landed twice - which is harmless, because landing is written to
 * be idempotent - rather than towards it being landed never.</p>
 *
 * @param landedAt where it ended up, so the sender can find it. A rocket's passengers are sent to
 *     this
 */
public record CargoLandedRequest(UUID requestId, String serverName, UUID cargoId, PlayerLocation landedAt,
                                 long createdAtEpochMillis) {

    public CargoLandedRequest {
        Objects.requireNonNull(requestId, "requestId");
        serverName = Messages.requireNonBlank(serverName, "serverName");
        Objects.requireNonNull(cargoId, "cargoId");
        Messages.requirePositive(createdAtEpochMillis, "createdAtEpochMillis");
    }

    public static CargoLandedRequest create(final String serverName, final UUID cargoId,
                                            final PlayerLocation landedAt) {
        return new CargoLandedRequest(UUID.randomUUID(), serverName, cargoId, landedAt,
            System.currentTimeMillis());
    }
}
