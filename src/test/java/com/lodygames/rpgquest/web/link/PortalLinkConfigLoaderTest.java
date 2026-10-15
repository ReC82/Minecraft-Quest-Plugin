package com.lodygames.rpgquest.web.link;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/** Issue #53 : configuration de /lien, fichier hors Git + surcharge env, fail-closed. */
class PortalLinkConfigLoaderTest {

    private static PortalLinkConfig load(Path file, Map<String, String> env) {
        return new PortalLinkConfigLoader(file, env::get, LoggerFactory.getLogger("test")).load();
    }

    @Test
    void disabledWhenNothingIsProvided(@TempDir Path dir) {
        assertFalse(load(dir.resolve("absent.properties"), Map.of()).enabled());
    }

    @Test
    void enabledFromTheLocalFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve(PortalLinkConfigLoader.FILE_NAME);
        Files.writeString(file, """
                enabled=true
                base-url=https://lodyquests.lodylands.com/
                token=secret-de-liaison
                """);

        PortalLinkConfig config = load(file, Map.of());

        assertTrue(config.enabled());
        assertEquals("https://lodyquests.lodylands.com", config.baseUrl());
        assertFalse(config.toString().contains("secret-de-liaison"), "jamais le jeton dans les journaux");
    }

    @Test
    void environmentOverridesTheFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve(PortalLinkConfigLoader.FILE_NAME);
        Files.writeString(file, "enabled=true\nbase-url=https://a.example\ntoken=fichier\n");

        PortalLinkConfig config = load(file, Map.of("RPGQUEST_PORTAL_LINK_TOKEN", "env",
                "RPGQUEST_PORTAL_LINK_BASE_URL", "https://b.example"));

        assertEquals("env", config.token());
        assertEquals("https://b.example", config.baseUrl());
    }

    @Test
    void failsClosedWithoutTokenOrOverPlainHttp(@TempDir Path dir) throws Exception {
        Path file = dir.resolve(PortalLinkConfigLoader.FILE_NAME);
        Files.writeString(file, "enabled=true\nbase-url=https://a.example\n");
        assertFalse(load(file, Map.of()).enabled(), "sans jeton");

        Files.writeString(file, "enabled=true\nbase-url=http://a.example\ntoken=t\n");
        assertFalse(load(file, Map.of()).enabled(), "HTTP en clair hors machine locale");

        Files.writeString(file, "enabled=true\nbase-url=http://127.0.0.1:8092\ntoken=t\n");
        assertTrue(load(file, Map.of()).enabled(), "local autorisé");
    }
}
