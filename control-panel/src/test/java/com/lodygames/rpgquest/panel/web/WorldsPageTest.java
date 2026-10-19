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
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Issue #560 (#331-W1) — page « Mondes » en lecture seule, d'après le relevé world.list. */
class WorldsPageTest {

    private static String world(String name, String env, boolean loaded, String manager, boolean main,
                                String roles, boolean essential, String links, String issues) {
        return "{\"name\":\"" + name + "\",\"loaded\":" + loaded + ",\"manager\":\"" + manager + "\",\"main\":" + main
                + ",\"essential\":" + essential + ",\"roles\":" + roles + ",\"multiverseKnown\":" + "MULTIVERSE".equals(manager)
                + ",\"rpgquestManaged\":" + "RPGQUEST".equals(manager) + ",\"environment\":\"" + env + "\","
                + "\"worldType\":" + (loaded ? "\"NORMAL\"" : "null") + ",\"seed\":" + (loaded ? "\"-4172093381123\"" : "null")
                + ",\"structures\":" + (loaded ? "true" : "null") + ",\"players\":" + (loaded ? "0" : "null")
                + ",\"difficulty\":\"EASY\",\"pvp\":true,\"hardcore\":false,\"spawn\":\"0, 64, 0\",\"generator\":null,"
                + "\"generatorSettings\":null,\"autoLoad\":true,\"links\":" + links + ",\"issues\":" + issues + "}";
    }

    private static final String NO_LINKS = "{\"portalsFrom\":[],\"portalsTo\":[],\"sites\":[],\"waypoints\":0,"
            + "\"autoWaypoints\":false,\"mobs\":[],\"npcSpots\":[],\"cities\":[]}";

    static final String RELEVE = "{\"mainWorld\":\"world\",\"roles\":{\"hub\":\"world_hub\",\"wild\":\"wild\","
            + "\"claims\":\"claims\"},\"multiverse\":{\"available\":true,\"version\":\"5.7.3\",\"reason\":\"\"},"
            + "\"worlds\":["
            + world("world", "NORMAL", true, "SERVER", true, "[]", true, NO_LINKS, "[]") + ","
            + world("world_hub", "NORMAL", true, "MULTIVERSE", false, "[\"HUB\"]", true,
                    "{\"portalsFrom\":[{\"id\":\"hub_to_wild\",\"world\":\"world_hub\",\"destinationWorld\":\"wild\","
                    + "\"enabled\":true,\"strategy\":\"RANDOM_SAFE\"}],\"portalsTo\":[],\"sites\":[{\"id\":\"buildsite_0001\","
                    + "\"name\":\"Place du marché\"}],\"waypoints\":3,\"autoWaypoints\":true,\"mobs\":[],\"npcSpots\":[],"
                    + "\"cities\":[]}", "[]") + ","
            + world("wild", "NORMAL", true, "RPGQUEST", false, "[\"WILD\"]", true, NO_LINKS, "[]") + ","
            + world("world_nether", "NETHER", true, "SERVER", false, "[]", false, NO_LINKS, "[]") + ","
            + world("claims", "NORMAL", false, "MULTIVERSE", false, "[\"CLAIMS\"]", true, NO_LINKS,
                    "[{\"code\":\"WORLD_ROLE_NOT_LOADED\",\"severity\":\"error\",\"message\":\"Le monde « claims » est "
                    + "configuré comme CLAIMS mais n'est pas chargé sur le serveur.\"}]")
            + "],\"issues\":[]}";

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
    void thePageIsInTheWorldNavigationGroupAndInvitesToRefresh() throws Exception {
        start();
        login();
        String page = get("/worlds?agent=" + TestConfig.AGENT_ID).body();
        assertTrue(page.contains("href=\"/worlds\""), "entrée de navigation");
        assertTrue(page.contains(">Monde<"), "groupe de navigation Monde");
        assertTrue(page.contains("Aucun relevé chargé"), "aucun monde inventé");
        assertTrue(page.contains("value=\"world.list\""), "seul Rafraîchir est proposé");
    }

