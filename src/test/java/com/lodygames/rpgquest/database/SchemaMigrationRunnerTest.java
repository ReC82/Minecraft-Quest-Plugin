package com.lodygames.rpgquest.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runner de migrations portable (issue #40) — testé ici sur SQLite avec la table de métadonnées
 * {@link MigrationTableHistory} (le mécanisme que MySQL/MariaDB utilisera en #41).
 */
class SchemaMigrationRunnerTest {

    private Connection open(Path dir, String name) throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + dir.resolve(name));
    }

    private static SchemaMigration ddl(int version, String sql) {
        return new SchemaMigration(version, "v" + version, (connection, dialect) -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute(sql);
            }
        });
    }

    @Test
    void appliesMigrationsInOrderAndIsIdempotent(@TempDir Path dir) throws SQLException {
        List<SchemaMigration> migrations = List.of(
                ddl(1, "CREATE TABLE a (id INTEGER)"),
                ddl(2, "CREATE TABLE b (id INTEGER)"),
                ddl(3, "CREATE TABLE c (id INTEGER)"));
        try (Connection connection = open(dir, "m.db")) {
            SchemaMigrationRunner runner = new SchemaMigrationRunner(
                    migrations, new MigrationTableHistory(), new SqliteDialect());
            assertEquals(3, runner.targetVersion());
            assertEquals(3, runner.run(connection));

            // rejouer : no-op, aucune erreur
            assertEquals(3, runner.run(connection));

            assertEquals(3, new MigrationTableHistory().currentVersion(connection));
            assertTrue(tableExists(connection, "a") && tableExists(connection, "b") && tableExists(connection, "c"));
        }
    }

    @Test
    void resumesFromRecordedVersion(@TempDir Path dir) throws SQLException {
        try (Connection connection = open(dir, "r.db")) {
            new SchemaMigrationRunner(List.of(ddl(1, "CREATE TABLE a (id INTEGER)")),
                    new MigrationTableHistory(), new SqliteDialect()).run(connection);

            List<Integer> applied = new ArrayList<>();
            SchemaMigration spy = new SchemaMigration(2, "v2", (c, d) -> {
                applied.add(2);
                try (Statement s = c.createStatement()) {
                    s.execute("CREATE TABLE b (id INTEGER)");
                }
            });
            new SchemaMigrationRunner(List.of(ddl(1, "CREATE TABLE a (id INTEGER)"), spy),
                    new MigrationTableHistory(), new SqliteDialect()).run(connection);

            assertEquals(List.of(2), applied, "V1 déjà appliquée n'est pas rejouée, V2 l'est une fois");
        }
    }

    @Test
    void failingMigrationRaisesAndDoesNotAdvancePastLastSuccess(@TempDir Path dir) throws SQLException {
        List<SchemaMigration> migrations = List.of(
                ddl(1, "CREATE TABLE a (id INTEGER)"),
                new SchemaMigration(2, "boom", (c, d) -> {
                    try (Statement s = c.createStatement()) {
                        s.execute("THIS IS NOT SQL");
                    }
                }),
                ddl(3, "CREATE TABLE c (id INTEGER)"));
        try (Connection connection = open(dir, "f.db")) {
            SchemaMigrationRunner runner = new SchemaMigrationRunner(
                    migrations, new MigrationTableHistory(), new SqliteDialect());

            SchemaMigrationException error = assertThrows(SchemaMigrationException.class,
                    () -> runner.run(connection));
            assertTrue(error.getMessage().contains("V2"));
            assertTrue(error.getMessage().contains("boom"));

            assertEquals(1, new MigrationTableHistory().currentVersion(connection), "reste à la dernière migration OK");
            assertTrue(tableExists(connection, "a"));
        }
    }

    @Test
    void rejectsDuplicateVersions() {
        assertThrows(IllegalArgumentException.class, () -> new SchemaMigrationRunner(
                List.of(ddl(1, "CREATE TABLE a (id INTEGER)"), ddl(1, "CREATE TABLE b (id INTEGER)")),
                new MigrationTableHistory(), new SqliteDialect()));
    }

    @Test
    void realCatalogueTargetsTheDeclaredCurrentVersion(@TempDir Path dir) throws SQLException {
        try (Connection connection = open(dir, "real.db")) {
            int reached = new SchemaMigrationRunner(SchemaMigrator.ALL, new MigrationTableHistory(), new SqliteDialect())
                    .run(connection);
            assertEquals(SchemaMigrator.CURRENT_VERSION, reached);
            assertTrue(tableExists(connection, "player_profiles"));
            assertTrue(tableExists(connection, "waystones"));
        }
    }

    private static boolean tableExists(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT name FROM sqlite_master WHERE type='table' AND name='" + table + "'")) {
            return resultSet.next();
        }
    }
}
