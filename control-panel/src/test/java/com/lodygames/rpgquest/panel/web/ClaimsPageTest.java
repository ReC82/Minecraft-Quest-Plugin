package com.lodygames.rpgquest.panel.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.panel.agent.AgentActionRow;
import com.lodygames.rpgquest.panel.agent.AgentStore;
import com.lodygames.rpgquest.panel.audit.InMemoryAuditLog;
import com.lodygames.rpgquest.panel.authz.Permission;
import com.lodygames.rpgquest.panel.authz.Role;
import com.lodygames.rpgquest.panel.bridge.BridgeClient;
import com.lodygames.rpgquest.panel.support.TestConfig;
import com.lodygames.rpgquest.panel.users.McBridgeRepository;
import com.lodygames.rpgquest.panel.users.SqliteUserRepository;
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
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Issue #637 — page « Claims » : relevé claim.list en lecture, et « M'y téléporter » qui ne déplace
 * jamais que le personnage lié à la session, après confirmation, une seule fois.
 */
class ClaimsPageTest {

    static final String OWNER = "11111111-1111-1111-1111-111111111111";
    static final String STRANGER = "22222222-2222-2222-2222-222222222222";
    static final String ADMIN_MC = "33333333-3333-3333-3333-333333333333";

    static final String RELEVE = "{\"claimsWorld\":\"claims\",\"owners\":2,\"claims\":["
            + "{\"id\":\"main_lily\",\"owner\":\"" + OWNER + "\",\"ownerName\":\"Lily\",\"main\":true,"
            + "\"world\":\"claims\",\"worldLoaded\":true,\"inClaimsWorld\":true,\"min\":\"-5, 60, -5\","
            + "\"max\":\"4, 120, 4\",\"reservedMin\":\"-50, -50\",\"reservedMax\":\"49, 49\",\"width\":10,"
            + "\"depth\":10,\"height\":61,\"center\":\"0, 0\",\"tier\":\"TIER_2\",\"members\":0,\"publicRedstone\":false},"
            + "{\"id\":\"atelier\",\"owner\":\"" + OWNER + "\",\"ownerName\":\"Lily\",\"main\":false,"
            + "\"world\":\"old_world\",\"worldLoaded\":false,\"inClaimsWorld\":false,\"min\":\"0, 0, 0\","
            + "\"max\":\"6, 10, 2\",\"reservedMin\":\"0, 0\",\"reservedMax\":\"6, 2\",\"width\":7,"
            + "\"depth\":3,\"height\":11,\"center\":\"3, 1\",\"tier\":null,\"members\":1,\"publicRedstone\":true},"
            + "{\"id\":\"main_inconnu\",\"owner\":\"" + STRANGER + "\",\"ownerName\":null,\"main\":true,"
            + "\"world\":\"claims\",\"worldLoaded\":true,\"inClaimsWorld\":true,\"min\":\"100, 60, 100\","
            + "\"max\":\"104, 70, 104\",\"reservedMin\":\"52, 52\",\"reservedMax\":\"151, 151\",\"width\":5,"
            + "\"depth\":5,\"height\":11,\"center\":\"102, 102\",\"tier\":\"TIER_1\",\"members\":0,\"publicRedstone\":false}]}";

    @TempDir
    Path tmp;

    private PanelApp app;
    private int port;
    private HttpClient client;
    private AgentStore store;
    private String db;
    private final Map<String, String> jar = new LinkedHashMap<>();

    @AfterEach
    void tearDown() {
        if (app != null) {
            app.stop();
        }
    }

    @Test
    void thePageIsInTheNavigationAndInventsNothingBeforeTheFirstReading() throws Exception {
        start();
        login();
        String page = get("/claims?agent=" + TestConfig.AGENT_ID).body();
        assertTrue(page.contains("href=\"/claims\""), "entrée de navigation");
        assertTrue(page.contains("Aucun relevé chargé"), "aucun claim inventé");
        assertTrue(page.contains("value=\"claim.list\""), "Rafraîchir proposé");
    }

    @Test
    void everyClaimIsListedWithItsOwnerAndAnUnknownNameIsNeverInvented() throws Exception {
        start();
        login();
        runAction("claim.list", RELEVE);
        String page = get("/claims?agent=" + TestConfig.AGENT_ID).body();
        assertTrue(page.contains("main_lily") && page.contains("atelier") && page.contains("main_inconnu"));
        assertTrue(page.contains("Lily") && page.contains("joueur 22222222…"), "pseudo inconnu : UUID abrégé");
        assertTrue(page.contains("Principaux · 2") && page.contains("Monde non chargé · 1"));
        assertTrue(page.contains("palier 2") && page.contains("forme libre (sans palier)"));
        assertTrue(page.contains("data-filter-text=\"Lily " + OWNER), "recherche par pseudo et UUID");
        String mine = get("/claims?agent=" + TestConfig.AGENT_ID + "&owner=" + OWNER).body();
        assertTrue(mine.contains("Claims de Lily") && mine.contains("atelier") && !mine.contains("main_inconnu"));
        String none = get("/claims?agent=" + TestConfig.AGENT_ID + "&owner=" + ADMIN_MC).body();
        assertTrue(none.contains("ne possède aucun claim"), "joueur sans claim : dit, pas inventé");
    }

