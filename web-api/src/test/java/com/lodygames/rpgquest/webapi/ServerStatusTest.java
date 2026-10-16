package com.lodygames.rpgquest.webapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.webapi.json.Json;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Quatre états distincts du serveur de jeu (#421) : l'absence ou l'ancienneté des données ne
 * permettent jamais d'annoncer « hors ligne » ; seul un arrêt annoncé par le serveur le permet.
 */
class ServerStatusTest {

    @TempDir
    Path tempDir;

    private ServerStatus statusOf(Instant generatedAt, boolean online) throws Exception {
        Path file = tempDir.resolve("snapshot.json");
        if (generatedAt != null) {
            Files.writeString(file, Json.write(Map.of("generatedAt", generatedAt.toString(),
                    "server", Map.of("online", online, "playerCount", 2, "maxPlayers", 20), "players", List.of())),
                    StandardCharsets.UTF_8);
        }
        return ServerStatus.of(ServerState.resolve(new SnapshotStore(file, 120, Logger.getLogger("test"))));
    }

    @Test
    void missingSnapshotIsUnknownWithNeutralBanner() throws Exception {
        ServerStatus status = statusOf(null, true);

        assertEquals(ServerStatus.Kind.UNKNOWN, status.kind());
        assertTrue(status.bannerHtml().contains("inconnu"));
        assertFalse(status.bannerHtml().toLowerCase().contains("hors ligne"));
        assertFalse(status.bannerHtml().contains("class=\"offline\""));
    }

    @Test
    void staleSnapshotIsNotOfflineAndShowsTheLastUpdate() throws Exception {
        ServerStatus status = statusOf(Instant.parse("2026-10-15T08:30:00Z"), true);

        assertEquals(ServerStatus.Kind.STALE, status.kind());
        assertTrue(status.bannerHtml().contains("non actualisées depuis le 15 octobre 2026 à 10h30"), status.bannerHtml());
        assertFalse(status.bannerHtml().contains("class=\"offline\""));
    }

    @Test
    void freshSnapshotIsOnline() throws Exception {
        ServerStatus status = statusOf(Instant.now(), true);

        assertEquals(ServerStatus.Kind.ONLINE, status.kind());
        assertTrue(status.online());
        assertEquals("", status.bannerHtml());
    }

    @Test
    void announcedShutdownIsOfflineEvenWhenOld() throws Exception {
        ServerStatus status = statusOf(Instant.parse("2026-10-15T08:30:00Z"), false);

        assertEquals(ServerStatus.Kind.OFFLINE, status.kind());
        assertTrue(status.bannerHtml().contains("class=\"offline\""));
        assertTrue(status.bannerHtml().contains("hors ligne"));
    }
}
