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

/** Issue #17 — page « Marchands » : catalogues de vente / rachat sans YAML. */
class MerchantsPageTest {

    static final String MERCHANTS = "[{\"id\":\"rpgquest:boutique\",\"file\":\"boutique.yml\","
            + "\"title\":\"<gold>Boutique</gold>\",\"type\":\"SELL\",\"enabled\":true,\"bundled\":false,"
            + "\"version\":\"0a0b0c0d\",\"offers\":[{\"direction\":\"SELL_TO_PLAYER\",\"item\":\"BREAD\","
            + "\"quantity\":4,\"price\":3,\"priceRef\":null,\"requiredQuest\":\"rpgquest:first_steps\","
            + "\"requiredQuestState\":\"COMPLETED\",\"requiredLevel\":null,\"requiredPermission\":null},"
            + "{\"direction\":null,\"item\":null,\"quantity\":0,\"price\":0,\"priceRef\":\"pain\"}],"
            + "\"usages\":[\"dialogue « rpgquest:guard »\"],\"unavailableRefs\":[\"pain\"]},"
            + "{\"id\":\"rpgquest:comptoir\",\"file\":\"comptoir.yml\",\"title\":\"Comptoir\",\"type\":\"BUY\","
            + "\"enabled\":false,\"bundled\":false,\"version\":\"1a1b1c1d\",\"offers\":[{\"direction\":"
            + "\"BUY_FROM_PLAYER\",\"item\":\"COBBLESTONE\",\"quantity\":64,\"price\":6}],\"usages\":[],"
            + "\"unavailableRefs\":[]}]";

    static String status(String merchants, String issues) {
        return "{\"available\":true,\"merchants\":" + merchants + ",\"issues\":" + issues
                + ",\"customItems\":[\"rpgquest:spider_fang\"],\"prices\":[{\"key\":\"pain\",\"label\":\"Pain\","
                + "\"type\":\"SELL_TO_PLAYER\",\"enabled\":false,\"summary\":\"…\"}],\"currency\":\"LodyCoins\"}";
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
        String page = get("/merchants?agent=" + TestConfig.AGENT_ID).body();
        assertTrue(page.contains("href=\"/merchants\""), "entrée de navigation");
        assertTrue(page.contains("État pas encore chargé"), "aucun état inventé");
        assertFalse(page.contains("Nouveau marchand"));
    }

    @Test
    void merchantsAreGroupedByKindWithUsagesStateAndGuards() throws Exception {
        start();
        login();
        runAction("merchant.catalog.status", status(MERCHANTS, "[\"cassé.yml : « title » est obligatoire.\"]"));
        String page = get("/merchants?agent=" + TestConfig.AGENT_ID).body();
        assertTrue(page.contains("Boutique (1)") && page.contains("Rachat (1)"), page);
        assertTrue(page.contains("Vend 4 × "), page);
        assertTrue(page.contains("si quête rpgquest:first_steps (COMPLETED)"), page);
        assertTrue(page.contains("Ouvert par : dialogue « rpgquest:guard »."), page);
        assertTrue(page.contains("Indisponible en jeu"), "prix lié inutilisable signalé");
        assertTrue(page.contains("fermé</span>") && page.contains("Ouvrir la boutique"), "état et bascule");
        assertTrue(page.contains("des dialogues l&#39;ouvrent encore") || page.contains("des dialogues l'ouvrent encore"),
                "suppression bloquée quand un dialogue l'utilise");
        assertTrue(page.contains("name=\"merchant_id\" value=\"rpgquest:comptoir\"><input type=\"hidden\" "
                + "name=\"expected_version\" value=\"1a1b1c1d\">"), "suppression avec version");
        assertTrue(page.contains("Fichiers de marchands refusés"), "fichiers refusés visibles");
        assertTrue(page.contains("<option value=\"pain\" label=\"Pain — Le PNJ vend (désactivé)\">"), page);
        assertTrue(page.contains("name=\"of_ref_1\" list=\"dl-merchant-prices\""), "offre liée relue");
    }

    @Test
    void submittingABuyCounterQueuesNormalisedOffers() throws Exception {
        start();
        login();
        runAction("merchant.catalog.status", status(MERCHANTS, "[]"));
        String token = csrf(get("/merchants?agent=" + TestConfig.AGENT_ID).body());
        HttpResponse<String> res = post("/agents/action", "_csrf=" + token + "&type=merchant.save&agent="
                + TestConfig.AGENT_ID + "&return=/merchants&create=true&enabled=true"
                + "&title=" + URLEncoder.encode("Comptoir du port", StandardCharsets.UTF_8)
                + "&catalog_type=BUY&of_dir_0=SELL_TO_PLAYER&of_item_0=minecraft:cod&of_qty_0=8&of_price_0="
                + URLEncoder.encode("1 200", StandardCharsets.UTF_8) + "&of_quest_0=&of_state_0=COMPLETED"
                + "&of_ref_1=&of_item_1=&of_ref_2=galets&of_level_2=3");
        assertEquals(303, res.statusCode());
        String queued = pollActions();
        assertTrue(queued.contains("\"merchant_id\":\"comptoir_du_port\""), queued);
        assertTrue(queued.contains("BUY_FROM_PLAYER|COD|8|1200|||||\\n||||galets|||3|"), queued);
    }

