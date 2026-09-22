package net.civmc.shards.api;

import java.util.Objects;
import java.util.UUID;

/**
 * @param state null when no parcel goes by that id - either it was never sent, or it landed long
 *     enough ago that the row has been pruned
 * @param landedAt where it was put down, set only once the state is {@link CargoState#LANDED}
 */
public record CargoStatusResponse(UUID requestId, CargoState state, String owningShard,
                                  PlayerLocation landedAt, String failureMessage,
                                  long completedAtEpochMillis) {

    public CargoStatusResponse {
        Objects.requireNonNull(requestId, "requestId");
        failureMessage = failureMessage == null ? "" : failureMessage;
        Messages.requirePositive(completedAtEpochMillis, "completedAtEpochMillis");
    }

    public boolean failed() {
        return !this.failureMessage.isEmpty();
    }

    public static CargoStatusResponse of(final UUID requestId, final CargoState state, final String owningShard,
                                         final PlayerLocation landedAt) {
        return new CargoStatusResponse(requestId, state, owningShard, landedAt, "", System.currentTimeMillis());
    }

    public static CargoStatusResponse error(final UUID requestId, final String failureMessage) {
        return new CargoStatusResponse(requestId, null, null, null,
            failureMessage == null || failureMessage.isBlank() ? "Cargo could not be read" : failureMessage,
            System.currentTimeMillis());
    }
}
