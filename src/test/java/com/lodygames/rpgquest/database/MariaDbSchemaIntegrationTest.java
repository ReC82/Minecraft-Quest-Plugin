package com.lodygames.rpgquest.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Issue #41 — schéma MariaDB depuis zéro : initialisation, historique de migrations portable,
 * idempotence, health. S'exécute uniquement si {@code RPGQUEST_DB_*} est défini (sinon ignoré).
 */
class MariaDbSchemaIntegrationTest {

    @BeforeAll
    static void requireMariaDb() {
        MariaDbTestSupport.assumeAvailable();
    }

    @BeforeEach
    void freshDatabase() throws SQLException {
        MariaDbTestSupport.wipe();
    }

    @Test
    void initialisesTheWholeSchemaFromAnEmptyDatabase() throws Exception {
        DatabaseManager database = new DatabaseManager(MariaDbTestSupport.engine());
        try {
            database.initialize().get(30, TimeUnit.SECONDS);
            assertEquals(DatabaseType.MYSQL, database.engineType());
            assertTrue(database.describeEngine().startsWith("mariadb "));
            assertFalse(database.describeEngine().toLowerCase().contains("password"));

            List<String> tables = database.execute(MariaDbSchemaIntegrationTest::listTables).get(10, TimeUnit.SECONDS);
            for (String expected : MariaDbTestSupport.RPGQUEST_TABLES) {
                assertTrue(tables.contains(expected), "table manquante : " + expected + " (présentes : " + tables + ")");
            }
            assertTrue(database.healthCheck().get(10, TimeUnit.SECONDS));
        } finally {
            database.shutdown();
        }
    }

    @Test
    void migrationHistoryTableRecordsEveryVersionInOrder() throws Exception {
        DatabaseManager database = new DatabaseManager(MariaDbTestSupport.engine());
        try {
            database.initialize().get(30, TimeUnit.SECONDS);
            List<Integer> versions = database.execute(connection -> {
                List<Integer> result = new ArrayList<>();
                try (Statement statement = connection.createStatement();
                     ResultSet resultSet = statement.executeQuery(
                             "SELECT version FROM rpgquest_schema_migrations ORDER BY version")) {
                    while (resultSet.next()) {
                        result.add(resultSet.getInt(1));
                    }
                }
                return result;
            }).get(10, TimeUnit.SECONDS);

            List<Integer> expected = new ArrayList<>();
            for (int v = 1; v <= SchemaMigrator.CURRENT_VERSION; v++) {
                expected.add(v);
            }
            assertEquals(expected, versions);
            int viaHistory = database.execute(new MigrationTableHistory()::currentVersion).get(10, TimeUnit.SECONDS);
            assertEquals(SchemaMigrator.CURRENT_VERSION, viaHistory);
        } finally {
            database.shutdown();
        }
    }

    @Test
    void reinitialisingAnUpToDateDatabaseIsANoOp() throws Exception {
        DatabaseManager first = new DatabaseManager(MariaDbTestSupport.engine());
        try {
            first.initialize().get(30, TimeUnit.SECONDS);
        } finally {
            first.shutdown();
        }
        // deuxième démarrage sur la même base déjà migrée : ne relance rien, ne lève rien
        DatabaseManager second = new DatabaseManager(MariaDbTestSupport.engine());
        try {
            second.initialize().get(30, TimeUnit.SECONDS);
            int count = second.execute(connection -> {
                try (Statement statement = connection.createStatement();
                     ResultSet resultSet = statement.executeQuery(
                             "SELECT COUNT(*) FROM rpgquest_schema_migrations")) {
                    resultSet.next();
                    return resultSet.getInt(1);
                }
            }).get(10, TimeUnit.SECONDS);
            assertEquals(SchemaMigrator.CURRENT_VERSION, count, "aucune ligne d'historique en double");
        } finally {
            second.shutdown();
        }
    }

    @Test
    void foreignKeysAndCharsetAreInnoDbUtf8mb4() throws Exception {
        DatabaseManager database = new DatabaseManager(MariaDbTestSupport.engine());
        try {
            database.initialize().get(30, TimeUnit.SECONDS);
            database.execute(connection -> {
                try (Statement statement = connection.createStatement();
                     ResultSet rs = statement.executeQuery(
                             "SELECT ENGINE, TABLE_COLLATION FROM information_schema.TABLES "
                                     + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'player_profiles'")) {
                    assertTrue(rs.next());
                    assertEquals("InnoDB", rs.getString(1));
                    assertTrue(rs.getString(2).startsWith("utf8mb4"));
                }
                // clé étrangère claim_members -> claims
                try (ResultSet rs = connection.createStatement().executeQuery(
                        "SELECT COUNT(*) FROM information_schema.KEY_COLUMN_USAGE "
                                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'claim_members' "
                                + "AND REFERENCED_TABLE_NAME = 'claims'")) {
                    rs.next();
                    assertTrue(rs.getInt(1) >= 1, "clé étrangère claim_members -> claims attendue");
                }
                return null;
            }).get(10, TimeUnit.SECONDS);
        } finally {
            database.shutdown();
        }
    }

    // ---- helpers ---------------------------------------------------------------------------

    private static List<String> listTables(Connection connection) throws SQLException {
        List<String> tables = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE()")) {
            while (resultSet.next()) {
                tables.add(resultSet.getString(1));
            }
        }
        return tables;
    }
}
