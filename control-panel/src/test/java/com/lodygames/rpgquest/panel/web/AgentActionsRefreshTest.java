package com.lodygames.rpgquest.panel.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.panel.agent.AgentStore;
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
 * Rafraîchissement des actions agent (issue #65, adapté #93) : l'endpoint {@code /agents/actions.json}
 * reflète le cycle de vie sans rechargement, il est enrichi pour les toasts et le centre de
 * notifications, et {@code panel.js} pilote toasts + cloche (plus de gros tableau « Actions
 * récentes »).
 */
class AgentActionsRefreshTest {

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

    private void start() throws Exception {
        String db = tmp.resolve("cp.db").toString();
        app = new PanelApp(TestConfig.withAgent(db, "http://127.0.0.1:1/admin/v1"),
                new InMemoryAuditLog(), new BridgeClient(Duration.ofMillis(300), Duration.ofMillis(400)),
                new AgentStore(db));
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
        assertTrue(m.find(), "jeton _csrf absent");
        return m.group(1);
    }

    private void loginOk() throws Exception {
        String token = csrf(get("/login").body());
        HttpResponse<String> res = post("/login",
                "username=" + TestConfig.OWNER_USERNAME
                        + "&password=" + URLEncoder.encode(TestConfig.OWNER_PASSWORD, StandardCharsets.UTF_8)
                        + "&_csrf=" + token);
        assertEquals(303, res.statusCode());
    }

    @Test
    void jsonEndpointReflectsActionLifecycleAndFeedsToastsAndNotifications() throws Exception {
        start();
        loginOk();

        // Création d'une action via le formulaire de la page Agents.
        String token = csrf(get("/agents").body());
        HttpResponse<String> created = post("/agents",
                "_csrf=" + token + "&agent=" + TestConfig.AGENT_ID + "&player=LoDyMcFly&key=CLAIM_TIER_1");
        assertEquals(303, created.statusCode());
        // Redirection avec un toast (plus de &ok=1).
        assertTrue(created.headers().firstValue("Location").orElse("").contains("&toast="),
                "la mutation redirige avec un toast");

        // La page Agents n'a plus de gros tableau « Actions récentes » : synthèse compacte + lien.
        String agentsPage = get("/agents").body();
        assertFalse(agentsPage.contains("data-actions-agent"), "plus de conteneur de tableau pollable");
        assertFalse(agentsPage.contains("Actions récentes"), "plus de bloc « Actions récentes »");
        assertTrue(agentsPage.contains("Activité récente"));
        assertTrue(agentsPage.contains("href=\"/actions\""), "lien vers l'historique complet");

        // Endpoint JSON : action présente, non terminale, enrichie pour l'UX #93.
        HttpResponse<String> json1 = get("/agents/actions.json?agent=" + TestConfig.AGENT_ID);
        assertEquals(200, json1.statusCode());
        assertEquals("application/json; charset=utf-8", json1.headers().firstValue("Content-Type").orElse(""));
        Map<String, Object> body1 = Json.parseObject(json1.body());
        assertTrue(((Number) body1.get("pending")).intValue() >= 1);
        assertTrue(((Number) body1.get("badge")).intValue() >= 1, "le badge signale l'action en cours");
        @SuppressWarnings("unchecked")
        List<Object> actions1 = (List<Object>) body1.get("actions");
        assertEquals(1, actions1.size());
        @SuppressWarnings("unchecked")
        Map<String, Object> row1 = (Map<String, Object>) actions1.get(0);
        assertEquals("player.variable.get", row1.get("type"));
        assertEquals(Boolean.FALSE, row1.get("terminal"));
        assertEquals("Lire une variable joueur", row1.get("label"));
        assertEquals("players", row1.get("domain"));
        assertEquals("pending", row1.get("group"));
        assertEquals("LoDyMcFly", row1.get("target"));
        assertTrue(String.valueOf(row1.get("notifHtml")).contains("notif-item"));
        assertTrue(String.valueOf(row1.get("idFull")).length() == 36);

        // L'agent relève l'action puis renvoie un résultat SUCCESS.
        HttpResponse<String> poll = client.send(HttpRequest.newBuilder(uri("/agent/v1/actions"))
                .header("Authorization", "Bearer " + TestConfig.AGENT_TOKEN)
                .header("X-Agent-Id", TestConfig.AGENT_ID).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, poll.statusCode());
        Map<String, Object> pollBody = Json.parseObject(poll.body());
        @SuppressWarnings("unchecked")
        List<Object> pending = (List<Object>) pollBody.get("actions");
        @SuppressWarnings("unchecked")
        Map<String, Object> pendingRow = (Map<String, Object>) pending.get(0);
        String actionId = (String) pendingRow.get("id");

        String result = "{\"action_id\":\"" + actionId + "\",\"status\":\"SUCCESS\",\"value\":\"true\","
                + "\"message\":\"LoDyMcFly : CLAIM_TIER_1 = true\"}";
        HttpResponse<String> resultRes = client.send(HttpRequest.newBuilder(
                        uri("/agent/v1/actions/" + actionId + "/result"))
                .header("Authorization", "Bearer " + TestConfig.AGENT_TOKEN)
                .header("X-Agent-Id", TestConfig.AGENT_ID)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(result)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resultRes.statusCode());

        // Endpoint JSON de nouveau : action terminale, group=success, pending == 0.
        Map<String, Object> body2 = Json.parseObject(get("/agents/actions.json?agent=" + TestConfig.AGENT_ID).body());
        assertEquals(0, ((Number) body2.get("pending")).intValue());
        @SuppressWarnings("unchecked")
        Map<String, Object> row2 = (Map<String, Object>) ((List<Object>) body2.get("actions")).get(0);
        assertEquals("SUCCESS", row2.get("status"));
        assertEquals(Boolean.TRUE, row2.get("terminal"));
        assertEquals("success", row2.get("group"));
        assertTrue(String.valueOf(row2.get("resultShort")).length() > 0);
        assertFalse(json1.body().contains(TestConfig.AGENT_TOKEN));
    }

    @Test
    void panelScriptDrivesToastsAndNotifications() throws Exception {
        start();
        String js = get("/assets/panel.js").body();
        assertTrue(js.contains("initToasts") && js.contains("initNotifications"));
        assertTrue(js.contains("/agents/actions.json"));
        assertTrue(js.contains("data-notif-badge") && js.contains("data-notif-list"));
        assertTrue(js.contains("data-toast-action") && js.contains(".pa-toast"));
        assertTrue(js.contains("navigator.clipboard") && js.contains("execCommand"), "copie + repli conservés");
        assertFalse(js.contains("tableHasPending"), "l'ancien tableau pollable a disparu");
    }

    @Test
    void jsonEndpointRequiresSessionAndKnownAgent() throws Exception {
        start();
        assertEquals(401, get("/agents/actions.json?agent=" + TestConfig.AGENT_ID).statusCode());
        loginOk();
        assertEquals(404, get("/agents/actions.json?agent=nope").statusCode());
    }

    @Test
    void panelScriptIsServedSameOriginAsJavaScript() throws Exception {
        start();
        HttpResponse<String> js = get("/assets/panel.js");
        assertEquals(200, js.statusCode());
        assertTrue(js.headers().firstValue("Content-Type").orElse("").startsWith("application/javascript"));
        assertTrue(js.body().contains("/agents/actions.json"));
        assertFalse(js.body().contains(TestConfig.SESSION_SECRET));
    }
}
