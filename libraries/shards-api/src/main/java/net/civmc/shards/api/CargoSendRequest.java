package net.civmc.shards.api;

import java.util.Objects;
import java.util.UUID;

/**
 * Hands a parcel to whichever shard owns where it is going.
 *
 * <p>Addressed the same two ways a player transfer is - by location, or by shard name - and for the
 * same reason: the sender never resolves the shard map itself.</p>
 *
 * <p>An answer of {@link CargoSendStatus#SENT} is an instruction as much as a result. The
 * destination owns the parcel from that moment, so the sender must destroy its own copy and make
 * that destruction durable. A sender that keeps it has duplicated it.</p>
 *
 * @param cargoId the sender's own id for this parcel, so that re-sending after a lost answer is the
 *     same parcel rather than a second one
 */
public record CargoSendRequest(UUID requestId, String serverName, UUID cargoId, String type, String payload,
                               PlayerLocation targetLocation, String targetShard,
                               long createdAtEpochMillis) {

    public CargoSendRequest {
        Objects.requireNonNull(requestId, "requestId");
        serverName = Messages.requireNonBlank(serverName, "serverName");
        Objects.requireNonNull(cargoId, "cargoId");
        type = Messages.requireNonBlank(type, "type");
        Objects.requireNonNull(payload, "payload");
        targetShard = targetShard == null || targetShard.isBlank() ? null : targetShard.trim();
        if (targetLocation == null && targetShard == null) {
            throw new IllegalArgumentException("a parcel needs either a targetLocation or a targetShard");
        }
        if (targetLocation != null && targetShard != null) {
            throw new IllegalArgumentException("a parcel cannot have both a targetLocation and a targetShard");
        }
        Messages.requirePositive(createdAtEpochMillis, "createdAtEpochMillis");
    }

    public static CargoSendRequest toLocation(final String serverName, final UUID cargoId, final String type,
                                              final String payload, final PlayerLocation targetLocation) {
        return new CargoSendRequest(UUID.randomUUID(), serverName, cargoId, type, payload,
            Objects.requireNonNull(targetLocation, "targetLocation"), null, System.currentTimeMillis());
    }

    public static CargoSendRequest toShard(final String serverName, final UUID cargoId, final String type,
                                           final String payload, final String targetShard) {
        return new CargoSendRequest(UUID.randomUUID(), serverName, cargoId, type, payload, null,
            Messages.requireNonBlank(targetShard, "targetShard"), System.currentTimeMillis());
    }
}
