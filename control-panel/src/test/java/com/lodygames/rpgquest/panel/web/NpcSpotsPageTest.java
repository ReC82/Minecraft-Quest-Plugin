package com.lodygames.rpgquest.panel.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.panel.agent.AgentActionRow;
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

/**
 * Issue #381 — la page {@code /npcs/spots} sur un vrai serveur HTTP : rendu des états, filtres,
 * formulaires, et ce qui part réellement vers l'agent.
 */
class NpcSpotsPageTest {

    @TempDir
    Path tmp;

    private PanelApp app;
    private AgentStore store;
    private int port;
    private HttpClient client;
    private final Map<String, String> jar = new LinkedHashMap<>();

    /** Relevé réaliste : un emplacement occupé, un libre, un en conflit dans un autre monde. */
    private static final String SPOTS = "{\"available\":true,\"worlds\":[\"world\",\"hub\"],"
            + "\"npcs\":{\"guard\":\"Garde\",\"test_npc\":\"PNJ de test\"},"
            + "\"spots\":["
            + "{\"id\":\"npcspot_0001\",\"name\":\"Porte nord\",\"world\":\"world\",\"x\":10.5,\"y\":64,"
            + "\"z\":-3.5,\"yaw\":180,\"pitch\":0,\"radius\":2,\"npcId\":\"guard\",\"npcName\":\"Garde\","
            + "\"citizensId\":7,\"status\":\"OCCUPIED\",\"statusMessage\":\"Occupé par « guard ».\","
            + "\"createdBy\":\"Lody\",\"createdAt\":\"t\",\"updatedAt\":\"t\","
            + "\"notes\":[\"PNJ central de la ville « Bourg » : déplacer le PNJ ne déplace ni le centre ni l'arrivée de la ville.\"]},"
            + "{\"id\":\"npcspot_0002\",\"name\":\"Place du marché\",\"world\":\"world\",\"x\":20,\"y\":64,"
            + "\"z\":20,\"yaw\":-90,\"pitch\":0,\"radius\":2,\"npcId\":\"\",\"npcName\":\"\",\"citizensId\":-1,"
            + "\"status\":\"FREE\",\"statusMessage\":\"Libre.\",\"createdBy\":\"Lody\",\"createdAt\":\"t\","
            + "\"updatedAt\":\"t\",\"notes\":[]},"
            + "{\"id\":\"npcspot_0003\",\"name\":\"Quai\",\"world\":\"hub\",\"x\":0,\"y\":70,\"z\":0,"
            + "\"yaw\":0,\"pitch\":0,\"radius\":2,\"npcId\":\"test_npc\",\"npcName\":\"PNJ de test\","
            + "\"citizensId\":-1,\"status\":\"CONFLICT\",\"statusMessage\":\"n'existe pas en jeu\","
            + "\"createdBy\":\"Lody\",\"createdAt\":\"t\",\"updatedAt\":\"t\",\"notes\":[]}"
            + "]}";

    @AfterEach
    void tearDown() {
        if (app != null) {
            app.stop();
        }
    }

    @Test
    void withoutASurveyThePageExplainsHowToReserveAndAsksToRefresh() throws Exception {
        start();
        String page = get("/npcs/spots?agent=" + TestConfig.AGENT_ID).body();
        assertTrue(page.contains("/rpgadmin npc spot mark"), "la marche à suivre en jeu");
        assertTrue(page.contains("Aucun relev") || page.contains("Rien n&#39;a encore") || page.contains("Rien n'a encore"));
    }

    @Test
    void theListShowsStatesNpcPositionDirectionAndNotes() throws Exception {
        start();
        seed();
        String page = get("/npcs/spots?agent=" + TestConfig.AGENT_ID).body();
        assertTrue(page.contains("Porte nord") && page.contains("npcspot_0001"));
        assertTrue(page.contains("occupé") && page.contains("libre") && page.contains("conflit"));
        assertTrue(page.contains("Citizens #7"), "lien Citizens visible");
        assertTrue(page.contains("10.5 64.0 -3.5"), "position lisible");
        assertTrue(page.contains("nord (180°)"), "orientation en mots");
        assertTrue(page.contains("PNJ central de la ville"), "dépendance signalée");
        assertTrue(page.contains("3 emplacement(s)"));
    }

