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
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Rendu de la page {@code /dialogues} V1 : graphe, actions typées, warnings, squelette de création. */
class DialoguesCatalogTest {

    @TempDir
    Path tmp;

    private PanelApp app;
    private int port;
    private HttpClient client;
    private final Map<String, String> jar = new LinkedHashMap<>();

    private static final String DIALOGUE_DETAILS = "{"
            + "\"total\":1,\"withWarnings\":1,\"nodeTotal\":3,"
            + "\"loadIssues\":[{\"file\":\"broken.yml\",\"message\":\"« nodes » est obligatoire.\"}],"
            + "\"declaredButMissing\":[{\"npcId\":\"ghost\",\"dialogueId\":\"rpgquest:ghost\"}],"
            + "\"dialogues\":[{"
            + "\"id\":\"rpgquest:guard\",\"key\":\"guard\",\"startNodeId\":\"greeting\","
            + "\"linkedNpcIds\":[\"guard\"],\"nodeCount\":3,\"choiceCount\":4,"
            + "\"referencedQuestIds\":[\"rpgquest:first_steps\"],\"startsQuestIds\":[\"rpgquest:first_steps\"],"
            + "\"warnings\":[{\"code\":\"NODE_UNREACHABLE\",\"severity\":\"info\",\"message\":\"le nœud « orphan » est inaccessible.\"}],"
            + "\"nodes\":["
            + "{\"id\":\"greeting\",\"speaker\":\"Garde\",\"text\":\"<white>Bonjour.</white>\",\"start\":true,\"reachable\":true,"
            + "\"choices\":[{\"text\":\"J'accepte\",\"nextNodeId\":\"accepted\","
            + "\"actions\":[{\"kind\":\"START_QUEST\",\"target\":\"rpgquest:first_steps\",\"value\":\"\",\"raw\":\"START_QUEST rpgquest:first_steps\"}],"
            + "\"conditions\":[{\"kind\":\"QUEST_STATE\",\"target\":\"rpgquest:first_steps\",\"value\":\"NOT_STARTED\",\"raw\":\"r\",\"negated\":false}]},"
            + "{\"text\":\"Non merci\",\"nextNodeId\":\"\",\"actions\":[],\"conditions\":[]}]},"
            + "{\"id\":\"accepted\",\"speaker\":\"Garde\",\"text\":\"Bien.\",\"start\":false,\"reachable\":true,"
            + "\"choices\":[{\"text\":\"OK\",\"nextNodeId\":\"\",\"actions\":[{\"kind\":\"CLOSE\",\"target\":\"\",\"value\":\"\",\"raw\":\"CLOSE\"}],\"conditions\":[]}]},"
            + "{\"id\":\"orphan\",\"speaker\":\"Garde\",\"text\":\"<gold>caché</gold>\",\"start\":false,\"reachable\":false,"
            + "\"choices\":[{\"text\":\"OK\",\"nextNodeId\":\"\",\"actions\":[],\"conditions\":[]}]}"
            + "]}]}";

    @AfterEach
    void tearDown() {
        if (app != null) {
            app.stop();
        }
    }

    @Test
    void emptyStateOffersRefreshAndSkeletonCreation() throws Exception {
        start();
        String page = get("/dialogues?agent=" + TestConfig.AGENT_ID).body();
        assertTrue(page.contains("<h1>Dialogues</h1>"));
        assertTrue(page.contains("Aucun catalogue chargé"));
        assertTrue(page.contains("name=\"type\" value=\"dialogue.list\""));
        assertTrue(page.contains("name=\"type\" value=\"dialogue.definition.create\""));
        // nav : entrée Dialogues active
        assertTrue(page.contains("href=\"/dialogues\""));
    }

    @Test
    void catalogRendersGraphTypedActionsAndWarnings() throws Exception {
        start();
        runListWithSuccess(DIALOGUE_DETAILS);
        String page = get("/dialogues?agent=" + TestConfig.AGENT_ID).body();

        assertTrue(page.contains("data-copy=\"rpgquest:guard\""), "id copiable");
        assertTrue(page.contains("Nœud de départ"));
        // MiniMessage rendu, jamais brut
        assertTrue(page.contains("<span style=\"color:"), "MiniMessage interprété");
        assertFalse(page.contains("&lt;white&gt;Bonjour."), "pas de balise brute dans le texte de nœud");
        // graphe : nœud de départ identifiable + nœud inaccessible marqué
        assertTrue(page.contains("dlg-node start"));
        assertTrue(page.contains("dlg-node") && page.contains("unreachable"));
        assertTrue(page.contains("inaccessible"));
        // action typée START_QUEST rendue en libellé lisible (titre humain si quest.list chargé, sinon prettify)
        assertTrue(page.contains("démarre") || page.contains("START_QUEST"));
        assertTrue(page.contains("→ accepted"), "transition next affichée");
        // warning info
        assertTrue(page.contains("NODE_UNREACHABLE"));
        // bannières : fichier rejeté + définition pointant vers dialogue absent
        assertTrue(page.contains("broken.yml"));
        assertTrue(page.contains("rpgquest:ghost"));
        // PNJ lié
        assertTrue(page.contains("PNJ liés"));
        assertTrue(page.contains("data-copy=\"guard\""));
    }

