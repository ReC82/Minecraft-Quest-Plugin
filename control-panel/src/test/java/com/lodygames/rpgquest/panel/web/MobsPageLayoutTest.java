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
 * Issue #169 — refonte de /mobs : trois vues distinctes, compteurs qui disent leur périmètre, nettoyage
 * des instances de test confirmé avant envoi avec sa portée, refus avant envoi visibles dans l'historique.
 */
class MobsPageLayoutTest {

    private static final String PROFILES = "{\"profiles\":["
            + "{\"id\":\"rpgquest:zombie_speedy\",\"category\":\"SPECIAL\",\"enabled\":true,\"entityType\":\"ZOMBIE\","
            + "\"displayName\":\"<aqua>Zombie Runner</aqua>\",\"spawnChance\":0.5,\"maxPopulation\":3,"
            + "\"alivePopulation\":5,\"loadedPopulation\":5,\"testPopulation\":2,\"communityActive\":0,"
            + "\"abilitiesSummary\":[],\"bossMode\":\"QUEST\",\"yaml\":\"id: x\"},"
            + "{\"id\":\"rpgquest:boss_zombie_entrainement\",\"category\":\"BOSS\",\"enabled\":true,"
            + "\"entityType\":\"ZOMBIE\",\"displayName\":\"Zombie d'entraînement\",\"spawnChance\":0.0,"
            + "\"alivePopulation\":0,\"loadedPopulation\":0,\"testPopulation\":0,\"communityActive\":1,"
            + "\"abilitiesSummary\":[],\"bossMode\":\"COMMUNITY\",\"yaml\":\"id: y\"}],"
            + "\"spawnSettings\":{\"enabled\":true,\"chance\":1.0,\"maxSimultaneousSpecial\":null},"
            + "\"hasIssues\":false,\"issues\":[]";
    private static final String STATUS = "{\"available\":true,\"enabled\":true,\"intervalMinutes\":15,"
            + "\"maxActive\":1,\"participationRadius\":100,\"now\":1000000,\"lastSpawn\":0,\"instances\":[],"
            + "\"profiles\":[],\"rewardTables\":[]}";

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
    void eachViewShowsOnlyItsOwnSectionWithTheTabsAndTheSummary() throws Exception {
        start();
        login();
        runAction("mob.list", PROFILES + ",\"testInstances\":2}");
        runAction("boss.community.status", STATUS);
        String profiles = get("/mobs?agent=" + TestConfig.AGENT_ID).body();
        String community = get("/mobs/community?agent=" + TestConfig.AGENT_ID).body();
        String spawns = get("/mobs/spawns?agent=" + TestConfig.AGENT_ID).body();

        for (String page : new String[] {profiles, community, spawns}) {
            assertTrue(page.contains("href=\"/mobs/community?agent="), "onglet Boss communautaires");
            assertTrue(page.contains("href=\"/mobs/spawns?agent="), "onglet Apparitions");
            assertTrue(page.contains("boss communautaires actifs"), "résumé compact des états");
        }
        assertTrue(profiles.contains("data-filter-input=\"mobs\""), "recherche immédiate des profils");
        assertFalse(profiles.contains("name=\"interval_minutes\""), "réglages communautaires hors de la vue Profils");
        assertFalse(profiles.contains("name=\"chance\""), "tirage du Wild hors de la vue Profils");
        assertTrue(community.contains("name=\"interval_minutes\""));
        assertFalse(community.contains("id=\"mob-accordion\""), "pas de catalogue dans la vue communautaire");
        assertTrue(spawns.contains("name=\"chance\""));
        assertTrue(spawns.contains("Zombie Runner"), "profils tirés au hasard listés");
        assertFalse(spawns.contains("Zombie d&#39;entraînement</td>"), "un boss n'est jamais tiré au hasard");
        // Aucune syntaxe de fichier dans le parcours courant ; un lien vers Récompenses à la place.
        assertFalse(community.contains("entitlements.yml"));
        assertFalse(community.contains("table:&lt;id&gt;") || profiles.contains("table:&lt;id&gt;"));
        assertTrue(community.contains("href=\"/rewards\""));
    }

