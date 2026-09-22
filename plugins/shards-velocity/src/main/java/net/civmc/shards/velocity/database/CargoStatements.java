package net.civmc.shards.velocity.database;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.jdbi.v3.sqlobject.transaction.Transaction;

public interface CargoStatements {

    @SqlQuery("""
        SELECT cargo_id, type, source_server, owning_server_uuid, payload, state,
               destination_world, destination_x, destination_y, destination_z,
               landed_world, landed_x, landed_y, landed_z, sent_at_epoch_millis
        FROM shard_cargo
        WHERE cargo_id = :cargoId
        """)
    Optional<CargoRow> select(UUID cargoId);

    /**
     * Takes a row lock for the rest of the transaction. Only meaningful inside one.
     */
    @SqlQuery("""
        SELECT cargo_id, type, source_server, owning_server_uuid, payload, state,
               destination_world, destination_x, destination_y, destination_z,
               landed_world, landed_x, landed_y, landed_z, sent_at_epoch_millis
        FROM shard_cargo
        WHERE cargo_id = :cargoId
        FOR UPDATE
        """)
    Optional<CargoRow> selectForUpdate(UUID cargoId);

    /**
     * Everything a shard owns and has not yet put in its world, oldest first - so a shard that has
     * been away collects a backlog in the order it was sent.
     */
    @SqlQuery("""
        SELECT cargo_id, type, source_server, owning_server_uuid, payload, state,
               destination_world, destination_x, destination_y, destination_z,
               landed_world, landed_x, landed_y, landed_z, sent_at_epoch_millis
        FROM shard_cargo
        WHERE owning_server_uuid = :owningServerUuid AND state = 'PENDING'
        ORDER BY sent_at_epoch_millis ASC
        LIMIT :limit
        """)
    List<CargoRow> selectPending(UUID owningServerUuid, int limit);

    @SqlUpdate("""
        INSERT INTO shard_cargo (cargo_id, type, source_server, owning_server_uuid, payload, state,
                                 destination_world, destination_x, destination_y, destination_z,
                                 sent_at_epoch_millis)
        VALUES (:cargoId, :type, :sourceServer, :owningServerUuid, :payload, 'PENDING',
                :destinationWorld, :destinationX, :destinationY, :destinationZ, :sentAtEpochMillis)
        """)
    void insert(UUID cargoId, String type, String sourceServer, UUID owningServerUuid, byte[] payload,
                String destinationWorld, Double destinationX, Double destinationY, Double destinationZ,
                long sentAtEpochMillis);

    /**
     * Writes down where the owning shard intends to put a parcel, leaving it pending.
     *
     * <p>Only ever set once. A shard that has already reserved a site and comes back to the parcel
     * after a restart must use the site it chose before, not a new one - otherwise a second attempt
     * builds a second rocket somewhere else, which is the duplicate this is here to avoid.</p>
     */
    @Transaction
    @SqlUpdate("""
        UPDATE shard_cargo
        SET landed_world = :siteWorld, landed_x = :siteX, landed_y = :siteY, landed_z = :siteZ
        WHERE cargo_id = :cargoId AND owning_server_uuid = :owningServerUuid AND state = 'PENDING'
              AND landed_world IS NULL
        """)
    int reserve(UUID cargoId, UUID owningServerUuid, String siteWorld, Double siteX, Double siteY, Double siteZ);

    /**
     * Records that the owning shard has put the parcel down and flushed it. The owner predicate is
     * the safety property, as it is for player data: a shard that does not own a parcel cannot mark
     * one landed, and so cannot be the second place it exists.
     */
    @Transaction
    @SqlUpdate("""
        UPDATE shard_cargo
        SET state = 'LANDED', landed_world = :landedWorld, landed_x = :landedX, landed_y = :landedY,
            landed_z = :landedZ
        WHERE cargo_id = :cargoId AND owning_server_uuid = :owningServerUuid AND state = 'PENDING'
        """)
    int markLanded(UUID cargoId, UUID owningServerUuid, String landedWorld, Double landedX, Double landedY,
                   Double landedZ);

    /**
     * Forgets parcels that landed long ago. Only ever {@code LANDED} rows: a {@code PENDING} one is
     * cargo that still has to be put somewhere, however old it is, and deleting it would be throwing
     * away the only copy.
     */
    @Transaction
    @SqlUpdate("""
        DELETE FROM shard_cargo
        WHERE state = 'LANDED' AND sent_at_epoch_millis < :before
        """)
    int pruneLanded(long before);
}
