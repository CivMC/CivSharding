package net.civmc.shards.api;

import java.util.Objects;
import java.util.UUID;

/**
 * Asks what has become of a parcel this server sent.
 *
 * <p>Read-only and owned by nobody: the sender no longer owns the parcel, and asking after it is not
 * a claim on it. What the sender is waiting for is where it landed, because that is where its
 * passengers have to be sent.</p>
 */
public record CargoStatusRequest(UUID requestId, String serverName, UUID cargoId, long createdAtEpochMillis) {

    public CargoStatusRequest {
        Objects.requireNonNull(requestId, "requestId");
        serverName = Messages.requireNonBlank(serverName, "serverName");
        Objects.requireNonNull(cargoId, "cargoId");
        Messages.requirePositive(createdAtEpochMillis, "createdAtEpochMillis");
    }

    public static CargoStatusRequest create(final String serverName, final UUID cargoId) {
        return new CargoStatusRequest(UUID.randomUUID(), serverName, cargoId, System.currentTimeMillis());
    }
}
