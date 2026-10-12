package com.lodygames.rpgquest.panel.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.panel.agent.AgentActionRow;
import com.lodygames.rpgquest.panel.agent.AgentActionStatus;
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
 * Issue #241 — sur la fiche d'une quête, les actions admin visent CETTE quête ; aucun sélecteur ne
 * retombe en silence sur une quête sans rapport (cas réel : {@code crystal_hunt} affichée sur la fiche
 * de {@code test_smelt_item}).
 */
class QuestContextualActionsTest {

    /** {@code crystal_hunt} en PREMIER : c'est ce qui la faisait apparaître par défaut. */
    private static final String QUESTS = "{\"quests\":["
            + "{\"id\":\"rpgquest:crystal_hunt\",\"title\":\"Chasse au cristal\",\"steps\":[]},"
            + "{\"id\":\"rpgquest:test_smelt_item\",\"title\":\"<gold>Test four</gold>\",\"steps\":[]}"
            + "],\"total\":2}";

    @TempDir
    Path tmp;

    private PanelApp app;
    private AgentStore store;
    private int port;
    private HttpClient client;
    private final Map<String, String> jar = new LinkedHashMap<>();

    @AfterEach
    void tearDown() {
        if (app != null) {
            app.stop();
        }
    }

    @Test
    void theOpenQuestCardOffersActionsOnThatQuest() throws Exception {
        start();
        String page = get("/quests?agent=" + TestConfig.AGENT_ID + "&player=LoDyMcFly"
                + "&focus=rpgquest:test_smelt_item").body();

        String card = cardOf(page, "rpgquest:test_smelt_item");
        assertTrue(card.contains("accordion-collapse collapse show"), "la fiche ouverte reste dépliée");
        assertTrue(card.contains("Démarrer cette quête"), card);
        assertTrue(card.contains("Compléter cette quête"));
        assertTrue(card.contains("Réinitialiser cette quête"));
        assertTrue(card.contains("name=\"quest_id\" value=\"rpgquest:test_smelt_item\""),
                "chaque bouton vise la quête de la fiche");
        assertFalse(card.contains("crystal_hunt"), "aucune autre quête dans la fiche");
        assertTrue(card.contains("LoDyMcFly"), "le joueur visé est nommé");
        assertTrue(card.contains("Test four") && !card.contains("&lt;gold&gt;"), "titre rendu");

        assertFalse(cardOf(page, "rpgquest:crystal_hunt").contains("collapse show"),
                "seule la fiche ouverte est dépliée");
    }

    @Test
    void theGenericSelectorStartsFromTheOpenQuestThenFromTheOther() throws Exception {
        start();
        String a = get("/quests?agent=" + TestConfig.AGENT_ID + "&player=LoDyMcFly"
                + "&focus=rpgquest:test_smelt_item").body();
        assertTrue(a.contains("<option value=\"rpgquest:test_smelt_item\" selected>"), "fiche A → A");
        assertFalse(a.contains("<option value=\"rpgquest:crystal_hunt\" selected>"));

        String b = get("/quests?agent=" + TestConfig.AGENT_ID + "&player=LoDyMcFly"
                + "&focus=rpgquest:crystal_hunt").body();
        assertTrue(b.contains("<option value=\"rpgquest:crystal_hunt\" selected>"), "fiche B → B");
    }

    @Test
    void withoutAnOpenQuestNothingIsPreselected() throws Exception {
        start();
        String page = get("/quests?agent=" + TestConfig.AGENT_ID + "&player=LoDyMcFly").body();
        assertFalse(page.contains("\" selected>rpgquest:"), "aucune quête choisie à la place de l'admin");
        assertTrue(page.contains("<option value=\"\" selected>— choisir une quête —</option>"), page.length() + "");
        assertTrue(page.contains("<select name=\"quest_id\" required>"), "le choix est obligatoire");
    }

    @Test
    void withoutAPlayerTheCardSaysHowAndKeepsTheContext() throws Exception {
        start();
        String page = get("/quests?agent=" + TestConfig.AGENT_ID + "&focus=rpgquest:test_smelt_item").body();
        String card = cardOf(page, "rpgquest:test_smelt_item");
        assertTrue(card.contains("Choisir d'abord un joueur") || card.contains("Choisir d&#39;abord un joueur"), card);
        assertTrue(card.contains("focus=rpgquest:test_smelt_item#etat-joueur"), "le lien garde la fiche");
        assertTrue(page.contains("<input type=\"hidden\" name=\"focus\" value=\"rpgquest:test_smelt_item\">"),
                "le formulaire de sélection du joueur conserve la quête ouverte");
    }

