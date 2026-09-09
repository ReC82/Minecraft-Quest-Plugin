package com.lodygames.rpgquest.panel.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

/** Page {@code /diagnostics} (issue #38) : auth, agrégation, cartes, filtres, recherche, actions, refresh. */
class DiagnosticsPageTest {

    @TempDir
    Path tmp;

    private PanelApp app;
    private int port;
    private HttpClient client;
    private AgentStore store;
    private final Map<String, String> jar = new LinkedHashMap<>();

    private static final String NPC_DETAILS = "{"
            + "\"citizensAvailable\":true,\"definedIds\":[\"guard\"],\"canonicalIds\":[\"guard\",\"guide\"],"
            + "\"npcs\":["
            + "{\"id\":\"guide\",\"displayName\":null,\"warnings\":[{\"code\":\"BINDING_NO_DEFINITION\","
            + "\"severity\":\"error\",\"message\":\"tag sans definition\"}]},"
            + "{\"id\":\"guard\",\"displayName\":\"<yellow>Garde</yellow>\",\"warnings\":[{\"code\":\"NOT_LINKED\","
            + "\"severity\":\"info\",\"message\":\"pas de binding\"}]}"
            + "]}";
    private static final String QUEST_DETAILS = "{\"quests\":[{\"id\":\"rpgquest:crystal_hunt\","
            + "\"title\":\"La chasse\",\"prerequisites\":[\"rpgquest:inexistante\"],\"steps\":[],\"rewards\":[]}]}";

    @AfterEach
    void tearDown() {
        if (app != null) {
            app.stop();
        }
    }

    // ---- tests --------------------------------------------------------------------------

    @Test
    void anonymousIsRedirectedToLogin() throws Exception {
        start();
        HttpResponse<String> r = get("/diagnostics");
        assertEquals(303, r.statusCode());
        assertEquals("/login", r.headers().firstValue("Location").orElse(""));
    }

    @Test
    void emptyStateIsPositiveWhenNoSnapshot() throws Exception {
        start();
        login();
        // aucun relevé de contenu, mais un heartbeat frais -> pas d'erreur serveur
        freshHeartbeat();
        String page = get("/diagnostics?agent=" + TestConfig.AGENT_ID).body();
        assertTrue(page.contains("<h1>Diagnostics</h1>"));
        assertTrue(page.contains("Aucun problème détecté"), "état vide positif");
        assertFalse(page.contains("data-filter-item=\"diag\""), "aucune carte de diagnostic");
    }

    @Test
    void aggregatesAndPresentsHumanFirstCards() throws Exception {
        start();
        login();
        seed("npc.list", NPC_DETAILS);
        seed("quest.list", QUEST_DETAILS);

        String page = get("/diagnostics?agent=" + TestConfig.AGENT_ID).body();

        // header + sous-titre
        assertTrue(page.contains("<h1>Diagnostics</h1>"));
        assertTrue(page.contains("État de cohérence de RPGQuest et problèmes à corriger."));

        // cartes synthétiques
        assertTrue(page.contains(">Erreurs<") || page.contains(">Erreur<"));
        assertTrue(page.contains(">Avertissements<") || page.contains(">Avertissement<"));
        assertTrue(page.contains(">Total<"));

        // une carte : titre HUMAIN, jamais le code comme titre principal
        assertTrue(page.contains("<span>Fiche RPGQuest manquante</span>"), "titre humain");
        assertFalse(page.contains("<span>BINDING_NO_DEFINITION</span>"), "jamais le code comme titre");
        assertTrue(page.contains("<strong>Conséquence :</strong>"));
        assertTrue(page.contains("<strong>À faire :</strong>"));

        // domaine + ressource
        assertTrue(page.contains(">PNJ<") && page.contains("class=\"diag-res\""));

        // code technique EN SECONDAIRE
        assertTrue(page.contains("Code technique : <code class=\"tid\">BINDING_NO_DEFINITION</code>"));

        // actions contextuelles
        assertTrue(page.contains("href=\"/npcs?agent=" + TestConfig.AGENT_ID + "&amp;focus=guide\""), "bouton Ouvrir");
        assertTrue(page.contains(">Créer la définition</a>"), "quick action réutilisant une action sûre");
        assertTrue(page.contains("href=\"/docs/pnj-depannage#fiche-rpgquest-manquante\""), "Comment corriger ? -> ancre précise");
        assertTrue(page.contains("Comment corriger ?</a>"));

        // un WARNING de référence quête
        assertTrue(page.contains("<span>Prérequis inconnu</span>"));
        assertTrue(page.contains("QUEST_PREREQ_UNKNOWN"));

        // tri : la carte ERROR apparaît avant la carte INFO
        assertTrue(page.indexOf("Fiche RPGQuest manquante") < page.indexOf("PNJ pas encore présent en jeu"),
                "ERROR trié avant INFO");
    }

