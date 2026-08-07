package be.lloyd.rpgquest.webapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import be.lloyd.rpgquest.webapi.json.Json;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Bout-en-bout via un vrai {@link com.sun.net.httpserver.HttpServer} lié sur
 * un port éphémère (0) et un vrai {@link HttpClient} — couvre les scénarios
 * de la mission étape 21 : API indisponible/serveur arrêté (mode dégradé),
 * jeton invalide, requête malformée, rate limit, données vides, caractères
 * Unicode.
 */
class HttpServerBootstrapTest {

    private static final String TOKEN = "test-token-123";

    @TempDir
    Path tempDir;

    private Path snapshotFile;
    private HttpClient client;
    private HttpServerBootstrap bootstrap;
    private int port;

    @BeforeEach
    void setUp() {
        snapshotFile = tempDir.resolve("snapshot.json");
        client = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() {
        if (bootstrap != null) {
            bootstrap.stop();
        }
    }

    private void startServer(long maxAgeSeconds, int rateLimitPerMinute) throws IOException {
        WebApiConfig config = new WebApiConfig(0, snapshotFile, maxAgeSeconds, TOKEN, rateLimitPerMinute, "RPGQuest Test");
        SnapshotStore store = new SnapshotStore(snapshotFile, maxAgeSeconds, Logger.getLogger("rpgquest-webapi-test"));
        bootstrap = new HttpServerBootstrap(config, store);
        bootstrap.start();
        port = bootstrap.boundPort();
    }

    private void writeSnapshot(String json) throws IOException {
        Files.writeString(snapshotFile, json, StandardCharsets.UTF_8);
    }

    private void writeFreshEmptySnapshot() throws IOException {
        writeSnapshot(Json.write(Map.of(
                "generatedAt", Instant.now().toString(),
                "server", Map.of("online", true, "playerCount", 0, "maxPlayers", 20),
                "players", List.of(),
                "leaderboards", Map.of(),
                "catalog", List.of(),
                "announcements", List.of())));
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET();
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    @Test
    void apiWithoutSnapshotReturnsDegradedStatusInsteadOfAnError() throws Exception {
        startServer(120, 60);

        HttpResponse<String> response = get("/api/status", TOKEN);

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"online\":false"));
        assertTrue(response.body().contains("no_data"));
    }

    @Test
    void staleSnapshotIsReportedAsDegraded() throws Exception {
        startServer(5, 60);
        writeSnapshot(Json.write(Map.of(
                "generatedAt", Instant.now().minusSeconds(3600).toString(),
                "server", Map.of("online", true, "playerCount", 3, "maxPlayers", 20),
                "players", List.of(),
                "leaderboards", Map.of(),
                "catalog", List.of(),
                "announcements", List.of())));

        HttpResponse<String> response = get("/api/status", TOKEN);

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"online\":false"));
        assertTrue(response.body().contains("stale"));
    }

    @Test
    void missingTokenIsRejected() throws Exception {
        startServer(120, 60);

        assertEquals(401, get("/api/status", null).statusCode());
    }

    @Test
    void wrongTokenIsRejected() throws Exception {
        startServer(120, 60);

        assertEquals(401, get("/api/status", "wrong-token").statusCode());
    }

    @Test
    void malformedQueryParamIsRejected() throws Exception {
        startServer(120, 60);

        HttpResponse<String> response = get("/api/leaderboards?limit=abc", TOKEN);

        assertEquals(400, response.statusCode());
        assertTrue(response.body().contains("bad_request"));
    }

    @Test
    void unknownApiRouteReturns404() throws Exception {
        startServer(120, 60);

        assertEquals(404, get("/api/nope", TOKEN).statusCode());
    }

    @Test
    void unknownSitePageReturns404() throws Exception {
        startServer(120, 60);

        assertEquals(404, get("/nope", null).statusCode());
    }

    @Test
    void rateLimitReturns429AfterThreshold() throws Exception {
        startServer(120, 2);
        writeFreshEmptySnapshot();

        get("/api/status", TOKEN);
        get("/api/status", TOKEN);
        HttpResponse<String> third = get("/api/status", TOKEN);

        assertEquals(429, third.statusCode());
    }

    @Test
    void emptyDataIsReturnedAsEmptyArraysNotAnError() throws Exception {
        startServer(120, 60);
        writeFreshEmptySnapshot();

        HttpResponse<String> response = get("/api/catalog", TOKEN);

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"items\":[]"));
    }

    @Test
    void unicodeAnnouncementRoundTripsThroughTheApi() throws Exception {
        startServer(120, 60);
        writeSnapshot(Json.write(Map.of(
                "generatedAt", Instant.now().toString(),
                "server", Map.of("online", true, "playerCount", 0, "maxPlayers", 20),
                "players", List.of(),
                "leaderboards", Map.of(),
                "catalog", List.of(),
                "announcements", List.of(Map.of("title", "Café ☕ 日本語 🎮", "body", "corps")))));

        HttpResponse<String> response = get("/api/announcements", TOKEN);

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("Café ☕ 日本語 🎮"));
    }

    @Test
    void publicSitePageDoesNotRequireAuth() throws Exception {
        startServer(120, 60);

        HttpResponse<String> response = get("/", null);

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("<html"));
    }

    @Test
    void degradedSitePageShowsOfflineBannerInsteadOfErroring() throws Exception {
        startServer(120, 60);

        HttpResponse<String> response = get("/status", null);

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("hors-ligne"));
    }
}
