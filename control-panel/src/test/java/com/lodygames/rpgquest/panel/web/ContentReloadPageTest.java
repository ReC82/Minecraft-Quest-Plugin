package com.lodygames.rpgquest.panel.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.panel.agent.AgentActionCatalog;
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
 * Rechargement du contenu depuis le panel (issue #131).
 *
 * <p>Le point le plus important de cette page n'est pas le bouton : c'est qu'elle énonce les
 * <strong>trois états</strong> et qu'elle dise qu'un rechargement <em>ne transfère rien</em> depuis
 * AWS. C'est le malentendu décrit par le ticket, et le seul qui fasse perdre du temps.</p>
 */
class ContentReloadPageTest {

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

    // ---- La page -----------------------------------------------------------------------------

    @Test
    void theOpsPageStatesTheThreeDistinctStates() throws Exception {
        start();
        login();
        freshHeartbeat();

        String page = get("/ops").body();

        assertTrue(page.contains("Rechargement du contenu"));
        assertTrue(page.contains("Enregistré dans la source"), "état 1");
        assertTrue(page.contains("Publié sur le serveur"), "état 2");
        assertTrue(page.contains("Chargé en jeu"), "état 3");
    }

    @Test
    void thePageSaysThatAReloadTransfersNothingFromAws() throws Exception {
        start();
        login();
        freshHeartbeat();

        String page = get("/ops").body();

        // L'avertissement central du ticket : sans lui, l'administrateur attend d'un rechargement
        // qu'il publie son fichier.
        assertTrue(page.contains("ne transfère"), page.contains("déploiement") ? "" : "mention du déploiement");
        assertTrue(page.contains("déploiement"));
    }

    @Test
    void thePageOffersEveryReloadableFamilyAndBothActions() throws Exception {
        start();
        login();
        freshHeartbeat();

        String page = get("/ops").body();

        for (String family : AgentActionCatalog.RELOAD_FAMILIES) {
            // Un nom distinct par case : des cases homonymes seraient écrasées par le parseur.
            assertTrue(page.contains("name=\"family_" + family + "\""), "famille " + family);
        }
        assertTrue(page.contains("value=\"content.reload.preview\""), "aperçu");
        assertTrue(page.contains("value=\"content.reload\""), "application");
        assertTrue(page.contains("le runtime précédent est conservé"), "garantie affichée");
        assertTrue(page.contains("aucune récompense n'est redistribuée"), "politique affichée");
    }

    // ---- Les actions ------------------------------------------------------------------------

    @Test
    void aPreviewIsEnqueuedWithTheSelectedFamiliesInDependencyOrder() throws Exception {
        start();
        login();
        freshHeartbeat();
        String token = csrf(get("/ops").body());

        // Exactement ce que le navigateur envoie : une clé par case cochée, dans l'ordre du DOM.
        HttpResponse<String> response = post("/agents/action",
                "type=content.reload.preview&agent=" + TestConfig.AGENT_ID
                        + "&family_dialogues=true&family_quests=true&family_items=true"
                        + "&return=/ops&_csrf=" + token);

        assertEquals(303, response.statusCode());
        AgentActionRow action = latestOfType("content.reload.preview");
        // Réordonné selon les dépendances, quel que soit l'ordre des cases cochées.
        assertEquals("items,quests,dialogues", action.params().get("families"));
    }

    @Test
    void anApplyIsEnqueuedAndRequiresConfirmation() throws Exception {
        start();
        login();
        freshHeartbeat();
        String token = csrf(get("/ops").body());

        HttpResponse<String> withoutConfirm = post("/agents/action",
                "type=content.reload&agent=" + TestConfig.AGENT_ID + "&families=quests"
                        + "&return=/ops&_csrf=" + token);
        assertTrue(withoutConfirm.headers().firstValue("Location").orElse("").contains("err="),
                "appliquer change ce que le serveur utilise pour des joueurs connectés");

        HttpResponse<String> withConfirm = post("/agents/action",
                "type=content.reload&agent=" + TestConfig.AGENT_ID + "&families=quests"
                        + "&confirm=true&return=/ops&_csrf=" + token);
        assertEquals(303, withConfirm.statusCode());
        assertEquals("quests", latestOfType("content.reload").params().get("families"));
    }

    @Test
    void anUnknownFamilyIsRefusedRatherThanPartiallyUnderstood() throws Exception {
        start();
        login();
        freshHeartbeat();
        String token = csrf(get("/ops").body());

        HttpResponse<String> response = post("/agents/action",
                "type=content.reload&agent=" + TestConfig.AGENT_ID
                        + "&families=quests,inventee&confirm=true&return=/ops&_csrf=" + token);

        String location = response.headers().firstValue("Location").orElse("");
        assertTrue(location.contains("err="), location);
        assertTrue(store.recentActions(TestConfig.AGENT_ID, 20).stream()
                .noneMatch(a -> "content.reload".equals(a.type())), "rien ne doit être enfilé");
    }

    @Test
    void noFamilySelectedIsRefusedWithAReadableMessage() throws Exception {
        start();
        login();
        freshHeartbeat();
        String token = csrf(get("/ops").body());

        HttpResponse<String> response = post("/agents/action",
                "type=content.reload&agent=" + TestConfig.AGENT_ID + "&confirm=true&return=/ops&_csrf=" + token);

        String location = response.headers().firstValue("Location").orElse("");
        assertTrue(location.contains("err="), location);
        assertTrue(java.net.URLDecoder.decode(location, StandardCharsets.UTF_8).contains("au moins une famille"),
                location);
    }

    @Test
    void aSuccessfulReloadRequeuesEveryContentCatalogSoTheBadgeDisappearsWithoutF5() throws Exception {
        start();
        login();
        freshHeartbeat();
        String token = csrf(get("/ops").body());
        post("/agents/action", "type=content.reload&agent=" + TestConfig.AGENT_ID
                + "&families=quests&confirm=true&return=/ops&_csrf=" + token);
        AgentActionRow action = latestOfType("content.reload");

        // Par l'ENDPOINT agent : c'est lui qui ré-enfile les relevés, pas le store.
        postAgentResult(action.id(), "{\"action_id\":\"" + action.id()
                + "\",\"status\":\"SUCCESS\",\"value\":\"abc123def456\","
                + "\"message\":\"Quêtes rechargées\",\"details\":{\"applied\":true}}");

        // Critère d'acceptation du ticket : le badge « pas encore chargé en jeu » doit disparaître
        // sans manipulation. Les relevés sont donc ré-enfilés automatiquement par le catalogue.
        List<String> requeued = store.recentActions(TestConfig.AGENT_ID, 50).stream()
                .filter(a -> "auto".equals(a.createdBy()))
                .map(AgentActionRow::type)
                .toList();
        for (String expected : List.of("quest.list", "story.list", "dialogue.list", "npc.list",
                "item.list", "mob.list")) {
            assertTrue(requeued.contains(expected), () -> expected + " attendu parmi " + requeued);
        }
    }

    @Test
    void aFailedReloadRequeuesNothing() throws Exception {
        start();
        login();
        freshHeartbeat();
        String token = csrf(get("/ops").body());
        post("/agents/action", "type=content.reload&agent=" + TestConfig.AGENT_ID
                + "&families=quests&confirm=true&return=/ops&_csrf=" + token);
        AgentActionRow action = latestOfType("content.reload");

        postAgentResult(action.id(), "{\"action_id\":\"" + action.id()
                + "\",\"status\":\"FAILED\",\"message\":\"Contenu invalide : rien n'a été rechargé.\"}");

        assertTrue(store.recentActions(TestConfig.AGENT_ID, 50).stream()
                .noneMatch(a -> "auto".equals(a.createdBy())),
                "un échec ne doit pas faire croire que les catalogues ont changé");
    }

    // ---- Permissions -------------------------------------------------------------------------

    @Test
    void theReloadSpecsCarryTheDedicatedPermissionAndTheRightSensitivity() {
        var preview = AgentActionCatalog.spec("content.reload.preview").orElseThrow();
        var apply = AgentActionCatalog.spec("content.reload").orElseThrow();

        assertEquals(com.lodygames.rpgquest.panel.authz.Permission.ACTION_CONTENT_RELOAD,
                preview.permission());
        assertEquals(com.lodygames.rpgquest.panel.authz.Permission.ACTION_CONTENT_RELOAD,
                apply.permission());
        assertFalse(preview.mutation(), "un aperçu ne touche aucun ensemble actif");
        assertTrue(apply.mutation());
        assertTrue(apply.sensitive(), "appliquer affecte des joueurs connectés");
    }

    // ---- Harnais -----------------------------------------------------------------------------

    /** Résultat posté par l'agent lui-même : c'est ce chemin qui ré-enfile les relevés. */
    private void postAgentResult(String actionId, String body) throws Exception {
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(
                        uri("/agent/v1/actions/" + actionId + "/result"))
                .header("Authorization", "Bearer " + TestConfig.AGENT_TOKEN)
                .header("X-Agent-Id", TestConfig.AGENT_ID)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
    }

    private AgentActionRow latestOfType(String type) {
        return store.recentActions(TestConfig.AGENT_ID, 50).stream()
                .filter(a -> type.equals(a.type()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("aucune action « " + type + " » enfilée"));
    }

    private void start() throws Exception {
        String db = tmp.resolve("cp.db").toString();
        store = new AgentStore(db);
        app = new PanelApp(TestConfig.withAgent(db, "http://127.0.0.1:1/admin/v1"),
                new InMemoryAuditLog(), new BridgeClient(Duration.ofMillis(300), Duration.ofMillis(400)), store);
        port = app.start();
        client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        jar.clear();
    }

    private void login() throws Exception {
        String token = csrf(get("/login").body());
        post("/login", "username=" + TestConfig.OWNER_USERNAME + "&password="
                + URLEncoder.encode(TestConfig.OWNER_PASSWORD, StandardCharsets.UTF_8) + "&_csrf=" + token);
    }

    private void freshHeartbeat() {
        store.saveHeartbeat(new com.lodygames.rpgquest.panel.agent.HeartbeatRecord(
                TestConfig.AGENT_ID, "dev", Instant.now(), Instant.now().toString(), "1",
                "RPGQuest", "0.1.0-SNAPSHOT", "ONLINE", 0, 20, 60, "{}", "{}"));
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
