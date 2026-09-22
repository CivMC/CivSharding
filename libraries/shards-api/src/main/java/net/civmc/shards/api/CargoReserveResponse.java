package net.civmc.shards.api;

import java.util.Objects;
import java.util.UUID;

/**
 * @param site the place now written against this parcel, which is what the shard must actually use.
 *     Usually the site it asked for; where one had already been reserved it is that earlier one, and
 *     landing at the site it asked for instead would build a second copy beside the first
 */
public record CargoReserveResponse(UUID requestId, CargoLandStatus status, PlayerLocation site,
                                   String failureMessage, long completedAtEpochMillis) {

    public CargoReserveResponse {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(status, "status");
        failureMessage = failureMessage == null ? "" : failureMessage;
        Messages.requirePositive(completedAtEpochMillis, "completedAtEpochMillis");
    }

    public static CargoReserveResponse reserved(final UUID requestId, final PlayerLocation site) {
        return new CargoReserveResponse(requestId, CargoLandStatus.RECORDED, site, "",
            System.currentTimeMillis());
    }

    public static CargoReserveResponse of(final UUID requestId, final CargoLandStatus status,
                                          final String failureMessage) {
        return new CargoReserveResponse(requestId, status, null, failureMessage, System.currentTimeMillis());
    }

    public static CargoReserveResponse error(final UUID requestId, final String failureMessage) {
        return new CargoReserveResponse(requestId, CargoLandStatus.ERROR, null, failureMessage,
            System.currentTimeMillis());
    }
}
