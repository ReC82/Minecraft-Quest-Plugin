package com.lodygames.rpgquest.web.push;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/** Issue #421 : l'envoi au portail est fermé par défaut, en HTTPS seulement, et ne montre jamais le jeton. */
class SnapshotPushConfigLoaderTest {

    private static SnapshotPushConfig load(Path file, Map<String, String> env) {
        return new SnapshotPushConfigLoader(file, env::get, LoggerFactory.getLogger("test")).load();
    }

    @Test
    void disabledWhenNothingIsProvided(@TempDir Path dir) {
        assertFalse(load(dir.resolve(SnapshotPushConfigLoader.FILE_NAME), Map.of()).enabled());
    }

    @Test
    void enabledFromTheLocalFileWithoutLeakingTheToken(@TempDir Path dir) throws Exception {
        Path file = dir.resolve(SnapshotPushConfigLoader.FILE_NAME);
        Files.writeString(file, "enabled=true\nbase-url=https://portail.example/\ntoken=jeton-secret\nrequest-timeout-ms=99\n");

        SnapshotPushConfig config = load(file, Map.of());

        assertTrue(config.enabled());
        assertEquals("https://portail.example", config.baseUrl());
        assertEquals("jeton-secret", config.token());
        assertEquals(1000, config.requestTimeoutMs(), "borné au minimum");
        assertFalse(config.toString().contains("jeton-secret"));
    }

    @Test
    void environmentOverridesTheFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve(SnapshotPushConfigLoader.FILE_NAME);
        Files.writeString(file, "enabled=false\nbase-url=https://ancien.example\ntoken=ancien\n");

        SnapshotPushConfig config = load(file, Map.of("RPGQUEST_PORTAL_SNAPSHOT_ENABLED", "true",
                "RPGQUEST_PORTAL_SNAPSHOT_BASE_URL", "https://nouveau.example",
                "RPGQUEST_PORTAL_SNAPSHOT_TOKEN", "nouveau"));

        assertTrue(config.enabled());
        assertEquals("https://nouveau.example", config.baseUrl());
        assertEquals("nouveau", config.token());
    }

    @Test
    void failsClosedWithoutTokenOrOverPlainHttp(@TempDir Path dir) throws Exception {
        Path file = dir.resolve(SnapshotPushConfigLoader.FILE_NAME);
        Files.writeString(file, "enabled=true\nbase-url=https://portail.example\n");
        assertFalse(load(file, Map.of()).enabled(), "sans jeton");

        Files.writeString(file, "enabled=true\nbase-url=http://portail.example\ntoken=t\n");
        assertFalse(load(file, Map.of()).enabled(), "HTTP en clair hors machine locale");

        Files.writeString(file, "enabled=true\nbase-url=http://127.0.0.1:8092\ntoken=t\n");
        assertTrue(load(file, Map.of()).enabled(), "HTTP local accepté (tests, tunnel)");
    }
}
