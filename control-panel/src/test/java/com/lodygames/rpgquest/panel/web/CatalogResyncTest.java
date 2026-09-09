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
 * Réconciliation mutualisée après mutation (issues #112 / #115 / #116 / #119 / #120).
 *
 * <p>Une mutation de contenu qui réussit doit ré-enfiler <strong>automatiquement</strong> le(s)
 * relevé(s) {@code *.list} déclaré(s) périmé(s) par sa spec — sans « Rafraîchir catalogue » manuel,
 * sans F5. Ces relevés portent {@code created_by = "auto"} : le centre de notifications les ignore,
 * mais {@code panel.js} les voit pour savoir quand recharger la page.</p>
 */
class CatalogResyncTest {

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
        loginOk();
    }

    @Test
    void successfulNpcMutationAutoEnqueuesTheNpcCatalogRefresh() throws Exception {
        start();
        String token = csrf(get("/npcs?agent=" + TestConfig.AGENT_ID).body());

        // 1. Mutation réversible : acceptée sans « confirm » (issue #111).
        HttpResponse<String> created = post("/agents/action", "_csrf=" + token
                + "&type=npc.definition.update&agent=" + TestConfig.AGENT_ID
                + "&return=/npcs&npc_id=iron_specialist&display_name=Robert&dialogue_id=rpgquest:robert_writer");
        assertEquals(303, created.statusCode());
        assertFalse(created.headers().firstValue("Location").orElse("").contains("err="));

        // 2. L'agent relève la mutation puis renvoie SUCCESS.
        String mutationId = firstPendingActionId();
        postResult(mutationId, "{\"action_id\":\"" + mutationId + "\",\"status\":\"SUCCESS\","
                + "\"message\":\"définition iron_specialist mise à jour\"}");

        // 3. Un relevé npc.list a été ré-enfilé automatiquement, sans intervention.
        Map<String, Object> json = actionsJson();
        @SuppressWarnings("unchecked")
        List<Object> actions = (List<Object>) json.get("actions");
        boolean autoNpcList = false;
        for (Object o : actions) {
            @SuppressWarnings("unchecked")
            Map<String, Object> a = (Map<String, Object>) o;
            if ("npc.list".equals(a.get("type"))) {
                autoNpcList = true;
                assertEquals(Boolean.TRUE, a.get("auto"), "le relevé ré-enfilé est marqué auto");
                assertEquals(Boolean.FALSE, a.get("terminal"), "il est encore en attente de l'agent");
            }
        }
        assertTrue(autoNpcList, "npc.list ré-enfilé après le succès de npc.definition.update");
        assertTrue(((Number) json.get("pending")).intValue() >= 1, "la file n'est pas au repos tant que le relevé n'est pas revenu");

        // 4. Le centre de notifications n'affiche PAS ce rouage interne, mais bien la mutation.
        String bell = get("/npcs?agent=" + TestConfig.AGENT_ID).body();
        assertTrue(bell.contains("Modifier une définition PNJ"), "la mutation opérateur reste visible");
        int listStart = bell.indexOf("data-notif-list");
        int listEnd = bell.indexOf("notif-foot-wrap");
        assertTrue(listStart > 0 && listEnd > listStart);
        assertFalse(bell.substring(listStart, listEnd).contains("Rafraîchir le catalogue des PNJ"),
                "le relevé auto n'apparaît pas dans la cloche");
    }

    @Test
    void citizensLinkRefreshesBothNpcAndCitizensCatalogsAndDedupes() throws Exception {
        start();
        String token = csrf(get("/npcs?agent=" + TestConfig.AGENT_ID).body());

        HttpResponse<String> link = post("/agents/action", "_csrf=" + token
                + "&type=npc.citizens.link&agent=" + TestConfig.AGENT_ID
                + "&return=/npcs&npc_id=iron_specialist&citizens_id=8");
        assertEquals(303, link.statusCode());
        String linkId = firstPendingActionId();
        postResult(linkId, "{\"action_id\":\"" + linkId + "\",\"status\":\"SUCCESS\","
                + "\"message\":\"lié à Citizens #8\"}");

        Map<String, Object> types = countOpenTypes();
        assertEquals(1, ((Number) types.getOrDefault("npc.list", 0)).intValue());
        assertEquals(1, ((Number) types.getOrDefault("npc.citizens.list", 0)).intValue());

        // Une deuxième mutation qui réussit pendant que les relevés sont encore en vol ne les empile pas.
        HttpResponse<String> link2 = post("/agents/action", "_csrf=" + token
                + "&type=npc.definition.update&agent=" + TestConfig.AGENT_ID
                + "&return=/npcs&npc_id=iron_specialist&display_name=Robert%20II");
        assertEquals(303, link2.statusCode());
        String link2Id = firstPendingActionIdOfType("npc.definition.update");
        postResult(link2Id, "{\"action_id\":\"" + link2Id + "\",\"status\":\"SUCCESS\",\"message\":\"ok\"}");

        Map<String, Object> types2 = countOpenTypes();
        assertEquals(1, ((Number) types2.getOrDefault("npc.list", 0)).intValue(), "npc.list non dédoublé");
    }

    @Test
    void idempotentResultRepostDoesNotEnqueueASecondRefresh() throws Exception {
        start();
        String token = csrf(get("/dialogues?agent=" + TestConfig.AGENT_ID).body());
        HttpResponse<String> created = post("/agents/action", "_csrf=" + token
                + "&type=dialogue.definition.create&agent=" + TestConfig.AGENT_ID
                + "&return=/dialogues&key=robert_writer&speaker=Robert&text=Bonjour");
        assertEquals(303, created.statusCode());
        String id = firstPendingActionId();
        String body = "{\"action_id\":\"" + id + "\",\"status\":\"SUCCESS\",\"message\":\"créé\"}";
        postResult(id, body);
        // L'agent renvoie le même résultat après un timeout : accepté (idempotent), mais aucun
        // second dialogue.list ré-enfilé.
        HttpResponse<String> repost = rawResult(id, body);
        assertEquals(200, repost.statusCode());
        assertEquals(1, ((Number) countOpenTypes().getOrDefault("dialogue.list", 0)).intValue());
    }

    @Test
    void failedMutationEnqueuesNoRefresh() throws Exception {
        start();
        String token = csrf(get("/dialogues?agent=" + TestConfig.AGENT_ID).body());
        HttpResponse<String> created = post("/agents/action", "_csrf=" + token
                + "&type=dialogue.definition.create&agent=" + TestConfig.AGENT_ID
                + "&return=/dialogues&key=robert_writer&speaker=Robert&text=Bonjour");
        assertEquals(303, created.statusCode());
        String id = firstPendingActionId();
        postResult(id, "{\"action_id\":\"" + id + "\",\"status\":\"FAILED\",\"message\":\"id déjà pris\"}");
        assertEquals(0, ((Number) countOpenTypes().getOrDefault("dialogue.list", 0)).intValue(),
                "un échec ne resynchronise rien");
    }

    @Test
    void panelScriptCarriesTheSharedReloadOnIdleMechanism() throws Exception {
        start();
        String js = get("/assets/panel.js").body();
        assertTrue(js.contains("reloadPlan") && js.contains("runReload"), "plan de rechargement mutualisé");
        assertTrue(js.contains("sawPending"), "n'agit que sur une action vue s'exécuter pendant la page");
        assertTrue(js.contains("data.pending === 0"), "recharge quand la file est au repos");
        // Un seul point de rechargement, dans le tick de polling — pas un reload par bouton.
        assertEquals(1, js.split("window.location.reload\\(\\)", -1).length - 1, "un seul appel reload");
    }

    // ---- helpers ---------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private Map<String, Object> actionsJson() throws Exception {
        return Json.parseObject(get("/agents/actions.json?agent=" + TestConfig.AGENT_ID).body());
    }

    /** {type -> nombre d'actions ouvertes (non terminales)} d'après /agents/actions.json. */
    private Map<String, Object> countOpenTypes() throws Exception {
        Map<String, Object> out = new LinkedHashMap<>();
        @SuppressWarnings("unchecked")
        List<Object> actions = (List<Object>) actionsJson().get("actions");
        for (Object o : actions) {
            @SuppressWarnings("unchecked")
            Map<String, Object> a = (Map<String, Object>) o;
            if (Boolean.FALSE.equals(a.get("terminal"))) {
                String t = String.valueOf(a.get("type"));
                out.merge(t, 1, (x, y) -> ((Number) x).intValue() + 1);
            }
        }
        return out;
    }

    private String firstPendingActionId() throws Exception {
        return pollPending().get(0);
    }

    private String firstPendingActionIdOfType(String type) throws Exception {
        HttpResponse<String> poll = client.send(HttpRequest.newBuilder(uri("/agent/v1/actions"))
                .header("Authorization", "Bearer " + TestConfig.AGENT_TOKEN)
                .header("X-Agent-Id", TestConfig.AGENT_ID).GET().build(), HttpResponse.BodyHandlers.ofString());
        @SuppressWarnings("unchecked")
        List<Object> actions = (List<Object>) Json.parseObject(poll.body()).get("actions");
        for (Object o : actions) {
            @SuppressWarnings("unchecked")
            Map<String, Object> a = (Map<String, Object>) o;
            if (type.equals(a.get("type"))) {
                return String.valueOf(a.get("id"));
            }
        }
        throw new AssertionError("aucune action en attente de type " + type);
    }

    private List<String> pollPending() throws Exception {
        HttpResponse<String> poll = client.send(HttpRequest.newBuilder(uri("/agent/v1/actions"))
                .header("Authorization", "Bearer " + TestConfig.AGENT_TOKEN)
                .header("X-Agent-Id", TestConfig.AGENT_ID).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, poll.statusCode());
        @SuppressWarnings("unchecked")
        List<Object> actions = (List<Object>) Json.parseObject(poll.body()).get("actions");
        java.util.ArrayList<String> ids = new java.util.ArrayList<>();
        for (Object o : actions) {
            @SuppressWarnings("unchecked")
            Map<String, Object> a = (Map<String, Object>) o;
            ids.add(String.valueOf(a.get("id")));
        }
        return ids;
    }

    private void postResult(String actionId, String body) throws Exception {
        assertEquals(200, rawResult(actionId, body).statusCode());
    }

    private HttpResponse<String> rawResult(String actionId, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(uri("/agent/v1/actions/" + actionId + "/result"))
                .header("Authorization", "Bearer " + TestConfig.AGENT_TOKEN)
                .header("X-Agent-Id", TestConfig.AGENT_ID)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
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
}
