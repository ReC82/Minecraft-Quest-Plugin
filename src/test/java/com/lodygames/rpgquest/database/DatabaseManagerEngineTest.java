package com.lodygames.rpgquest.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link DatabaseManager} piloté par un {@link DatabaseEngine} (issue #40). */
class DatabaseManagerEngineTest {

    @Test
    void legacyPathConstructorStillWorksExactlyAsBefore(@TempDir Path dir) throws Exception {
        DatabaseManager database = new DatabaseManager(dir.resolve("data.db"));
        try {
            database.initialize().get(10, TimeUnit.SECONDS);
            assertEquals(DatabaseType.SQLITE, database.engineType());
            assertTrue(database.describeEngine().contains("data.db"));

            int version = database.execute(connection -> {
                try (var statement = connection.createStatement();
                     var resultSet = statement.executeQuery("PRAGMA user_version")) {
                    return resultSet.next() ? resultSet.getInt(1) : -1;
                }
            }).get(10, TimeUnit.SECONDS);
            assertEquals(SchemaMigrator.CURRENT_VERSION, version);

            assertTrue(database.healthCheck().get(10, TimeUnit.SECONDS));
        } finally {
            database.shutdown();
        }
    }

    @Test
    void initializeViaExplicitSqliteEngineRunsMigrations(@TempDir Path dir) throws Exception {
        DatabaseEngine engine = DatabaseEngineFactory.create(DatabaseSettings.sqlite("g.db"), dir);
        DatabaseManager database = new DatabaseManager(engine);
        try {
            database.initialize().get(10, TimeUnit.SECONDS);
            long profiles = database.execute(connection -> {
                try (var statement = connection.createStatement();
                     var resultSet = statement.executeQuery("SELECT COUNT(*) FROM player_profiles")) {
                    resultSet.next();
                    return resultSet.getLong(1);
                }
            }).get(10, TimeUnit.SECONDS);
            assertEquals(0L, profiles);
            assertEquals("sqlite", database.dialect().name());
        } finally {
            database.shutdown();
        }
    }

    @Test
    void healthCheckIsFalseWhenNotInitialisedAndTrueAfter(@TempDir Path dir) throws Exception {
        DatabaseManager database = new DatabaseManager(dir.resolve("h.db"));
        try {
            // pas encore initialisé : connexion nulle -> false, jamais d'exception
            assertFalse(database.healthCheck().get(10, TimeUnit.SECONDS));
            database.initialize().get(10, TimeUnit.SECONDS);
            assertTrue(database.healthCheck().get(10, TimeUnit.SECONDS));
        } finally {
            database.shutdown();
        }
        assertTrue(database.isClosed());
    }

    @Test
    void mysqlEngineMakesInitializeFailWithAnActionableMessage(@TempDir Path dir) {
        DatabaseEngine engine = DatabaseEngineFactory.create(
                new DatabaseSettings(DatabaseType.MYSQL, DatabaseSettings.SqliteSettings.defaults(),
                        DatabaseSettings.MySqlSettings.defaults()),
                dir, k -> null);
        DatabaseManager database = new DatabaseManager(engine);
        try {
            ExecutionException error = assertThrows(ExecutionException.class,
                    () -> database.initialize().get(10, TimeUnit.SECONDS));
            assertTrue(error.getCause().getMessage().contains("#41"));
        } finally {
            database.shutdown();
        }
    }
}
