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

/** Issue #175 — section « Boss communautaires du Wild » de la page /mobs. */
class CommunityBossPageTest {

    private static final String ENTITY = "123e4567-e89b-12d3-a456-426614174000";
    private static final String STATUS = "{\"available\":true,\"enabled\":true,\"intervalMinutes\":15,"
            + "\"maxActive\":1,\"participationRadius\":64,\"nextDue\":1600000,\"lastSpawn\":400000,\"lastAttempt\":400000,"
            + "\"lastOutcome\":\"Plafond atteint (1/1 boss actif(s)) : aucune nouvelle apparition.\","
            + "\"now\":1000000,\"instances\":[{\"entityId\":\"" + ENTITY + "\",\"profileId\":\"rpgquest:ogre\","
            + "\"profileName\":\"Ogre des collines\",\"world\":\"wild\",\"x\":120,\"y\":70,\"z\":-40,"
            + "\"biome\":\"minecraft:dark_forest\",\"waypointName\":\"Pierre aux Corbeaux\","
            + "\"waypointDistance\":130,\"waypointDirection\":\"nord-est\",\"spawnedAt\":400000,\"test\":true}],"
            + "\"profiles\":[{\"id\":\"rpgquest:ogre\",\"displayName\":\"<red>Ogre des collines</red>\","
            + "\"enabled\":true,\"bossMode\":\"COMMUNITY\",\"communityWeight\":1,\"worlds\":[],\"biomes\":[]}]}";

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
    void sectionInvitesToLoadTheStatusBeforeAnyReading() throws Exception {
        start();
        login();
        String page = get("/mobs/community?agent=" + TestConfig.AGENT_ID).body();
        assertTrue(page.contains("Boss communautaires du Wild"), "section présente");
        assertTrue(page.contains("Rafraîchir l&#39;état") || page.contains("Rafraîchir l'état"), "bouton de relevé");
        assertTrue(page.contains("État pas encore chargé"), "aucun état inventé");
    }

    @Test
    void loadedStatusShowsPlaceWaypointScheduleAndConfirmedRemoval() throws Exception {
        start();
        login();
        runAction("boss.community.status", STATUS);
        String page = get("/mobs/community?agent=" + TestConfig.AGENT_ID).body();

        assertTrue(page.contains("actives"), "scheduler actif");
        assertTrue(page.contains("dans 10 min"), "prochain essai relatif à l'horloge du serveur");
        assertTrue(page.contains("Ogre des collines"));
        assertTrue(page.contains("dark forest"), "biome lisible");
        assertTrue(page.contains("Pierre aux Corbeaux"), "waypoint réel relayé");
        assertTrue(page.contains("nord-est"));
        assertTrue(page.contains("Apparition de test"), "bouton de test immédiat");
        assertTrue(page.contains("name=\"interval_minutes\""), "intervalle modifiable");
        assertTrue(page.contains("name=\"max_active\""), "plafond modifiable");
        assertTrue(page.contains("name=\"participation_radius\""), "distance de participation modifiable (#176)");
        assertTrue(page.contains("name=\"glow\""), "contour lumineux réglable (#471)");
        assertTrue(page.contains("name=\"glow_radius\""), "distance d'allumage réglable (#471)");
        assertTrue(page.contains("name=\"surface_only\""), "surface uniquement réglable (#471)");
        assertTrue(page.contains("N'importe où dans le Wild"), "apparition sans joueur dans le Wild (#471)");
        assertTrue(page.contains("value=\"64\""), "valeur courante relue");
        assertTrue(page.contains("Retirer ce boss"));
        assertTrue(page.contains("Retirer toutes les apparitions de test"));
        assertTrue(page.contains("name=\"confirm\" value=\"true\" required"), "retrait avec case de confirmation");
        // L'identifiant technique ne sert qu'au formulaire, jamais au premier plan.
        assertFalse(page.replace("value=\"" + ENTITY + "\"", "").contains(ENTITY), "UUID jamais affiché");
    }

