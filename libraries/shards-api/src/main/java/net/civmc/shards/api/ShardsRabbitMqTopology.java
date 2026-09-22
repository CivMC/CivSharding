package net.civmc.shards.api;

/**
 * Names of the queues the proxy and its servers exchange messages over.
 *
 * <p>There is a queue per operation rather than one queue carrying a tagged union. The bodies have
 * little in common - a save carries a payload, a claim does not - so a single request type would be
 * mostly fields that are null for every operation but one, and would need a polymorphic adapter to
 * deserialize. A queue each keeps every message a plain record.</p>
 */
public final class ShardsRabbitMqTopology {

    public static final String CONTENT_TYPE_JSON = "application/json";

    public static final String SERVER_STARTUP_QUEUE = "shards.server.startup";
    public static final boolean SERVER_STARTUP_QUEUE_DURABLE = true;

    public static final String PLAYER_CLAIM_QUEUE = "shards.playerdata.claim";
    public static final String PLAYER_SAVE_QUEUE = "shards.playerdata.save";
    public static final String PLAYER_RELEASE_QUEUE = "shards.playerdata.release";
    public static final String PLAYER_TRANSFER_QUEUE = "shards.playerdata.transfer";
    public static final String PLAYER_CHECKPOINT_QUEUE = "shards.playerdata.checkpoint";

    /**
     * Where a shard asks which shard a named player is on.
     *
     * <p>Beside the player data queues rather than among them because nothing is read or written:
     * the answer comes from who is connected to the proxy, not from the store.</p>
     */
    public static final String PLAYER_LOCATE_QUEUE = "shards.playerdata.locate";
    // Somebody typed a name and is waiting to see what happens. One still sitting here seconds later
    // is answering a question its asker has already given up on
    public static final int PLAYER_LOCATE_TTL_MILLIS = 10_000;

    /**
     * Where things that are not a player are handed from one shard to another.
     *
     * <p>Apart from the player queues because a parcel outlives the request that moved it. A player
     * transfer is finished when it is answered; a parcel is a row that stays until a shard has put it
     * in its world, which may be after a restart, so these are the operations on a store rather than
     * one handover.</p>
     */
    public static final String CARGO_SEND_QUEUE = "shards.cargo.send";
    public static final String CARGO_FETCH_QUEUE = "shards.cargo.fetch";
    public static final String CARGO_RESERVE_QUEUE = "shards.cargo.reserve";
    public static final String CARGO_LANDED_QUEUE = "shards.cargo.landed";
    public static final String CARGO_STATUS_QUEUE = "shards.cargo.status";

    public static final String BORDER_PROBE_QUEUE = "shards.border.probe";
    // A probe is about where a player is standing right now, so one still sitting in the queue
    // seconds later is answering a question nobody has any more. The sender has already given up on
    // it, and its reply would be dropped as unmatched, so it is dropped here instead
    public static final int BORDER_PROBE_TTL_MILLIS = 10_000;

    public static final String SKY_STATE_QUEUE = "shards.sky.state";
    // The sky moves on while a request waits, so an answer to one that has been sitting here would
    // put a shard behind by however long it sat. Dropped instead: the next poll is seconds away and
    // asks about now
    public static final int SKY_STATE_TTL_MILLIS = 10_000;
    public static final String NIGHT_SKIP_QUEUE = "shards.sky.nightskip";

    /**
     * Every request queue survives a broker restart, including the probe queue, whose messages are
     * worthless within seconds.
     *
     * <p>Not a judgement about the messages - it is that the alternative does not exist. A transient
     * queue that is not exclusive is a feature RabbitMQ now refuses, and refuses at the connection
     * level: declaring one does not fail that queue, it tears down the whole connection. Asking for
     * one cost this plugin its entire request consumer at startup and was recovered from silently
     * enough that only the probes stayed missing. Short-lived messages say so with a TTL instead.</p>
     */
    public static final boolean REQUEST_QUEUE_DURABLE = true;

    public static final String MIRROR_QUEUE_PREFIX = "shards.mirror.";
    // A chunk request whose asker has given up is work nobody will look at, and the mirror asks about
    // whatever a player has just walked towards - by the time a late one is answered they are
    // somewhere else. Longer than the probe's, because a chunk costs real work to read and is worth
    // waiting a little for
    public static final int MIRROR_REQUEST_TTL_MILLIS = 30_000;

    /**
     * Where a shard is asked about its own chunks.
     *
     * <p>Addressed to a shard rather than to the proxy, which is the first message in this project
     * that is. The proxy has the shard map but no world, so it cannot answer what is in a chunk - it
     * only says whose chunk it is.</p>
     */
    public static String mirrorQueue(final String serverName) {
        return MIRROR_QUEUE_PREFIX + serverName;
    }

