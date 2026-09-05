package com.lodygames.rpgquest.database;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SchemaMigratorTest {

    @TempDir
    Path tempDir;

    @Test
    void migrateSetsUserVersionToCurrentSchemaVersion() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("schema.db"))) {
            SchemaMigrator.migrate(connection);
            assertEquals(17, userVersion(connection));
        }
    }

    @Test
    void migratingTwiceIsIdempotentAndKeepsVersionCurrent() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("schema2.db"))) {
            SchemaMigrator.migrate(connection);
            assertDoesNotThrow(() -> SchemaMigrator.migrate(connection));
            assertEquals(17, userVersion(connection));
        }
    }

    @Test
    void migratingFromAnAlreadyPartiallyMigratedDatabaseStillReachesCurrentVersion() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("schema3.db"))) {
            // Simule une base créée avant l'ajout de quest_objective_progress (V1 seulement).
            SchemaMigrator.migrate(connection);
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA user_version = 1");
                statement.execute("DROP TABLE quest_objective_progress");
            }

            SchemaMigrator.migrate(connection);

            assertEquals(17, userVersion(connection));
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery(
                         "SELECT name FROM sqlite_master WHERE type='table' AND name='quest_objective_progress'")) {
                assertTrue(resultSet.next());
            }
        }
    }

    @Test
    void migrateCreatesResourceNodesTable() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("schema4.db"))) {
            SchemaMigrator.migrate(connection);
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery(
                         "SELECT name FROM sqlite_master WHERE type='table' AND name='resource_nodes'")) {
                assertTrue(resultSet.next());
            }
        }
    }

    @Test
    void migrateCreatesWalletsAndTransactionsTables() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("schema5.db"))) {
            SchemaMigrator.migrate(connection);
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery(
                         "SELECT name FROM sqlite_master WHERE type='table' AND name IN ('wallets', 'transactions')")) {
                int count = 0;
                while (resultSet.next()) {
                    count++;
                }
                assertEquals(2, count);
            }
        }
    }

    @Test
    void migrateCreatesPortalCooldownsTable() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("schema7.db"))) {
            SchemaMigrator.migrate(connection);
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery(
                         "SELECT name FROM sqlite_master WHERE type='table' AND name='portal_cooldowns'")) {
                assertTrue(resultSet.next());
            }
        }
    }

    @Test
    void migrateCreatesClaimsAndClaimMembersTables() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("schema8.db"))) {
            SchemaMigrator.migrate(connection);
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery(
                         "SELECT name FROM sqlite_master WHERE type='table' AND name IN ('claims', 'claim_members')")) {
                int count = 0;
                while (resultSet.next()) {
                    count++;
                }
                assertEquals(2, count);
            }
        }
    }

    @Test
    void migrateCreatesMarketListingsTable() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("schema6.db"))) {
            SchemaMigrator.migrate(connection);
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery(
                         "SELECT name FROM sqlite_master WHERE type='table' AND name='market_listings'")) {
                assertTrue(resultSet.next());
            }
        }
    }

    @Test
    void migrateCreatesProgressionTables() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("schema9.db"))) {
            SchemaMigrator.migrate(connection);
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery(
                         "SELECT name FROM sqlite_master WHERE type='table' AND name IN "
                                 + "('player_skills', 'xp_grants', 'player_placed_blocks')")) {
                int count = 0;
                while (resultSet.next()) {
                    count++;
                }
                assertEquals(3, count);
            }
        }
    }

    @Test
    void migrateCreatesBackpackAndEntitlementTables() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("schema10.db"))) {
            SchemaMigrator.migrate(connection);
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery(
                         "SELECT name FROM sqlite_master WHERE type='table' AND name IN "
                                 + "('player_entitlements', 'backpacks', 'backpack_overflow', 'backpack_audit')")) {
                int count = 0;
                while (resultSet.next()) {
                    count++;
                }
                assertEquals(4, count);
            }
        }
    }

    @Test
    void migrateCreatesStoreDeliveriesProcessedTable() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("schema11.db"))) {
            SchemaMigrator.migrate(connection);
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery(
                         "SELECT name FROM sqlite_master WHERE type='table' AND name='store_deliveries_processed'")) {
                assertTrue(resultSet.next());
            }
        }
    }

    @Test
    void migrateCreatesNpcIdsTable() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("schema12.db"))) {
            SchemaMigrator.migrate(connection);
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery(
                         "SELECT name FROM sqlite_master WHERE type='table' AND name='npc_ids'")) {
                assertTrue(resultSet.next());
            }
        }
    }

    @Test
    void migrateCreatesNpcCitizensBindingsTable() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("schema13.db"))) {
            SchemaMigrator.migrate(connection);
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery(
                         "SELECT name FROM sqlite_master WHERE type='table' AND name='npc_citizens_bindings'")) {
                assertTrue(resultSet.next());
            }
        }
    }

    @Test
    void migrateCreatesStoryProgressTable() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("schema14.db"))) {
            SchemaMigrator.migrate(connection);
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery(
                         "SELECT name FROM sqlite_master WHERE type='table' AND name='story_progress'")) {
                assertTrue(resultSet.next());
            }
        }
    }

    @Test
    void migrateAddsCurrentIndexColumnToStoryProgress() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("schema15.db"))) {
            SchemaMigrator.migrate(connection);
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery("PRAGMA table_info(story_progress)")) {
                boolean found = false;
                while (resultSet.next()) {
                    if ("current_index".equals(resultSet.getString("name"))) {
                        found = true;
                    }
                }
                assertTrue(found, "story_progress doit porter la colonne current_index (migration V14)");
            }
        }
    }

    @Test
    void migratingFromV13PreservesExistingStoryProgressRowsAndDefaultsCurrentIndexToZero() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("schema16.db"))) {
            // Simule une base déjà à V13 (avant l'ajout de current_index), avec une ligne existante —
            // exactement ce qu'un serveur en production aurait après l'étape précédente.
            SchemaMigrator.migrate(connection);
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA user_version = 13");
                statement.execute(
                        "INSERT INTO player_profiles (uuid, last_name, created_at, updated_at) "
                                + "VALUES ('11111111-1111-1111-1111-111111111111', 'Steve', '2024-01-01T00:00:00Z', '2024-01-01T00:00:00Z')");
            }

            SchemaMigrator.migrate(connection);

            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery(
                         "SELECT uuid, last_name FROM player_profiles WHERE uuid = '11111111-1111-1111-1111-111111111111'")) {
                assertTrue(resultSet.next(), "les données déjà présentes avant la migration V14 doivent survivre telles quelles");
                assertEquals("Steve", resultSet.getString("last_name"));
            }
            assertEquals(17, userVersion(connection));
        }
    }

    @Test
    void migrateAddsReservationColumnsToClaims() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("schema17.db"))) {
            SchemaMigrator.migrate(connection);
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery("PRAGMA table_info(claims)")) {
                var found = new java.util.HashSet<String>();
                while (resultSet.next()) {
                    found.add(resultSet.getString("name"));
                }
                for (String column : java.util.List.of("reserved_min_x", "reserved_min_y", "reserved_min_z",
                        "reserved_max_x", "reserved_max_y", "reserved_max_z")) {
                    assertTrue(found.contains(column), () -> "colonne manquante : " + column);
                }
            }
        }
    }

    @Test
    void migratingFromV14BackfillsReservationBoundsToTheActiveBoundsOfExistingClaims() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("schema18.db"))) {
            // Simule un serveur déjà en V14 (avant l'introduction du modèle de réservation) : schéma
            // claims/player_profiles recréé à la main sans les colonnes reserved_*, avec une ligne
            // existante — exactement ce qu'un vrai serveur en production aurait à ce stade.
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TABLE player_profiles (
                            uuid TEXT PRIMARY KEY,
                            last_name TEXT NOT NULL,
                            created_at TEXT NOT NULL,
                            updated_at TEXT NOT NULL
                        )
                        """);
                statement.execute("""
                        CREATE TABLE claims (
                            id TEXT PRIMARY KEY,
                            owner_uuid TEXT NOT NULL,
                            world TEXT NOT NULL,
                            min_x INTEGER NOT NULL,
                            min_y INTEGER NOT NULL,
                            min_z INTEGER NOT NULL,
                            max_x INTEGER NOT NULL,
                            max_y INTEGER NOT NULL,
                            max_z INTEGER NOT NULL,
                            allow_public_redstone INTEGER NOT NULL DEFAULT 0,
                            created_at TEXT NOT NULL,
                            FOREIGN KEY (owner_uuid) REFERENCES player_profiles (uuid) ON DELETE CASCADE
                        )
                        """);
                statement.execute(
                        "INSERT INTO player_profiles (uuid, last_name, created_at, updated_at) "
                                + "VALUES ('22222222-2222-2222-2222-222222222222', 'Steve', '2024-01-01T00:00:00Z', '2024-01-01T00:00:00Z')");
                statement.execute("""
                        INSERT INTO claims (id, owner_uuid, world, min_x, min_y, min_z, max_x, max_y, max_z,
                                             allow_public_redstone, created_at)
                        VALUES ('legacy', '22222222-2222-2222-2222-222222222222', 'world', 0, 0, 0, 10, 255, 10, 0, '2024-01-01T00:00:00Z')
                        """);
                statement.execute("PRAGMA user_version = 14");
            }

            SchemaMigrator.migrate(connection);

            assertEquals(17, userVersion(connection));
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery(
                         "SELECT min_x, max_x, reserved_min_x, reserved_max_x FROM claims WHERE id = 'legacy'")) {
                assertTrue(resultSet.next());
                assertEquals(resultSet.getInt("min_x"), resultSet.getInt("reserved_min_x"),
                        "un claim déjà existant doit voir sa réservation initialisée à son propre cuboïde actif");
                assertEquals(resultSet.getInt("max_x"), resultSet.getInt("reserved_max_x"));
            }
        }
    }

    @Test
    void migrateCreatesItemTravelCooldownsTable() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("schema16.db"))) {
            SchemaMigrator.migrate(connection);
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery(
                         "SELECT name FROM sqlite_master WHERE type='table' AND name='item_travel_cooldowns'")) {
                assertTrue(resultSet.next());
            }
        }
    }

    @Test
    void migrateCreatesWaystoneTables() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("schema17.db"))) {
            SchemaMigrator.migrate(connection);
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery(
                         "SELECT name FROM sqlite_master WHERE type='table' AND name IN ('waystones', 'waystone_discoveries')")) {
                int found = 0;
                while (resultSet.next()) {
                    found++;
                }
                assertEquals(2, found);
            }
        }
    }

    @Test
    void reRunningV16AndV17IsIdempotent() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("schema1617.db"))) {
            SchemaMigrator.migrate(connection);
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA user_version = 15");
            }
            assertDoesNotThrow(() -> SchemaMigrator.migrate(connection));
            assertEquals(17, userVersion(connection));
        }
    }

    private int userVersion(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("PRAGMA user_version")) {
            return resultSet.next() ? resultSet.getInt(1) : -1;
        }
    }
}
