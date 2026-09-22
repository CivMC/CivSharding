package net.civmc.shards.api;

public enum CargoLandStatus {

    RECORDED,
    /**
     * Another shard owns this parcel. The landing is not recorded, because a shard that does not own
     * a parcel putting it in its world is the duplicate this whole mechanism exists to prevent.
     */
    NOT_HELD,
    NO_ROW,
    ERROR
}
