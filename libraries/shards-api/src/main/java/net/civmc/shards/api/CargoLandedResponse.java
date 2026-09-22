package net.civmc.shards.api;

import java.util.Objects;
import java.util.UUID;

public record CargoLandedResponse(UUID requestId, CargoLandStatus status, String failureMessage,
                                  long completedAtEpochMillis) {

    public CargoLandedResponse {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(status, "status");
        failureMessage = failureMessage == null ? "" : failureMessage;
        Messages.requirePositive(completedAtEpochMillis, "completedAtEpochMillis");
    }

    public static CargoLandedResponse of(final UUID requestId, final CargoLandStatus status,
                                         final String failureMessage) {
        return new CargoLandedResponse(requestId, status, failureMessage, System.currentTimeMillis());
    }

    public static CargoLandedResponse error(final UUID requestId, final String failureMessage) {
        return new CargoLandedResponse(requestId, CargoLandStatus.ERROR, failureMessage,
            System.currentTimeMillis());
    }
}
