package com.lodygames.rpgquest.panel.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Issue #735 — page « Classements du site » : liste filtrable, gestes directs, formulaire guidé
 * normalisé avant la file, aperçu agrégé et podium fictif — jamais un pseudo réel.
 */
class LeaderboardsPageTest {

    static final String BOARDS = "[{\"id\":\"mobs_week\",\"name\":\"Monstres vaincus cette semaine\","
            + "\"category\":\"combat\",\"metric\":\"ENTITY_KILL\",\"filter\":{\"entity-family\":[\"boss\",\"hostile\"]},"
            + "\"period\":\"weekly\",\"size\":10,\"status\":\"enabled\",\"order\":10,\"visible\":true,"
            + "\"featured\":true,\"version\":\"0a0b0c0d0e0f\",\"window\":{\"key\":\"2026-W43\","
            + "\"start\":\"2026-10-18T22:00:00Z\",\"end\":\"2026-10-25T23:00:00Z\"}},"
            + "{\"id\":\"ores_draft\",\"name\":\"Mineurs (brouillon)\",\"description\":\"Pour les mineurs\","
            + "\"category\":\"recolte\",\"icon\":\"ore\",\"metric\":\"BLOCK_BREAK\",\"period\":\"monthly\","
            + "\"size\":5.0,\"status\":\"draft\",\"order\":20,\"visible\":false,\"featured\":false,"
            + "\"version\":\"1a1b1c1d1e1f\"}]";

    static String status(String source, boolean writable, String boards) {
        return "{\"available\":true,\"source\":\"" + source + "\",\"valid\":true,\"errors\":[],"
                + "\"timezone\":\"Europe/Brussels\",\"webExportEnabled\":false,\"fileExists\":"
                + !"bundled".equals(source) + ",\"writable\":" + writable + ",\"readOnlyReason\":"
                + (writable ? "null" : "\"Les classements sont définis à la main dans statistics.yml\"")
                + ",\"boards\":" + boards + ",\"events\":[{\"id\":\"halloween\",\"name\":\"Halloween\","
                + "\"start\":\"2026-10-31T00:00:00Z\",\"end\":\"2026-11-02T00:00:00Z\",\"state\":\"PLANNED\"}],"
                + "\"categories\":[\"combat\"],\"families\":{\"entity-family\":[{\"id\":\"zombies\","
                + "\"label\":\"Zombies\",\"size\":5},{\"id\":\"hostile\",\"label\":\"Monstres hostiles\",\"size\":40}],"
                + "\"block-family\":[{\"id\":\"ores\",\"label\":\"Minerais\",\"size\":20}]},"
                + "\"metrics\":[{\"id\":\"ENTITY_KILL\",\"dated\":true,\"filters\":[\"entity\"],"
                + "\"trackedSince\":\"2026-10-16T08:00:00Z\"},{\"id\":\"BLOCK_BREAK\",\"dated\":true,"
                + "\"filters\":[\"block\"],\"trackedSince\":null}]}";
    }

    @TempDir
    Path tmp;

    private PanelApp app;
    private int port;
    private HttpClient client;
    private AgentStore store;
    private final Map<String, String> jar = new LinkedHashMap<>();

    @AfterEach
    void tearDown() {
        if (app != null) {
            app.stop();
        }
    }

    @Test
    void thePageIsInTheNavigationAndInvitesToLoadTheStatus() throws Exception {
        start();
        login();
        String page = get("/leaderboards?agent=" + TestConfig.AGENT_ID).body();
        assertTrue(page.contains("href=\"/leaderboards\""), "entrée de navigation");
        assertTrue(page.contains("État pas encore chargé"), "aucun état inventé");
        assertFalse(page.contains("Nouveau classement"));
    }

