package net.civmc.shards.api;

public enum CargoSendStatus {

    /**
     * The destination shard owns the parcel now. The sender may - and must - destroy its own copy.
     */
    SENT,
    /**
     * No shard owns the ground it was addressed to, or no shard goes by that name. Nothing was
     * written, so the sender still has it.
     */
    NO_DESTINATION,
    /**
     * The destination is not answering. Nothing was written: cargo could be left in the table for it
     * to collect whenever it came back, but the sender has to destroy its copy on being told the
     * handover happened, and doing that for a shard that may be down for days is how a rocket's hold
     * disappears for a week.
     */
    DESTINATION_UNAVAILABLE,
    /**
     * A parcel with this id already exists. The sender is retrying something that already worked, so
     * this is success on the second attempt rather than a failure - the response carries the same
     * destination it carried the first time.
     */
    ALREADY_SENT,
    STORE_REFUSED,
    ERROR
}
