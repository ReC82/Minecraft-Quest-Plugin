package com.lodygames.rpgquest.panel.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.panel.agent.AgentStore;
import com.lodygames.rpgquest.panel.audit.AuditEntry;
import com.lodygames.rpgquest.panel.audit.InMemoryAuditLog;
import com.lodygames.rpgquest.panel.bridge.BridgeClient;
import com.lodygames.rpgquest.panel.json.Json;
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
 * Pages métier (Joueurs / Quêtes / Stories) + création générique d'action whitelistée
 * ({@code POST /agents/action}) : protection par session, whitelist stricte, confirmation
 * obligatoire pour les mutations, audit.
 */
class BusinessPagesTest {

    @TempDir
    Path tmp;

    private InMemoryAuditLog audit;
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

    private void start() throws Exception {
        audit = new InMemoryAuditLog();
        String db = tmp.resolve("cp.db").toString();
        app = new PanelApp(TestConfig.withAgent(db, "http://127.0.0.1:1/admin/v1"),
                audit, new BridgeClient(Duration.ofMillis(300), Duration.ofMillis(400)), new AgentStore(db));
        port = app.start();
        client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        jar.clear();
    }

    private HttpResponse<String> send(HttpRequest.Builder builder) throws Exception {
        if (!jar.isEmpty()) {
            String cookie = jar.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue())
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
        assertTrue(m.find(), "jeton _csrf absent");
        return m.group(1);
    }

    private void loginOk() throws Exception {
        String token = csrf(get("/login").body());
        assertEquals(303, post("/login", "username=" + TestConfig.OWNER_USERNAME
                + "&password=" + URLEncoder.encode(TestConfig.OWNER_PASSWORD, StandardCharsets.UTF_8)
                + "&_csrf=" + token).statusCode());
    }

    private int pendingFor(String agent) throws Exception {
        Map<String, Object> body = Json.parseObject(get("/agents/actions.json?agent=" + agent).body());
        return ((Number) body.get("pending")).intValue();
    }

    // ---- Tests -----------------------------------------------------------------------

    @Test
    void businessPagesAreSessionProtected() throws Exception {
        start();
        for (String path : List.of("/players", "/quests", "/stories", "/npcs", "/dialogues")) {
            HttpResponse<String> res = get(path);
            assertEquals(303, res.statusCode(), path + " doit exiger une session");
            assertEquals("/login", res.headers().firstValue("Location").orElse(""));
        }
    }

    @Test
    void businessPagesRenderWithWhitelistedForms() throws Exception {
        start();
        loginOk();
        String players = get("/players").body();
        assertTrue(players.contains("<h1>Joueurs</h1>"));
        assertTrue(players.contains("name=\"type\" value=\"player.list\""));
        assertTrue(players.contains("/assets/panel.js"));

        assertTrue(get("/quests").body().contains("name=\"type\" value=\"quest.list\""));
        assertTrue(get("/stories").body().contains("name=\"type\" value=\"story.list\""));
        assertTrue(get("/npcs").body().contains("name=\"type\" value=\"npc.list\""));
        assertTrue(get("/dialogues").body().contains("name=\"type\" value=\"dialogue.list\""));
    }

    @Test
    void readActionIsCreatedAndPolled() throws Exception {
        start();
        loginOk();
        String token = csrf(get("/players").body());
        HttpResponse<String> res = post("/agents/action",
                "_csrf=" + token + "&type=player.list&agent=" + TestConfig.AGENT_ID + "&return=/players");
        assertEquals(303, res.statusCode());
        assertTrue(res.headers().firstValue("Location").orElse("").startsWith("/players?agent="));
        assertTrue(pendingFor(TestConfig.AGENT_ID) >= 1, "une action player.list en attente");
        assertTrue(audit.recent(10).stream().anyMatch(e ->
                e.action().equals("agent.action.create") && e.result().equals("PENDING")));
    }

    @Test
    void mutationWithoutConfirmIsRejectedAndNotCreated() throws Exception {
        start();
        loginOk();
        String token = csrf(get("/quests").body());
        HttpResponse<String> res = post("/agents/action", "_csrf=" + token
                + "&type=quest.complete&agent=" + TestConfig.AGENT_ID
                + "&return=/quests&player=LoDyMcFly&quest_id=rpgquest:crystal_hunt");
        assertEquals(303, res.statusCode());
        assertTrue(res.headers().firstValue("Location").orElse("").contains("err="), "redirection avec erreur");
        assertEquals(0, pendingFor(TestConfig.AGENT_ID), "aucune action créée sans confirmation");
        assertTrue(audit.recent(10).stream().anyMatch(e ->
                e.action().equals("agent.action.create") && e.result().equals("DENIED")));
    }

    @Test
    void mutationWithConfirmIsCreated() throws Exception {
        start();
        loginOk();
        String token = csrf(get("/quests").body());
        HttpResponse<String> res = post("/agents/action", "_csrf=" + token
                + "&type=quest.start&agent=" + TestConfig.AGENT_ID
                + "&return=/quests&player=LoDyMcFly&quest_id=rpgquest:crystal_hunt&confirm=true&force=true");
        assertEquals(303, res.statusCode());
        assertEquals(1, pendingFor(TestConfig.AGENT_ID));
        AuditEntry created = audit.recent(10).stream()
                .filter(e -> e.action().equals("agent.action.create") && e.result().equals("PENDING"))
                .findFirst().orElseThrow();
        assertTrue(created.target().contains("type=quest.start"));
    }

    @Test
    void unknownActionTypeIsRefusedAndAudited() throws Exception {
        start();
        loginOk();
        String token = csrf(get("/players").body());
        HttpResponse<String> res = post("/agents/action",
                "_csrf=" + token + "&type=console.run&agent=" + TestConfig.AGENT_ID + "&return=/players&command=op+me");
        assertEquals(303, res.statusCode());
        assertTrue(res.headers().firstValue("Location").orElse("").contains("err="));
        assertEquals(0, pendingFor(TestConfig.AGENT_ID));
        assertTrue(audit.recent(10).stream().anyMatch(e ->
                e.action().equals("agent.action.create") && e.result().equals("DENIED")));
    }

    @Test
    void actionEndpointRequiresCsrf() throws Exception {
        start();
        loginOk();
        HttpResponse<String> res = post("/agents/action",
                "type=player.list&agent=" + TestConfig.AGENT_ID + "&return=/players");
        assertEquals(403, res.statusCode());
    }

    @Test
    void actionEndpointRequiresSession() throws Exception {
        start();
        HttpResponse<String> res = post("/agents/action", "type=player.list&agent=" + TestConfig.AGENT_ID);
        assertEquals(303, res.statusCode());
        assertEquals("/login", res.headers().firstValue("Location").orElse(""));
    }

    @Test
    void returnPathIsWhitelisted() throws Exception {
        start();
        loginOk();
        String token = csrf(get("/players").body());
        HttpResponse<String> res = post("/agents/action", "_csrf=" + token
                + "&type=player.list&agent=" + TestConfig.AGENT_ID + "&return=https://evil.example/x");
        assertEquals(303, res.statusCode());
        String location = res.headers().firstValue("Location").orElse("");
        assertFalse(location.contains("evil.example"));
        assertTrue(location.startsWith("/agents?agent="));
    }
}
