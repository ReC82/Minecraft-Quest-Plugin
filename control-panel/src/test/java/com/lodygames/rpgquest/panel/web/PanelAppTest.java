package com.lodygames.rpgquest.panel.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.panel.audit.AuditEntry;
import com.lodygames.rpgquest.panel.audit.InMemoryAuditLog;
import com.lodygames.rpgquest.panel.bridge.BridgeClient;
import com.lodygames.rpgquest.panel.support.StubBridge;
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
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PanelAppTest {

    @TempDir
    Path tmp;

    private InMemoryAuditLog audit;
    private PanelApp app;
    private int port;
    private HttpClient client;
    /** Bocal à cookies explicite : la CookieManager du JDK gère mal les hôtes IP sans attribut Domain. */
    private final Map<String, String> jar = new LinkedHashMap<>();

    @AfterEach
    void tearDown() {
        if (app != null) {
            app.stop();
        }
    }

    private void startWith(String bridgeUrl) throws Exception {
        audit = new InMemoryAuditLog();
        app = new PanelApp(TestConfig.withBridgeUrl(tmp.resolve("cp.db").toString(), bridgeUrl),
                audit, new BridgeClient(Duration.ofMillis(400), Duration.ofMillis(600)));
        port = app.start();
        client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        jar.clear();
    }

    private HttpResponse<String> send(HttpRequest.Builder builder) throws Exception {
        if (!jar.isEmpty()) {
            String cookie = jar.entrySet().stream()
                    .map(e -> e.getKey() + "=" + e.getValue())
                    .reduce((a, b) -> a + "; " + b).orElse("");
            builder.header("Cookie", cookie);
        }
        HttpResponse<String> res = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        for (String setCookie : res.headers().allValues("Set-Cookie")) {
            String[] first = setCookie.split(";", 2);
            int eq = first[0].indexOf('=');
            String name = first[0].substring(0, eq).trim();
            String value = first[0].substring(eq + 1).trim();
            if (setCookie.toLowerCase().contains("max-age=0") || value.isEmpty()) {
                jar.remove(name);
            } else {
                jar.put(name, value);
            }
        }
        return res;
    }

    private HttpResponse<String> get(String path) throws Exception {
        return send(HttpRequest.newBuilder(uri(path)).GET());
    }

    private HttpResponse<String> post(String path, String form) throws Exception {
        return send(HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)));
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private static String csrf(String html) {
        Matcher m = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"").matcher(html);
        assertTrue(m.find(), "jeton _csrf absent de la page");
        return m.group(1);
    }

    private void loginOk() throws Exception {
        String token = csrf(get("/login").body());
        HttpResponse<String> res = post("/login",
                "username=" + TestConfig.OWNER_USERNAME
                        + "&password=" + URLEncoder.encode(TestConfig.OWNER_PASSWORD, StandardCharsets.UTF_8)
                        + "&_csrf=" + token);
        assertEquals(303, res.statusCode(), "connexion valide -> redirection");
        assertEquals("/dashboard", res.headers().firstValue("Location").orElse(""));
    }

    // ---- Tests -------------------------------------------------------------------------

    @Test
    void livenessRespondsWithoutAuth() throws Exception {
        startWith("http://127.0.0.1:1/admin/v1");
        HttpResponse<String> res = get("/health");
        assertEquals(200, res.statusCode());
        assertTrue(res.body().contains("\"panel\":\"ONLINE\""));
    }

    @Test
    void dashboardIsProtected() throws Exception {
        startWith("http://127.0.0.1:1/admin/v1");
        HttpResponse<String> res = get("/dashboard");
        assertEquals(303, res.statusCode());
        assertEquals("/login", res.headers().firstValue("Location").orElse(""));
    }

    @Test
    void invalidLoginIsRejectedAndAudited() throws Exception {
        startWith("http://127.0.0.1:1/admin/v1");
        String token = csrf(get("/login").body());
        HttpResponse<String> res = post("/login", "username=owner&password=wrong&_csrf=" + token);
        assertEquals(401, res.statusCode());
        assertTrue(res.body().contains("Identifiants invalides"));
        assertFalse(jar.containsKey("panel_session"), "aucun cookie de session sur échec");
        assertTrue(audit.recent(10).stream().anyMatch(e -> e.action().equals("login.failure")));
    }

    @Test
    void validLoginGivesAccessToDashboard() throws Exception {
        startWith("http://127.0.0.1:1/admin/v1");
        loginOk();
        assertTrue(jar.containsKey("panel_session"), "cookie de session posé");
        HttpResponse<String> dash = get("/dashboard");
        assertEquals(200, dash.statusCode());
        assertTrue(dash.body().contains("Dashboard"));
        assertTrue(dash.body().contains("RPGQuest DEV"), "libellé de la cible affiché");
        assertTrue(audit.recent(10).stream().anyMatch(e -> e.action().equals("login.success")));
    }

    @Test
    void logoutRequiresCsrfAndEndsTheSession() throws Exception {
        startWith("http://127.0.0.1:1/admin/v1");
        loginOk();
        assertEquals(403, post("/logout", "").statusCode(), "logout sans jeton CSRF");

        String token = csrf(get("/dashboard").body());
        HttpResponse<String> ok = post("/logout", "_csrf=" + token);
        assertEquals(303, ok.statusCode());
        assertEquals("/login", ok.headers().firstValue("Location").orElse(""));
        assertFalse(jar.containsKey("panel_session"), "cookie de session effacé");
        assertEquals(303, get("/dashboard").statusCode(), "session invalidée après logout");
        assertTrue(audit.recent(10).stream().anyMatch(e -> e.action().equals("logout")));
    }

    @Test
    void responsesNeverLeakSecrets() throws Exception {
        startWith("http://127.0.0.1:1/admin/v1");
        String loginBody = get("/login").body();
        loginOk();
        String dashBody = get("/dashboard").body();
        for (String body : new String[] {loginBody, dashBody}) {
            assertFalse(body.contains(TestConfig.SESSION_SECRET), "secret de session exposé");
            assertFalse(body.contains(TestConfig.BRIDGE_TOKEN), "jeton du bridge exposé");
            assertFalse(body.contains(TestConfig.OWNER_HASH), "hash owner exposé");
        }
    }

    @Test
    void bridgeUnavailableIsShownClearlyNotAsServerError() throws Exception {
        startWith("http://127.0.0.1:2/admin/v1"); // port fermé
        loginOk();
        HttpResponse<String> dash = get("/dashboard");
        assertEquals(200, dash.statusCode(), "le dashboard reste 200 même si le bridge est down");
        assertTrue(dash.body().contains("indisponible"), "message d'indisponibilité présent");
        assertTrue(dash.body().contains("OFFLINE"));
    }

    @Test
    void dashboardShowsRealBridgeDataWhenReachable() throws Exception {
        try (StubBridge stub = new StubBridge(TestConfig.BRIDGE_TOKEN)) {
            startWith(stub.baseUrl());
            loginOk();
            String body = get("/dashboard").body();
            assertTrue(body.contains("ONLINE"));
            assertTrue(body.contains("1.2.3-test"), "version plugin du bridge affichée");
            assertTrue(body.contains("world_hub"), "monde essentiel affiché");
            assertTrue(body.contains("2 / 20"), "joueurs en ligne affichés");
        }
    }

    @Test
    void bridgeRejectsWrongTokenAndPanelReportsIt() throws Exception {
        try (StubBridge stub = new StubBridge("the-real-token")) {
            startWith(stub.baseUrl()); // TestConfig utilise BRIDGE_TOKEN, différent -> refus
            loginOk();
            String body = get("/dashboard").body();
            assertTrue(body.contains("indisponible"));
            assertTrue(body.toLowerCase().contains("refus"), "le refus du jeton est mentionné");
        }
    }

    @Test
    void killSwitchServes503ForEverythingButLiveness() throws Exception {
        audit = new InMemoryAuditLog();
        app = new PanelApp(TestConfig.disabled(tmp.resolve("cp.db").toString()), audit, new BridgeClient());
        port = app.start();
        client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        jar.clear();
        assertEquals(200, get("/health").statusCode());
        assertEquals(503, get("/login").statusCode());
        assertEquals(503, get("/dashboard").statusCode());
    }

    @Test
    void auditEntriesCarryActorActionResultAndTimestamp() throws Exception {
        startWith("http://127.0.0.1:1/admin/v1");
        loginOk();
        AuditEntry entry = audit.recent(10).stream()
                .filter(e -> e.action().equals("login.success")).findFirst().orElseThrow();
        assertEquals("owner", entry.actor());
        assertEquals("OK", entry.result());
        assertNotNull(entry.ts());
        assertTrue(entry.target().contains("env=dev"));
    }
}
