package net.civmc.shards.api;

import java.util.Objects;
import java.util.UUID;

/**
 * Asks for the parcels this shard owns and has not yet put in its world.
 *
 * <p>Asked on a timer and at startup, rather than the proxy pushing an arrival. A push would be
 * quicker and is not what makes this correct: an announcement can be missed, and a parcel that is
 * missed is a rocket's hold that never arrives. Polling a table is the mechanism that cannot lose
 * one, so it is the only one - a shard that has been down for a week collects everything waiting for
 * it the moment it comes back.</p>
 */
public record CargoFetchRequest(UUID requestId, String serverName, long createdAtEpochMillis) {

    public CargoFetchRequest {
        Objects.requireNonNull(requestId, "requestId");
        serverName = Messages.requireNonBlank(serverName, "serverName");
        Messages.requirePositive(createdAtEpochMillis, "createdAtEpochMillis");
    }

    public static CargoFetchRequest create(final String serverName) {
        return new CargoFetchRequest(UUID.randomUUID(), serverName, System.currentTimeMillis());
    }
}
