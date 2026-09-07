package com.lodygames.rpgquest.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Sélection et comportement des moteurs (issue #40). Un seul point de câblage : la factory. */
class DatabaseEngineTest {

    @Test
    void factorySelectsSqliteEngineForSqliteSettings(@TempDir Path dir) {
        DatabaseEngine engine = DatabaseEngineFactory.create(DatabaseSettings.sqlite("game.db"), dir);
        assertInstanceOf(SqliteDatabaseEngine.class, engine);
        assertEquals(DatabaseType.SQLITE, engine.type());
        assertEquals("sqlite (game.db)", engine.describe());
        assertInstanceOf(SqliteDialect.class, engine.dialect());
        assertInstanceOf(PragmaUserVersionHistory.class, engine.schemaHistory());
    }

    @Test
    void factorySelectsMySqlEngineForMySqlSettings(@TempDir Path dir) {
        DatabaseSettings mysql = new DatabaseSettings(DatabaseType.MYSQL,
                DatabaseSettings.SqliteSettings.defaults(), DatabaseSettings.MySqlSettings.defaults());
        DatabaseEngine engine = DatabaseEngineFactory.create(mysql, dir, k -> null);
        assertInstanceOf(MySqlDatabaseEngine.class, engine);
        assertEquals(DatabaseType.MYSQL, engine.type());
        assertInstanceOf(MySqlDialect.class, engine.dialect());
        assertInstanceOf(MigrationTableHistory.class, engine.schemaHistory());
    }

    @Test
    void sqliteEngineOpensAndConfiguresAConnection(@TempDir Path dir) throws SQLException {
        DatabaseEngine engine = DatabaseEngineFactory.create(DatabaseSettings.sqlite("x.db"), dir);
        try (Connection connection = engine.openConnection()) {
            engine.configureSession(connection);
            assertFalse(connection.isClosed());
        }
    }

    @Test
    void mySqlEngineFailsCleanlyPointingToIssue41(@TempDir Path dir) {
        DatabaseEngine engine = DatabaseEngineFactory.create(
                new DatabaseSettings(DatabaseType.MYSQL, DatabaseSettings.SqliteSettings.defaults(),
                        DatabaseSettings.MySqlSettings.defaults()),
                dir, k -> null);
        SQLException error = assertThrows(SQLException.class, engine::openConnection);
        assertTrue(error.getMessage().contains("#41"));
        assertTrue(error.getMessage().contains("sqlite"));
        // aide diagnostique : la variable d'environnement manquante est nommée, jamais sa valeur
        assertTrue(error.getMessage().contains("RPGQUEST_DB_PASSWORD"));
    }

    @Test
    void mySqlEngineDescribeAndErrorNeverLeakThePassword(@TempDir Path dir) {
        DatabaseSettings settings = new DatabaseSettings(DatabaseType.MYSQL,
                DatabaseSettings.SqliteSettings.defaults(),
                new DatabaseSettings.MySqlSettings("h", 3306, "d", "u", "RPGQUEST_DB_PASSWORD",
                        DatabaseSettings.PoolSettings.defaults()));
        DatabaseEngine engine = DatabaseEngineFactory.create(settings, dir,
                Map.of("RPGQUEST_DB_PASSWORD", "TOP-SECRET")::get);
        assertFalse(engine.describe().contains("TOP-SECRET"));
        SQLException error = assertThrows(SQLException.class, engine::openConnection);
        assertFalse(error.getMessage().contains("TOP-SECRET"));
    }
}
