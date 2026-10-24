package com.lodygames.rpgquest.web.push;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.config.WebExportConfig;
import com.lodygames.rpgquest.player.PublicProfilePolicy;
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

    @Test
    void fullSnapshotToThePortalIsOptInWithItsOwnBoundedInterval(@TempDir Path dir) throws Exception {
        Path file = dir.resolve(SnapshotPushConfigLoader.FILE_NAME);
        Files.writeString(file, "enabled=true\nbase-url=https://portail.example\ntoken=t\n");
        SnapshotPushConfig statusOnly = load(file, Map.of());
        assertFalse(statusOnly.snapshotEnabled(), "relevé d'état seul par défaut");
        WebExportConfig base = new WebExportConfig(false, "web-export", 30, false, 10, java.util.List.of(),
                java.util.List.of(), PublicProfilePolicy.Visibility.PRIVATE);
        assertEquals(base, statusOnly.applyTo(base), "config.yml inchangé");

        Files.writeString(file, "enabled=true\nbase-url=https://portail.example\ntoken=t\nsnapshot-enabled=true\n"
                + "snapshot-interval-seconds=60\nsnapshot-profile-visibility=PUBLIC\n");
        SnapshotPushConfig beta = load(file, Map.of());
        assertTrue(beta.snapshotEnabled());
        assertEquals(SnapshotPushConfig.MIN_SNAPSHOT_INTERVAL_SECONDS, beta.snapshotIntervalSeconds(), "10 min au plus court");
        WebExportConfig effective = beta.applyTo(base);
        assertTrue(effective.enabled(), "l'export tourne pour le portail");
        assertEquals(600, effective.intervalSeconds());
        assertEquals(PublicProfilePolicy.Visibility.PUBLIC, effective.profileVisibilityDefault());
        assertEquals(base.leaderboardSize(), effective.leaderboardSize());

        Files.writeString(file, "enabled=true\nbase-url=https://portail.example\ntoken=t\nsnapshot-enabled=true\n"
                + "snapshot-profile-visibility=tout-le-monde\n");
        SnapshotPushConfig defaults = load(file, Map.of());
        assertEquals(900, defaults.snapshotIntervalSeconds(), "15 min par défaut");
        assertEquals(PublicProfilePolicy.Visibility.PRIVATE, defaults.applyTo(base).profileVisibilityDefault(),
                "valeur inconnue : visibilité de config.yml conservée");

        Files.writeString(file, "enabled=false\nsnapshot-enabled=true\n");
        assertEquals(base, load(file, Map.of()).applyTo(base), "canal fermé : rien n'est activé");
    }
}
