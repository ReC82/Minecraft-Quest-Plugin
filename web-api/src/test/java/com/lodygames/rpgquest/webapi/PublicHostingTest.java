package com.lodygames.rpgquest.webapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.webapi.json.Json;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Configuration d'hébergement public (#52) : sonde {@code /health}, écoute locale, boutique absente,
 * aucun pseudo publié tant que {@code player-data-public=false}, limite de fréquence par visiteur
 * derrière nginx.
 */
class PublicHostingTest {

    private static final String TOKEN = "test-token-123";

    @TempDir
    Path tempDir;

    private final HttpClient client = HttpClient.newHttpClient();
    private HttpServerBootstrap bootstrap;
    private int port;

    @AfterEach
    void tearDown() {
        if (bootstrap != null) {
            bootstrap.stop();
        }
    }

    /** Les valeurs par défaut du chargeur, telles qu'un web-api.properties vide les donne. */
    private void startWithDefaults(int rateLimitPerMinute) throws Exception {
        Path properties = tempDir.resolve("web-api.properties");
        Files.writeString(properties, "port=0\nrate-limit-per-minute=" + rateLimitPerMinute
                + "\ntrust-proxy-headers=true\nsnapshot-file=" + tempDir.resolve("snapshot.json").toString().replace('\\', '/')
                + "\nstore-database-file=" + tempDir.resolve("store.db").toString().replace('\\', '/') + "\n");
        WebApiConfig config = WebApiConfigLoader.load(properties, TOKEN, null);
        SnapshotStore snapshotStore = new SnapshotStore(config.snapshotFile(), config.snapshotMaxAgeSeconds(),
                Logger.getLogger("rpgquest-webapi-test"));
        bootstrap = new HttpServerBootstrap(config, snapshotStore, null);
        bootstrap.start();
        port = bootstrap.boundPort();
    }

    private void writeSnapshotWithAlice() throws IOException {
        Files.writeString(tempDir.resolve("snapshot.json"), Json.write(Map.of(
                "generatedAt", Instant.now().toString(),
                "server", Map.of("online", true, "playerCount", 1, "maxPlayers", 20),
                "players", List.of(Map.of("name", "Alice")),
                "leaderboards", Map.of("GLOBAL", List.of(Map.of("name", "Alice", "xp", 500))),
                "statistics", Map.of("scopes", List.of("lifetime"),
                        "lifetime", Map.of("RPG_XP_GLOBAL", List.of(Map.of("name", "Alice", "value", 500)))),
                "profiles", List.of(Map.of("name", "Alice", "skills", Map.of("GLOBAL", 500), "questsCompleted", 2)),
                "catalog", List.of(),
                "announcements", List.of())), StandardCharsets.UTF_8);
    }

    private HttpResponse<String> get(String path, String realIp) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET();
        if (realIp != null) {
            builder.header("X-Real-IP", realIp);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    @Test
    void loaderDefaultsAreSafeForPublicHosting() throws Exception {
        WebApiConfig config = WebApiConfigLoader.load(tempDir.resolve("absent.properties"), null, null);

        assertEquals("127.0.0.1", config.bindAddress());
        assertFalse(config.storeEnabled());
        assertFalse(config.playerDataPublic());
        assertFalse(config.trustProxyHeaders());
    }

    @Test
    void invalidBooleanIsRejectedAtLoad() throws Exception {
        Path properties = tempDir.resolve("web-api.properties");
        Files.writeString(properties, "player-data-public=oui\n");

        assertThrows(IllegalStateException.class, () -> WebApiConfigLoader.load(properties, null, null));
    }

    @Test
    void healthReportsSnapshotStateWithoutPlayerData() throws Exception {
        startWithDefaults(60);

        HttpResponse<String> missing = get("/health", null);
        assertEquals(200, missing.statusCode());
        assertTrue(missing.body().contains("\"status\":\"UP\""), missing.body());
        assertTrue(missing.body().contains("\"snapshot\":\"MISSING\""), missing.body());
        assertTrue(missing.body().contains("\"playerDataPublic\":false"), missing.body());
        assertTrue(missing.body().contains("\"store\":false"), missing.body());

        writeSnapshotWithAlice();
        HttpResponse<String> fresh = get("/health", null);
        assertTrue(fresh.body().contains("\"snapshot\":\"FRESH\""), fresh.body());
        assertFalse(fresh.body().contains("Alice"), fresh.body());
    }

    @Test
    void noPseudonymIsPublishedWhilePlayerDataIsPrivate() throws Exception {
        startWithDefaults(60);
        writeSnapshotWithAlice();

        for (String path : List.of("/", "/status", "/joueurs", "/joueurs/Alice", "/leaderboards", "/wiki",
                "/confidentialite")) {
            HttpResponse<String> response = get(path, null);
            assertFalse(response.body().contains("Alice"), path + " publie un pseudo : " + response.body());
        }
        assertTrue(get("/joueurs", null).body().contains("pas encore publiée"));
    }

    @Test
    void storeRoutesAreAbsentWhenStoreIsDisabled() throws Exception {
        startWithDefaults(60);

        assertEquals(404, get("/store", null).statusCode());
        assertEquals(404, get("/store/checkout", null).statusCode());
        HttpResponse<String> api = client.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/api/store/orders"))
                .header("Authorization", "Bearer " + TOKEN).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(404, api.statusCode());
    }

    @Test
    void rateLimitIsPerVisitorBehindTheLocalProxy() throws Exception {
        startWithDefaults(2);

        assertEquals(200, get("/", "203.0.113.1").statusCode());
        assertEquals(200, get("/", "203.0.113.1").statusCode());
        assertEquals(429, get("/", "203.0.113.1").statusCode());
        assertEquals(200, get("/", "203.0.113.2").statusCode(), "un autre visiteur garde son propre quota");
        assertEquals(200, get("/", "pas-une-ip").statusCode(), "en-tête invalide : repli sur l'adresse du proxy");
    }
}
