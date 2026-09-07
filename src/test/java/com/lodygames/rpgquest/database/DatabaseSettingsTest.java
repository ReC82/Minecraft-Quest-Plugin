package com.lodygames.rpgquest.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** Modèle de configuration de persistance (issues #40 / #41) : défauts, secrets hors config. */
class DatabaseSettingsTest {

    private static DatabaseSettings.MySqlSettings mysql(String host, int port, String db, String user, String sslMode) {
        return new DatabaseSettings.MySqlSettings(host, port, db, user, "RPGQUEST_DB_PASSWORD", sslMode,
                DatabaseSettings.PoolSettings.defaults());
    }

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
        assertEquals("disable", settings.mysql().sslMode());
        assertEquals(10, settings.mysql().pool().maximumPoolSize());
        assertEquals(2, settings.mysql().pool().minimumIdle());
    }

    @Test
    void describeNeverContainsAPasswordOrJdbcSecret() {
        DatabaseSettings settings = new DatabaseSettings(DatabaseType.MYSQL,
                DatabaseSettings.SqliteSettings.defaults(),
                mysql("db.example", 3307, "rpg", "rpguser", "trust"));
        String described = settings.describe();
        assertTrue(described.startsWith("mariadb rpguser@db.example:3307/rpg"));
        assertFalse(described.toLowerCase().contains("password"));
        assertFalse(described.contains("s3cr3t"));
    }

    @Test
    void passwordIsResolvedFromEnvironmentOnly() {
        DatabaseSettings.MySqlSettings settings = DatabaseSettings.MySqlSettings.defaults();
        assertTrue(settings.resolvePassword(k -> null).isEmpty(), "variable absente -> pas de mot de passe");
        assertEquals("s3cr3t",
                settings.resolvePassword(Map.of("RPGQUEST_DB_PASSWORD", "s3cr3t")::get).orElseThrow());
    }

    @Test
    void jdbcUrlUsesMariadbDriverAndHasNoCredentials() {
        String url = DatabaseSettings.MySqlSettings.defaults().jdbcUrl();
        assertEquals("jdbc:mariadb://localhost:3306/rpgquest", url);
        assertFalse(url.contains("@"));
    }

    @Test
    void sslModeIsNormalisedAndUnknownRejected() {
        assertEquals("disable", mysql("h", 3306, "d", "u", "OFF").sslMode());
        assertEquals("verify-ca", mysql("h", 3306, "d", "u", "verify_ca").sslMode());
        assertThrows(IllegalArgumentException.class, () -> mysql("h", 3306, "d", "u", "please"));
    }

    @Test
    void poolClampsInvalidValues() {
        DatabaseSettings.PoolSettings pool = new DatabaseSettings.PoolSettings(50, 5, 10L, -1L, 999_999_999L);
        assertEquals(5, pool.maximumPoolSize());
        assertTrue(pool.minimumIdle() <= pool.maximumPoolSize());
        assertEquals(10_000L, pool.connectionTimeoutMs(), "trop court -> défaut");
        assertEquals(1_800_000L, pool.maxLifetimeMs(), "négatif -> défaut");
        assertEquals(0L, pool.keepaliveMs(), "keepalive >= maxLifetime -> désactivé");
    }
}
