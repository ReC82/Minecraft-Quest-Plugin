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

/** Rendu et écritures de la page {@code /npcs} V2 : définition logique vs binding Citizens. */
class NpcsCatalogTest {

    @TempDir
    Path tmp;

    private PanelApp app;
    private int port;
    private HttpClient client;
    private final Map<String, String> jar = new LinkedHashMap<>();

    // Payload npc.list V2 : guard (défini + lié) + woodcutter_bob (référencé, sans définition).
    private static final String NPC_DETAILS = "{"
            + "\"citizensAvailable\":true,\"total\":2,\"withDefinition\":1,\"withoutDefinition\":1,"
            + "\"bound\":1,\"withWarnings\":1,"
            + "\"definedIds\":[\"guard\"],\"canonicalIds\":[\"guard\",\"woodcutter_bob\"],"
            + "\"npcs\":["
            + "{\"id\":\"woodcutter_bob\",\"displayName\":null,\"logicalDefinitionPresent\":false,"
            + "\"citizensBindingPresent\":false,\"citizensNumericId\":null,\"bindingCount\":0,\"enabled\":true,"
            + "\"description\":null,\"role\":null,\"definedDialogueId\":null,\"hasDialogue\":false,\"dialogueId\":null,"
            + "\"dialogueNodes\":0,\"dialogueChoices\":0,\"dialogueStartsQuests\":[],\"questsGiven\":[],"
            + "\"questsReferenced\":[\"rpgquest:woodcutters_request\"],\"sources\":[\"QUEST_TALK\"],"
            + "\"state\":\"UNDEFINED_REFERENCE\",\"warnings\":[{\"code\":\"NO_DEFINITION\",\"severity\":\"error\","
            + "\"message\":\"Aucune définition logique RPGQuest pour « woodcutter_bob » (référencé par objectif « parler à »). À migrer : créer la définition.\"}]},"
            + "{\"id\":\"guard\",\"displayName\":\"<yellow>Garde</yellow>\",\"logicalDefinitionPresent\":true,"
            + "\"citizensBindingPresent\":true,\"citizensNumericId\":6,\"bindingCount\":1,\"enabled\":true,"
            + "\"description\":\"Garde du village\",\"role\":\"quest_giver\",\"definedDialogueId\":\"rpgquest:guard\","
            + "\"hasDialogue\":true,\"dialogueId\":\"rpgquest:guard\",\"dialogueNodes\":6,\"dialogueChoices\":9,"
            + "\"dialogueStartsQuests\":[\"rpgquest:first_steps\"],\"questsGiven\":[\"rpgquest:crystal_hunt\"],"
            + "\"questsReferenced\":[\"rpgquest:crystal_hunt\"],\"sources\":[\"DEFINITION\",\"BINDING\",\"DIALOGUE\"],"
            + "\"state\":\"LINKED\",\"warnings\":[]}"
            + "]}";

    @AfterEach
    void tearDown() {
        if (app != null) {
            app.stop();
        }
    }

    @Test
    void catalogSeparatesDefinitionFromCitizensBindingAndOffersWrites() throws Exception {
        start();
        runListWithSuccess(NPC_DETAILS);
        String page = get("/npcs?agent=" + TestConfig.AGENT_ID).body();

        assertTrue(page.contains("Définition RPGQuest"), "bloc définition");
        assertTrue(page.contains("Binding Citizens"), "bloc binding");
        assertTrue(page.contains("Garde"), "nom lisible depuis la définition");
        assertTrue(page.contains("<span style=\"color:"), "MiniMessage interprété dans le titre");
        // le titre de carte ne montre jamais la balise brute (l'input d'édition, lui, garde la valeur brute)
        int guardNameAt = page.lastIndexOf("class=\"entity-name\"");
        assertFalse(page.substring(guardNameAt, guardNameAt + 150).contains("&lt;yellow&gt;"), "titre sans balise brute");
        assertTrue(page.contains("data-copy=\"guard\""), "id copiable");
        assertTrue(page.contains("PNJ Citizens #6"), "binding Citizens affiché");
        assertTrue(page.contains("quest_giver") || page.contains("Quest Giver"), "rôle affiché");

        // woodcutter_bob : sans définition -> erreur de contenu + état
        assertTrue(page.contains("sans définition"), "PNJ sans définition marqué");
        assertTrue(page.contains("non défini") || page.contains("UNDEFINED_REFERENCE"), "état non défini");
        assertTrue(page.contains("data-copy=\"NO_DEFINITION\""), "code d'anomalie");
        assertTrue(page.indexOf("data-copy=\"woodcutter_bob\"") < page.indexOf("data-copy=\"guard\""),
                "PNJ en erreur listé avant le PNJ sain");

        // écritures proposées (rôle OWNER)
        assertTrue(page.contains("name=\"type\" value=\"npc.definition.create\""), "formulaire de création");
        assertTrue(page.contains("name=\"type\" value=\"npc.definition.update\""), "formulaire d'édition");
        assertTrue(page.contains("Créer la définition « woodcutter_bob »"), "création pré-remplie sur la carte non définie");

        // registre canonique : définis vs tous
        assertTrue(page.contains("Registre canonique"));
        assertTrue(page.contains("Définis"));
    }

