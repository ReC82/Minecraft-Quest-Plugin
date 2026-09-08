package com.lodygames.rpgquest.panel.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.panel.agent.AgentStore;
import com.lodygames.rpgquest.panel.audit.InMemoryAuditLog;
import com.lodygames.rpgquest.panel.bridge.BridgeClient;
import com.lodygames.rpgquest.panel.support.TestConfig;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Fumée <em>bout-en-bout</em> du parcours authentifié du Control Panel (recommandation de l'audit
 * #74) : login owner → session posée → les pages protégées sont réellement <strong>rendues</strong>
 * (pas seulement les handlers isolés). Test HTTP + session, sans navigateur.
 *
 * <p>Le bridge pointe sur un port fermé : ces pages ne doivent pas dépendre d'un plugin joignable
 * pour répondre 200 (le contenu « live » vient ensuite du canal agent).</p>
 */
class AuthenticatedSmokeTest {

    private static final List<String> PROTECTED_PAGES =
            List.of("/dashboard", "/players", "/quests", "/stories", "/npcs");

    @TempDir
    Path tmp;

    private PanelApp app;
    private int port;
    private HttpClient client;
    private final Map<String, String> jar = new LinkedHashMap<>();

    @AfterEach
    void tearDown() {
        if (app != null) {
            app.stop();
        }
    }

    @Test
    void unauthenticatedProtectedPagesRedirectToLogin() throws Exception {
        start();
        for (String path : PROTECTED_PAGES) {
            HttpResponse<String> res = get(path);
            assertEquals(303, res.statusCode(), path + " doit exiger une session");
            assertEquals("/login", res.headers().firstValue("Location").orElse(""), path);
        }
    }

    @Test
    void loginThenAllProtectedPagesRender() throws Exception {
        start();

        String token = csrf(get("/login").body());
        HttpResponse<String> login = post("/login", "username=" + TestConfig.OWNER_USERNAME
                + "&password=" + URLEncoder.encode(TestConfig.OWNER_PASSWORD, StandardCharsets.UTF_8)
                + "&_csrf=" + token);
        assertEquals(303, login.statusCode(), "login valide -> redirection");
        assertEquals("/dashboard", login.headers().firstValue("Location").orElse(""));
        assertTrue(jar.containsKey("panel_session"), "cookie de session posé");

        for (String path : PROTECTED_PAGES) {
            HttpResponse<String> res = get(path);
            assertEquals(200, res.statusCode(), path + " doit être rendu une fois connecté");
            assertTrue(res.body().contains("<!doctype html>") || res.body().contains("<!DOCTYPE html>")
                    || res.body().contains("<html"), path + " : page HTML complète");
        }

        assertTrue(get("/dashboard").body().contains("Dashboard"));
        assertTrue(get("/players").body().contains("<h1>Joueurs</h1>"));
        assertTrue(get("/quests").body().contains("<h1>Quêtes</h1>"));
        assertTrue(get("/stories").body().contains("<h1>Stories</h1>"));
        assertTrue(get("/npcs").body().contains("<h1>PNJ</h1>"));
    }

    // ---- infra ------------------------------------------------------------------------

    private void start() throws Exception {
        String db = tmp.resolve("cp.db").toString();
        app = new PanelApp(TestConfig.withAgent(db, "http://127.0.0.1:2/admin/v1"),
                new InMemoryAuditLog(), new BridgeClient(Duration.ofMillis(300), Duration.ofMillis(400)),
                new AgentStore(db));
        port = app.start();
        client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        jar.clear();
    }

    private static String csrf(String html) {
        Matcher m = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"").matcher(html);
        assertTrue(m.find(), "jeton _csrf absent");
        return m.group(1);
    }

    private HttpResponse<String> get(String p) throws Exception {
        return send(HttpRequest.newBuilder(uri(p)).GET());
    }

    private HttpResponse<String> post(String p, String form) throws Exception {
        return send(HttpRequest.newBuilder(uri(p))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)));
    }

    private URI uri(String p) {
        return URI.create("http://127.0.0.1:" + port + p);
    }

    private HttpResponse<String> send(HttpRequest.Builder b) throws Exception {
        if (!jar.isEmpty()) {
            b.header("Cookie", jar.entrySet().stream()
                    .map(e -> e.getKey() + "=" + e.getValue()).reduce((x, y) -> x + "; " + y).orElse(""));
        }
        HttpResponse<String> res = client.send(b.build(), HttpResponse.BodyHandlers.ofString());
        for (String sc : res.headers().allValues("Set-Cookie")) {
            String[] f = sc.split(";", 2);
            int eq = f[0].indexOf('=');
            String n = f[0].substring(0, eq).trim();
            String v = f[0].substring(eq + 1).trim();
            if (sc.toLowerCase().contains("max-age=0") || v.isEmpty()) {
                jar.remove(n);
            } else {
                jar.put(n, v);
            }
        }
        return res;
    }
}
