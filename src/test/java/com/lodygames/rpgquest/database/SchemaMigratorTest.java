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
            assertEquals(10, userVersion(connection));
        }
    }

    @Test
    void migratingTwiceIsIdempotentAndKeepsVersionCurrent() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("schema2.db"))) {
            SchemaMigrator.migrate(connection);
            assertDoesNotThrow(() -> SchemaMigrator.migrate(connection));
            assertEquals(10, userVersion(connection));
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

            assertEquals(10, userVersion(connection));
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

    private int userVersion(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("PRAGMA user_version")) {
            return resultSet.next() ? resultSet.getInt(1) : -1;
        }
    }
}
