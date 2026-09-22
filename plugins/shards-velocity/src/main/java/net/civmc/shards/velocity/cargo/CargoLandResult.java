package net.civmc.shards.velocity.cargo;

import java.util.UUID;

public sealed interface CargoLandResult {

    /**
     * Recorded, or already recorded. Landing twice is the safe direction for this to fail in - the
     * receiver is written to put a parcel down idempotently - so a repeat is success, not an error.
     */
    record Recorded() implements CargoLandResult {
    }

    record NoRow() implements CargoLandResult {
    }

    /**
     * Another shard owns the parcel. Nothing was recorded, and the shard that sent this has put
     * something in its world that it does not own - which is the duplicate the whole table exists to
     * prevent, so it is worth an error in the log rather than a quiet no-op.
     */
    record HeldByOther(UUID owningServerUuid) implements CargoLandResult {
    }
}
