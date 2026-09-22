package net.civmc.shards.api;

import java.util.Objects;
import java.util.UUID;

/**
 * Where the proxy last saw a named player.
 *
 * @param playerUuid who the name turned out to be, or null when nobody by that name is connected
 * @param playerName their name as they are really spelt, since the name asked about was typed
 * @param shardName the shard they are connected to now, or null when they are not on one
 * @param location where they were when their data was last written back, or null when that is not
 *     known or does not belong to {@code shardName} any more. Only ever a hint about where to put
 *     somebody down: it is as old as the last checkpoint, and whoever uses it has to cope with a
 *     player who has since walked away from it
 */
public record PlayerLocateResponse(UUID requestId, UUID playerUuid, String playerName, String shardName,
                                   PlayerLocation location, String failureMessage,
                                   long completedAtEpochMillis) {

    public PlayerLocateResponse {
        Objects.requireNonNull(requestId, "requestId");
        playerName = playerName == null ? "" : playerName;
        shardName = shardName == null || shardName.isBlank() ? null : shardName.trim();
        failureMessage = failureMessage == null ? "" : failureMessage;
        Messages.requirePositive(completedAtEpochMillis, "completedAtEpochMillis");
    }

    public static PlayerLocateResponse found(final UUID requestId, final UUID playerUuid,
                                             final String playerName, final String shardName,
                                             final PlayerLocation location) {
        return new PlayerLocateResponse(requestId, playerUuid, playerName, shardName, location, "",
            System.currentTimeMillis());
    }

    /**
     * Nobody by that name is connected anywhere. Not an error: it is the ordinary answer to a
     * mistyped name, and it is the answer the asker shows to whoever typed it.
     */
    public static PlayerLocateResponse notFound(final UUID requestId, final String playerName) {
        return new PlayerLocateResponse(requestId, null, playerName, null, null, "",
            System.currentTimeMillis());
    }

    public static PlayerLocateResponse error(final UUID requestId, final String failureMessage) {
        return new PlayerLocateResponse(requestId, null, "", null, null, failureMessage,
            System.currentTimeMillis());
    }

    public boolean failed() {
        return !this.failureMessage.isEmpty();
    }

    /**
     * Whether they are somewhere this network can send anybody: connected, and connected to a shard.
     */
    public boolean isOnAShard() {
        return this.playerUuid != null && this.shardName != null;
    }
}
