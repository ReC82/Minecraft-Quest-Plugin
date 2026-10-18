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

/** Issues #177 tranche 2 / #474 — page « Récompenses » : catalogue et tables sans YAML. */
class RewardTablesPageTest {

    static final String TABLE = "{\"id\":\"butin_ogre\",\"label\":\"Butin de l'ogre\",\"mode\":\"single\","
            + "\"modeLabel\":\"Une récompense maximum\",\"enabled\":true,\"version\":\"1a2b3c4d\","
            + "\"totalChance\":\"37,5 %\",\"explanation\":\"aucun gain : 62,5 %.\",\"entries\":["
            + "{\"id\":\"gem\",\"rewardKey\":\"gem\",\"rewardLabel\":\"Gemme rare\",\"rewardKnown\":true,"
            + "\"chance\":\"12,5 %\",\"chanceValue\":\"12.5\"},"
            + "{\"id\":\"book\",\"rewardKey\":\"book\",\"rewardLabel\":\"Livre\",\"rewardKnown\":true,"
            + "\"chance\":\"25 %\",\"chanceValue\":\"25\"}]}";
    static final String REWARDS = "[{\"key\":\"gem\",\"label\":\"Gemme rare\",\"summary\":\"2 × DIAMOND\"},"
            + "{\"key\":\"book\",\"label\":\"Livre\",\"summary\":\"ENCHANTED_BOOK (sharpness 3)\"}]";

    static String status(boolean fileExists, boolean valid, boolean enabled, String errors, String tables) {
        return "{\"available\":true,\"fileExists\":" + fileExists + ",\"valid\":" + valid + ",\"enabled\":" + enabled
                + ",\"errors\":" + errors + ",\"rewards\":" + (fileExists && valid ? REWARDS : "[]")
                + ",\"tables\":" + tables + "}";
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
        String page = get("/rewards?agent=" + TestConfig.AGENT_ID).body();
        assertTrue(page.contains("href=\"/rewards\""), "entrée de navigation");
        assertTrue(page.contains("État pas encore chargé"), "aucun état inventé");
        assertFalse(page.contains("Nouvelle table"));
    }

    @Test
    void withoutCatalogNothingCanBeCreatedAndTheReasonIsGiven() throws Exception {
        start();
        login();
        runAction("reward.catalog.status", status(false, false, false, "[]", "[]"));
        String page = get("/rewards?agent=" + TestConfig.AGENT_ID).body();
        assertTrue(page.contains("Aucun catalogue de récompenses sur ce serveur"), page);
        assertFalse(page.contains("Nouvelle table"));
    }

    @Test
    void aRefusedFileShowsItsErrorsAndBlocksWriting() throws Exception {
        start();
        login();
        runAction("reward.catalog.status", status(true, false, false,
                "[\"tables « x » : la somme dépasse 100 %\"]", "[]"));
        String page = get("/rewards?agent=" + TestConfig.AGENT_ID).body();
        assertTrue(page.contains("Le serveur refuse"), page);
        assertTrue(page.contains("la somme dépasse 100 %"));
        assertFalse(page.contains("Nouvelle table"));
    }

    @Test
    void tablesAreEditableWithCatalogRewardsChancesAndModes() throws Exception {
        start();
        login();
        runAction("reward.catalog.status", status(true, true, false, "[]", "[" + TABLE + "]"));
        String page = get("/rewards?agent=" + TestConfig.AGENT_ID).body();

        assertTrue(page.contains("Aucun tirage ni nouvelle récompense"), "catalogue désactivé expliqué");
        assertTrue(page.contains("Nouvelle table"));
        assertTrue(page.contains("Une récompense au plus") && page.contains("Chaque récompense tentée séparément"));
        assertTrue(page.contains("<datalist id=\"dl-reward-catalog\"><option value=\"gem\" label=\"Gemme rare — 2 × DIAMOND\">"),
                "récompenses du catalogue recherchables");
        assertTrue(page.contains("name=\"entry_reward_0\" aria-label=\"Récompense ligne 1\" value=\"gem\""), page);
        assertTrue(page.contains("data-chance value=\"12,5\""), "chance actuelle relue");
        assertTrue(page.contains("name=\"expected_version\" value=\"1a2b3c4d\""), "contrôle de version");
        assertTrue(page.contains("Total : 37,5 % · chance de ne rien gagner : 62,5 %."), "somme visible");
        assertTrue(page.contains("Désactiver") && page.contains("Dupliquer") && page.contains("Modifier"));
        assertTrue(page.contains("value=\"butin_ogre_copie\""), "identifiant de copie proposé");
        assertTrue(page.contains("table:butin_ogre"), "usage côté boss rappelé");
    }