    @Test
    void emptyStateStillOffersDefinitionCreation() throws Exception {
        start();
        String page = get("/npcs?agent=" + TestConfig.AGENT_ID).body();
        assertTrue(page.contains("<h1>PNJ</h1>"));
        assertTrue(page.contains("Aucun catalogue chargé"));
        assertTrue(page.contains("name=\"type\" value=\"npc.list\""));
        assertTrue(page.contains("name=\"type\" value=\"npc.definition.create\""));
    }

    @Test
    void createDefinitionActionIsValidatedAndQueued() throws Exception {
        start();
        String token = csrf(get("/npcs?agent=" + TestConfig.AGENT_ID).body());
        // sans confirm -> refusé
        HttpResponse<String> noConfirm = post("/agents/action", "_csrf=" + token
                + "&type=npc.definition.create&agent=" + TestConfig.AGENT_ID
                + "&return=/npcs&npc_id=woodcutter_bob&display_name=" + enc("Bûcheron Bob"));
        assertTrue(noConfirm.headers().firstValue("Location").orElse("").contains("err="), "confirm obligatoire");

        HttpResponse<String> ok = post("/agents/action", "_csrf=" + token
                + "&type=npc.definition.create&agent=" + TestConfig.AGENT_ID
                + "&return=/npcs&npc_id=woodcutter_bob&display_name=" + enc("Bûcheron Bob")
                + "&dialogue_id=rpgquest:woodcutter_bob&enabled=true&confirm=true");
        assertEquals(303, ok.statusCode());
        assertTrue(ok.headers().firstValue("Location").orElse("").startsWith("/npcs?agent="));
        assertTrue(pendingFor(TestConfig.AGENT_ID) >= 1, "une action npc.definition.create en attente");

        // id invalide -> refusé
        HttpResponse<String> bad = post("/agents/action", "_csrf=" + token
                + "&type=npc.definition.create&agent=" + TestConfig.AGENT_ID
                + "&return=/npcs&npc_id=" + enc("Bad Id") + "&display_name=X&confirm=true");
        assertTrue(bad.headers().firstValue("Location").orElse("").contains("err="));
    }

    @Test
    void questGiverSetActionIsValidatedAndQueued() throws Exception {
        start();
        String token = csrf(get("/npcs?agent=" + TestConfig.AGENT_ID).body());
        HttpResponse<String> ok = post("/agents/action", "_csrf=" + token
                + "&type=quest.giver.set&agent=" + TestConfig.AGENT_ID
                + "&return=/npcs&quest_id=rpgquest:woodcutters_request&npc_id=woodcutter_bob&confirm=true");
        assertEquals(303, ok.statusCode());
        assertTrue(pendingFor(TestConfig.AGENT_ID) >= 1);
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
        String token = csrf(get("/npcs?agent=" + TestConfig.AGENT_ID).body());
        post("/agents/action", "_csrf=" + token + "&type=npc.list&agent=" + TestConfig.AGENT_ID + "&return=/npcs");
        HttpResponse<String> poll = client.send(HttpRequest.newBuilder(uri("/agent/v1/actions"))
                .header("Authorization", "Bearer " + TestConfig.AGENT_TOKEN)
                .header("X-Agent-Id", TestConfig.AGENT_ID).GET().build(), HttpResponse.BodyHandlers.ofString());
        Matcher m = Pattern.compile("\"id\"\\s*:\\s*\"([0-9a-f-]{36})\"").matcher(poll.body());
        m.find();
        String id = m.group(1);
        String result = "{\"action_id\":\"" + id + "\",\"status\":\"SUCCESS\",\"value\":\"2\","
                + "\"message\":\"2 PNJ\",\"details\":" + details + "}";
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
