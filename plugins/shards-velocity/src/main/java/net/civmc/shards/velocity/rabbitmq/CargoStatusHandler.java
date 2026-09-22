package net.civmc.shards.velocity.rabbitmq;

import java.util.Optional;
import java.util.UUID;
import net.civmc.shards.api.CargoStatusRequest;
import net.civmc.shards.api.CargoStatusResponse;
import net.civmc.shards.api.ShardsRabbitMqTopology;
import net.civmc.shards.velocity.cargo.CargoService;
import net.civmc.shards.velocity.database.CargoRow;
import org.slf4j.Logger;

/**
 * Says what has become of a parcel, for the shard that sent it.
 *
 * <p>What the sender is really waiting for is where it was put down: a rocket's passengers have to be
 * sent to the place its hold ended up, and only the shard that landed it knows where that is.
 * Read-only - asking after a parcel is not a claim on it, and the sender gave up owning it the moment
 * it was stored.</p>
 */
public final class CargoStatusHandler implements RequestHandler<CargoStatusRequest, CargoStatusResponse> {

    private final CargoService cargoService;
    private final Logger logger;

    public CargoStatusHandler(final CargoService cargoService, final Logger logger) {
        this.cargoService = cargoService;
        this.logger = logger;
    }

    @Override
    public String queue() {
        return ShardsRabbitMqTopology.CARGO_STATUS_QUEUE;
    }

    @Override
    public Class<CargoStatusRequest> requestType() {
        return CargoStatusRequest.class;
    }

    @Override
    public UUID requestId(final CargoStatusRequest request) {
        return request.requestId();
    }

    @Override
    public CargoStatusResponse handle(final CargoStatusRequest request) {
        try {
            final Optional<CargoRow> row = this.cargoService.find(request.cargoId());
            // No row is an answer, not a failure: it was never sent, or it landed long enough ago to
            // have been pruned. The caller tells those apart by whether it has just sent it
            return row.map(found -> CargoStatusResponse.of(request.requestId(), found.cargoState(),
                    found.owningServerUuid().toString(), found.landedAt()))
                .orElseGet(() -> CargoStatusResponse.of(request.requestId(), null, null, null));
        } catch (final RuntimeException exception) {
            this.logger.error("Could not read cargo {}", request.cargoId(), exception);
            return CargoStatusResponse.error(request.requestId(), "Could not read cargo");
        }
    }

    @Override
    public CargoStatusResponse failure(final UUID requestId, final String message) {
        return CargoStatusResponse.error(requestId, message);
    }
}