    @Test
    void submittingTheFormQueuesANormalisedSaveForTheAgent() throws Exception {
        start();
        login();
        runAction("reward.catalog.status", status(true, true, true, "[]", "[" + TABLE + "]"));
        String token = csrf(get("/rewards?agent=" + TestConfig.AGENT_ID).body());
        HttpResponse<String> res = post("/agents/action", "_csrf=" + token + "&type=reward.table.save&agent="
                + TestConfig.AGENT_ID + "&return=/rewards&create=false&table_id=butin_ogre&expected_version=1a2b3c4d"
                + "&label=" + URLEncoder.encode("Butin de l'ogre", StandardCharsets.UTF_8) + "&mode=single&enabled=true"
                + "&entry_id_0=gem&entry_reward_0=gem&entry_chance_0=" + URLEncoder.encode("12,5", StandardCharsets.UTF_8)
                + "&entry_id_1=book&entry_reward_1=&entry_chance_1="
                + "&entry_id_2=&entry_reward_2=book&entry_chance_2=40");
        assertEquals(303, res.statusCode(), res.body());
        String queued = pollActions();
        assertTrue(queued.contains("reward.table.save"), queued);
        assertTrue(queued.contains("gem=gem@12.5;book=book@40"), "ligne vidée retirée, nouvelle ligne ajoutée : " + queued);
        assertTrue(queued.contains("1a2b3c4d"), queued);
    }

    @Test
    void aBadFormIsRefusedBeforeReachingTheServer() throws Exception {
        start();
        login();
        runAction("reward.catalog.status", status(true, true, true, "[]", "[" + TABLE + "]"));
        String token = csrf(get("/rewards?agent=" + TestConfig.AGENT_ID).body());
        HttpResponse<String> res = post("/agents/action", "_csrf=" + token + "&type=reward.table.save&agent="
                + TestConfig.AGENT_ID + "&return=/rewards&create=true&label=Coffre&mode=single&enabled=true"
                + "&entry_reward_0=gem&entry_chance_0=70&entry_reward_1=book&entry_chance_1=40");
        String location = res.headers().firstValue("Location").orElse("");
        assertTrue(java.net.URLDecoder.decode(location, StandardCharsets.UTF_8).contains("110 %"), location);
        assertFalse(pollActions().contains("reward.table.save"));
    }

    // ---- Retour propriétaire du 18/10 : formulaire de table ------------------------------------------

    @Test
    void eachChosenRewardShowsItsNameAndQuantityThenItsKey() throws Exception {
        start();
        login();
        runAction("reward.catalog.status", status(true, true, true, "[]", "[" + TABLE + "]"));
        String page = get("/rewards?agent=" + TestConfig.AGENT_ID).body();
        assertTrue(page.contains("data-rt-picked><strong>Gemme rare — 2 × DIAMOND</strong> · <code>gem</code>"), page);
        assertTrue(page.contains("data-rt-submit") && page.contains("data-rt-why"), "bouton et raison pour panel.js");
    }

