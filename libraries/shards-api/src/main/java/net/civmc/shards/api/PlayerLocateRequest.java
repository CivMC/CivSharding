package net.civmc.shards.api;

import java.util.Objects;
import java.util.UUID;

/**
 * Asks the proxy where a named player is on the network.
 *
 * <p>A shard knows only the people connected to it, so a name typed by somebody standing here is
 * either one of ours or, as far as this server can tell, nobody at all. The proxy holds every
 * connection, so it is the only process that can turn a name into a shard.</p>
 *
 * <p>Nothing is written and no lock is taken: this is a question about where somebody is, asked while
 * they carry on playing, and the answer stops being true the moment they walk.</p>
 */
public record PlayerLocateRequest(UUID requestId, String serverName, String playerName,
                                  long createdAtEpochMillis) {

    public PlayerLocateRequest {
        Objects.requireNonNull(requestId, "requestId");
        serverName = Messages.requireNonBlank(serverName, "serverName");
        playerName = Messages.requireNonBlank(playerName, "playerName");
        Messages.requirePositive(createdAtEpochMillis, "createdAtEpochMillis");
    }

    public static PlayerLocateRequest create(final String serverName, final String playerName) {
        return new PlayerLocateRequest(UUID.randomUUID(), serverName, playerName, System.currentTimeMillis());
    }
}
