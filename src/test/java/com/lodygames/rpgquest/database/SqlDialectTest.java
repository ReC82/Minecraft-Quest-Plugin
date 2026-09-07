package com.lodygames.rpgquest.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Encapsulation des différences SQL par moteur (issue #40). */
class SqlDialectTest {

    private final SqlDialect sqlite = new SqliteDialect();
    private final SqlDialect mysql = new MySqlDialect();

    @Test
    void sqliteUpsertMatchesTheSyntaxUsedByRepositories() {
        String sql = sqlite.upsert("player_variables",
                List.of("player_uuid", "variable_key", "variable_value"),
                List.of("player_uuid", "variable_key"),
                List.of("variable_value"));
        assertEquals("INSERT INTO player_variables (player_uuid, variable_key, variable_value) VALUES (?, ?, ?) "
                + "ON CONFLICT (player_uuid, variable_key) DO UPDATE SET variable_value = excluded.variable_value", sql);
    }

    @Test
    void mysqlUpsertUsesOnDuplicateKeyUpdate() {
        String sql = mysql.upsert("player_variables",
                List.of("player_uuid", "variable_key", "variable_value"),
                List.of("player_uuid", "variable_key"),
                List.of("variable_value"));
        assertEquals("INSERT INTO player_variables (player_uuid, variable_key, variable_value) VALUES (?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE variable_value = VALUES(variable_value)", sql);
    }

    @Test
    void insertOrIgnoreDiffersByEngine() {
        assertEquals("INSERT OR IGNORE INTO wallets (player_uuid, balance, updated_at) VALUES (?, ?, ?)",
                sqlite.insertOrIgnore("wallets", List.of("player_uuid", "balance", "updated_at")));
        assertEquals("INSERT IGNORE INTO wallets (player_uuid, balance, updated_at) VALUES (?, ?, ?)",
                mysql.insertOrIgnore("wallets", List.of("player_uuid", "balance", "updated_at")));
    }

    @Test
    void autoIncrementPrimaryKeyDiffersByEngine() {
        assertEquals("id INTEGER PRIMARY KEY AUTOINCREMENT", sqlite.autoIncrementPrimaryKey("id"));
        assertEquals("id BIGINT PRIMARY KEY AUTO_INCREMENT", mysql.autoIncrementPrimaryKey("id"));
    }

    @Test
    void sqliteColumnExistsReadsPragmaTableInfo(@TempDir java.nio.file.Path dir) throws SQLException {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("d.db"))) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE t (a INTEGER, b TEXT)");
            }
            assertTrue(sqlite.columnExists(connection, "t", "a"));
            assertTrue(sqlite.columnExists(connection, "t", "b"));
            assertFalse(sqlite.columnExists(connection, "t", "c"));
        }
    }

    @Test
    void namesAreStable() {
        assertEquals("sqlite", sqlite.name());
        assertEquals("mysql", mysql.name());
        assertEquals("SELECT 1", sqlite.healthQuery());
        assertEquals("SELECT 1", mysql.healthQuery());
    }
}
