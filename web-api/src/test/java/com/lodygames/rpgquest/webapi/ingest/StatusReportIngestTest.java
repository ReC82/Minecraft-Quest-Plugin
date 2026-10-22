package com.lodygames.rpgquest.webapi.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.webapi.HttpServerBootstrap;
import com.lodygames.rpgquest.webapi.SnapshotStore;
import com.lodygames.rpgquest.webapi.WebApiConfig;
import com.lodygames.rpgquest.webapi.WebApiConfigLoader;
import com.lodygames.rpgquest.webapi.json.Json;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Relevé d'état agrégé (#421, {@code "kind": "status"}) : arrive même sans export nominatif, ne
 * transporte que « en ligne ? » et deux compteurs, est idempotent (doublon / retard accusés sans
 * écriture) et pilote les quatre états ONLINE / OFFLINE / STALE / UNKNOWN du portail.
 */
class StatusReportIngestTest {

    private static final String SNAPSHOT_TOKEN = "snapshot-token-456";
    private static final String BOOT = "boot-aaaa-1111";

    @TempDir
    Path tempDir;

    private final HttpClient client = HttpClient.newHttpClient();
    private HttpServerBootstrap bootstrap;
    private int port;
    private Path heartbeatFile;

    @AfterEach
    void tearDown() {
        if (bootstrap != null) {
            bootstrap.stop();
        }
    }

    private void start() throws Exception {
        Path snapshotFile = tempDir.resolve("web-export").resolve("snapshot.json");
        heartbeatFile = SnapshotStore.heartbeatFile(snapshotFile);
        Path properties = tempDir.resolve("web-api.properties");
        Files.writeString(properties, "port=0\nrate-limit-per-minute=600\n"
                + "snapshot-file=" + snapshotFile.toString().replace('\\', '/') + "\n");
        WebApiConfig config = WebApiConfigLoader.load(properties, "api-token-123", null, null, SNAPSHOT_TOKEN);
        bootstrap = new HttpServerBootstrap(config, new SnapshotStore(config.snapshotFile(),
                config.snapshotMaxAgeSeconds(), Logger.getLogger("rpgquest-webapi-test")), null);
        bootstrap.start();
        port = bootstrap.boundPort();
    }

