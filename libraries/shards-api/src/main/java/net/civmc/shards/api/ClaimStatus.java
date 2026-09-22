package net.civmc.shards.api;

public enum ClaimStatus {

    /** The player has stored data and it is now owned by the requesting server. */
    LOADED,

    /**
     * No row existed, and one has been created owned by the requesting server. The requesting server
     * must read this as <strong>keep whatever is already on disk</strong>. Reading it as an
     * authoritative empty player wipes everyone at once the first time the table is empty.
     */
    NEW_PLAYER,

    /** Another server still holds the lock. The login cannot proceed. */
    HELD_BY_OTHER,

    /**
     * The asking server is not named in the shard map, so it owns no player data and was given
     * none. The holding lobby is the usual case: what a player does there has no bearing on the
     * inventory the shards share, so nothing is loaded for them and nothing is locked on their
     * behalf.
     *
     * <p>Not a refusal. The login proceeds with whatever that server has on disk, which is exactly
     * how a server outside the shard map is meant to behave. A server that asked at all is one
     * whose own copy of this rule disagrees with the proxy's, which is worth saying out loud, but
     * it is not a reason to keep anybody out.</p>
     */
    NOT_A_SHARD,

    /** The proxy could not answer. The login cannot proceed. */
    ERROR
}