    @Test
    void aFormRefusedByThePanelComesBackWhole() throws Exception {
        start();
        login();
        runAction("reward.catalog.status", status(true, true, true, "[]", "[" + TABLE + "]"));
        String token = csrf(get("/rewards?agent=" + TestConfig.AGENT_ID).body());
        post("/agents/action", "_csrf=" + token + "&type=reward.table.save&agent=" + TestConfig.AGENT_ID
                + "&return=/rewards&create=true&label=" + URLEncoder.encode("Coffre du marais", StandardCharsets.UTF_8)
                + "&table_id=coffre_marais&mode=single"
                + "&entry_reward_0=gem&entry_chance_0=70&entry_reward_1=book&entry_chance_1=50");
        assertFalse(pollActions().contains("reward.table.save"), "rien envoyé au serveur");

        String page = get("/rewards?agent=" + TestConfig.AGENT_ID).body();
        assertTrue(page.contains("Refusé, rien n&#39;a été enregistré") || page.contains("Refusé, rien n'a été enregistré"),
                page);
        assertTrue(page.contains("120 %"), "raison du refus");
        assertTrue(page.contains("class=\"collapse show\" id=\"rt-new\""), "formulaire de création rouvert");
        assertTrue(page.contains("data-rt-label value=\"Coffre du marais\""), "nom conservé");
        assertTrue(page.contains("data-rt-id value=\"coffre_marais\""), "identifiant conservé");
        assertTrue(page.contains("id=\"rt-new-m-single\" value=\"single\" data-chance-mode checked"), "mode conservé");
        assertFalse(page.contains("id=\"rt-new-en\" name=\"enabled\" value=\"true\" checked"),
                "activation décochée conservée");
        assertTrue(page.contains("name=\"entry_reward_1\" aria-label=\"Récompense ligne 2\" value=\"book\""), page);
        assertTrue(page.contains("data-chance value=\"70\"") && page.contains("data-chance value=\"50\""),
                "chances conservées");

        String again = get("/rewards?agent=" + TestConfig.AGENT_ID).body();
        assertFalse(again.contains("data-refused-form"), "réaffiché une seule fois");
    }

    @Test
    void aSaveRefusedByThePluginReopensTheTableWithWhatWasSent() throws Exception {
        start();
        login();
        runAction("reward.catalog.status", status(true, true, true, "[]", "[" + TABLE + "]"));
        String token = csrf(get("/rewards?agent=" + TestConfig.AGENT_ID).body());
        post("/agents/action", "_csrf=" + token + "&type=reward.table.save&agent=" + TestConfig.AGENT_ID
                + "&return=/rewards&create=false&table_id=butin_ogre&expected_version=1a2b3c4d"
                + "&label=" + URLEncoder.encode("Butin modifié", StandardCharsets.UTF_8) + "&mode=independent"
                + "&entry_id_0=gem&entry_reward_0=gem&entry_chance_0=90&entry_reward_1=book&entry_chance_1=30");
        String id = answerQueued("FAILED", "Refusé par le serveur, rien n'a été écrit : récompense inconnue.");

        String page = get("/rewards?agent=" + TestConfig.AGENT_ID + "&refused=" + id).body();
        assertTrue(page.contains("data-refused-form"), page);
        assertTrue(page.contains("récompense inconnue"), "message du plugin");
        assertTrue(page.contains("class=\"collapse mt-2 show\" id=\"rt-butin_ogre-edit\""), "fiche rouverte");
        assertTrue(page.contains("data-rt-label value=\"Butin modifié\""), "nom envoyé, pas l'ancien");
        assertTrue(page.contains("id=\"rt-butin_ogre-f-m-independent\" value=\"independent\" data-chance-mode checked"),
                "mode envoyé");
        assertTrue(page.contains("data-chance value=\"90\"") && page.contains("data-chance value=\"30\""), page);
        assertTrue(page.contains("Total : 120 % (chaque ligne est tirée à part"), "120 % accepté en indépendant");

        assertFalse(get("/rewards?agent=" + TestConfig.AGENT_ID).body().contains("data-refused-form"),
                "sans ?refused, la page normale");
        assertFalse(get("/rewards?agent=" + TestConfig.AGENT_ID + "&refused=00000000-0000-0000-0000-000000000000")
                .body().contains("data-refused-form"), "action inconnue ignorée");
    }

