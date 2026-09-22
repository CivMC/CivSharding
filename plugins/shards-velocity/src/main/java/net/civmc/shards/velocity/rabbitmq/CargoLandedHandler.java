package net.civmc.shards.velocity.rabbitmq;

import java.util.UUID;
import net.civmc.shards.api.CargoLandStatus;
import net.civmc.shards.api.CargoLandedRequest;
import net.civmc.shards.api.CargoLandedResponse;
import net.civmc.shards.api.ShardServerId;
import net.civmc.shards.api.ShardsRabbitMqTopology;
import net.civmc.shards.velocity.cargo.CargoLandResult;
import net.civmc.shards.velocity.cargo.CargoService;
import org.slf4j.Logger;

/**
 * Records that a shard has put a parcel in its world and flushed it to disk.
 */
public final class CargoLandedHandler implements RequestHandler<CargoLandedRequest, CargoLandedResponse> {

    private final CargoService cargoService;
    private final Logger logger;

    public CargoLandedHandler(final CargoService cargoService, final Logger logger) {
        this.cargoService = cargoService;
        this.logger = logger;
    }

    @Override
    public String queue() {
        return ShardsRabbitMqTopology.CARGO_LANDED_QUEUE;
    }

    @Override
    public Class<CargoLandedRequest> requestType() {
        return CargoLandedRequest.class;
    }

    @Override
    public UUID requestId(final CargoLandedRequest request) {
        return request.requestId();
    }

    @Override
    public CargoLandedResponse handle(final CargoLandedRequest request) {
        final CargoLandResult result = this.cargoService.landed(request.cargoId(),
            ShardServerId.of(request.serverName()), request.landedAt());
        return switch (result) {
            case CargoLandResult.Recorded ignored -> {
                this.logger.info("Cargo {} has been landed by {}", request.cargoId(), request.serverName());
                yield CargoLandedResponse.of(request.requestId(), CargoLandStatus.RECORDED, null);
            }
            case CargoLandResult.NoRow ignored -> {
                this.logger.warn("{} landed cargo {}, which no row knows about", request.serverName(),
                    request.cargoId());
                yield CargoLandedResponse.of(request.requestId(), CargoLandStatus.NO_ROW, "No such cargo");
            }
            case CargoLandResult.HeldByOther heldByOther -> {
                // A shard has put something in its world that belongs to another shard, so it now
                // exists in two places. Nothing here can take it back; saying so is what makes it
                // findable
                this.logger.error("{} landed cargo {}, which belongs to {}", request.serverName(),
                    request.cargoId(), heldByOther.owningServerUuid());
                yield CargoLandedResponse.of(request.requestId(), CargoLandStatus.NOT_HELD,
                    "That cargo belongs to another shard");
            }
        };
    }

    @Override
    public CargoLandedResponse failure(final UUID requestId, final String message) {
        return CargoLandedResponse.error(requestId, message);
    }
}