    @Test
    void boardsAreListedWithPlainWordsStatusesAndActions() throws Exception {
        start();
        login();
        runAction("leaderboard.catalog.status", status("bundled", true, BOARDS));
        String page = get("/leaderboards?agent=" + TestConfig.AGENT_ID).body();
        assertTrue(page.contains("Catalogue <strong>par défaut</strong>"), page);
        assertTrue(page.contains("Le site public n'affiche encore aucun classement réel"), "décision #55 rappelée");
        assertTrue(page.contains("Monstres et créatures vaincus — boss, Monstres hostiles"), "cibles en mots");
        assertTrue(page.contains("Chaque semaine (du lundi 19 octobre 2026 à 00:00 au lundi 26 octobre 2026 à 00:00)"),
                "bornes dans le fuseau de Bruxelles");
        assertTrue(page.contains("À l'accueil"));
        assertTrue(page.contains("Caché du site"));
        assertTrue(page.contains("Mettre en pause") && page.contains("Activer"), "geste selon le statut");
        assertTrue(page.contains("Je confirme l'archivage"));
        assertTrue(page.contains("top 5"), "taille relue du JSON sans décimale");
        assertTrue(page.contains("value=\"5\""), "taille du formulaire entière");
        assertTrue(page.contains("Nouveau classement"));
        assertTrue(page.contains("Mesure suivie depuis") || page.contains("suivie depuis le 16 octobre 2026"));

        String filtered = get("/leaderboards?agent=" + TestConfig.AGENT_ID + "&statut=draft").body();
        assertFalse(filtered.contains("id=\"lb-mobs_week\""), "filtre par statut");
        assertTrue(filtered.contains("id=\"lb-ores_draft\""));
    }

    @Test
    void aListInStatisticsYmlIsShownReadOnly() throws Exception {
        start();
        login();
        runAction("leaderboard.catalog.status", status("statistics_file", false, BOARDS));
        String page = get("/leaderboards?agent=" + TestConfig.AGENT_ID).body();
        assertTrue(page.contains("Lecture seule."), page);
        assertFalse(page.contains("Nouveau classement"));
        assertFalse(page.contains("Mettre en pause"));
    }

    @Test
    void thePreviewShowsOnlyAggregatesAndAFictionalPodium() throws Exception {
        start();
        login();
        runAction("leaderboard.catalog.status", status("managed_file", true, BOARDS));
        runAction("leaderboard.preview&board_id=mobs_week", "{\"available\":true,\"board\":{\"id\":\"mobs_week\",\"name\":"
                + "\"Monstres vaincus cette semaine\",\"metric\":\"ENTITY_KILL\",\"period\":\"weekly\",\"size\":3,"
                + "\"status\":\"enabled\"},\"timezone\":\"Europe/Brussels\",\"window\":{\"key\":\"2026-W43\","
                + "\"start\":\"2026-10-18T22:00:00Z\",\"end\":\"2026-10-25T23:00:00Z\"},\"previous\":{\"key\":"
                + "\"2026-W42\",\"archived\":true},\"trackedSince\":\"2026-10-16T08:00:00Z\",\"participation\":"
                + "{\"players\":2,\"total\":41},\"warnings\":[\"Échantillon faible : 2 joueur(s) sur la période en cours.\"]}");
        String page = get("/leaderboards?agent=" + TestConfig.AGENT_ID).body();
        assertTrue(page.contains("<strong>2</strong> participant(s)"), page);
        assertTrue(page.contains("Échantillon faible"));
        assertTrue(page.contains("résultats figés (archivés)"));
        assertTrue(page.contains("Données fictives") && page.contains("Demo_Aventurier"), "podium étiqueté fictif");
        assertTrue(page.contains("au dimanche") || page.contains("au lundi 26 octobre 2026"), "fin exclue en heure locale");
    }

    @Test
    void theGuidedFormIsNormalisedBeforeTheQueue() throws Exception {
        start();
        login();
        runAction("leaderboard.catalog.status", status("bundled", true, BOARDS));
        String token = csrf(get("/leaderboards?agent=" + TestConfig.AGENT_ID).body());
        HttpResponse<String> res = post("/agents/action", "_csrf=" + token + "&type=leaderboard.save&agent="
                + TestConfig.AGENT_ID + "&return=/leaderboards&create=true&timezone=Europe/Brussels"
                + "&name=" + URLEncoder.encode("Zombies d'Halloween", StandardCharsets.UTF_8)
                + "&category=combat&icon=skull&metric=ENTITY_KILL&period=custom&size=10&visible=on"
                + "&fam__entity-family__zombies=on&targets=" + URLEncoder.encode("husk, drowned", StandardCharsets.UTF_8)
                + "&start_local=2026-10-31T00:00&end_local=2026-11-02T00:00");
        assertEquals(303, res.statusCode());
        String queued = pollActions();
        assertTrue(queued.contains("\"board_id\":\"zombies_d_halloween\""), queued);
        assertTrue(queued.contains("\"filter\":\"entity-family=zombies;entity=HUSK,DROWNED\""), queued);
        assertTrue(queued.contains("\"start\":\"2026-10-31T00:00+01:00\""), "heure d'hiver : " + queued);
        assertTrue(queued.contains("\"visible\":\"true\"") && queued.contains("\"featured\":\"false\""), queued);
    }

