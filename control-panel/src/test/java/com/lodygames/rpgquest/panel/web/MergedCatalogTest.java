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
import java.nio.file.Files;
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
 * Catalogue fusionné source + runtime (issue #144) : une quête / story enregistrée dans la source
 * éditable doit apparaître immédiatement dans {@code /quests} · {@code /stories} et dans les lookups
 * d'édition, avec un état explicite, sans redémarrage Minecraft — mais jamais présentée comme déjà
 * active en jeu.
 */
class MergedCatalogTest {

    @TempDir
    Path tmp;

    private PanelApp app;
    private int port;
    private HttpClient client;
    private Path contentRoot;
    private final Map<String, String> jar = new LinkedHashMap<>();

    private static final String LILY_PUMPKIN_YAML = """
            id: rpgquest:lily_pumpkin
            title: "Une citrouille pour Lily"
            description: "Lily veut une citrouille."
            category: "lily"
            icon: BOOK
            repeatable: false
            giver: lily

            steps:
              - id: pumpkin_ingredient
                objectives:
                  - type: COLLECT_ITEM
                    material: PUMPKIN
                    amount: 1
            """;

    @AfterEach
    void tearDown() {
        if (app != null) {
            app.stop();
        }
    }

    // ---- A : quête runtime uniquement -----------------------------------------------------

    @Test
    void runtimeOnlyQuestIsVisibleAndFlaggedOutOfSource() throws Exception {
        start();
        runQuestList("[{\"id\":\"rpgquest:crystal_hunt\",\"title\":\"Chasse\",\"category\":\"c\","
                + "\"repeatable\":false,\"steps\":[],\"rewards\":[]}]");
        String cat = questCatalog();
        assertTrue(cat.contains("rpgquest:crystal_hunt"), "quête runtime visible");
        assertTrue(cat.contains(">Hors source</span>"), "badge « hors source » : pas de fichier source");
    }

    // ---- B : quête source uniquement -----------------------------------------------------

    @Test
    void sourceOnlyQuestIsVisibleWithSourceOnlyBadge() throws Exception {
        start();
        writeQuest("lily_pumpkin", LILY_PUMPKIN_YAML);
        runQuestList("[]"); // relevé runtime vide

        String cat = questCatalog();
        assertTrue(cat.contains("Une citrouille pour Lily"), "titre humain de la quête source");
        assertTrue(cat.contains("rpgquest:lily_pumpkin"), "id technique conservé");
        assertTrue(cat.contains(">Source uniquement</span>"), "badge d'état source-only");
        assertTrue(cat.contains("prochain rechargement du contenu RPGQuest"),
                "note : pas encore chargée en jeu");
        // objectif relu depuis la source (verbe FR + jeton technique conservé)
        assertTrue(cat.contains("Collecter"), "objectif source rendu en clair");
        assertTrue(cat.contains("PUMPKIN"), "jeton technique de l'objectif conservé");
    }

    // ---- C : quête source + runtime = une seule entrée ---------------------------------

    @Test
    void sourceAndRuntimeQuestMergeIntoASingleEntry() throws Exception {
        start();
        writeQuest("lily_pumpkin", LILY_PUMPKIN_YAML);
        runQuestList("[{\"id\":\"rpgquest:lily_pumpkin\",\"title\":\"Une citrouille pour Lily\","
                + "\"category\":\"lily\",\"repeatable\":false,\"steps\":[],\"rewards\":[]}]");

        String cat = questCatalog();
        assertEquals(1, countOccurrences(cat, "data-res-id=\"rpgquest:lily_pumpkin\""),
                "une seule ligne d'accordion pour l'id fusionné");
        assertTrue(cat.contains("1 quête(s)"), "compteur = 1 (pas 2)");
        assertFalse(cat.contains(">Source uniquement</span>"), "état = synchronisé, pas de badge");
        assertFalse(cat.contains(">Hors source</span>"));
    }

    // ---- D : création via /quests/new visible au retour catalogue --------------------

    @Test
    void questCreatedFromEditorShowsUpInCatalogWithoutAgentCall() throws Exception {
        start();
        String token = csrf(get("/quests/new").body());
        String form = "id=lily_pumpkin&title=" + enc("Une citrouille pour Lily")
                + "&description=" + enc("desc") + "&category=lily&icon=BOOK"
                + "&step.0.id=pumpkin_ingredient"
                + "&obj.0.0.kind=COLLECT_ITEM&obj.0.0.material=PUMPKIN&obj.0.0.amount=1"
                + "&_csrf=" + token + "&_action=save";
        assertEquals(303, post("/quests/save", form).statusCode());

        // aucun relevé quest.list n'a jamais réussi : le catalogue voit quand même la source
        String cat = questCatalog();
        assertTrue(cat.contains("rpgquest:lily_pumpkin"), "quête créée visible sans appel agent");
        assertTrue(cat.contains(">Source uniquement</span>"));
    }

    // ---- E : disponible dans le lookup /stories/new --------------------------------------

    @Test
    void newSourceQuestIsAvailableInStoryEditorLookup() throws Exception {
        start();
        writeQuest("lily_pumpkin", LILY_PUMPKIN_YAML);
        String body = get("/stories/new").body();
        String datalist = slice(body, "<datalist id=\"dl-quest\">", "</datalist>");
        assertTrue(datalist.contains("value=\"lily_pumpkin\""), "quête source proposée à la composition de story");
        assertTrue(datalist.contains("Une citrouille pour Lily"), "titre humain dans le lookup");
    }

    // ---- F : disponible comme prérequis dans l'éditeur de quête -------------------------

    @Test
    void newSourceQuestIsAvailableAsPrerequisiteLookup() throws Exception {
        start();
        writeQuest("lily_pumpkin", LILY_PUMPKIN_YAML);
        String body = get("/quests/new").body();
        String datalist = slice(body, "<datalist id=\"dl-quest\">", "</datalist>");
        assertTrue(datalist.contains("value=\"lily_pumpkin\""), "quête source disponible en prérequis");
    }

    // ---- G : un refresh runtime ne fait pas disparaître une quête source-only -----------

    @Test
    void runtimeRefreshKeepsSourceOnlyQuestVisible() throws Exception {
        start();
        writeQuest("lily_pumpkin", LILY_PUMPKIN_YAML);
        // relevé runtime SUCCESS qui ne contient PAS lily_pumpkin
        runQuestList("[{\"id\":\"rpgquest:crystal_hunt\",\"title\":\"Chasse\",\"category\":\"c\","
                + "\"repeatable\":false,\"steps\":[],\"rewards\":[]}]");

        String cat = questCatalog();
        assertTrue(cat.contains("rpgquest:crystal_hunt"), "quête runtime présente");
        assertTrue(cat.contains("rpgquest:lily_pumpkin"), "quête source-only toujours présente après refresh runtime");
        assertTrue(cat.contains(">Source uniquement</span>"));
        assertTrue(cat.contains("2 quête(s)"));
    }

    // ---- H : aucune confusion « source » / « actif en jeu » ----------------------------

    @Test
    void sourceOnlyQuestNeverClaimsToBeActiveInGame() throws Exception {
        start();
        writeQuest("lily_pumpkin", LILY_PUMPKIN_YAML);
        runQuestList("[]");
        String cat = questCatalog();
        int item = cat.indexOf("data-res-id=\"rpgquest:lily_pumpkin\"");
        String block = cat.substring(item);
        assertTrue(block.contains("n'est pas encore chargée par le serveur DEV"),
                "le tooltip dit explicitement : pas encore chargée");
        // le badge « OK » de synthèse (cohérence des références) reste autorisé, mais jamais un
        // libellé qui laisserait croire que le contenu est déjà live.
        assertFalse(block.contains("active en jeu") || block.contains("chargée par le serveur.</"),
                "aucune affirmation d'activité runtime");
    }

    // ---- I : édition d'une quête source existante reflétée immédiatement ----------------

    @Test
    void editingAnExistingSourceQuestIsReflectedInTheCatalog() throws Exception {
        start();
        writeQuest("lily_pumpkin", LILY_PUMPKIN_YAML);
        assertTrue(questCatalog().contains("Une citrouille pour Lily"));

        writeQuest("lily_pumpkin", LILY_PUMPKIN_YAML.replace("Une citrouille pour Lily", "Deux citrouilles pour Lily"));
        String cat = questCatalog();
        assertTrue(cat.contains("Deux citrouilles pour Lily"), "le catalogue relit la source à chaque affichage");
        assertFalse(cat.contains("Une citrouille pour Lily"));
    }

    // ---- stories : source-only visible + badge -----------------------------------------

    @Test
    void sourceOnlyStoryIsVisibleWithBadge() throws Exception {
        start();
        Files.writeString(contentRoot.resolve("stories/lily_memories.yml"),
                "id: lily_memories\nname: \"Souvenirs de Lily\"\nquests:\n  - rpgquest:lily_pumpkin\n");
        String cat = storyCatalog();
        assertTrue(cat.contains("Souvenirs de Lily"), "story source visible");
        assertTrue(cat.contains(">Source uniquement</span>"), "badge d'état source-only sur la story");
    }

    // ---- helpers ----------------------------------------------------------------------

    private String questCatalog() throws Exception {
        return section(get("/quests?agent=" + TestConfig.AGENT_ID).body());
    }

    private String storyCatalog() throws Exception {
        return section(get("/stories?agent=" + TestConfig.AGENT_ID).body());
    }

    private static String section(String page) {
        int a = page.indexOf("<h2>Catalogue</h2>");
        int b = page.indexOf("<h2>", a + 4);
        return a < 0 ? page : page.substring(a, b < 0 ? page.length() : b);
    }

    private static String slice(String s, String from, String to) {
        int a = s.indexOf(from);
        if (a < 0) {
            return "";
        }
        int b = s.indexOf(to, a + from.length());
        return b < 0 ? s.substring(a) : s.substring(a, b);
    }

    private static int countOccurrences(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
    }

    private void writeQuest(String slug, String yaml) throws Exception {
        Files.writeString(contentRoot.resolve("quests/" + slug + ".yml"), yaml);
    }

    private void runQuestList(String questsJsonArray) throws Exception {
        String token = csrf(get("/quests?agent=" + TestConfig.AGENT_ID).body());
        post("/agents/action", "_csrf=" + token + "&type=quest.list&agent=" + TestConfig.AGENT_ID + "&return=/quests");
        HttpResponse<String> poll = client.send(HttpRequest.newBuilder(uri("/agent/v1/actions"))
                .header("Authorization", "Bearer " + TestConfig.AGENT_TOKEN)
                .header("X-Agent-Id", TestConfig.AGENT_ID).GET().build(), HttpResponse.BodyHandlers.ofString());
        Matcher m = Pattern.compile("\"id\"\\s*:\\s*\"([0-9a-f-]{36})\"").matcher(poll.body());
        if (!m.find()) {
            throw new AssertionError("action quest.list non relevée");
        }
        String id = m.group(1);
        String result = "{\"action_id\":\"" + id + "\",\"status\":\"SUCCESS\",\"value\":\"1\","
                + "\"message\":\"ok\",\"details\":{\"quests\":" + questsJsonArray + "}}";
        client.send(HttpRequest.newBuilder(uri("/agent/v1/actions/" + id + "/result"))
                .header("Authorization", "Bearer " + TestConfig.AGENT_TOKEN)
                .header("X-Agent-Id", TestConfig.AGENT_ID)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(result)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private void start() throws Exception {
        contentRoot = tmp.resolve("content");
        Files.createDirectories(contentRoot.resolve("quests"));
        Files.createDirectories(contentRoot.resolve("stories"));
        String db = tmp.resolve("cp.db").toString();
        app = new PanelApp(
                TestConfig.withContentDir(db, "http://127.0.0.1:1/admin/v1", contentRoot.toString()),
                new InMemoryAuditLog(),
                new BridgeClient(Duration.ofMillis(300), Duration.ofMillis(400)),
                new AgentStore(db));
        port = app.start();
        client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        jar.clear();
        String token = csrf(get("/login").body());
        post("/login", "username=" + TestConfig.OWNER_USERNAME + "&password="
                + enc(TestConfig.OWNER_PASSWORD) + "&_csrf=" + token);
    }

    private static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }

    private static String csrf(String html) {
        Matcher m = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"").matcher(html);
        if (!m.find()) {
            throw new IllegalStateException("pas de jeton _csrf");
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