    @Test
    void createSkeletonActionIsValidatedAndQueued() throws Exception {
        start();
        String token = csrf(get("/dialogues?agent=" + TestConfig.AGENT_ID).body());

        HttpResponse<String> noConfirm = post("/agents/action", "_csrf=" + token
                + "&type=dialogue.definition.create&agent=" + TestConfig.AGENT_ID
                + "&return=/dialogues&key=woodcutter_bob&speaker=" + enc("Bûcheron Bob") + "&text=" + enc("Bonjour"));
        assertTrue(noConfirm.headers().firstValue("Location").orElse("").contains("err="), "confirm obligatoire");

        HttpResponse<String> ok = post("/agents/action", "_csrf=" + token
                + "&type=dialogue.definition.create&agent=" + TestConfig.AGENT_ID
                + "&return=/dialogues&key=woodcutter_bob&speaker=" + enc("Bûcheron Bob")
                + "&text=" + enc("<white>Bonjour.</white>") + "&confirm=true");
        assertEquals(303, ok.statusCode());
        assertTrue(pendingFor(TestConfig.AGENT_ID) >= 1);

        HttpResponse<String> bad = post("/agents/action", "_csrf=" + token
                + "&type=dialogue.definition.create&agent=" + TestConfig.AGENT_ID
                + "&return=/dialogues&key=" + enc("Bad Key") + "&speaker=X&text=Y&confirm=true");
        assertTrue(bad.headers().firstValue("Location").orElse("").contains("err="));
    }

    // ---- helpers ----------------------------------------------------------------------

    private static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }

    private int pendingFor(String agent) throws Exception {
        Map<String, Object> body = Json.parseObject(get("/agents/actions.json?agent=" + agent).body());
        return ((Number) body.get("pending")).intValue();
    }

    private void runListWithSuccess(String details) throws Exception {
        String token = csrf(get("/dialogues?agent=" + TestConfig.AGENT_ID).body());
        post("/agents/action", "_csrf=" + token + "&type=dialogue.list&agent=" + TestConfig.AGENT_ID + "&return=/dialogues");
        HttpResponse<String> poll = client.send(HttpRequest.newBuilder(uri("/agent/v1/actions"))
                .header("Authorization", "Bearer " + TestConfig.AGENT_TOKEN)
                .header("X-Agent-Id", TestConfig.AGENT_ID).GET().build(), HttpResponse.BodyHandlers.ofString());
        Matcher m = Pattern.compile("\"id\"\\s*:\\s*\"([0-9a-f-]{36})\"").matcher(poll.body());
        m.find();
        String id = m.group(1);
        String result = "{\"action_id\":\"" + id + "\",\"status\":\"SUCCESS\",\"value\":\"1\","
                + "\"message\":\"1 dialogue\",\"details\":" + details + "}";
        client.send(HttpRequest.newBuilder(uri("/agent/v1/actions/" + id + "/result"))
                .header("Authorization", "Bearer " + TestConfig.AGENT_TOKEN)
                .header("X-Agent-Id", TestConfig.AGENT_ID)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(result)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private void start() throws Exception {
        String db = tmp.resolve("cp.db").toString();
        app = new PanelApp(TestConfig.withAgent(db, "http://127.0.0.1:1/admin/v1"),
                new InMemoryAuditLog(), new BridgeClient(Duration.ofMillis(300), Duration.ofMillis(400)),
                new AgentStore(db));
        port = app.start();
        client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        jar.clear();
        String token = csrf(get("/login").body());
        post("/login", "username=" + TestConfig.OWNER_USERNAME + "&password="
                + URLEncoder.encode(TestConfig.OWNER_PASSWORD, StandardCharsets.UTF_8) + "&_csrf=" + token);
    }

    private static String csrf(String html) {
        Matcher m = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"").matcher(html);
        m.find();
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
