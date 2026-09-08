package com.lodygames.rpgquest.panel.web;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Rendu du catalogue {@code /npcs} (V1) : nom lisible, id copiable, relations et anomalies. */
class NpcsCatalogTest {

    @TempDir
    Path tmp;

    private PanelApp app;
    private int port;
    private HttpClient client;
    private final Map<String, String> jar = new LinkedHashMap<>();

    // Payload npc.list : un PNJ sain (guard) + un tag orphelin (garde) + un id référencé sans PNJ.
    private static final String NPC_DETAILS = "{"
            + "\"citizensAvailable\":true,\"total\":3,\"bound\":2,\"unbound\":1,\"withWarnings\":2,"
            + "\"canonicalIds\":[\"guard\",\"libraire\"],"
            + "\"npcs\":["
            + "{\"id\":\"libraire\",\"displayName\":null,\"citizensNumericId\":null,\"bindingCount\":0,\"bound\":false,"
            + "\"hasDialogue\":true,\"dialogueId\":\"rpgquest:libraire\",\"dialogueNodes\":3,\"dialogueChoices\":4,"
            + "\"dialogueStartsQuests\":[\"rpgquest:premiers_pas\"],\"questsGiven\":[\"rpgquest:premiers_pas\"],"
            + "\"questsReferenced\":[],\"sources\":[\"DIALOGUE\",\"QUEST_GIVER\"],"
            + "\"warnings\":[{\"code\":\"QUEST_REF_NO_NPC\",\"severity\":\"warning\","
            + "\"message\":\"Référencé par une quête mais aucun PNJ Citizens n'est tagué « libraire ».\"}]},"
            + "{\"id\":\"garde\",\"displayName\":null,\"citizensNumericId\":3,\"bindingCount\":1,\"bound\":true,"
            + "\"hasDialogue\":false,\"dialogueId\":null,\"dialogueNodes\":0,\"dialogueChoices\":0,"
            + "\"dialogueStartsQuests\":[],\"questsGiven\":[],\"questsReferenced\":[],\"sources\":[\"BINDING\"],"
            + "\"warnings\":[{\"code\":\"TAGGED_UNUSED\",\"severity\":\"info\","
            + "\"message\":\"PNJ tagué « garde » mais aucun dialogue ni quête ne l'utilise. Id canonique proche : « guard » ?\"}]},"
            + "{\"id\":\"guard\",\"displayName\":\"Garde\",\"citizensNumericId\":7,\"bindingCount\":1,\"bound\":true,"
            + "\"hasDialogue\":true,\"dialogueId\":\"rpgquest:guard\",\"dialogueNodes\":6,\"dialogueChoices\":9,"
            + "\"dialogueStartsQuests\":[\"rpgquest:first_steps\"],\"questsGiven\":[\"rpgquest:crystal_hunt\"],"
            + "\"questsReferenced\":[\"rpgquest:crystal_hunt\"],\"sources\":[\"BINDING\",\"DIALOGUE\"],\"warnings\":[]}"
            + "]}";

    @AfterEach
    void tearDown() {
        if (app != null) {
            app.stop();
        }
    }

    @Test
    void catalogRendersNamesIdsRelationsAndWarnings() throws Exception {
        start();
        runListWithSuccess(NPC_DETAILS);
        String page = get("/npcs?agent=" + TestConfig.AGENT_ID).body();

        // nom lisible d'abord (depuis le dialogue), id technique copiable
        assertTrue(page.contains("Garde"), "nom du PNJ affiché");
        assertTrue(page.contains("data-copy=\"guard\""), "id RPGQuest copiable");
        assertTrue(page.contains("Citizens #7"), "id Citizens affiché");
        assertFalse(page.contains("&lt;gold&gt;") || page.contains("<gold>"), "aucune balise brute");

        // relations
        assertTrue(page.contains("rpgquest:guard"), "id de dialogue conservé");
        assertTrue(page.contains("Donne"), "meta-line quêtes données");
        assertTrue(page.contains("Objectif « parler à »"), "meta-line quêtes référencées");
        assertTrue(page.contains("Le dialogue démarre"), "meta-line START_QUEST du dialogue");

        // anomalies : pastille de sévérité + message + code
        assertTrue(page.contains("ATTENTION"), "pastille warning");
        assertTrue(page.contains("INFO"), "pastille info");
        assertTrue(page.contains("Id canonique proche"), "suggestion garde -> guard");
        assertTrue(page.contains("data-copy=\"TAGGED_UNUSED\""), "code d'anomalie visible/copiable");
        assertTrue(page.contains("non tagué"), "PNJ sans binding marqué");

        // résumé + ids canoniques (#66)
        assertTrue(page.contains("2 avec avertissement") || page.contains("withWarnings")
                || page.contains("avec avertissement"), "résumé compteurs");
        assertTrue(page.contains("IDs canoniques connus"), "bloc ids canoniques");
        assertTrue(page.contains("data-copy=\"libraire\""), "id canonique listé");

        // ordre préservé du payload (l'agent trie : anomalies d'abord) -> carte 'libraire' avant 'guard'
        assertTrue(page.indexOf("data-copy=\"libraire\"") < page.indexOf("data-copy=\"guard\""),
                "PNJ en anomalie listés avant les PNJ sains");
    }

    @Test
    void emptyStateBeforeAnyRefresh() throws Exception {
        start();
        String page = get("/npcs?agent=" + TestConfig.AGENT_ID).body();
        assertTrue(page.contains("<h1>PNJ</h1>"));
        assertTrue(page.contains("Aucun catalogue chargé"));
        assertTrue(page.contains("name=\"type\" value=\"npc.list\""));
    }

    // ---- helpers (identiques à QuestsCatalogTest) -----------------------------------------

    private void runListWithSuccess(String details) throws Exception {
        String token = csrf(get("/npcs?agent=" + TestConfig.AGENT_ID).body());
        post("/agents/action", "_csrf=" + token + "&type=npc.list&agent=" + TestConfig.AGENT_ID + "&return=/npcs");
        HttpResponse<String> poll = client.send(HttpRequest.newBuilder(uri("/agent/v1/actions"))
                .header("Authorization", "Bearer " + TestConfig.AGENT_TOKEN)
                .header("X-Agent-Id", TestConfig.AGENT_ID).GET().build(), HttpResponse.BodyHandlers.ofString());
        Matcher m = Pattern.compile("\"id\"\\s*:\\s*\"([0-9a-f-]{36})\"").matcher(poll.body());
        m.find();
        String id = m.group(1);
        String result = "{\"action_id\":\"" + id + "\",\"status\":\"SUCCESS\",\"value\":\"3\","
                + "\"message\":\"3 PNJ\",\"details\":" + details + "}";
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