    @Test
    void worldsShowManagerRoleEssentialBadgeAndLinks() throws Exception {
        start();
        login();
        runAction("world.list", RELEVE);
        String page = get("/worlds?agent=" + TestConfig.AGENT_ID).body();
        assertTrue(page.contains("géré par RPGQuest (worlds.yml)"), "wild géré par RPGQuest");
        assertTrue(page.contains("géré par Multiverse"));
        assertTrue(page.contains("géré par le serveur (monde principal)"));
        assertTrue(page.contains("<span class=\"role hub\">Hub</span>"));
        assertTrue(page.contains("Essentiel</span>"));
        assertTrue(page.contains("Nether · génération Nether"));
        assertTrue(page.contains("départ de 1 portail(s)"), page);
        assertTrue(page.contains("Non chargé"));
        assertTrue(page.contains("Avec problème · 1"));
        assertTrue(page.contains("Multiverse-Core 5.7.3 lu"));
    }

    @Test
    void theFicheIsReadOnlyAndExplainsProblems() throws Exception {
        start();
        login();
        runAction("world.list", RELEVE);
        String hub = get("/worlds?agent=" + TestConfig.AGENT_ID + "&world=world_hub").body();
        assertTrue(hub.contains("Fixé à la création") && hub.contains("-4172093381123"), "graine affichée");
        assertTrue(hub.contains("hub_to_wild") && hub.contains("point sûr aléatoire"), "portail lié");
        assertTrue(hub.contains("Place du marché"), "emplacement lié");
        assertTrue(hub.contains("génération automatique : <strong>active</strong>"));
        String claims = get("/worlds?agent=" + TestConfig.AGENT_ID + "&world=claims").body();
        assertTrue(claims.contains("Monde à rôle non chargé"), "aide du diagnostic");
        assertTrue(claims.contains("/docs/mondes#monde-a-role-non-chargé") || claims.contains("/docs/mondes#"), claims);
        assertTrue(claims.contains("inconnue"), "graine illisible = inconnue");
        String missing = get("/worlds?agent=" + TestConfig.AGENT_ID + "&world=lody_test_flat").body();
        assertTrue(missing.contains("n'apparaît pas dans le dernier relevé"));
    }

    @Test
    void noMutatingWorldActionIsExposedAnywhereOnThePage() throws Exception {
        start();
        login();
        runAction("world.list", RELEVE);
        for (String path : new String[] {"/worlds?agent=" + TestConfig.AGENT_ID,
                "/worlds?agent=" + TestConfig.AGENT_ID + "&world=world_hub"}) {
            String page = get(path).body();
            Matcher m = Pattern.compile("name=\"type\" value=\"([^\"]+)\"").matcher(page);
            while (m.find()) {
                assertFalse(m.group(1).startsWith("world.") && !m.group(1).equals("world.list"),
                        "action mutative exposée : " + m.group(1));
            }
            assertFalse(page.contains("Créer un monde") || page.contains("Décharger") || page.contains(">Charger<"));
        }
    }

    @Test
    void aMutatingWorldActionIsRefusedByTheCatalog() throws Exception {
        start();
        login();
        String token = csrf(get("/worlds?agent=" + TestConfig.AGENT_ID).body());
        HttpResponse<String> res = post("/agents/action", "_csrf=" + token + "&type=world.create&agent="
                + TestConfig.AGENT_ID + "&return=/worlds&name=lody_test_flat");
        assertFalse(pollActions().contains("world.create"), "aucune action world.create mise en file");
        assertTrue(res.statusCode() >= 300, "refusé / redirigé");
    }

    private String pollActions() throws Exception {
        return client.send(HttpRequest.newBuilder(uri("/agent/v1/actions"))
                .header("Authorization", "Bearer " + TestConfig.AGENT_TOKEN)
                .header("X-Agent-Id", TestConfig.AGENT_ID).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    private void runAction(String type, String details) throws Exception {
        String token = csrf(get("/worlds?agent=" + TestConfig.AGENT_ID).body());
        post("/agents/action", "_csrf=" + token + "&type=" + type + "&agent=" + TestConfig.AGENT_ID
                + "&return=/worlds");
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
