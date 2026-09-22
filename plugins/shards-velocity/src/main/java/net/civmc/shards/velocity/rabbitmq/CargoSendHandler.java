package net.civmc.shards.velocity.rabbitmq;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.civmc.shards.api.CargoSendRequest;
import net.civmc.shards.api.CargoSendResponse;
import net.civmc.shards.api.CargoSendStatus;
import net.civmc.shards.api.ShardServerId;
import net.civmc.shards.api.ShardsRabbitMqTopology;
import net.civmc.shards.velocity.cargo.CargoSendResult;
import net.civmc.shards.velocity.cargo.CargoService;
import net.civmc.shards.velocity.placement.ShardPlacementService;
import org.slf4j.Logger;

/**
 * Makes another shard answerable for a parcel.
 *
 * <p>The destination is resolved here rather than by the sender, for the same reason a player
 * transfer's is: the shard map lives in one process, and a server working out for itself where
 * something belongs can only ever do so from a stale copy of it.</p>
 */
public final class CargoSendHandler implements RequestHandler<CargoSendRequest, CargoSendResponse> {

    // The same half second a player transfer allows, and for a related reason. Cargo could perfectly
    // well be left in the table for a shard that is down - that is what the table is for - but the
    // sender destroys its own copy on being told the handover happened, and doing that for a shard
    // that may be down for days puts a rocket's hold out of reach for days
    private static final long REACHABILITY_TIMEOUT_MILLIS = 500L;

    private final CargoService cargoService;
    private final ShardPlacementService placementService;
    private final ProxyServer proxyServer;
    private final Logger logger;

    public CargoSendHandler(final CargoService cargoService, final ShardPlacementService placementService,
                            final ProxyServer proxyServer, final Logger logger) {
        this.cargoService = cargoService;
        this.placementService = placementService;
        this.proxyServer = proxyServer;
        this.logger = logger;
    }

    @Override
    public String queue() {
        return ShardsRabbitMqTopology.CARGO_SEND_QUEUE;
    }

    @Override
    public Class<CargoSendRequest> requestType() {
        return CargoSendRequest.class;
    }

    @Override
    public UUID requestId(final CargoSendRequest request) {
        return request.requestId();
    }

    @Override
    public CargoSendResponse handle(final CargoSendRequest request) {
        final Optional<String> destination = resolveDestination(request);
        if (destination.isEmpty()) {
            return CargoSendResponse.of(request.requestId(), CargoSendStatus.NO_DESTINATION,
                request.targetShard() == null
                    ? "No shard owns that location"
                    : "No shard named " + request.targetShard());
        }

        final Optional<RegisteredServer> target = this.proxyServer.getServer(destination.get());
        if (target.isEmpty()) {
            this.logger.error("Shard {} owns the cargo's destination but is not registered with the proxy",
                destination.get());
            return CargoSendResponse.of(request.requestId(), CargoSendStatus.DESTINATION_UNAVAILABLE,
                "Destination shard is not registered");
        }
        if (!isReachable(target.get(), destination.get())) {
            return CargoSendResponse.of(request.requestId(), CargoSendStatus.DESTINATION_UNAVAILABLE,
                "Destination shard is not answering");
        }

        final UUID owningServerUuid = ShardServerId.of(destination.get());
        final CargoSendResult result;
        try {
            result = this.cargoService.store(request.cargoId(), request.type(), request.serverName(),
                owningServerUuid, Base64.getDecoder().decode(request.payload()), request.targetLocation(),
                request.createdAtEpochMillis());
        } catch (final RuntimeException exception) {
            // Nothing was written, so the sender still has it and must not destroy it
            this.logger.error("Could not store cargo {} from {}", request.cargoId(), request.serverName(),
                exception);
            return CargoSendResponse.of(request.requestId(), CargoSendStatus.STORE_REFUSED,
                "Could not store the cargo");
        }

        return switch (result) {
            case CargoSendResult.Stored ignored -> {
                this.logger.info("Cargo {} ({}) from {} is now {}'s", request.cargoId(), request.type(),
                    request.serverName(), destination.get());
                yield CargoSendResponse.sent(request.requestId(), CargoSendStatus.SENT, destination.get());
            }
            case CargoSendResult.AlreadyStored ignored ->
                CargoSendResponse.sent(request.requestId(), CargoSendStatus.ALREADY_SENT, destination.get());
            case CargoSendResult.IdReused reused -> {
                this.logger.error("{} sent cargo {} to {}, but that id already belongs to {}",
                    request.serverName(), request.cargoId(), destination.get(), reused.owningServerUuid());
                yield CargoSendResponse.of(request.requestId(), CargoSendStatus.STORE_REFUSED,
                    "That cargo id is already in use for another destination");
            }
        };
    }

    private boolean isReachable(final RegisteredServer target, final String destination) {
        try {
            target.ping().get(REACHABILITY_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            return true;
        } catch (final InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        } catch (final ExecutionException | TimeoutException exception) {
            this.logger.warn("Not sending cargo to {}: it is not answering", destination);
            return false;
        }
    }

    private Optional<String> resolveDestination(final CargoSendRequest request) {
        if (request.targetShard() != null) {
            return this.placementService.isShard(request.targetShard())
                ? Optional.of(request.targetShard())
                : Optional.empty();
        }
        return this.placementService.shardFor(request.targetLocation());
    }

    @Override
    public CargoSendResponse failure(final UUID requestId, final String message) {
        return CargoSendResponse.error(requestId, message);
    }
}