    private static Map<String, Object> report(String bootId, long sequence, Instant generatedAt, boolean online,
                                              int playerCount) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", 1);
        root.put("kind", "status");
        root.put("generatedAt", generatedAt.toString());
        root.put("bootId", bootId);
        root.put("sequence", sequence);
        Map<String, Object> server = new LinkedHashMap<>();
        server.put("online", online);
        server.put("playerCount", playerCount);
        server.put("maxPlayers", 20);
        root.put("server", server);
        return root;
    }

    private HttpResponse<String> post(String token, Map<String, Object> body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/snapshot"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private String get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).body();
    }

    @Test
    void statusReportAloneTurnsTheGameOnlineWithoutAnySnapshot() throws Exception {
        start();
        assertTrue(get("/health").contains("\"heartbeat\":\"MISSING\""));

        HttpResponse<String> response = post(SNAPSHOT_TOKEN, report(BOOT, 1, Instant.now(), true, 3));

        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().contains("\"result\":\"STORED\""), response.body());
        String health = get("/health");
        assertTrue(health.contains("\"snapshot\":\"MISSING\""), health);
        assertTrue(health.contains("\"heartbeat\":\"FRESH\""), health);
        assertTrue(health.contains("\"server\":\"ONLINE\""), health);
        assertFalse(health.contains("\"lastUpdate\":null"), health);
        assertTrue(get("/status").contains("Serveur en ligne — 3"), "nombre agrégé seulement");
        assertTrue(get("/").contains("● En ligne"));
        try (var files = Files.list(heartbeatFile.getParent())) {
            assertEquals(1, files.count(), "aucun fichier temporaire, aucun snapshot.json créé");
        }
    }

    @Test
    void duplicatesAndLateReportsAreAcknowledgedWithoutRewriting() throws Exception {
        start();
        Instant now = Instant.now();
        assertEquals(200, post(SNAPSHOT_TOKEN, report(BOOT, 5, now, true, 4)).statusCode());
        String stored = Files.readString(heartbeatFile);

        HttpResponse<String> duplicate = post(SNAPSHOT_TOKEN, report(BOOT, 5, now, true, 4));
        HttpResponse<String> late = post(SNAPSHOT_TOKEN, report(BOOT, 4, now.minusSeconds(30), true, 9));
        HttpResponse<String> oldBoot = post(SNAPSHOT_TOKEN, report("boot-older-0000", 99, now.minusSeconds(600), false, 0));

        assertTrue(duplicate.body().contains("DUPLICATE"), duplicate.body());
        assertTrue(late.body().contains("OUT_OF_ORDER"), late.body());
        assertTrue(oldBoot.body().contains("OUT_OF_ORDER"), oldBoot.body());
        assertEquals(stored, Files.readString(heartbeatFile), "un renvoi ne fait jamais reculer l'état");
        assertTrue(get("/status").contains("Serveur en ligne — 4"), "compteur remplacé, jamais cumulé");
    }

    @Test
    void newerReportReplacesThePlayerCountAndANewBootIsAccepted() throws Exception {
        start();
        Instant now = Instant.now();
        post(SNAPSHOT_TOKEN, report(BOOT, 1, now.minusSeconds(60), true, 4));
        post(SNAPSHOT_TOKEN, report(BOOT, 2, now.minusSeconds(30), true, 1));
        assertTrue(get("/status").contains("Serveur en ligne — 1"));

        HttpResponse<String> restarted = post(SNAPSHOT_TOKEN, report("boot-bbbb-2222", 1, now, true, 0));

        assertTrue(restarted.body().contains("STORED"), restarted.body());
        assertTrue(Files.readString(heartbeatFile).contains("boot-bbbb-2222"));
    }

    @Test
    void announcedShutdownIsOfflineAndSilenceIsOnlyStale() throws Exception {
        start();
        post(SNAPSHOT_TOKEN, report(BOOT, 1, Instant.now(), true, 2));
        post(SNAPSHOT_TOKEN, report(BOOT, 2, Instant.now(), false, 0));
        assertTrue(get("/health").contains("\"server\":\"OFFLINE\""));
        assertTrue(get("/").contains("● Hors ligne"));

        // Canal muet : dernier relevé « en ligne » reçu il y a longtemps → STALE, jamais OFFLINE.
        Files.writeString(heartbeatFile, Json.write(Map.of("schemaVersion", 1, "bootId", BOOT, "sequence", 3,
                "generatedAt", "2026-10-15T08:30:00Z", "receivedAt", "2026-10-15T08:30:00Z",
                "server", Map.of("online", true, "playerCount", 2, "maxPlayers", 20))));
        String health = get("/health");
        assertTrue(health.contains("\"server\":\"STALE\""), health);
        assertTrue(health.contains("\"heartbeat\":\"STALE\""), health);
        assertTrue(get("/status").contains("non actualisées depuis le 15 octobre 2026"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void anyFieldOutsideTheClosedScopeIsRefusedBeforeWriting() throws Exception {
        start();
        Map<String, Object> withNames = report(BOOT, 1, Instant.now(), true, 1);
        withNames.put("players", List.of("Alice"));
        Map<String, Object> withUuid = report(BOOT, 1, Instant.now(), true, 1);
        ((Map<String, Object>) withUuid.get("server")).put("uuid", "00000000-0000-0000-0000-000000000001");
        Map<String, Object> negative = report(BOOT, 1, Instant.now(), true, -1);
        Map<String, Object> future = report(BOOT, 1, Instant.now().plusSeconds(3600), true, 1);
        Map<String, Object> badBoot = report("x", 1, Instant.now(), true, 1);
        Map<String, Object> unknownKind = report(BOOT, 1, Instant.now(), true, 1);
        unknownKind.put("kind", "players");

        for (Map<String, Object> body : List.of(withNames, withUuid, negative, future, badBoot, unknownKind)) {
            assertEquals(400, post(SNAPSHOT_TOKEN, body).statusCode(), Json.write(body));
        }
        assertFalse(Files.exists(heartbeatFile));
    }

    @Test
    void statusReportNeedsTheDedicatedToken() throws Exception {
        start();
        assertEquals(401, post("api-token-123", report(BOOT, 1, Instant.now(), true, 1)).statusCode());
        assertEquals(401, post(null, report(BOOT, 1, Instant.now(), true, 1)).statusCode());
        assertFalse(Files.exists(heartbeatFile));
    }
}
