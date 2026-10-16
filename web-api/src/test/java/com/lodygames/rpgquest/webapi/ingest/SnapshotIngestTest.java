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
 * Canal serveur Minecraft → portail (#421) : {@code POST /api/snapshot} n'accepte qu'un instantané
 * authentifié par le jeton dédié, borné en taille et bien formé, et le dépose de façon atomique.
 * Un envoi refusé laisse le fichier précédent intact.
 */
class SnapshotIngestTest {

    private static final String API_TOKEN = "api-token-123";
    private static final String SNAPSHOT_TOKEN = "snapshot-token-456";
    private static final String PREVIOUS = "{\"previous\":true}";

    @TempDir
    Path tempDir;

    private final HttpClient client = HttpClient.newHttpClient();
    private HttpServerBootstrap bootstrap;
    private int port;
    private Path snapshotFile;

    @AfterEach
    void tearDown() {
        if (bootstrap != null) {
            bootstrap.stop();
        }
    }

    private void start(String snapshotToken) throws Exception {
        snapshotFile = tempDir.resolve("web-export").resolve("snapshot.json");
        Path properties = tempDir.resolve("web-api.properties");
        Files.writeString(properties, "port=0\nrate-limit-per-minute=600\nsnapshot-ingest-max-bytes=65536\n"
                + "snapshot-file=" + snapshotFile.toString().replace('\\', '/') + "\n");
        WebApiConfig config = WebApiConfigLoader.load(properties, API_TOKEN, null, null, snapshotToken);
        bootstrap = new HttpServerBootstrap(config, new SnapshotStore(config.snapshotFile(),
                config.snapshotMaxAgeSeconds(), Logger.getLogger("rpgquest-webapi-test")), null);
        bootstrap.start();
        port = bootstrap.boundPort();
    }

    private static Map<String, Object> snapshot(boolean online) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", 2);
        root.put("generatedAt", Instant.now().toString());
        root.put("server", Map.of("online", online, "playerCount", 1, "maxPlayers", 20));
        root.put("players", List.of());
        root.put("leaderboards", Map.of());
        root.put("catalog", List.of());
        root.put("announcements", List.of());
        return root;
    }

    private HttpResponse<String> post(String path, String token, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private void writePrevious() throws Exception {
        Files.createDirectories(snapshotFile.getParent());
        Files.writeString(snapshotFile, PREVIOUS, StandardCharsets.UTF_8);
    }

    @Test
    void validSnapshotIsStoredVerbatimAndTurnsHealthFresh() throws Exception {
        start(SNAPSHOT_TOKEN);
        assertTrue(get("/health").body().contains("\"snapshot\":\"MISSING\""));
        String body = Json.write(snapshot(true));

        HttpResponse<String> response = post("/api/snapshot", SNAPSHOT_TOKEN, body);

        assertEquals(200, response.statusCode(), response.body());
        assertEquals(body, Files.readString(snapshotFile, StandardCharsets.UTF_8));
        String health = get("/health").body();
        assertTrue(health.contains("\"snapshot\":\"FRESH\""), health);
        assertTrue(health.contains("\"server\":\"ONLINE\""), health);
        try (var files = Files.list(snapshotFile.getParent())) {
            assertEquals(1, files.count(), "aucun fichier temporaire laissé derrière");
        }
    }

    @Test
    void wrongOrMissingTokenIsRejectedWithoutWriting() throws Exception {
        start(SNAPSHOT_TOKEN);
        writePrevious();
        String body = Json.write(snapshot(true));

        assertEquals(401, post("/api/snapshot", "mauvais", body).statusCode());
        assertEquals(401, post("/api/snapshot", null, body).statusCode());
        assertEquals(401, post("/api/snapshot", API_TOKEN, body).statusCode(), "le jeton général n'ouvre pas la route");
        assertEquals(PREVIOUS, Files.readString(snapshotFile));
    }

    @Test
    void routeIsClosedWithoutConfiguredToken() throws Exception {
        start(null);
        assertEquals(401, post("/api/snapshot", "", Json.write(snapshot(true))).statusCode());
        assertFalse(Files.exists(snapshotFile));
    }

    @Test
    void oversizedBodyIsRejectedWith413() throws Exception {
        start(SNAPSHOT_TOKEN);
        writePrevious();
        Map<String, Object> big = snapshot(true);
        big.put("catalog", List.of("x".repeat(70_000)));

        assertEquals(413, post("/api/snapshot", SNAPSHOT_TOKEN, Json.write(big)).statusCode());
        assertEquals(PREVIOUS, Files.readString(snapshotFile));
    }

    @Test
    void malformedSnapshotsLeaveThePreviousFileUntouched() throws Exception {
        start(SNAPSHOT_TOKEN);
        writePrevious();
        Map<String, Object> badVersion = snapshot(true);
        badVersion.put("schemaVersion", 99);
        Map<String, Object> noDate = snapshot(true);
        noDate.remove("generatedAt");
        Map<String, Object> future = snapshot(true);
        future.put("generatedAt", Instant.now().plusSeconds(3600).toString());
        Map<String, Object> noServer = snapshot(true);
        noServer.remove("server");
        Map<String, Object> withUuid = snapshot(true);
        withUuid.put("players", List.of(Map.of("name", "Alice", "uuid", "00000000-0000-0000-0000-000000000001")));

        for (String body : List.of("{pas du json", "[1,2]", Json.write(badVersion), Json.write(noDate),
                Json.write(future), Json.write(noServer), Json.write(withUuid))) {
            assertEquals(400, post("/api/snapshot", SNAPSHOT_TOKEN, body).statusCode(), body);
        }
        assertEquals(PREVIOUS, Files.readString(snapshotFile));
    }

    @Test
    void onlyPostOnTheExactPathIsAccepted() throws Exception {
        start(SNAPSHOT_TOKEN);
        assertEquals(405, get("/api/snapshot").statusCode());
        assertEquals(404, post("/api/snapshotx", SNAPSHOT_TOKEN, Json.write(snapshot(true))).statusCode());
    }

    @Test
    void announcedShutdownIsShownAsOfflineOnlyThen() throws Exception {
        start(SNAPSHOT_TOKEN);
        assertEquals(200, post("/api/snapshot", SNAPSHOT_TOKEN, Json.write(snapshot(false))).statusCode());

        assertTrue(get("/status").body().contains("a annoncé son arrêt"));
        assertTrue(get("/").body().contains("● Hors ligne"));
        assertTrue(get("/health").body().contains("\"server\":\"OFFLINE\""));
    }
}
