package net.civmc.shards.velocity.cargo;

import java.util.UUID;

public sealed interface CargoSendResult {

    /**
     * The row was written. The destination owns the parcel and the sender must now destroy its copy.
     */
    record Stored() implements CargoSendResult {
    }

    /**
     * A parcel with this id was already stored, addressed to the same shard. The sender is retrying
     * an attempt whose answer went missing, so this is the first attempt's success being reported
     * again rather than a new outcome.
     */
    record AlreadyStored() implements CargoSendResult {
    }

    /**
     * A parcel with this id exists and is addressed somewhere else. Nothing was written: the id is
     * the sender's, so two different destinations under one id is a bug in the sender, and guessing
     * which it meant would put items on a shard nobody asked for.
     */
    record IdReused(UUID owningServerUuid) implements CargoSendResult {
    }
}