    @Test
    void startingFromTheCardTargetsThatQuestAndComesBackToIt() throws Exception {
        start();
        String page = get("/quests?agent=" + TestConfig.AGENT_ID + "&player=LoDyMcFly"
                + "&focus=rpgquest:test_smelt_item").body();

        HttpResponse<String> res = post("/agents/action", "_csrf=" + csrf(page) + "&agent=" + TestConfig.AGENT_ID
                + "&type=quest.start&return=/quests&player=LoDyMcFly&quest_id=rpgquest:test_smelt_item"
                + "&focus=rpgquest:test_smelt_item&confirm=true");

        assertEquals(303, res.statusCode());
        String location = res.headers().firstValue("Location").orElse("");
        assertTrue(location.contains("focus=rpgquest%3Atest_smelt_item"), location);
        AgentActionRow queued = store.recentActions(TestConfig.AGENT_ID, 50).stream()
                .filter(a -> a.type().equals("quest.start")).findFirst().orElseThrow();
        assertEquals("rpgquest:test_smelt_item", queued.params().get("quest_id"));
    }

    @Test
    void aForgedFocusIsDropped() throws Exception {
        start();
        String page = get("/quests?agent=" + TestConfig.AGENT_ID + "&player=LoDyMcFly").body();
        HttpResponse<String> res = post("/agents/action", "_csrf=" + csrf(page) + "&agent=" + TestConfig.AGENT_ID
                + "&type=quest.start&return=/quests&player=LoDyMcFly&quest_id=rpgquest:crystal_hunt"
                + "&focus=" + URLEncoder.encode("x\"><script>", StandardCharsets.UTF_8) + "&confirm=true");
        assertFalse(res.headers().firstValue("Location").orElse("").contains("focus="));
    }

    @Test
    void aQuestNotLoadedByTheServerExplainsWhy() throws Exception {
        start();
        String page = get("/quests?agent=" + TestConfig.AGENT_ID + "&player=LoDyMcFly&focus=rpgquest:absente").body();
        assertFalse(page.contains("\" selected>rpgquest:"), "une quête inconnue n'est pas présélectionnée");
    }

    // ---- Harnais -------------------------------------------------------------------------------

    /** Le bloc HTML de la fiche d'une quête (de son item d'accordéon au suivant). */
    private static String cardOf(String page, String questId) {
        int at = page.indexOf("data-res-id=\"" + questId + "\"");
        if (at < 0) {
            at = page.indexOf("<code class=\"tid npc-id\">" + questId + "</code>");
        }
        assertTrue(at >= 0, "fiche introuvable : " + questId);
        int start = page.lastIndexOf("<div class=\"accordion-item", at);
        int end = page.indexOf("<div class=\"accordion-item", at + 1);
        int section = page.indexOf("id=\"etat-joueur\"", at);
        if (end < 0 || (section >= 0 && section < end)) {
            end = section; // dernière fiche : elle s'arrête à la section joueur
        }
        return page.substring(start, end < 0 ? page.length() : end);
    }

    private void start() throws Exception {
        String db = tmp.resolve("cp.db").toString();
        store = new AgentStore(db);
        app = new PanelApp(TestConfig.withAgent(db, "http://127.0.0.1:1/admin/v1"), new InMemoryAuditLog(),
                new BridgeClient(Duration.ofMillis(300), Duration.ofMillis(400)), store);
        port = app.start();
        client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        jar.clear();
        String token = csrf(get("/login").body());
        post("/login", "username=" + TestConfig.OWNER_USERNAME + "&password="
                + URLEncoder.encode(TestConfig.OWNER_PASSWORD, StandardCharsets.UTF_8) + "&_csrf=" + token);
        String id = store.createAction(TestConfig.AGENT_ID, "quest.list", Map.of(), "owner");
        store.recordResult(id, TestConfig.AGENT_ID, AgentActionStatus.SUCCESS, "2", "2 quête(s)",
                "{\"action_id\":\"" + id + "\",\"status\":\"SUCCESS\",\"details\":" + QUESTS + "}", Instant.now());
    }

    private static String csrf(String html) {
        Matcher m = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"").matcher(html);
        if (!m.find()) {
            throw new IllegalStateException("pas de jeton CSRF");
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
