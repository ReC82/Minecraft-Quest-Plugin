package com.lodygames.rpgquest.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Sélection et comportement des moteurs (issues #40 / #41). Un seul point de câblage : la factory. */
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
    void sqliteEngineLendsTheSameSingleConnection(@TempDir Path dir) throws SQLException {
        DatabaseEngine engine = DatabaseEngineFactory.create(DatabaseSettings.sqlite("x.db"), dir);
        engine.start();
        try {
            Connection first = engine.borrow();
            engine.release(first);
            Connection second = engine.borrow();
            engine.release(second);
            assertSame(first, second, "SQLite : connexion unique réutilisée (comportement historique)");
            assertFalse(first.isClosed());
        } finally {
            engine.close();
        }
    }

    @Test
    void mySqlEngineFailsCleanlyWhenPasswordEnvVarIsMissing(@TempDir Path dir) {
        DatabaseEngine engine = DatabaseEngineFactory.create(
                new DatabaseSettings(DatabaseType.MYSQL, DatabaseSettings.SqliteSettings.defaults(),
                        DatabaseSettings.MySqlSettings.defaults()),
                dir, k -> null);
        SQLException error = assertThrows(SQLException.class, engine::start);
        assertTrue(error.getMessage().contains("RPGQUEST_DB_PASSWORD"),
                "le nom de la variable manquante est indiqué");
        assertFalse(error.getMessage().toLowerCase().contains("select"));
    }

    @Test
    void mySqlEngineFailsCleanlyWhenServerUnreachable(@TempDir Path dir) {
        // hôte inexistant + timeout court : échec borné, pas de boucle de reconnexion
        DatabaseSettings.MySqlSettings settings = new DatabaseSettings.MySqlSettings(
                "127.0.0.1", 1, "rpgquest", "rpgquest", "PW", "disable",
                new DatabaseSettings.PoolSettings(1, 1, 800L, 60_000L, 0L));
        DatabaseEngine engine = new MySqlDatabaseEngine(settings, k -> "irrelevant");
        assertThrows(SQLException.class, engine::start);
        engine.close();
    }

    @Test
    void mySqlEngineDescribeNeverLeaksThePassword(@TempDir Path dir) {
        DatabaseSettings.MySqlSettings settings = new DatabaseSettings.MySqlSettings(
                "h", 3306, "d", "u", "PW", "disable", DatabaseSettings.PoolSettings.defaults());
        DatabaseEngine engine = new MySqlDatabaseEngine(settings, k -> "TOP-SECRET");
        assertFalse(engine.describe().contains("TOP-SECRET"));
        assertTrue(engine.describe().startsWith("mariadb u@h:3306/d"));
        assertNotSame(engine.dialect(), null);
    }
}
