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

/** Issue #336 — page « Prix et coûts » : vente, rachat et fabrication sans YAML. */
class PricesPageTest {

    static final String PRICES = "[{\"key\":\"pain\",\"label\":\"Pain du village\",\"type\":\"SELL_TO_PLAYER\","
            + "\"item\":\"BREAD\",\"amount\":4,\"coins\":3,\"ingredients\":[],\"demo\":true,\"enabled\":true,"
            + "\"version\":\"0a0b0c0d\",\"summary\":\"4 × BREAD vendu pour 3 pièce(s)\","
            + "\"usages\":[\"marchand « Atelier » (rpgquest:atelier)\"]},"
            + "{\"key\":\"epee\",\"label\":\"Épée forgée\",\"type\":\"CRAFT\",\"item\":\"IRON_SWORD\",\"amount\":1,"
            + "\"coins\":10,\"ingredients\":[{\"item\":\"IRON_INGOT\",\"amount\":2},{\"item\":\"rpgquest:spider_fang\","
            + "\"amount\":1}],\"demo\":false,\"enabled\":false,\"version\":\"1a1b1c1d\",\"summary\":\"…\",\"usages\":[]}]";
    static final String MERCHANTS = "[{\"id\":\"rpgquest:atelier\",\"title\":\"Atelier\",\"inlineOffers\":1,"
            + "\"priceRefs\":[\"pain\",\"fantome\"],\"unavailableRefs\":[\"fantome\"]}]";

    static String status(boolean fileExists, boolean valid, String errors, String warnings, String prices) {
        return "{\"available\":true,\"fileExists\":" + fileExists + ",\"valid\":" + valid + ",\"errors\":" + errors
                + ",\"warnings\":" + warnings + ",\"prices\":" + prices + ",\"merchants\":" + MERCHANTS
                + ",\"customItems\":[\"rpgquest:spider_fang\"],\"currency\":\"LodyCoins\"}";
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
        String page = get("/prices?agent=" + TestConfig.AGENT_ID).body();
        assertTrue(page.contains("href=\"/prices\""), "entrée de navigation");
        assertTrue(page.contains("État pas encore chargé"), "aucun état inventé");
        assertFalse(page.contains("Nouveau prix"));
    }

    @Test
    void withoutFileTheFirstPriceCanBeCreated() throws Exception {
        start();
        login();
        runAction("price.catalog.status", status(false, true, "[]", "[]", "[]"));
        String page = get("/prices?agent=" + TestConfig.AGENT_ID).body();
        assertTrue(page.contains("Aucun prix défini sur ce serveur"), page);
        assertTrue(page.contains("Nouveau prix"));
        assertTrue(page.contains("Indisponible en jeu (prix absent ou désactivé) : fantome"), "offres sans prix signalées");
    }

    @Test
    void aRefusedFileShowsItsErrorsAndBlocksWriting() throws Exception {
        start();
        login();
        runAction("price.catalog.status", status(true, false, "[\"Boucle d'enrichissement : BREAD\"]", "[]", "[]"));
        String page = get("/prices?agent=" + TestConfig.AGENT_ID).body();
        assertTrue(page.contains("Le serveur refuse"), page);
        assertTrue(page.contains("Boucle d&#39;enrichissement") || page.contains("Boucle d'enrichissement"), page);
        assertFalse(page.contains("Nouveau prix"));
    }

    @Test
    void pricesAreGroupedByKindWithDemoBadgeUsagesAndVersion() throws Exception {
        start();
        login();
        runAction("price.catalog.status", status(true, true, "[]", "[\"1 prix marqué(s) « démonstration »\"]", PRICES));
        String page = get("/prices?agent=" + TestConfig.AGENT_ID).body();
        assertTrue(page.contains("Le PNJ vend (1)") && page.contains("Le PNJ fabrique (1)"), page);
        assertTrue(page.contains("Le joueur paie <strong>3 LodyCoins</strong> et reçoit 4 × "), page);
        assertTrue(page.contains("démonstration</span>"), "badge démonstration");
        assertTrue(page.contains("Cité par : marchand « Atelier » (rpgquest:atelier)."), page);
        assertTrue(page.contains("name=\"expected_version\" value=\"0a0b0c0d\""), "contrôle de version");
        assertTrue(page.contains("name=\"ing_id_1\" aria-label=\"Ingrédient 2\" list=\"dl-price-items\" "
                + "autocomplete=\"off\" placeholder=\"Ingrédient…\" value=\"rpgquest:spider_fang\""), "ingrédients relus");
        assertTrue(page.contains("<option value=\"rpgquest:spider_fang\" label=\"Objet RPGQuest\">"));
        assertTrue(page.contains("À vérifier"), "avertissements visibles");
    }

    @Test
    void submittingACraftQueuesANormalisedSave() throws Exception {
        start();
        login();
        runAction("price.catalog.status", status(true, true, "[]", "[]", PRICES));
        String token = csrf(get("/prices?agent=" + TestConfig.AGENT_ID).body());
        HttpResponse<String> res = post("/agents/action", "_csrf=" + token + "&type=price.save&agent="
                + TestConfig.AGENT_ID + "&return=/prices&create=true&enabled=true&demo=true"
                + "&label=" + URLEncoder.encode("Lame forgée", StandardCharsets.UTF_8)
                + "&price_type=CRAFT&item=iron_sword&amount=1&coins=" + URLEncoder.encode("1 000", StandardCharsets.UTF_8)
                + "&ing_id_0=minecraft:iron_ingot&ing_amount_0=3&ing_id_1=&ing_amount_1=");
        assertEquals(303, res.statusCode());
        String queued = pollActions();
        assertTrue(queued.contains("\"price_key\":\"lame_forgee\""), queued);
        assertTrue(queued.contains("\"item\":\"IRON_SWORD\""), queued);
        assertTrue(queued.contains("\"coins\":\"1000\""), queued);
        assertTrue(queued.contains("\"ingredients\":\"IRON_INGOT@3\""), queued);
    }

    @Test
    void aPriceWithoutCoinsIsRefusedBeforeReachingTheServer() throws Exception {
        start();
        login();
        runAction("price.catalog.status", status(true, true, "[]", "[]", PRICES));
        String token = csrf(get("/prices?agent=" + TestConfig.AGENT_ID).body());
        HttpResponse<String> res = post("/agents/action", "_csrf=" + token + "&type=price.save&agent="
                + TestConfig.AGENT_ID + "&return=/prices&create=true&enabled=true&label=Pain&price_type=SELL_TO_PLAYER"
                + "&item=BREAD&amount=1&coins=");
        String location = java.net.URLDecoder.decode(res.headers().firstValue("Location").orElse(""),
                StandardCharsets.UTF_8);
        assertTrue(location.contains("aucun prix n'est jamais mis par défaut"), location);
        assertFalse(pollActions().contains("price.save"));
    }

    private String pollActions() throws Exception {
        return client.send(HttpRequest.newBuilder(uri("/agent/v1/actions"))
                .header("Authorization", "Bearer " + TestConfig.AGENT_TOKEN)
                .header("X-Agent-Id", TestConfig.AGENT_ID).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    private void runAction(String type, String details) throws Exception {
        String token = csrf(get("/prices?agent=" + TestConfig.AGENT_ID).body());
        post("/agents/action", "_csrf=" + token + "&type=" + type + "&agent=" + TestConfig.AGENT_ID
                + "&return=/prices");
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
