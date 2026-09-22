package net.civmc.shards.velocity.rabbitmq;

import java.util.List;
import java.util.UUID;
import net.civmc.shards.api.CargoFetchRequest;
import net.civmc.shards.api.CargoFetchResponse;
import net.civmc.shards.api.CargoParcel;
import net.civmc.shards.api.ShardServerId;
import net.civmc.shards.api.ShardsRabbitMqTopology;
import net.civmc.shards.velocity.cargo.CargoService;
import org.slf4j.Logger;

/**
 * Tells a shard what it owns and has not yet put down.
 *
 * <p>A shard asks; the proxy does not announce. An announcement is quicker and would still need this
 * behind it, because a parcel whose announcement was missed is somebody's belongings that never
 * arrive - so the mechanism that cannot miss one is the only one here.</p>
 */
public final class CargoFetchHandler implements RequestHandler<CargoFetchRequest, CargoFetchResponse> {

    private final CargoService cargoService;
    private final Logger logger;

    public CargoFetchHandler(final CargoService cargoService, final Logger logger) {
        this.cargoService = cargoService;
        this.logger = logger;
    }

    @Override
    public String queue() {
        return ShardsRabbitMqTopology.CARGO_FETCH_QUEUE;
    }

    @Override
    public Class<CargoFetchRequest> requestType() {
        return CargoFetchRequest.class;
    }

    @Override
    public UUID requestId(final CargoFetchRequest request) {
        return request.requestId();
    }

    @Override
    public CargoFetchResponse handle(final CargoFetchRequest request) {
        try {
            final List<CargoParcel> parcels = this.cargoService.pending(ShardServerId.of(request.serverName()));
            return CargoFetchResponse.of(request.requestId(), parcels);
        } catch (final RuntimeException exception) {
            // Said plainly rather than answered with an empty list. A shard told there is nothing for
            // it by a database that is down would go on believing that, and the parcels waiting for it
            // are the ones nobody else is going to land
            this.logger.error("Could not read the cargo waiting for {}", request.serverName(), exception);
            return CargoFetchResponse.error(request.requestId(), "Could not read cargo");
        }
    }

    @Override
    public CargoFetchResponse failure(final UUID requestId, final String message) {
        return CargoFetchResponse.error(requestId, message);
    }
}
