package com.lodygames.rpgquest.webapi.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.webapi.WebApiConfig;
import com.lodygames.rpgquest.webapi.WebApiConfigLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AccountSettingsLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void accountsAreDisabledByDefault() throws Exception {
        WebApiConfig config = WebApiConfigLoader.load(tempDir.resolve("absent.properties"), null, null, null);

        assertFalse(config.accounts().enabled());
        assertFalse(config.accounts().secureCookies(), "http://localhost par défaut");
    }

    @Test
    void readsAccountKeysAndTakesTheLinkTokenFromEnvironmentOnly() throws Exception {
        Path file = tempDir.resolve("web-api.properties");
        Files.writeString(file, """
                public-base-url=https://lodyquests.lodylands.com
                accounts-enabled=true
                accounts-database-file=data/accounts.db
                accounts-session-ttl-hours=24
                accounts-link-code-ttl-minutes=5
                link-token=ignored-from-file
                """);

        AccountSettings settings = WebApiConfigLoader.load(file, null, null, "from-env").accounts();

        assertTrue(settings.enabled());
        assertTrue(settings.secureCookies());
        assertEquals(Path.of("data/accounts.db"), settings.databaseFile());
        assertEquals(Duration.ofHours(24), settings.sessionTtl());
        assertEquals(Duration.ofMinutes(5), settings.linkCodeTtl());
        assertEquals("from-env", settings.linkToken());
    }

    @Test
    void refusesOutOfRangeDurations() throws Exception {
        Path file = tempDir.resolve("web-api.properties");
        Files.writeString(file, "accounts-link-code-ttl-minutes=120\n");

        assertThrows(IllegalStateException.class, () -> WebApiConfigLoader.load(file, null, null, null));
    }
}