    @Test
    void aMonsterFamilyOnABlockMeasureAndAnUnconfirmedArchiveAreRefused() throws Exception {
        start();
        login();
        runAction("leaderboard.catalog.status", status("bundled", true, BOARDS));
        String token = csrf(get("/leaderboards?agent=" + TestConfig.AGENT_ID).body());
        HttpResponse<String> res = post("/agents/action", "_csrf=" + token + "&type=leaderboard.save&agent="
                + TestConfig.AGENT_ID + "&return=/leaderboards&create=true&name=Test&category=recolte"
                + "&metric=BLOCK_BREAK&period=weekly&size=10&fam__entity-family__zombies=on");
        String location = java.net.URLDecoder.decode(res.headers().firstValue("Location").orElse(""),
                StandardCharsets.UTF_8);
        assertTrue(location.contains("familles de monstres ne s'appliquent"), location);
        res = post("/agents/action", "_csrf=" + token + "&type=leaderboard.status&agent=" + TestConfig.AGENT_ID
                + "&return=/leaderboards&board_id=mobs_week&status=archived");
        location = java.net.URLDecoder.decode(res.headers().firstValue("Location").orElse(""), StandardCharsets.UTF_8);
        assertTrue(location.contains("Je confirme l'archivage"), location);
        assertFalse(pollActions().contains("leaderboard.s"), "rien en file");
    }

    private String pollActions() throws Exception {
        return client.send(HttpRequest.newBuilder(uri("/agent/v1/actions"))
                .header("Authorization", "Bearer " + TestConfig.AGENT_TOKEN)
                .header("X-Agent-Id", TestConfig.AGENT_ID).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    private void runAction(String type, String details) throws Exception {
        String token = csrf(get("/leaderboards?agent=" + TestConfig.AGENT_ID).body());
        post("/agents/action", "_csrf=" + token + "&type=" + type + "&agent=" + TestConfig.AGENT_ID
                + "&return=/leaderboards");
        HttpResponse<String> poll = client.send(HttpRequest.newBuilder(uri("/agent/v1/actions"))
                .header("Authorization", "Bearer " + TestConfig.AGENT_TOKEN)
                .header("X-Agent-Id", TestConfig.AGENT_ID).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        Matcher m = Pattern.compile("\"id\"\\s*:\\s*\"([0-9a-f-]{36})\"").matcher(poll.body());
        if (!m.find()) {
            throw new IllegalStateException("aucune action en file : " + poll.body());
        }
        client.send(HttpRequest.newBuilder(uri("/agent/v1/actions/" + m.group(1) + "/result"))
                .header("Authorization", "Bearer " + TestConfig.AGENT_TOKEN)
                .header("X-Agent-Id", TestConfig.AGENT_ID)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"status\":\"SUCCESS\",\"message\":\"ok\",\"details\":" + details + "}"))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private void start() throws Exception {
        String db = tmp.resolve("cp.db").toString();
        store = new AgentStore(db);
        app = new PanelApp(TestConfig.withAgent(db, "http://127.0.0.1:1/admin/v1"),
                new InMemoryAuditLog(), new BridgeClient(Duration.ofMillis(300), Duration.ofMillis(400)), store);
        port = app.start();
        client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        jar.clear();
        store.saveHeartbeat(new com.lodygames.rpgquest.panel.agent.HeartbeatRecord(
                TestConfig.AGENT_ID, "dev", Instant.now(), Instant.now().toString(), "1",
                "RPGQuest", "0.1.0-SNAPSHOT", "ONLINE", 0, 20, 60, "{}", "{}"));
    }

    private void login() throws Exception {
        String token = csrf(get("/login").body());
        post("/login", "username=" + TestConfig.OWNER_USERNAME + "&password="
                + URLEncoder.encode(TestConfig.OWNER_PASSWORD, StandardCharsets.UTF_8) + "&_csrf=" + token);
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
