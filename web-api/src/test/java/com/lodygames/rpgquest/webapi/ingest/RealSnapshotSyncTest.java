package com.lodygames.rpgquest.webapi.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.webapi.HttpServerBootstrap;
import com.lodygames.rpgquest.webapi.ServerState;
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
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * #421 (bêta du 24/10) : instantanés complets réels toutes les 10 à 15 min. Numérotés, ils ne sont
 * jamais écrits deux fois ni en retard ; leurs données restent affichées entre deux envois tant que
 * le relevé d'état (30 s) dit le serveur en ligne, datées par {@code syncedAt}, puis disparaissent
 * au-delà de {@code snapshot-data-max-age-seconds}. {@code /health} expose la synchronisation et la
 * charge du serveur.
 */
class RealSnapshotSyncTest {

    private static final String TOKEN = "snapshot-token-456";
    private static final String BOOT = "boot-aaaa-1111";

    @TempDir
    Path tempDir;

    private final HttpClient client = HttpClient.newHttpClient();
    private HttpServerBootstrap bootstrap;
    private SnapshotStore store;
    private int port;
    private Path snapshotFile;

    @AfterEach
    void tearDown() {
        if (bootstrap != null) {
            bootstrap.stop();
        }
    }

    private void start() throws Exception {
        snapshotFile = tempDir.resolve("web-export").resolve("snapshot.json");
        Path properties = tempDir.resolve("web-api.properties");
        Files.writeString(properties, "port=0\nrate-limit-per-minute=600\nsnapshot-data-max-age-seconds=3600\n"
                + "snapshot-file=" + snapshotFile.toString().replace('\\', '/') + "\n");
        WebApiConfig config = WebApiConfigLoader.load(properties, "api", null, null, TOKEN);
        store = new SnapshotStore(config.snapshotFile(), config.snapshotMaxAgeSeconds(),
                config.snapshotIngest().dataMaxAgeSeconds(), Logger.getLogger("test"));
        bootstrap = new HttpServerBootstrap(config, store, null);
        bootstrap.start();
        port = bootstrap.boundPort();
    }

    private static Map<String, Object> snapshot(String bootId, long sequence, Instant generatedAt) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", 2);
        root.put("generatedAt", generatedAt.toString());
        root.put("server", Map.of("online", true, "playerCount", 1, "maxPlayers", 20));
        root.put("players", List.of());
        root.put("leaderboards", Map.of());
        root.put("profiles", List.of(Map.of("name", "Alice", "questsCompleted", 3)));
        if (bootId != null) {
            root.put("bootId", bootId);
            root.put("sequence", sequence);
        }
        root.put("export", Map.of("intervalSeconds", 900, "collectMs", 42, "mainThreadMs", 3, "profiles", 1));
        return root;
    }

    private static Map<String, Object> status(long sequence, Map<String, Object> extra) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", 1);
        root.put("kind", "status");
        root.put("generatedAt", Instant.now().toString());
        root.put("bootId", BOOT);
        root.put("sequence", sequence);
        Map<String, Object> server = new LinkedHashMap<>(Map.of("online", true, "playerCount", 2, "maxPlayers", 20));
        server.putAll(extra);
        root.put("server", server);
        return root;
    }

    private HttpResponse<String> post(Map<String, Object> body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/snapshot"))
                .header("Authorization", "Bearer " + TOKEN)
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private String health() throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health")).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).body();
    }

    @Test
    void numberedSnapshotsAreNeverWrittenTwiceNorLate() throws Exception {
        start();
        Instant now = Instant.now();
        String second = Json.write(snapshot(BOOT, 2, now));

        assertTrue(post(snapshot(BOOT, 2, now)).body().contains("STORED"));
        assertTrue(post(snapshot(BOOT, 2, now)).body().contains("DUPLICATE"), "renvoi après erreur réseau");
        assertTrue(post(snapshot(BOOT, 1, now.minusSeconds(900))).body().contains("OUT_OF_ORDER"));
        assertTrue(post(snapshot("boot-old-0000", 9, now.minusSeconds(60))).body().contains("OUT_OF_ORDER"),
                "démarrage précédent en retard");
        assertEquals(second, Files.readString(snapshotFile), "aucune réécriture");

        assertTrue(post(snapshot("boot-new-2222", 1, now.plusSeconds(1))).body().contains("STORED"), "redémarrage");
        assertTrue(post(snapshot(null, 0, now.plusSeconds(2))).body().contains("STORED"), "plugin ancien, non numéroté");
        assertEquals(400, post(Map.of("schemaVersion", 2, "generatedAt", now.toString(),
                "server", Map.of("online", true), "bootId", BOOT)).statusCode(), "numérotation incomplète");
    }

    @Test
    void dataStaysVisibleBetweenSnapshotsWhileTheServerIsOnlineThenExpires() throws Exception {
        start();
        Instant tenMinutesAgo = Instant.now().minus(Duration.ofMinutes(10));
        post(snapshot(BOOT, 1, tenMinutesAgo));
        post(status(5, Map.of()));

        Map<String, Object> state = ServerState.resolve(store);
        assertTrue(ServerState.isOnline(state));
        assertEquals(1, ((List<?>) state.get("profiles")).size(), "instantané de 10 min encore affiché");
        assertEquals(tenMinutesAgo.toString(), state.get(ServerState.SYNCED_AT));
        assertEquals(2L, ((Map<?, ?>) state.get("server")).get("playerCount"), "compteur du relevé, plus récent");
        String health = health();
        assertTrue(health.contains("\"sync\":{\"data\":\"FRESH\""), health);
        assertTrue(health.contains("\"sequence\":1"), health);
        assertTrue(health.contains("\"collectMs\":42"), health);

        Files.writeString(snapshotFile, Json.write(snapshot(BOOT, 1, Instant.now().minus(Duration.ofHours(2)))));
        Map<String, Object> expired = ServerState.resolve(store);
        assertTrue(ServerState.isOnline(expired), "le serveur reste en ligne");
        assertTrue(((List<?>) expired.get("profiles")).isEmpty(), "données de 2 h retirées, jamais montrées comme actuelles");
        assertNull(expired.get(ServerState.SYNCED_AT));
        assertTrue(health().contains("\"data\":\"STALE\""));
    }

    @Test
    void statusReportCarriesTheServerLoadWithinBounds() throws Exception {
        start();
        assertEquals(200, post(status(1, Map.of("tps", 19.97, "mspt", 12.5))).statusCode());
        String health = health();
        assertTrue(health.contains("\"load\":{\"tps\":19.97,\"mspt\":12.5}"), health);

        assertEquals(400, post(status(2, Map.of("tps", -1))).statusCode());
        assertEquals(400, post(status(3, Map.of("mspt", "lent"))).statusCode());
        assertFalse(health().contains("lent"));
    }
}