    @Test
    void theFicheShowsTheClaimAndNeverTeleportsWithoutALinkedCharacter() throws Exception {
        start();
        login();
        runAction("claim.list", RELEVE);
        String fiche = get("/claims?agent=" + TestConfig.AGENT_ID + "&claim=main_lily").body();
        assertTrue(fiche.contains("Claim de Lily") && fiche.contains(OWNER));
        assertTrue(fiche.contains("-5, 60, -5") && fiche.contains("4, 120, 4") && fiche.contains("10 × 10 blocs"));
        assertTrue(fiche.contains("Aucun personnage Minecraft n'est lié"), "pas de personnage lié : aucun formulaire");
        assertFalse(fiche.contains("value=\"claim.teleport\""));
        assertTrue(get("/claims?agent=" + TestConfig.AGENT_ID + "&claim=disparu").body()
                .contains("n'apparaît pas dans le dernier relevé"));

        String token = csrf(fiche);
        post("/agents/action", "_csrf=" + token + "&type=claim.teleport&agent=" + TestConfig.AGENT_ID
                + "&return=/claims&player=" + OWNER + "&claim=main_lily&owner=" + OWNER + "&confirm=true");
        assertTrue(teleports().isEmpty(), "sans personnage lié, rien n'est envoyé — pas même le propriétaire");
    }

    @Test
    void openingTheFicheShowsTargetAndDestinationButSendsNothing() throws Exception {
        start();
        login();
        linkOwnerTo(ADMIN_MC, "LodyAdmin");
        runAction("claim.list", RELEVE);
        String fiche = get("/claims?agent=" + TestConfig.AGENT_ID + "&claim=main_lily").body();
        assertTrue(fiche.contains("value=\"claim.teleport\""));
        assertTrue(fiche.contains("Personnage déplacé") && fiche.contains("LodyAdmin") && fiche.contains(ADMIN_MC));
        assertTrue(fiche.contains("Destination") && fiche.contains("main_lily"));
        assertTrue(fiche.contains("name=\"confirm\" value=\"true\" required"), "confirmation explicite");
        get("/claims?agent=" + TestConfig.AGENT_ID + "&claim=main_lily");
        assertTrue(teleports().isEmpty(), "jamais de téléportation à l'ouverture ni au rafraîchissement");
        String unloaded = get("/claims?agent=" + TestConfig.AGENT_ID + "&claim=atelier").body();
        assertTrue(unloaded.contains("n'est pas chargé : téléportation impossible"));
        assertFalse(unloaded.contains("value=\"claim.teleport\""));
    }

    @Test
    void theTeleportAlwaysMovesTheLinkedCharacterOnceAndNeverTheOwner() throws Exception {
        start();
        login();
        linkOwnerTo(ADMIN_MC, "LodyAdmin");
        runAction("claim.list", RELEVE);
        String token = csrf(get("/claims?agent=" + TestConfig.AGENT_ID + "&claim=main_lily").body());
        String forged = "_csrf=" + token + "&type=claim.teleport&agent=" + TestConfig.AGENT_ID
                + "&return=/claims&player=" + OWNER + "&claim=main_lily&owner=" + OWNER + "&focus=main_lily";

        HttpResponse<String> noConfirm = post("/agents/action", forged);
        assertTrue(noConfirm.headers().firstValue("Location").orElse("").contains("err="));
        assertTrue(teleports().isEmpty(), "sans confirmation, rien n'est envoyé");

        HttpResponse<String> first = post("/agents/action", forged + "&confirm=true");
        assertTrue(first.headers().firstValue("Location").orElse("").contains("focus=main_lily"), "la fiche reste ouverte");
        post("/agents/action", forged + "&confirm=true");
        List<AgentActionRow> sent = teleports();
        assertEquals(1, sent.size(), "double clic : une seule téléportation");
        assertEquals(ADMIN_MC, sent.get(0).params().get("player"), "toujours le personnage lié, jamais le propriétaire");
        assertEquals("main_lily", sent.get(0).params().get("claim"));
        assertEquals(OWNER, sent.get(0).params().get("owner"));
    }

    @Test
    void readingAndTeleportingAreTwoDistinctRights() {
        assertTrue(Role.ADMIN.has(Permission.CLAIM_READ) && Role.ADMIN.has(Permission.CLAIM_TELEPORT));
        assertTrue(Role.TESTER.has(Permission.CLAIM_READ));
        assertFalse(Role.TESTER.has(Permission.CLAIM_TELEPORT), "un testeur consulte sans se téléporter");
        assertFalse(Role.READ_ONLY.has(Permission.CLAIM_READ), "les positions des bases restent hors lecture seule");
        assertFalse(Role.BUILDER.has(Permission.CLAIM_TELEPORT) || Role.CONTENT_EDITOR.has(Permission.CLAIM_TELEPORT));
    }

    private List<AgentActionRow> teleports() {
        return store.recentActions(TestConfig.AGENT_ID, 50).stream()
                .filter(a -> "claim.teleport".equals(a.type())).toList();
    }

    private void linkOwnerTo(String mcUuid, String mcName) {
        String userId = new SqliteUserRepository(db).findByUsername(TestConfig.OWNER_USERNAME).orElseThrow().id();
        new McBridgeRepository(db).link(new McBridgeRepository.Link(userId, mcUuid, mcName, Instant.now(), "test"));
    }

    private String pollActions() throws Exception {
        return client.send(HttpRequest.newBuilder(uri("/agent/v1/actions"))
                .header("Authorization", "Bearer " + TestConfig.AGENT_TOKEN)
                .header("X-Agent-Id", TestConfig.AGENT_ID).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    private void runAction(String type, String details) throws Exception {
        String token = csrf(get("/claims?agent=" + TestConfig.AGENT_ID).body());
        post("/agents/action", "_csrf=" + token + "&type=" + type + "&agent=" + TestConfig.AGENT_ID
                + "&return=/claims");
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
        db = tmp.resolve("cp.db").toString();
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