    /**
     * Where a shard announces blocks that have just changed near its border.
     *
     * <p>A fanout, so a shard does not have to know which neighbour is looking at which of its
     * chunks - which would mean tracking that and keeping it up to date as players walk. Every shard
     * gets every announcement and drops the ones about chunks it has not fetched, which it can answer
     * from its own cache without asking anybody.</p>
     */
    public static final String MIRROR_UPDATE_EXCHANGE = "shards.mirror.updates";
    // Worthless within seconds: a receiver that has been away is going to re-read the chunk anyway,
    // and applying a minute-old change on top of a fresh read would put back what was taken away
    public static final int MIRROR_UPDATE_TTL_MILLIS = 15_000;

    /**
     * Where a shard announces where its players are standing.
     *
     * <p>Its own exchange rather than sharing the block one, so a receiver knows what a message is
     * before reading it - and so the two can be told apart in the broker's own statistics, which is
     * the only place their very different rates will ever be visible.</p>
     */
    public static final String MIRROR_PLAYER_EXCHANGE = "shards.mirror.players";
    // Two ticks. A position is only ever the latest one, so a late message is not missed, it is
    // replaced - and applying one that is a second old would drag somebody backwards
    public static final int MIRROR_PLAYER_TTL_MILLIS = 100;

    /**
     * Where a shard announces where its minecarts, animals and dropped items are.
     *
     * <p>Apart from the players for the same reason the players are apart from the blocks: a receiver
     * knows what a message is before reading it, and the rates can be told apart in the broker's own
     * statistics. A shard with a rail line along a seam and nobody near it sends these and no player
     * announcements at all.</p>
     */
    public static final String MIRROR_MOB_EXCHANGE = "shards.mirror.mobs";
    // The same two ticks, and for the same reason: a minecart's position is only ever the latest one
    public static final int MIRROR_MOB_TTL_MILLIS = 100;

    /**
     * Where a shard announces the particles it has just shown.
     *
     * <p>Its own exchange again, and the one carrying events rather than state: a position is replaced
     * by the next one, a puff of smoke is not. A receiver that misses one has missed it.</p>
     */
    public static final String MIRROR_PARTICLE_EXCHANGE = "shards.mirror.particles";
    // Longer than a position's two ticks, because a burst is not replaced by a later one and a
    // slightly late puff of smoke is still smoke. Short all the same: one that arrives a second late
    // is smoke from something that finished happening
    public static final int MIRROR_PARTICLE_TTL_MILLIS = 1_000;

    /**
     * Where a shard announces what its players have just said in local chat.
     *
     * <p>A fanout like the rest of the mirror, and for the same reason: who can hear a sentence
     * depends on where everybody is standing at that moment, which the speaker's shard does not
     * know about anybody but its own. Every shard gets every line and drops the ones spoken out of
     * earshot of all of its players, which it can answer without asking anybody.</p>
     */
    public static final String LOCAL_CHAT_EXCHANGE = "shards.chat.local";
    // Long enough to survive a hiccup, short enough that nobody is answered a sentence from before
    // they walked up. A late line of chat is worse than a lost one: it reads as somebody talking to
    // themselves
    public static final int LOCAL_CHAT_TTL_MILLIS = 5_000;

    /**
     * Where a shard asks whoever has a player to send them somewhere.
     *
     * <p>A fanout for the same reason the rest of the mirror is one: which shard somebody is on is
     * only known at the moment it is asked, and a message addressed to the answer would be addressed
     * to where they were. Every shard hears it and the one that has them acts.</p>
     */
    public static final String PLAYER_SUMMON_EXCHANGE = "shards.player.summon";
    // Somebody typed a command and is watching. A summons that has been sitting in a queue is one
    // whose asker has given up, and acting on it would move a player for no visible reason
    public static final int PLAYER_SUMMON_TTL_MILLIS = 10_000;

    public static final String REPLY_QUEUE_PREFIX = "shards.replies.";

    /**
     * Where a server's replies are delivered.
     *
     * <p>Named after the server rather than left for the broker to name. A broker-generated name
     * changes when a client recovers its connection, and anything holding the old one addresses
     * replies to a queue that no longer exists - which fails silently, because an unroutable message
     * is discarded rather than refused.</p>
     */
    public static String replyQueue(final String serverName) {
        return REPLY_QUEUE_PREFIX + serverName;
    }

    private ShardsRabbitMqTopology() {
    }
}