    @Test
    void filtersAndSearchArePresentAndCombinable() throws Exception {
        start();
        login();
        seed("npc.list", NPC_DETAILS);
        seed("quest.list", QUEST_DETAILS);
        String page = get("/diagnostics?agent=" + TestConfig.AGENT_ID).body();

        // recherche
        assertTrue(page.contains("<div class=\"input-group diag-search\">"));
        assertTrue(page.contains("data-filter-input=\"diag\""));
        // deux groupes de puces pour le même scope (gravité + domaine) -> combinables
        assertEquals(2, count(page, "data-filter-chips=\"diag\""), "un groupe gravité + un groupe domaine");
        assertTrue(page.contains("data-filter-chip=\"err\"") && page.contains("data-filter-chip=\"info\""));
        assertTrue(page.contains("data-filter-chip=\"pnj\"") && page.contains("data-filter-chip=\"quests\""));
        // chaque carte porte sa catégorie combinée (gravité + domaine) + son texte de recherche
        assertTrue(page.contains("data-filter-cat=\"err pnj\"") || page.contains("data-filter-cat=\"info pnj\""));
        assertTrue(page.contains("data-filter-text="));
        // panel.js gère plusieurs groupes de puces (rétro-compatible)
        String js = get("/assets/panel.js").body();
        assertTrue(js.contains("querySelectorAll('[data-filter-chips=\"'"), "panel.js : plusieurs groupes de puces");
    }

    @Test
    void groupsIdenticalDiagnosticsAboveThreshold() throws Exception {
        start();
        login();
        StringBuilder npcs = new StringBuilder("{\"citizensAvailable\":true,\"npcs\":[");
        for (int i = 0; i < 5; i++) {
            npcs.append(i > 0 ? "," : "").append("{\"id\":\"npc").append(i).append("\",\"displayName\":null,")
                    .append("\"warnings\":[{\"code\":\"BINDING_NO_DEFINITION\",\"severity\":\"error\",\"message\":\"x\"}]}");
        }
        npcs.append("]}");
        seed("npc.list", npcs.toString());
        String page = get("/diagnostics?agent=" + TestConfig.AGENT_ID).body();
        assertTrue(page.contains("class=\"diag-group\""), "regroupement au-delà du seuil");
        assertTrue(page.contains("5 PNJ concernés"));
        assertTrue(page.contains("<details class=\"diag-group-more\"><summary>Voir les 5</summary>"));
    }

    @Test
    void refreshEnqueuesCoordinatedListActionsAndToasts() throws Exception {
        start();
        login();
        String token = csrf(get("/diagnostics?agent=" + TestConfig.AGENT_ID).body());
        HttpResponse<String> r = post("/diagnostics/refresh",
                "_csrf=" + token + "&agent=" + TestConfig.AGENT_ID);
        assertEquals(303, r.statusCode());
        String loc = r.headers().firstValue("Location").orElse("");
        assertTrue(loc.startsWith("/diagnostics?agent=") && loc.contains("&toast="), loc);
        // exactement les 4 relevés dont dépendent les diagnostics, pas dix
        assertEquals(4, store.recentActions(TestConfig.AGENT_ID, 20).size());
    }

    @Test
    void refreshRequiresCsrf() throws Exception {
        start();
        login();
        HttpResponse<String> r = post("/diagnostics/refresh", "agent=" + TestConfig.AGENT_ID);
        assertEquals(403, r.statusCode());
        assertEquals(0, store.recentActions(TestConfig.AGENT_ID, 20).size());
    }

    @Test
    void homeTileShowsCountersAndDashboardShowsSummary() throws Exception {
        start();
        login();
        seed("npc.list", NPC_DETAILS);
        seed("quest.list", QUEST_DETAILS);

        String home = get("/home").body();
        assertTrue(home.contains("class=\"home-tile\" href=\"/diagnostics\""), "tuile Diagnostics active");
        assertTrue(home.contains("erreur"), "compteur d'erreurs sur la tuile");

        String dash = get("/dashboard").body();
        assertTrue(dash.contains("État du contenu"));
        assertTrue(dash.contains("Voir les diagnostics"));
    }

    @Test
    void navSidebarLinksToDiagnostics() throws Exception {
        start();
        login();
        String page = get("/diagnostics?agent=" + TestConfig.AGENT_ID).body();
        assertTrue(page.contains("class=\"navlink active\" href=\"/diagnostics\""), "entrée sidebar active");
        assertFalse(page.contains("Diagnostics</span><em class=\"nav-soon\">"), "plus « bientôt »");
    }

    // ---- helpers ----------------------------------------------------------------------

    private void seed(String type, String detailsJson) {
        String id = store.createAction(TestConfig.AGENT_ID, type, Map.of(), "test");
        store.recordResult(id, TestConfig.AGENT_ID, AgentActionStatus.SUCCESS, "1", "ok",
                "{\"action_id\":\"" + id + "\",\"status\":\"SUCCESS\",\"details\":" + detailsJson + "}", Instant.now());
    }

    private void freshHeartbeat() {
        store.saveHeartbeat(new com.lodygames.rpgquest.panel.agent.HeartbeatRecord(
                TestConfig.AGENT_ID, "dev", Instant.now(), null, "agent/v1", "RPGQuest", "7.7.7",
                "ONLINE", 0, 30, 10, "{}", "{}"));
    }

    private static int count(String hay, String needle) {
        int n = 0;
        for (int i = hay.indexOf(needle); i >= 0; i = hay.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
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
