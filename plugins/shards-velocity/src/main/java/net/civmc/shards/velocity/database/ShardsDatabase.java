package net.civmc.shards.velocity.database;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import net.civmc.nameapi.Migrator;

public final class ShardsDatabase {

    private ShardsDatabase() {
    }

    public static void migrate(final DataSource dataSource) throws SQLException {
        final Migrator migrator = new Migrator();
        // Own namespace, so this runs independently of zorweth's migrations in the same database
        migrator.registerMigration("shards", 0,
            """
                CREATE TABLE IF NOT EXISTS shard_player_data (
                    player_uuid VARCHAR(36) NOT NULL,
                    payload LONGBLOB,
                    owning_server_uuid VARCHAR(36),
                    world VARCHAR(64),
                    x DOUBLE,
                    y DOUBLE,
                    z DOUBLE,
                    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
                    PRIMARY KEY (player_uuid),
                    INDEX idx_shard_player_data_owning_server_uuid (owning_server_uuid)
                )
                """);

        // Cargo: things moved between shards with no player carrying them. Its own table rather than
        // a shape bolted onto shard_player_data, because the two are only superficially alike - a
        // player row is permanent and its owner is usually nobody, while a parcel row exists only
        // between being sent and being put down, and always has an owner
        migrator.registerMigration("shards", 1,
            """
                CREATE TABLE IF NOT EXISTS shard_cargo (
                    cargo_id VARCHAR(36) NOT NULL,
                    type VARCHAR(64) NOT NULL,
                    source_server VARCHAR(64) NOT NULL,
                    owning_server_uuid VARCHAR(36) NOT NULL,
                    payload LONGBLOB NOT NULL,
                    state VARCHAR(16) NOT NULL,
                    destination_world VARCHAR(64),
                    destination_x DOUBLE,
                    destination_y DOUBLE,
                    destination_z DOUBLE,
                    landed_world VARCHAR(64),
                    landed_x DOUBLE,
                    landed_y DOUBLE,
                    landed_z DOUBLE,
                    sent_at_epoch_millis BIGINT NOT NULL,
                    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
                    PRIMARY KEY (cargo_id),
                    INDEX idx_shard_cargo_owner_state (owning_server_uuid, state)
                )
                """);

        try (Connection connection = dataSource.getConnection()) {
            migrator.migrate(connection);
        }
    }
}
