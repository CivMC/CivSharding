package net.civmc.shards.api;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record CargoFetchResponse(UUID requestId, List<CargoParcel> parcels, String failureMessage,
                                 long completedAtEpochMillis) {

    public CargoFetchResponse {
        Objects.requireNonNull(requestId, "requestId");
        parcels = parcels == null ? List.of() : List.copyOf(parcels);
        failureMessage = failureMessage == null ? "" : failureMessage;
        Messages.requirePositive(completedAtEpochMillis, "completedAtEpochMillis");
    }

    public boolean failed() {
        return !this.failureMessage.isEmpty();
    }

    public static CargoFetchResponse of(final UUID requestId, final List<CargoParcel> parcels) {
        return new CargoFetchResponse(requestId, parcels, "", System.currentTimeMillis());
    }

    public static CargoFetchResponse error(final UUID requestId, final String failureMessage) {
        return new CargoFetchResponse(requestId, List.of(),
            failureMessage == null || failureMessage.isBlank() ? "Cargo could not be read" : failureMessage,
            System.currentTimeMillis());
    }
}