    @Test
    void anIndependentTableAbove100PercentIsSent() throws Exception {
        start();
        login();
        runAction("reward.catalog.status", status(true, true, true, "[]", "[" + TABLE + "]"));
        String token = csrf(get("/rewards?agent=" + TestConfig.AGENT_ID).body());
        HttpResponse<String> res = post("/agents/action", "_csrf=" + token + "&type=reward.table.save&agent="
                + TestConfig.AGENT_ID + "&return=/rewards&create=true&label=Coffre&mode=independent&enabled=true"
                + "&entry_reward_0=gem&entry_chance_0=70&entry_reward_1=book&entry_chance_1=50");
        assertFalse(java.net.URLDecoder.decode(res.headers().firstValue("Location").orElse(""), StandardCharsets.UTF_8)
                .contains("err="));
        assertTrue(pollActions().contains("gem=gem@70;book=book@50"));
    }

    /** Répond à la première action en file ; renvoie son identifiant. */
    private String answerQueued(String status, String message) throws Exception {
        Matcher m = Pattern.compile("\"id\"\\s*:\\s*\"([0-9a-f-]{36})\"").matcher(pollActions());
        if (!m.find()) {
            throw new IllegalStateException("aucune action en file");
        }
        client.send(HttpRequest.newBuilder(uri("/agent/v1/actions/" + m.group(1) + "/result"))
                .header("Authorization", "Bearer " + TestConfig.AGENT_TOKEN)
                .header("X-Agent-Id", TestConfig.AGENT_ID)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"status\":\"" + status + "\",\"message\":\""
                        + message + "\",\"details\":{}}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        return m.group(1);
    }

    // ---- Issue #474 : catalogue administré depuis PlugAdmin ----------------------------------------

    static final String PREVIEW = "\"# Catalogue des récompenses\\nenabled: false\\nrewards: {}\\ntables: {}\\n\"";
    static final String REWARDS_474 = "[{\"key\":\"gem\",\"label\":\"Gemme rare\",\"summary\":\"2 × DIAMOND\","
            + "\"enabled\":true,\"version\":\"0a0b0c0d\",\"items\":[{\"item\":\"DIAMOND\",\"amount\":2,"
            + "\"enchantments\":\"\",\"potion\":\"\"}],\"perkKey\":\"\",\"perkTier\":\"\","
            + "\"usages\":[\"table « Butin de l'ogre » (butin_ogre)\",\"boss « Ogre » (rpgquest:ogre)\"]},"
            + "{\"key\":\"book\",\"label\":\"Livre\",\"summary\":\"ENCHANTED_BOOK (sharpness 3)\","
            + "\"enabled\":false,\"version\":\"1a1b1c1d\",\"items\":[{\"item\":\"ENCHANTED_BOOK\",\"amount\":1,"
            + "\"enchantments\":\"sharpness:3\",\"potion\":\"\"}],\"perkKey\":\"\",\"perkTier\":\"\",\"usages\":[]}]";

    static String status474(boolean fileExists, boolean enabled) {
        String table = TABLE.substring(0, TABLE.length() - 1) + ",\"usages\":[\"quête « Le marais » (rpgquest:marais)\"]}";
        return "{\"available\":true,\"fileExists\":" + fileExists + ",\"valid\":" + fileExists + ",\"enabled\":"
                + enabled + ",\"errors\":[],\"rewards\":" + (fileExists ? REWARDS_474 : "[]") + ",\"tables\":"
                + (fileExists ? "[" + table + "]" : "[]") + ",\"initialText\":" + (fileExists ? "\"\"" : PREVIEW)
                + ",\"customItems\":[\"rpgquest:return_stone\"],\"enchantments\":[\"sharpness\",\"unbreaking\"],"
                + "\"potions\":[\"strength\"]}";
    }

    @Test
    void withoutCatalogTheExactFileIsPreviewedAndCreatedOnlyAfterConfirmation() throws Exception {
        start();
        login();
        runAction("reward.catalog.status", status474(false, false));
        String page = get("/rewards?agent=" + TestConfig.AGENT_ID).body();
        assertTrue(page.contains("Créer le catalogue"), page);
        assertTrue(page.contains("enabled: false"), "aperçu du fichier exact");
        assertTrue(page.contains("name=\"confirm\" value=\"yes\""), "confirmation explicite");
        assertFalse(page.contains("Nouvelle récompense") || page.contains("Nouvelle table"));

        String token = csrf(page);
        HttpResponse<String> refused = post("/agents/action", "_csrf=" + token + "&type=reward.catalog.init&agent="
                + TestConfig.AGENT_ID + "&return=/rewards");
        assertTrue(java.net.URLDecoder.decode(refused.headers().firstValue("Location").orElse(""),
                StandardCharsets.UTF_8).contains("confirmation"));
        assertFalse(pollActions().contains("reward.catalog.init"));
        post("/agents/action", "_csrf=" + token + "&type=reward.catalog.init&agent=" + TestConfig.AGENT_ID
                + "&return=/rewards&confirm=yes");
        assertTrue(pollActions().contains("reward.catalog.init"));
    }

    @Test
    void rewardsAreEditableWithUsagesAndTheGlobalSwitchIsExplained() throws Exception {
        start();
        login();
        runAction("reward.catalog.status", status474(true, false));
        String page = get("/rewards?agent=" + TestConfig.AGENT_ID).body();
        assertTrue(page.contains("Activer les récompenses"), "interrupteur général");
        assertTrue(page.contains("Nouvelle récompense"));
        assertTrue(page.contains("Utilisée par : table « Butin de l&#39;ogre » (butin_ogre), boss « Ogre » "
                + "(rpgquest:ogre)."), page);
        assertTrue(page.contains("2 usage(s) ci-dessus ne la donneront plus"), "conséquence de la désactivation");
        assertTrue(page.contains("name=\"item_id_0\" aria-label=\"Objet 1\" value=\"DIAMOND\""), "objet relu");
        assertTrue(page.contains("value=\"sharpness 3\""), "enchantement relu");
        assertTrue(page.contains("name=\"expected_version\" value=\"0a0b0c0d\""), "contrôle de version");
        assertTrue(page.contains("<datalist id=\"dl-reward-items\"><option value=\"rpgquest:return_stone\""),
                "objets RPGQuest recherchables");
        assertTrue(page.contains("Livre (désactivée)"), "récompense désactivée signalée dans les choix de table");
        assertTrue(page.contains("Tirée par : quête « Le marais » (rpgquest:marais)."), "usages d'une table");
        assertTrue(page.contains("value=\"gem_copie\""), "identifiant de copie proposé");
    }

    @Test
    void submittingARewardQueuesANormalisedSave() throws Exception {
        start();
        login();
        runAction("reward.catalog.status", status474(true, true));
        String token = csrf(get("/rewards?agent=" + TestConfig.AGENT_ID).body());
        HttpResponse<String> res = post("/agents/action", "_csrf=" + token + "&type=reward.definition.save&agent="
                + TestConfig.AGENT_ID + "&return=/rewards&create=true&enabled=true"
                + "&label=" + URLEncoder.encode("Épée du héros", StandardCharsets.UTF_8)
                + "&item_id_0=DIAMOND_SWORD&item_amount_0=1&item_ench_0=" + URLEncoder.encode("sharpness 3",
                StandardCharsets.UTF_8) + "&item_potion_0=&item_id_1=&item_amount_1=");
        assertEquals(303, res.statusCode(), res.body());
        String queued = pollActions();
        assertTrue(queued.contains("reward.definition.save"), queued);
        assertTrue(queued.contains("DIAMOND_SWORD@1+ench=sharpness:3"), queued);
        assertTrue(queued.contains("epee_du_heros"), queued);
    }

    private String pollActions() throws Exception {
        return client.send(HttpRequest.newBuilder(uri("/agent/v1/actions"))
                .header("Authorization", "Bearer " + TestConfig.AGENT_TOKEN)
                .header("X-Agent-Id", TestConfig.AGENT_ID).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    private void runAction(String type, String details) throws Exception {
        String token = csrf(get("/rewards?agent=" + TestConfig.AGENT_ID).body());
        post("/agents/action", "_csrf=" + token + "&type=" + type + "&agent=" + TestConfig.AGENT_ID
                + "&return=/rewards");
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
