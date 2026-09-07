package com.lodygames.rpgquest.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** Modèle de configuration de persistance (issue #40) : défauts, secrets hors config. */
class DatabaseSettingsTest {

    @Test
    void defaultsToSqliteDataDb() {
        DatabaseSettings settings = DatabaseSettings.sqliteDefault();
        assertEquals(DatabaseType.SQLITE, settings.type());
        assertEquals("data.db", settings.sqlite().file());
        assertEquals("sqlite (data.db)", settings.describe());
    }

    @Test
    void nullComponentsFallBackToSafeDefaults() {
        DatabaseSettings settings = new DatabaseSettings(null, null, null);
        assertEquals(DatabaseType.SQLITE, settings.type());
        assertEquals("data.db", settings.sqlite().file());
        assertEquals(3306, settings.mysql().port());
        assertEquals("RPGQUEST_DB_PASSWORD", settings.mysql().passwordEnv());
        assertEquals(10, settings.mysql().pool().maxSize());
    }

    @Test
    void describeNeverContainsAPassword() {
        DatabaseSettings settings = new DatabaseSettings(DatabaseType.MYSQL,
                DatabaseSettings.SqliteSettings.defaults(),
                new DatabaseSettings.MySqlSettings("db.example", 3307, "rpg", "rpguser", "RPGQUEST_DB_PASSWORD",
                        DatabaseSettings.PoolSettings.defaults()));
        String described = settings.describe();
        assertEquals("mysql rpguser@db.example:3307/rpg", described);
        assertFalse(described.toLowerCase().contains("password"));
        assertFalse(described.contains("s3cr3t"));
    }

    @Test
    void passwordIsResolvedFromEnvironmentOnly() {
        DatabaseSettings.MySqlSettings mysql = DatabaseSettings.MySqlSettings.defaults();
        assertTrue(mysql.resolvePassword(k -> null).isEmpty(), "variable absente -> pas de mot de passe");
        assertEquals("s3cr3t",
                mysql.resolvePassword(Map.of("RPGQUEST_DB_PASSWORD", "s3cr3t")::get).orElseThrow());
    }

    @Test
    void jdbcUrlHasNoCredentials() {
        String url = DatabaseSettings.MySqlSettings.defaults().jdbcUrl();
        assertEquals("jdbc:mysql://localhost:3306/rpgquest", url);
        assertFalse(url.contains("@"));
    }
}