    @Test
    void countersSayTheirScopeAndTheTestSpawnNamesTheChosenProfile() throws Exception {
        start();
        login();
        runAction("mob.list", PROFILES + ",\"testInstances\":2}");
        String page = get("/mobs?agent=" + TestConfig.AGENT_ID).body();

        assertTrue(page.contains("5 en jeu (dont 2 de test)"), page);
        assertTrue(page.contains("0 en jeu · 1 boss communautaire actif (zone non chargée)"), "1 actif contre 0 en jeu");
        assertTrue(page.contains("Comment lire les compteurs"), "périmètres expliqués");
        assertTrue(page.contains("Tester « Zombie Runner »"), "apparition de test rattachée au profil");
    }

    @Test
    void testCleanupShowsItsScopeAndRequiresAConfirmationBeforeSending() throws Exception {
        start();
        login();
        runAction("mob.list", PROFILES + ",\"testInstances\":2}");
        String page = get("/mobs?agent=" + TestConfig.AGENT_ID).body();
        int zone = page.indexOf("id=\"mob-test-clear\"");
        assertTrue(zone > 0, "zone de nettoyage distincte");
        String card = page.substring(zone, page.indexOf("</form>", zone));
        assertTrue(card.contains("2 instance(s) de test en zone chargée"), card);
        assertTrue(card.contains("Zombie Runner × 2"), "portée par profil");
        assertTrue(card.contains("ne sont jamais touchés"), "mobs naturels et boss réels préservés");
        assertTrue(card.contains("name=\"confirm\" value=\"true\" required"), "confirmation avant envoi");
        assertTrue(card.contains("value=\"mob.test.clear\""));
        assertTrue(card.contains("btn danger"), "bouton destructif marqué");
    }

    @Test
    void noTestInstanceMeansNoCleanupButtonAndAnOlderPluginSaysTheCountIsUnknown() throws Exception {
        start();
        login();
        runAction("mob.list", PROFILES + ",\"testInstances\":0}");
        String none = get("/mobs?agent=" + TestConfig.AGENT_ID).body();
        assertTrue(none.contains("Aucune instance de test en jeu"));
        assertFalse(none.contains("value=\"mob.test.clear\""));

        runAction("mob.list", PROFILES + "}");
        String old = get("/mobs?agent=" + TestConfig.AGENT_ID).body();
        assertTrue(old.contains("Nombre inconnu"), "jamais un 0 inventé");
        assertTrue(old.contains("value=\"mob.test.clear\""));
    }

    @Test
    void aCleanupRefusedBeforeSendingIsListedInTheHistoryApartFromExecutedActions() throws Exception {
        start();
        login();
        String token = csrf(get("/mobs?agent=" + TestConfig.AGENT_ID).body());
        HttpResponse<String> r = post("/agents/action", "_csrf=" + token + "&type=mob.test.clear&agent="
                + TestConfig.AGENT_ID + "&return=/mobs");
        assertTrue(r.headers().firstValue("Location").orElse("").contains("err="), "refus visible tout de suite");
        assertTrue(store.recentActions(TestConfig.AGENT_ID, 50).stream().noneMatch(a -> "mob.test.clear".equals(a.type())),
                "rien n'est envoyé à l'agent");

        String refused = get("/actions?status=refused").body();
        assertTrue(refused.contains("Supprimer les instances de test"), refused);
        assertTrue(refused.contains("Confirmation obligatoire"), "motif");
        assertTrue(refused.contains(TestConfig.OWNER_USERNAME), "acteur");
        assertTrue(refused.contains("refusées par PlugAdmin avant tout envoi"), "distinct des actions exécutées");
        assertTrue(get("/actions").body().contains("Refusées avant envoi (1)"), "accès depuis l'historique");
    }

    @Test
    void anActionSentFromTheCommunityViewComesBackToIt() throws Exception {
        start();
        login();
        String token = csrf(get("/mobs/community?agent=" + TestConfig.AGENT_ID).body());
        HttpResponse<String> r = post("/agents/action", "_csrf=" + token + "&type=boss.community.status&agent="
                + TestConfig.AGENT_ID + "&return=/mobs/community");
        assertTrue(r.headers().firstValue("Location").orElse("").startsWith("/mobs/community?agent="),
                r.headers().firstValue("Location").orElse(""));
    }

    @Test
    void auditTargetHintKeepsOnlyBoundedTargetKeys() {
        assertEquals(" player=Bob mob_id=rpgquest:x", PanelApp.auditTargetHint(Map.of("player", "Bob",
                "mob_id", "rpgquest:x", "yaml", "secret: true", "_csrf", "t")).replace("  ", " "));
        assertEquals("", PanelApp.auditTargetHint(Map.of("confirm", "true")));
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