    @Test
    void recentDefeatsListParticipantsVerdictsAndDeclaredRewards() throws Exception {
        start();
        login();
        runAction("boss.community.status", STATUS.substring(0, STATUS.length() - 1)
                + ",\"recentDefeats\":[{\"entityId\":\"" + ENTITY + "\",\"profileId\":\"rpgquest:ogre\","
                + "\"profileName\":\"Ogre des collines\",\"defeatedAt\":940000,\"world\":\"wild\","
                + "\"biome\":\"minecraft:plains\",\"radius\":100,\"killerName\":\"Bob\",\"test\":false,"
                + "\"participants\":[{\"uuid\":\"u1\",\"name\":\"Alice\",\"eligible\":true,\"reason\":\"\"},"
                + "{\"uuid\":\"u2\",\"name\":\"Carol\",\"eligible\":false,\"reason\":\"trop loin du boss (143 blocs, 100 au plus)\"}],"
                + "\"rewardKeys\":[\"boss_ogre\"],\"rewardStatus\":\"1 droit(s) déclaré(s) pour 1 joueur(s) éligible(s).\"}]}");
        String page = get("/mobs/community?agent=" + TestConfig.AGENT_ID).body();

        assertTrue(page.contains("Victoires récentes et participants"));
        assertTrue(page.contains("dernier coup : Bob"));
        assertTrue(page.contains("Alice"), page);
        assertTrue(page.contains("trop loin du boss (143 blocs, 100 au plus)"));
        assertTrue(page.contains("récompenses à 100 blocs au plus"));
        assertTrue(page.contains("boss_ogre"));
        assertTrue(page.contains("1 droit(s) déclaré(s)"));
    }

    @Test
    void rewardTablesShowModeChancesExplanationAndDraws() throws Exception {
        start();
        login();
        runAction("boss.community.status", STATUS.substring(0, STATUS.length() - 1)
                + ",\"recentDefeats\":[{\"entityId\":\"" + ENTITY + "\",\"profileId\":\"rpgquest:ogre\","
                + "\"profileName\":\"Ogre\",\"defeatedAt\":940000,\"world\":\"wild\",\"biome\":\"minecraft:plains\","
                + "\"killerName\":\"\",\"test\":false,\"participants\":[],\"rewardKeys\":[\"table:butin_ogre\"],"
                + "\"rewardStatus\":\"ok\",\"draws\":[\"Alice : table « butin_ogre » v1a2b3c4d → Gemme\"]}],"
                + "\"rewardTables\":[{\"id\":\"butin_ogre\",\"label\":\"Butin de l'ogre\",\"mode\":\"single\","
                + "\"modeLabel\":\"Une récompense maximum\",\"enabled\":true,\"version\":\"1a2b3c4d\","
                + "\"totalChance\":\"37,5 %\",\"explanation\":\"aucun gain : 62,5 %.\",\"entries\":["
                + "{\"id\":\"gem\",\"rewardKey\":\"gem\",\"rewardLabel\":\"Gemme\",\"rewardKnown\":true,\"chance\":\"12,5 %\"},"
                + "{\"id\":\"x\",\"rewardKey\":\"nope\",\"rewardLabel\":\"nope\",\"rewardKnown\":false,\"chance\":\"25 %\"}]}]}");
        String page = get("/mobs/community?agent=" + TestConfig.AGENT_ID).body();

        assertTrue(page.contains("Tables de récompenses aléatoires"), page);
        assertTrue(page.contains("Butin de l&#39;ogre") || page.contains("Butin de l'ogre"), page);
        assertTrue(page.contains("Une récompense maximum"));
        assertTrue(page.contains("12,5 %"));
        assertTrue(page.contains("aucun gain : 62,5 %"));
        assertTrue(page.contains("récompense inconnue"));
        assertTrue(page.contains("Tirages : Alice"), page);
    }

    /** Issue #177 tranche 2 : récompenses de participation choisies dans une liste recherchable. */
    @Test
    void participationRewardsAreChosenFromCatalogRewardsAndTables() throws Exception {
        start();
        login();
        runAction("reward.catalog.status", RewardTablesPageTest.status(true, true, true, "[]",
                "[" + RewardTablesPageTest.TABLE + "]"));
        String page = get("/mobs?agent=" + TestConfig.AGENT_ID).body();
        assertTrue(page.contains("<datalist id=\"dl-boss-rewards\"><option value=\"gem\" label=\"Gemme rare\">"), page);
        assertTrue(page.contains("<option value=\"table:butin_ogre\" label=\"Tirage : Butin de l&#39;ogre\">"), page);
        assertTrue(page.contains("data-multisel=\"participation_rewards\""), "multisélection");
        assertTrue(page.contains("data-multisel-exact"), "table:x et x restent distincts");
        assertTrue(page.contains("href=\"/rewards\""), "lien vers l'éditeur de tables");
    }

