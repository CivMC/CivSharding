package net.civmc.shards.velocity.rabbitmq;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.civmc.shards.api.PlayerLocateRequest;
import net.civmc.shards.api.PlayerLocateResponse;
import net.civmc.shards.api.PlayerLocation;
import net.civmc.shards.api.ShardsRabbitMqTopology;
import net.civmc.shards.velocity.placement.ShardPlacementService;
import net.civmc.shards.velocity.playerdata.PlayerDataService;

/**
 * Says which shard a named player is on.
 *
 * <p>A shard sees only its own players, so a name typed there is either somebody standing in front of
 * you or, as far as that server can tell, nobody at all. That is what made teleporting to a person on
 * another shard impossible rather than merely refused: the command never found them to refuse.</p>
 *
 * <p>Answered from who is connected to the proxy rather than from the store, because a stored row
 * says where somebody was when they were last written back and this question is about now. The store
 * is read only for a hint about where to put the asker down - see {@link #landingSpot} - and the
 * answer is deliberately honest that it is a hint.</p>
 *
 * <p>Nothing is written and no lock is taken. Asking where somebody is must never be able to disturb
 * them.</p>
 */
public final class PlayerLocateHandler implements RequestHandler<PlayerLocateRequest, PlayerLocateResponse> {

    private final PlayerDataService playerDataService;
    private final ShardPlacementService placementService;
    private final ProxyServer proxyServer;

    public PlayerLocateHandler(final PlayerDataService playerDataService,
                               final ShardPlacementService placementService, final ProxyServer proxyServer) {
        this.playerDataService = playerDataService;
        this.placementService = placementService;
        this.proxyServer = proxyServer;
    }

    @Override
    public String queue() {
        return ShardsRabbitMqTopology.PLAYER_LOCATE_QUEUE;
    }

    @Override
    public Map<String, Object> arguments() {
        // Somebody typed a name and is watching for what happens next. One left in the queue is
        // dropped rather than answered after they have given up
        return Map.of("x-message-ttl", ShardsRabbitMqTopology.PLAYER_LOCATE_TTL_MILLIS);
    }

    @Override
    public Class<PlayerLocateRequest> requestType() {
        return PlayerLocateRequest.class;
    }

    @Override
    public UUID requestId(final PlayerLocateRequest request) {
        return request.requestId();
    }

    @Override
    public PlayerLocateResponse handle(final PlayerLocateRequest request) {
        final Optional<Player> player = this.proxyServer.getPlayer(request.playerName());
        if (player.isEmpty()) {
            return PlayerLocateResponse.notFound(request.requestId(), request.playerName());
        }
        final Optional<ServerConnection> connection = player.get().getCurrentServer();
        if (connection.isEmpty()) {
            // Connected to the proxy and not yet to a server: mid-handover, or still being placed.
            // Nobody can be sent to them while they are between two servers
            return PlayerLocateResponse.notFound(request.requestId(), player.get().getUsername());
        }
        final String server = connection.get().getServerInfo().getName();
        if (!this.placementService.isShard(server)) {
            // The lobby, or a server that is not part of the shard map. They are somewhere, but not
            // somewhere a border crossing can reach
            return PlayerLocateResponse.notFound(request.requestId(), player.get().getUsername());
        }
        return PlayerLocateResponse.found(request.requestId(), player.get().getUniqueId(),
            player.get().getUsername(), server, landingSpot(player.get().getUniqueId(), server));
    }

    /**
     * Roughly where they are, for putting somebody down near them.
     *
     * <p>Their last checkpoint, which is a minute old at worst and a whole shard wrong at worst after
     * that: somebody who has crossed a border since is stored somewhere their shard no longer owns.
     * So it is offered only when it still lands on the shard they are actually connected to, and
     * withheld rather than corrected when it does not - the shard name is the part of this answer
     * that is true now, and a caller with no landing spot can still be sent to the right server.</p>
     */
    private PlayerLocation landingSpot(final UUID playerUuid, final String server) {
        return this.playerDataService.storedLocation(playerUuid)
            .filter(location -> this.placementService.shardFor(location)
                .map(server::equals)
                .orElse(false))
            .orElse(null);
    }

    @Override
    public PlayerLocateResponse failure(final UUID requestId, final String message) {
        return PlayerLocateResponse.error(requestId, message);
    }
}