    @Test
    void filtersNarrowByStatusWorldAndText() throws Exception {
        start();
        seed();
        String free = get("/npcs/spots?agent=" + TestConfig.AGENT_ID + "&status=FREE").body();
        assertTrue(free.contains("id=\"spot-npcspot_0002\""));
        assertFalse(free.contains("id=\"spot-npcspot_0001\""));
        String hub = get("/npcs/spots?agent=" + TestConfig.AGENT_ID + "&world=hub").body();
        assertTrue(hub.contains("id=\"spot-npcspot_0003\""));
        assertFalse(hub.contains("id=\"spot-npcspot_0002\""));
        String text = get("/npcs/spots?agent=" + TestConfig.AGENT_ID + "&q=garde").body();
        assertTrue(text.contains("id=\"spot-npcspot_0001\""));
        assertFalse(text.contains("id=\"spot-npcspot_0003\""));
    }

    @Test
    void anOccupiedSpotOffersReleaseButNotDeletionAndAFreeOneTheReverse() throws Exception {
        start();
        seed();
        String page = get("/npcs/spots?agent=" + TestConfig.AGENT_ID).body();
        String occupied = page.substring(page.indexOf("id=\"spot-npcspot_0001\""), page.indexOf("id=\"spot-npcspot_0002\""));
        assertTrue(occupied.contains("value=\"npc.spot.release\""));
        assertFalse(occupied.contains("value=\"npc.spot.delete\""), "suppression protégée tant qu'occupé");
        String free = page.substring(page.indexOf("id=\"spot-npcspot_0002\""), page.indexOf("id=\"spot-npcspot_0003\""));
        assertTrue(free.contains("value=\"npc.spot.delete\""));
        assertFalse(free.contains("value=\"npc.spot.release\""));
        assertTrue(free.contains("<option value=\"guard\">Garde (guard)</option>"), "PNJ plaçables proposés");
    }

    @Test
    void theNpcParameterPreselectsItInFreeSpots() throws Exception {
        start();
        seed();
        String page = get("/npcs/spots?agent=" + TestConfig.AGENT_ID + "&npc=test_npc").body();
        String free = page.substring(page.indexOf("id=\"spot-npcspot_0002\""), page.indexOf("id=\"spot-npcspot_0003\""));
        assertTrue(free.contains("<option value=\"test_npc\" selected>"));
    }

    @Test
    void placingQueuesOneActionOnlyWithConfirmation() throws Exception {
        start();
        seed();
        String token = csrf(get("/npcs/spots?agent=" + TestConfig.AGENT_ID).body());
        post("/agents/action", "_csrf=" + token + "&agent=" + TestConfig.AGENT_ID
                + "&type=npc.spot.place&return=/npcs/spots&spot_id=npcspot_0002&npc_id=test_npc");
        assertEquals(0, count("npc.spot.place"), "sans case cochée, rien ne part");
        post("/agents/action", "_csrf=" + token + "&agent=" + TestConfig.AGENT_ID
                + "&type=npc.spot.place&return=/npcs/spots&spot_id=npcspot_0002&npc_id=test_npc&confirm=true");
        AgentActionRow queued = queued("npc.spot.place");
        assertEquals("npcspot_0002", queued.params().get("spot_id"));
        assertEquals("test_npc", queued.params().get("npc_id"));
    }

    @Test
    void forgedIdsAndNamesAreRefusedBeforeTheQueue() throws Exception {
        start();
        seed();
        String token = csrf(get("/npcs/spots?agent=" + TestConfig.AGENT_ID).body());
        post("/agents/action", "_csrf=" + token + "&agent=" + TestConfig.AGENT_ID
                + "&type=npc.spot.rename&return=/npcs/spots&spot_id=buildsite_0001&name=X");
        post("/agents/action", "_csrf=" + token + "&agent=" + TestConfig.AGENT_ID
                + "&type=npc.spot.rename&return=/npcs/spots&spot_id=npcspot_0001&name=" + enc("x".repeat(65)));
        assertEquals(0, count("npc.spot.rename"));
        post("/agents/action", "_csrf=" + token + "&agent=" + TestConfig.AGENT_ID
                + "&type=npc.spot.orient&return=/npcs/spots&spot_id=npcspot_0001&facing=east");
        assertEquals("-90", queued("npc.spot.orient").params().get("yaw"), "« l'est » devient un angle");
    }