    @Test
    void withoutAnyDefeatTheSectionSaysSoHonestly() throws Exception {
        start();
        login();
        runAction("boss.community.status", STATUS);
        assertTrue(get("/mobs/community?agent=" + TestConfig.AGENT_ID).body()
                .contains("Aucun boss communautaire vaincu depuis le dernier démarrage"));
    }

    @Test
    void nearPlayerOffersAnOnlinePlayerFromThePlayersPageReadingInTheWild() throws Exception {
        start();
        login();
        runAction("boss.community.status", STATUS);
        runAction("player.catalog", "{\"players\":[{\"uuid\":\"u1\",\"name\":\"LoDyMcFly\",\"online\":true,"
                + "\"world\":\"wild\"},{\"uuid\":\"u2\",\"name\":\"Bob\",\"online\":true,\"world\":\"world_hub\"},"
                + "{\"uuid\":\"u3\",\"name\":\"Carol\",\"online\":false}]}");
        String page = get("/mobs/community?agent=" + TestConfig.AGENT_ID).body();
        String select = page.substring(page.indexOf("id=\"cb-t-pl\""), page.indexOf("</select>", page.indexOf("id=\"cb-t-pl\"")));

        assertTrue(select.contains("<option value=\"LoDyMcFly\">"), "joueur en ligne dans le Wild proposé");
        assertFalse(select.contains("Bob"), "joueur hors du Wild non proposé");
        assertFalse(select.contains("Carol"), "joueur hors ligne non proposé");
        assertTrue(page.contains("1 joueur(s) dans le Wild"), "décompte expliqué");
        assertTrue(page.contains("Bob (world_hub)"), "joueur hors Wild nommé avec son monde");
        assertTrue(page.contains("Actualiser les joueurs connectés"), "relevé disponible sur la vue");
        assertTrue(page.contains("value=\"player.catalog\""), "même relevé que la page Joueurs");
    }

    @Test
    void nearPlayerExplainsWhenNoOneIsInTheWild() throws Exception {
        start();
        login();
        runAction("boss.community.status", STATUS);
        runAction("player.catalog", "{\"players\":[{\"uuid\":\"u2\",\"name\":\"Bob\",\"online\":true,"
                + "\"world\":\"world_hub\"}]}");
        String page = get("/mobs/community?agent=" + TestConfig.AGENT_ID).body();
        assertTrue(page.contains("Aucun joueur connecté dans le Wild"), page);
        assertTrue(page.contains("Hors du Wild, donc non proposés : Bob (world_hub)"));
    }

    @Test
    void nearPlayerExplainsMissingPlayerData() throws Exception {
        start();
        login();
        runAction("boss.community.status", STATUS);
        String page = get("/mobs/community?agent=" + TestConfig.AGENT_ID).body();
        assertTrue(page.contains("Liste des joueurs connectés pas encore chargée"), "données absentes expliquées");
        assertTrue(page.contains("N'importe où dans le Wild"), "le choix sans joueur reste possible");
    }

    @Test
    void relativeTimesReadNaturally() {
        org.junit.jupiter.api.Assertions.assertEquals("dans 15 min", AgentPages.relative(15 * 60_000L));
        org.junit.jupiter.api.Assertions.assertEquals("il y a 2 h", AgentPages.relative(-2 * 3_600_000L));
        org.junit.jupiter.api.Assertions.assertEquals("dans moins d'une minute", AgentPages.relative(10_000L));
    }

    private void runAction(String type, String details) throws Exception {
        String token = csrf(get("/mobs/community?agent=" + TestConfig.AGENT_ID).body());
        post("/agents/action", "_csrf=" + token + "&type=" + type + "&agent=" + TestConfig.AGENT_ID
                + "&return=/mobs");
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
                "RPGQuest", "0.1.0-SNAPSHOT", "ONLINE", 0, 20, 60,
                "{\"wild\":{\"name\":\"wild\",\"loaded\":true}}", "{}"));
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
