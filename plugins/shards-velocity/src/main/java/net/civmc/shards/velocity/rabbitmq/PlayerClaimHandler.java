package net.civmc.shards.velocity.rabbitmq;

import java.util.Base64;
import java.util.UUID;
import net.civmc.shards.api.ClaimStatus;
import net.civmc.shards.api.PlayerClaimRequest;
import net.civmc.shards.api.PlayerClaimResponse;
import net.civmc.shards.api.ShardServerId;
import net.civmc.shards.api.ShardsRabbitMqTopology;
import net.civmc.shards.velocity.placement.ShardPlacementService;
import net.civmc.shards.velocity.playerdata.ClaimResult;
import net.civmc.shards.velocity.playerdata.InFlightTransfers;
import net.civmc.shards.velocity.playerdata.PlayerDataService;
import org.slf4j.Logger;

/**
 * Takes ownership of a player's data on behalf of the server they are logging in to.
 *
 * <p>Only for a server in the shard map. The network has servers that are not shards - the holding
 * lobby above all - where what a player carries is that server's own business and has no bearing on
 * the inventory the shards share. Handing one of those a lock would stop the shards loading that
 * player for as long as they stood in a lobby, and would eventually write a lobby's idea of them
 * over the real thing.</p>
 */
public final class PlayerClaimHandler implements RequestHandler<PlayerClaimRequest, PlayerClaimResponse> {

    private final PlayerDataService playerDataService;
    private final ShardPlacementService placementService;
    private final InFlightTransfers inFlightTransfers;
    private final Logger logger;

    public PlayerClaimHandler(final PlayerDataService playerDataService,
                              final ShardPlacementService placementService,
                              final InFlightTransfers inFlightTransfers, final Logger logger) {
        this.playerDataService = playerDataService;
        this.placementService = placementService;
        this.inFlightTransfers = inFlightTransfers;
        this.logger = logger;
    }

    @Override
    public String queue() {
        return ShardsRabbitMqTopology.PLAYER_CLAIM_QUEUE;
    }

    @Override
    public Class<PlayerClaimRequest> requestType() {
        return PlayerClaimRequest.class;
    }

    @Override
    public UUID requestId(final PlayerClaimRequest request) {
        return request.requestId();
    }

    @Override
    public PlayerClaimResponse handle(final PlayerClaimRequest request) {
        // Checked here rather than trusted to the asking server, because the shard map lives here and
        // nowhere else. A server that asks anyway is running against a different idea of the map, so
        // it is said out loud - but its players are let in owning nothing, which is what a server
        // outside the map is supposed to do
        if (!this.placementService.isShard(request.serverName())) {
            this.logger.warn("{} is not a shard, so it was given no lock on {}. Nothing it does to "
                + "that player is written back", request.serverName(), request.playerUuid());
            return PlayerClaimResponse.notAShard(request.requestId());
        }
        final ClaimResult result = this.playerDataService.claim(request.playerUuid(),
            ShardServerId.of(request.serverName()));
        // Read whatever the outcome, so a refused claim does not leave the record to greet them on a
        // later login that has nothing to do with a crossing
        final boolean arriving = this.inFlightTransfers.consumeIsArriving(request.playerUuid());
        return switch (result) {
            case ClaimResult.Loaded loaded -> PlayerClaimResponse.loaded(request.requestId(),
                loaded.payload() == null ? null : Base64.getEncoder().encodeToString(loaded.payload()),
                loaded.location(), arriving);
            case ClaimResult.NewPlayer ignored -> PlayerClaimResponse.newPlayer(request.requestId());
            case ClaimResult.HeldBy heldBy -> {
                // Expected during a handover, when the server being left has not released yet. Logged
                // at info because a claim refused for a player nobody is transferring is worth seeing
                this.logger.info("Refused claim of {} for {}: still held by {}", request.playerUuid(),
                    request.serverName(), heldBy.server());
                yield PlayerClaimResponse.heldByOther(request.requestId(), heldBy.server());
            }
        };
    }

    @Override
    public PlayerClaimResponse failure(final UUID requestId, final String message) {
        return PlayerClaimResponse.error(requestId, message);
    }
}
