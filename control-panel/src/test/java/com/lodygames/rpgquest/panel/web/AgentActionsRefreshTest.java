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
 * Rafraîchissement automatique des actions agent (issue #65) : l'endpoint JSON reflète le statut
 * courant sans rechargement de page, et le script est servi en même origine (conforme CSP).
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
    void jsonEndpointReflectsActionLifecycleWithoutPageReload() throws Exception {
        start();
        loginOk();

        // Création d'une action via le formulaire de la page Agents.
        String token = csrf(get("/agents").body());
        HttpResponse<String> created = post("/agents",
                "_csrf=" + token + "&agent=" + TestConfig.AGENT_ID + "&player=LoDyMcFly&key=CLAIM_TIER_1");
        assertEquals(303, created.statusCode());

        // La page rend le conteneur pollable avec au moins une action PENDING.
        String agentsPage = get("/agents").body();
        assertTrue(agentsPage.contains("data-actions-agent=\"" + TestConfig.AGENT_ID + "\""));
        assertTrue(agentsPage.contains("<script src=\"/assets/panel.js\""));
        // Critère #65 : l'action neuve apparaît immédiatement en PENDING, et le compteur
        // exposé au script est strictement positif (le polling doit démarrer).
        assertTrue(pendingAttr(agentsPage) >= 1, "data-actions-pending doit être >= 1 après création");
        assertTrue(agentsPage.contains("pill--pending") && agentsPage.contains("PENDING</span>"),
                "la nouvelle action doit être rendue avec la pastille PENDING");

        // Endpoint JSON : action présente, non terminale, pending >= 1.
        HttpResponse<String> json1 = get("/agents/actions.json?agent=" + TestConfig.AGENT_ID);
        assertEquals(200, json1.statusCode());
        assertEquals("application/json; charset=utf-8", json1.headers().firstValue("Content-Type").orElse(""));
        Map<String, Object> body1 = Json.parseObject(json1.body());
        assertTrue(((Number) body1.get("pending")).intValue() >= 1, "au moins une action en cours");
        @SuppressWarnings("unchecked")
        List<Object> actions1 = (List<Object>) body1.get("actions");
        assertEquals(1, actions1.size());
        @SuppressWarnings("unchecked")
        Map<String, Object> row1 = (Map<String, Object>) actions1.get(0);
        assertEquals("player.variable.get", row1.get("type"));
        assertEquals(Boolean.FALSE, row1.get("terminal"));
        // Non-régression du rendu enrichi consommé par panel.js (lot UX) : le JSON porte le
        // libellé humain du type, la pastille normalisée et l'id complet pour la copie.
        assertTrue(String.valueOf(row1.get("typeHtml")).contains("Lire une variable joueur"), "libellé de type");
        assertTrue(String.valueOf(row1.get("typeHtml")).contains("data-copy=\"player.variable.get\""));
        assertTrue(String.valueOf(row1.get("statusHtml")).contains("pill--pending"));
        assertTrue(String.valueOf(row1.get("idFull")).length() == 36, "id complet fourni pour la copie");

        // L'agent relève l'action (id complet) puis renvoie un résultat SUCCESS.
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

        // Endpoint JSON de nouveau : action terminale, pending == 0 (le polling s'arrêtera).
        Map<String, Object> body2 = Json.parseObject(get("/agents/actions.json?agent=" + TestConfig.AGENT_ID).body());
        assertEquals(0, ((Number) body2.get("pending")).intValue());
        @SuppressWarnings("unchecked")
        Map<String, Object> row2 = (Map<String, Object>) ((List<Object>) body2.get("actions")).get(0);
        assertEquals("SUCCESS", row2.get("status"));
        assertEquals(Boolean.TRUE, row2.get("terminal"));
        assertTrue(String.valueOf(row2.get("result")).contains("true"));
        assertFalse(json1.body().contains(TestConfig.AGENT_TOKEN));

        // La page rendue reflète maintenant l'état terminal : compteur à 0 (pas de polling
        // permanent) et pastille SUCCESS.
        String settledPage = get("/agents").body();
        assertEquals(0, pendingAttr(settledPage), "data-actions-pending doit retomber à 0 une fois l'action terminée");
        assertTrue(settledPage.contains("pill--success") && settledPage.contains("SUCCESS</span>"));
    }

    /** Premier chiffre de {@code data-actions-pending="N"} dans la page (0 si absent). */
    private static int pendingAttr(String html) {
        Matcher m = Pattern.compile("data-actions-pending=\"(\\d+)\"").matcher(html);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    @Test
    void panelScriptStartsFromServerCounterOrVisibleRowsAndDoesAnImmediatePoll() throws Exception {
        start();
        String js = get("/assets/panel.js").body();
        // Double signal de départ : compteur serveur ET lecture du tableau rendu (un compteur
        // absent ou périmé ne doit pas laisser une action bloquée en PENDING).
        assertTrue(js.contains("data-actions-pending"));
        assertTrue(js.contains("tableHasPending"));
        assertTrue(js.contains("PENDING") && js.contains("DELIVERED"), "statuts non terminaux reconnus");
        // Premier relevé immédiat (pas d'attente de l'intervalle avant la première mise à jour).
        assertTrue(js.contains("tick(); // premier relevé immédiat"));
        // Arrêt garanti dès qu'il n'y a plus d'action en cours.
        assertTrue(js.contains("data.pending > 0") && js.contains("stop(null)"));
        // Lot UX : rendu enrichi (typeHtml/statusHtml) + copie d'identifiant, sans casser le polling.
        assertTrue(js.contains("a.typeHtml") && js.contains("a.statusHtml"), "cellules serveur réutilisées");
        assertTrue(js.contains("navigator.clipboard") && js.contains("execCommand"), "copie + repli");
        assertTrue(js.contains("data-copy"));
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