    @Test
    void captureIsAttributedToTheSessionNotTheForm() throws Exception {
        start();
        seed();
        String token = csrf(get("/npcs/spots?agent=" + TestConfig.AGENT_ID).body());
        post("/agents/action", "_csrf=" + token + "&agent=" + TestConfig.AGENT_ID
                + "&type=npc.spot.capture&return=/npcs/spots&npc_id=guard&by=Intrus");
        assertEquals(TestConfig.OWNER_USERNAME, queued("npc.spot.capture").params().get("by"));
    }

    @Test
    void theNpcPageLinksToSpots() throws Exception {
        start();
        assertTrue(get("/npcs?agent=" + TestConfig.AGENT_ID).body().contains("href=\"/npcs/spots\""));
    }

    private void start() throws Exception {
        String db = tmp.resolve("cp.db").toString();
        store = new AgentStore(db);
        app = new PanelApp(TestConfig.withAgent(db, "http://127.0.0.1:1/admin/v1"),
                new InMemoryAuditLog(), new BridgeClient(Duration.ofMillis(300), Duration.ofMillis(400)),
                store);
        port = app.start();
        client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        jar.clear();
        String token = csrf(get("/login").body());
        post("/login", "username=" + TestConfig.OWNER_USERNAME + "&password="
                + URLEncoder.encode(TestConfig.OWNER_PASSWORD, StandardCharsets.UTF_8)
                + "&_csrf=" + token);
    }

    private void seed() throws Exception {
        seedWith(SPOTS);
    }

    private void seedWith(String details) throws Exception {
        String token = csrf(get("/npcs/spots?agent=" + TestConfig.AGENT_ID).body());
        post("/agents/action", "_csrf=" + token + "&type=npc.spot.list&agent="
                + TestConfig.AGENT_ID + "&return=/npcs/spots");
        HttpResponse<String> poll = client.send(HttpRequest.newBuilder(uri("/agent/v1/actions"))
                .header("Authorization", "Bearer " + TestConfig.AGENT_TOKEN)
                .header("X-Agent-Id", TestConfig.AGENT_ID).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        Matcher m = Pattern.compile("\"id\"\\s*:\\s*\"([0-9a-f-]{36})\"").matcher(poll.body());
        if (!m.find()) {
            throw new IllegalStateException("aucune action à livrer : " + poll.body());
        }
        String id = m.group(1);
        String result = "{\"action_id\":\"" + id + "\",\"status\":\"SUCCESS\",\"value\":\"2\","
                + "\"message\":\"2 emplacement(s)\",\"details\":" + details + "}";
        client.send(HttpRequest.newBuilder(uri("/agent/v1/actions/" + id + "/result"))
                .header("Authorization", "Bearer " + TestConfig.AGENT_TOKEN)
                .header("X-Agent-Id", TestConfig.AGENT_ID)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(result)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private long count(String type) {
        return store.recentActions(TestConfig.AGENT_ID, 200).stream()
                .filter(a -> a.type().equals(type)).count();
    }

    private AgentActionRow queued(String type) {
        return store.recentActions(TestConfig.AGENT_ID, 200).stream()
                .filter(a -> a.type().equals(type)).findFirst().orElseThrow();
    }

    private static String enc(String raw) {
        return URLEncoder.encode(raw, StandardCharsets.UTF_8);
    }

    private static String csrf(String html) {
        Matcher m = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"").matcher(html);
        if (!m.find()) {
            throw new IllegalStateException("aucun jeton CSRF dans la page");
        }
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
                    .map(e -> e.getKey() + "=" + e.getValue())
                    .reduce((x, y) -> x + "; " + y).orElse(""));
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