    @Test
    void aLineWithBothAPriceRefAndAnItemIsRefusedBeforeReachingTheServer() throws Exception {
        start();
        login();
        runAction("merchant.catalog.status", status(MERCHANTS, "[]"));
        String token = csrf(get("/merchants?agent=" + TestConfig.AGENT_ID).body());
        HttpResponse<String> res = post("/agents/action", "_csrf=" + token + "&type=merchant.save&agent="
                + TestConfig.AGENT_ID + "&return=/merchants&create=true&title=X&catalog_type=SELL"
                + "&of_ref_0=pain&of_item_0=BREAD&of_qty_0=1&of_price_0=1");
        String location = java.net.URLDecoder.decode(res.headers().firstValue("Location").orElse(""),
                StandardCharsets.UTF_8);
        assertTrue(location.contains("pas les deux"), location);
        assertFalse(pollActions().contains("merchant.save"));
    }

    /** Issue #18 : un atelier d'artisan est regroupé à part et chaque recette dit ce qu'elle demande. */
    @Test
    void anArtisanWorkshopListsItsRecipesInPlainWords() throws Exception {
        start();
        login();
        String atelier = "[{\"id\":\"rpgquest:forge\",\"file\":\"forge.yml\",\"title\":\"Forge\","
                + "\"type\":\"CRAFT\",\"enabled\":true,\"bundled\":false,\"version\":\"2a2b2c2d\",\"offers\":["
                + "{\"direction\":null,\"item\":null,\"quantity\":0,\"price\":0,\"priceRef\":\"epee\"}],"
                + "\"usages\":[],\"unavailableRefs\":[]}]";
        runAction("merchant.catalog.status", "{\"available\":true,\"merchants\":" + atelier + ",\"issues\":[],"
                + "\"customItems\":[],\"prices\":[{\"key\":\"epee\",\"label\":\"Épée\",\"type\":\"CRAFT\","
                + "\"enabled\":true,\"summary\":\"1 × IRON_SWORD fabriqué contre 2 × IRON_INGOT + 5 pièce(s)\"}],"
                + "\"currency\":\"LodyCoins\"}");
        String page = get("/merchants?agent=" + TestConfig.AGENT_ID).body();
        assertTrue(page.contains("Atelier d&#39;artisan (1)") || page.contains("Atelier d'artisan (1)"), page);
        assertTrue(page.contains("1 × IRON_SWORD fabriqué contre 2 × IRON_INGOT + 5 pièce(s)"), page);
        assertTrue(page.contains("value=\"CRAFT\" checked"), "nature relue dans le formulaire");
    }

    @Test
    void aWorkshopRefusesAnItemSoldAtAWrittenPrice() throws Exception {
        start();
        login();
        runAction("merchant.catalog.status", status(MERCHANTS, "[]"));
        String token = csrf(get("/merchants?agent=" + TestConfig.AGENT_ID).body());
        HttpResponse<String> res = post("/agents/action", "_csrf=" + token + "&type=merchant.save&agent="
                + TestConfig.AGENT_ID + "&return=/merchants&create=true&title=Forge&catalog_type=CRAFT"
                + "&of_item_0=BREAD&of_qty_0=1&of_price_0=1");
        String location = java.net.URLDecoder.decode(res.headers().firstValue("Location").orElse(""),
                StandardCharsets.UTF_8);
        assertTrue(location.contains("ne propose que des recettes"), location);
        assertFalse(pollActions().contains("merchant.save"));
    }

    @Test
    void deleteNeedsAnExplicitConfirmation() throws Exception {
        start();
        login();
        runAction("merchant.catalog.status", status(MERCHANTS, "[]"));
        String token = csrf(get("/merchants?agent=" + TestConfig.AGENT_ID).body());
        HttpResponse<String> res = post("/agents/action", "_csrf=" + token + "&type=merchant.delete&agent="
                + TestConfig.AGENT_ID + "&return=/merchants&merchant_id=rpgquest:comptoir");
        String location = java.net.URLDecoder.decode(res.headers().firstValue("Location").orElse(""),
                StandardCharsets.UTF_8);
        assertTrue(location.contains("Confirmation obligatoire"), location);
        assertFalse(pollActions().contains("merchant.delete"));
    }

    private String pollActions() throws Exception {
        return client.send(HttpRequest.newBuilder(uri("/agent/v1/actions"))
                .header("Authorization", "Bearer " + TestConfig.AGENT_TOKEN)
                .header("X-Agent-Id", TestConfig.AGENT_ID).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    private void runAction(String type, String details) throws Exception {
        String token = csrf(get("/merchants?agent=" + TestConfig.AGENT_ID).body());
        post("/agents/action", "_csrf=" + token + "&type=" + type + "&agent=" + TestConfig.AGENT_ID
                + "&return=/merchants");
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
