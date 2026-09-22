package net.civmc.shards.velocity.rabbitmq;

import java.util.UUID;
import net.civmc.shards.api.CargoLandStatus;
import net.civmc.shards.api.CargoReserveResponse;
import net.civmc.shards.api.CargoReserveRequest;
import net.civmc.shards.api.ShardServerId;
import net.civmc.shards.api.ShardsRabbitMqTopology;
import net.civmc.shards.velocity.cargo.CargoLandResult;
import net.civmc.shards.velocity.cargo.CargoService;
import org.slf4j.Logger;

/**
 * Writes down where a shard is about to put a parcel, before it puts it there.
 *
 * <p>Answers with the same statuses a landing does, because the same three things can be wrong with
 * it and they mean the same: no such parcel, somebody else's parcel, or recorded.</p>
 */
public final class CargoReserveHandler implements RequestHandler<CargoReserveRequest, CargoReserveResponse> {

    private final CargoService cargoService;
    private final Logger logger;

    public CargoReserveHandler(final CargoService cargoService, final Logger logger) {
        this.cargoService = cargoService;
        this.logger = logger;
    }

    @Override
    public String queue() {
        return ShardsRabbitMqTopology.CARGO_RESERVE_QUEUE;
    }

    @Override
    public Class<CargoReserveRequest> requestType() {
        return CargoReserveRequest.class;
    }

    @Override
    public UUID requestId(final CargoReserveRequest request) {
        return request.requestId();
    }

    @Override
    public CargoReserveResponse handle(final CargoReserveRequest request) {
        final CargoService.CargoReserveResult result = this.cargoService.reserve(request.cargoId(),
            ShardServerId.of(request.serverName()), request.site());
        return switch (result.outcome()) {
            case CargoLandResult.Recorded ignored ->
                CargoReserveResponse.reserved(request.requestId(), result.site());
            case CargoLandResult.NoRow ignored ->
                CargoReserveResponse.of(request.requestId(), CargoLandStatus.NO_ROW, "No such cargo");
            case CargoLandResult.HeldByOther heldByOther -> {
                this.logger.error("{} tried to reserve a site for cargo {}, which belongs to {}",
                    request.serverName(), request.cargoId(), heldByOther.owningServerUuid());
                yield CargoReserveResponse.of(request.requestId(), CargoLandStatus.NOT_HELD,
                    "That cargo belongs to another shard");
            }
        };
    }

    @Override
    public CargoReserveResponse failure(final UUID requestId, final String message) {
        return CargoReserveResponse.error(requestId, message);
    }
}
