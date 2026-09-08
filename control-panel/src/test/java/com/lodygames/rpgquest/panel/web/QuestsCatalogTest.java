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

/**
 * Rendu du catalogue {@code /quests} (issue #74) : libellé humain prioritaire, aucune balise
 * MiniMessage brute, identifiants techniques conservés en second plan, objectifs lisibles.
 */
class QuestsCatalogTest {

    @TempDir
    Path tmp;

    private PanelApp app;
    private int port;
    private HttpClient client;
    private final Map<String, String> jar = new LinkedHashMap<>();

    // Formats réellement produits par BukkitAgentActions.describeRewards / QuestObjective.describe.
    private static final String QUEST_DETAILS = "{\"quests\":[{"
            + "\"id\":\"rpgquest:crystal_hunt\",\"title\":\"<gold>La chasse aux cristaux</gold>\","
            + "\"category\":\"crafting\",\"repeatable\":false,"
            + "\"prerequisites\":[\"rpgquest:first_steps\"],"
            + "\"steps\":[{\"id\":\"hunt_spiders\",\"objectives\":[\"Tuer SPIDER (x5)\"]},"
            + "{\"id\":\"gather_crystals\",\"objectives\":[\"Collecter AMETHYST_SHARD (x2)\"]}],"
            + "\"rewards\":[\"+100 XP\",\"+1x DIAMOND_SWORD\","
            + "\"variable CLAIM_TIER_1 = true\","
            + "\"commande console : customitem give %player% rpgquest:miner_pickaxe 1\"]}]}";

    @AfterEach
    void tearDown() {
        if (app != null) {
            app.stop();
        }
    }

    @Test
    void catalogIsReadableAndKeepsTechnicalIds() throws Exception {
        start();
        runListWithSuccess("quest.list", "quests", QUEST_DETAILS);
        String cat = section(get("/quests?agent=" + TestConfig.AGENT_ID).body());

        // libellé humain visible, aucune balise brute
        assertTrue(cat.contains("La chasse aux cristaux"));
        assertFalse(cat.contains("<gold>") || cat.contains("&lt;gold&gt;"), "aucune balise MiniMessage brute");
        assertTrue(cat.contains("<span style=\"color:"), "couleur MiniMessage interprétée");
        assertTrue(cat.contains("class=\"entity-name\""), "titre dans un composant nom d'entité");

        // ids techniques conservés, dans le style « second plan » (.tid) et copiables (data-copy)
        assertTrue(cat.contains("class=\"tid\"") && cat.contains("rpgquest:crystal_hunt"), "id quête conservé");
        assertTrue(cat.contains("data-copy=\"rpgquest:crystal_hunt\""), "id quête copiable");
        assertTrue(cat.contains("hunt_spiders"), "id d'étape conservé");
        assertTrue(cat.contains("rpgquest:first_steps"), "prérequis (id) conservé");

        // #76 : objectifs en français quand connu, repli prettify sinon (apostrophe échappée en HTML)
        assertTrue(cat.contains("Tuer Araignée (x5)"), "objectif FR : SPIDER -> Araignée");
        assertTrue(cat.contains("Collecter Éclat d&#39;améthyste (x2)"), "objectif FR : AMETHYST_SHARD");

        // #77 : récompenses lisibles + valeur technique conservée
        assertTrue(cat.contains("+100 XP"), "XP inchangé");
        assertTrue(cat.contains("Objet : Épée en diamant ×1"), "item reward lisible");
        assertTrue(cat.contains("Débloque : Claim Tier 1"), "variable reward lisible");
        assertTrue(cat.contains("Objet : Miner Pickaxe ×1"), "commande customitem résumée");
        assertTrue(cat.contains("customitem give %player% rpgquest:miner_pickaxe 1"),
                "commande brute conservée en secondaire");
        assertTrue(cat.contains("variable CLAIM_TIER_1 = true"), "valeur technique de la variable conservée");
    }

    @Test
    void emptyCatalogUsesTheSharedEmptyState() throws Exception {
        start();
        String page = get("/quests?agent=" + TestConfig.AGENT_ID).body();
        assertTrue(page.contains("class=\"empty\""));
        assertTrue(page.contains("Aucun catalogue chargé"));
    }

    // ---- helpers ----------------------------------------------------------------------

    private static String section(String page) {
        int a = page.indexOf("<h2>Catalogue</h2>");
        int b = page.indexOf("<h2>", a + 4);
        return a < 0 ? page : page.substring(a, b < 0 ? page.length() : b);
    }

    private void runListWithSuccess(String type, String key, String details) throws Exception {
        String token = csrf(get("/quests?agent=" + TestConfig.AGENT_ID).body());
        post("/agents/action", "_csrf=" + token + "&type=" + type + "&agent=" + TestConfig.AGENT_ID + "&return=/quests");
        HttpResponse<String> poll = client.send(HttpRequest.newBuilder(uri("/agent/v1/actions"))
                .header("Authorization", "Bearer " + TestConfig.AGENT_TOKEN)
                .header("X-Agent-Id", TestConfig.AGENT_ID).GET().build(), HttpResponse.BodyHandlers.ofString());
        Matcher m = Pattern.compile("\"id\"\\s*:\\s*\"([0-9a-f-]{36})\"").matcher(poll.body());
        m.find();
        String id = m.group(1);
        String result = "{\"action_id\":\"" + id + "\",\"status\":\"SUCCESS\",\"value\":\"1\","
                + "\"message\":\"1 " + key + "\",\"details\":" + details + "}";
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
