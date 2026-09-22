package net.civmc.shards.api;

import java.util.Objects;
import java.util.UUID;

/**
 * Says where the owning shard has decided to put a parcel, before it puts it there.
 *
 * <p>This is what makes landing safe to repeat. A parcel can be offered to its shard more than once -
 * the answer saying it landed can go missing, or the server can be killed between writing the blocks
 * and reporting them - and the second attempt must produce the same rocket in the same place rather
 * than a second one somewhere else. Choosing the site involves searching for open ground, so it is
 * not a decision that repeats on its own; writing it down here is what makes it repeat.</p>
 *
 * <p>The parcel stays {@code PENDING}. A reservation is a note about where it is going, not a claim
 * that it has arrived, and nothing about ownership changes.</p>
 */
public record CargoReserveRequest(UUID requestId, String serverName, UUID cargoId, PlayerLocation site,
                                  long createdAtEpochMillis) {

    public CargoReserveRequest {
        Objects.requireNonNull(requestId, "requestId");
        serverName = Messages.requireNonBlank(serverName, "serverName");
        Objects.requireNonNull(cargoId, "cargoId");
        Objects.requireNonNull(site, "site");
        Messages.requirePositive(createdAtEpochMillis, "createdAtEpochMillis");
    }

    public static CargoReserveRequest create(final String serverName, final UUID cargoId,
                                             final PlayerLocation site) {
        return new CargoReserveRequest(UUID.randomUUID(), serverName, cargoId, site, System.currentTimeMillis());
    }
}
