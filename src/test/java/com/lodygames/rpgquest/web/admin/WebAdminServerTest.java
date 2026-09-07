package com.lodygames.rpgquest.web.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Bridge d'administration (issue #37) : le contrat d'authentification et la forme du
 * {@code /admin/v1/health}, sans Bukkit — {@link HealthSource} est une fausse source, l'écoute se
 * fait sur un port éphémère via une source d'environnement injectée.
 */
class WebAdminServerTest {

    private WebAdminServer server;
    private final HttpClient client = HttpClient.newHttpClient();

    private static final HealthSource FAKE = new HealthSource() {
        @Override public String pluginName() {
            return "RPGQuest";
        }

        @Override public String pluginVersion() {
            return "9.9.9-test";
        }

        @Override public int playersOnline() {
            return 3;
        }

        @Override public int maxPlayers() {
            return 42;
        }

        @Override public long uptimeSeconds() {
            return 123;
        }

        @Override public String targetEnv() {
            return "DEV";
        }

        @Override public List<WorldInfo> essentialWorlds() {
            return List.of(new WorldInfo("hub", "world_hub", true),
                    new WorldInfo("claims", "claims", true),
                    new WorldInfo("wild", "wild", false));
        }
    };

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    private void startWith(Map<String, String> env) {
        server = new WebAdminServer(FAKE, LoggerFactory.getLogger("test"), env::get);
        server.start();
    }

    private static Map<String, String> baseEnv() {
        return new java.util.HashMap<>(Map.of(
                "RPGQUEST_WEB_ADMIN_ENABLED", "true",
                "RPGQUEST_WEB_ADMIN_TOKEN", "s3cr3t",
                "RPGQUEST_WEB_ADMIN_BIND", "127.0.0.1",
                "RPGQUEST_WEB_ADMIN_PORT", "0"));
    }

    private HttpResponse<String> health(String bearer) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + server.boundPort() + "/admin/v1/health")).GET();
        if (bearer != null) {
            b.header("Authorization", bearer);
        }
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void doesNotStartUnlessEnabledAndTokenPresent() {
        server = new WebAdminServer(FAKE, LoggerFactory.getLogger("test"), key -> null);
        server.start();
        assertEquals(-1, server.boundPort(), "désactivé par défaut");

        Map<String, String> noToken = baseEnv();
        noToken.remove("RPGQUEST_WEB_ADMIN_TOKEN");
        server.stop();
        server = new WebAdminServer(FAKE, LoggerFactory.getLogger("test"), noToken::get);
        server.start();
        assertEquals(-1, server.boundPort(), "fail-closed sans jeton");
    }

    @Test
    void rejectsMissingAndWrongToken() throws Exception {
        startWith(baseEnv());
        assertEquals(401, health(null).statusCode());
        assertEquals(401, health("Bearer nope").statusCode());
        assertEquals(401, health("s3cr3t").statusCode(), "sans le préfixe Bearer");
    }

    @Test
    void returnsRealHealthWithTheRightToken() throws Exception {
        startWith(baseEnv());
        HttpResponse<String> res = health("Bearer s3cr3t");
        assertEquals(200, res.statusCode());
        String body = res.body();
        assertTrue(body.contains("\"status\":\"ONLINE\""));
        assertTrue(body.contains("\"version\":\"9.9.9-test\""));
        assertTrue(body.contains("\"bridge_api_version\":\"v1\""));
        assertTrue(body.contains("\"players_online\":3"));
        assertTrue(body.contains("\"max_players\":42"));
        assertTrue(body.contains("world_hub"));
        assertTrue(body.contains("\"env\":\"DEV\""));
        assertTrue(body.contains("generated_at"));
    }

    @Test
    void nonGetIsRejected() throws Exception {
        startWith(baseEnv());
        HttpResponse<String> res = client.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + server.boundPort() + "/admin/v1/health"))
                .header("Authorization", "Bearer s3cr3t")
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(405, res.statusCode());
    }
}
