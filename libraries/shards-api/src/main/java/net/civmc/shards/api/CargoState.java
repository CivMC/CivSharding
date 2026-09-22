package net.civmc.shards.api;

/**
 * Where a parcel has got to.
 *
 * <p>Two states and no more, because there are only two places a parcel can be and the whole point
 * is that it is never in both. {@link #PENDING} means the destination shard owns it and has not yet
 * put it in its world; {@link #LANDED} means it has, and has flushed it to disk. Nothing represents
 * "in flight": the parcel becomes the destination's the moment the row is written, before the sender
 * has destroyed its own copy, and the sender collapsing that overlap is what makes the handover
 * safe.</p>
 */
public enum CargoState {

    PENDING,
    LANDED
}
