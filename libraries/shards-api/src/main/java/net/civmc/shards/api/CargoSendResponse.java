package net.civmc.shards.api;

import java.util.Objects;
import java.util.UUID;

/**
 * @param destinationShard which shard owns the parcel now, set for {@link CargoSendStatus#SENT} and
 *     {@link CargoSendStatus#ALREADY_SENT} and null otherwise
 */
public record CargoSendResponse(UUID requestId, CargoSendStatus status, String destinationShard,
                                String failureMessage, long completedAtEpochMillis) {

    public CargoSendResponse {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(status, "status");
        failureMessage = failureMessage == null ? "" : failureMessage;
        Messages.requirePositive(completedAtEpochMillis, "completedAtEpochMillis");
    }

    public static CargoSendResponse sent(final UUID requestId, final CargoSendStatus status,
                                         final String destinationShard) {
        return new CargoSendResponse(requestId, status, destinationShard, "", System.currentTimeMillis());
    }

    public static CargoSendResponse of(final UUID requestId, final CargoSendStatus status,
                                       final String failureMessage) {
        return new CargoSendResponse(requestId, status, null, failureMessage, System.currentTimeMillis());
    }

    public static CargoSendResponse error(final UUID requestId, final String failureMessage) {
        return new CargoSendResponse(requestId, CargoSendStatus.ERROR, null, failureMessage,
            System.currentTimeMillis());
    }
}
