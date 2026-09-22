package net.civmc.shards.api;

import java.util.Objects;
import java.util.UUID;

/**
 * Things being moved between shards without a player carrying them.
 *
 * <p>A player's belongings are safe across a border because they never exist anywhere but on the
 * player: the snapshot is written and ownership given up in one transaction, and what the source
 * destroys is a session, not a thing in the world. A rocket's hold is not like that. It is blocks
 * and chest contents in a world, which persist only when their chunk is saved - so moving it means
 * something really is destroyed at one end and created at the other, and the two are on different
 * machines.</p>
 *
 * <p>A parcel is the row that makes that safe. It is written before the sender destroys anything and
 * deleted after the destination has flushed it to disk, so the invariant is not "exactly one copy at
 * every instant" - which two machines cannot give you - but the achievable one: <strong>at every
 * instant the parcel exists in at least one durable place, and the end that would make a second copy
 * refuses to</strong>. A crash always resolves towards the parcel still being owned by the row and
 * landed again, never towards it being gone.</p>
 *
 * <p>What is in {@code payload} is not this library's business. Shards knows a parcel has an owner, a
 * destination and a size; only the plugin that sent it knows it is a rocket. {@code type} is how the
 * receiving end finds the code that does.</p>
 *
 * @param cargoId chosen by the sender, which is what makes sending idempotent - a retry after an
 *     answer went missing addresses the same parcel rather than creating a second one
 * @param type whose parcel this is, e.g. {@code rocket}. Routed on at the destination
 * @param payload base64 of whatever the sender wants carried
 * @param destination where it is going, in the coordinates the destination shard will use. Null when
 *     the parcel was addressed to a shard by name and has no particular place to be
 * @param reservedAt the exact place the owning shard has already decided to put this parcel, null
 *     until it has decided. It is what makes landing a parcel twice harmless: a second attempt puts
 *     the same thing in the same place rather than choosing again, so the repeat overwrites the
 *     first instead of standing beside it
 */
public record CargoParcel(UUID cargoId, String type, String sourceServer, String payload,
                          PlayerLocation destination, PlayerLocation reservedAt, long sentAtEpochMillis) {

    public CargoParcel {
        Objects.requireNonNull(cargoId, "cargoId");
        type = Messages.requireNonBlank(type, "type");
        sourceServer = Messages.requireNonBlank(sourceServer, "sourceServer");
        Objects.requireNonNull(payload, "payload");
        Messages.requirePositive(sentAtEpochMillis, "sentAtEpochMillis");
    }
}
