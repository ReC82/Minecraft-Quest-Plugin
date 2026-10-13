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
 * Catalogue de dialogues fusionné source + runtime (issue #145) : un dialogue créé depuis
 * {@code /dialogues/new} apparaît immédiatement dans {@code /dialogues} avec un état explicite, sans
 * redémarrage Minecraft — et jamais présenté comme déjà actif en jeu. Couvre aussi la création avec
 * PNJ pré-sélectionné et le rattachement via {@code npc.definition.update}.
 */
class DialogueSourceMergeTest {

    @TempDir
    Path tmp;

    private PanelApp app;
    private int port;
    private HttpClient client;
    private Path contentRoot;
    private final Map<String, String> jar = new LinkedHashMap<>();

    private static final String GUARD_RUNTIME = "{\"id\":\"rpgquest:guard\",\"key\":\"guard\","
            + "\"startNodeId\":\"greeting\",\"linkedNpcIds\":[\"guard\"],\"nodeCount\":1,\"choiceCount\":1,"
            + "\"referencedQuestIds\":[],\"startsQuestIds\":[],\"warnings\":[],\"nodes\":[{\"id\":\"greeting\","
            + "\"speaker\":\"Garde\",\"text\":\"Bonjour.\",\"start\":true,\"reachable\":true,\"choices\":[]}]}";

    private static final String LILY_INTRO_YAML = "# Dialogue RPGQuest\n"
            + "id: rpgquest:lily_intro\nstart: start\nnodes:\n  start:\n    speaker: \"Lily\"\n"
            + "    text: \"<yellow>Bonjour.</yellow>\"\n    choices:\n      - text: \"Au revoir\"\n"
            + "        actions:\n          - type: CLOSE\n";

    @AfterEach
    void tearDown() {
        if (app != null) {
            app.stop();
        }
    }

    // ---- A : dialogue runtime uniquement -> visible + « Hors source » --------------------

    @Test
    void runtimeOnlyDialogueIsVisibleAndFlaggedOutOfSource() throws Exception {
        start();
        runDialogueList("[" + GUARD_RUNTIME + "]");
        String cat = catalog();
        assertTrue(cat.contains("data-res-id=\"rpgquest:guard\""), "dialogue runtime visible");
        assertTrue(cat.contains(">Hors source</span>"), "badge « hors source »");
    }

    // ---- B : dialogue source uniquement -> visible + « Source uniquement » ---------------

    @Test
    void sourceOnlyDialogueIsVisibleWithBadgeAndNote() throws Exception {
        start();
        writeDialogue("lily_intro", LILY_INTRO_YAML);
        String cat = catalog();
        assertTrue(cat.contains("data-res-id=\"rpgquest:lily_intro\""), "dialogue source visible sans relevé runtime");
        assertTrue(cat.contains(">Source uniquement</span>"), "badge d'état source-only");
        assertTrue(cat.contains("pas encore chargé par le serveur DEV"), "note : pas encore chargé en jeu");
    }

    // ---- C : source + runtime même id -> une seule entrée -------------------------------

    @Test
    void sourceAndRuntimeSameIdMergeIntoASingleEntry() throws Exception {
        start();
        writeDialogue("lily_intro", LILY_INTRO_YAML);
        runDialogueList("[{\"id\":\"rpgquest:lily_intro\",\"key\":\"lily_intro\",\"startNodeId\":\"start\","
                + "\"linkedNpcIds\":[],\"nodeCount\":1,\"choiceCount\":1,\"referencedQuestIds\":[],"
                + "\"startsQuestIds\":[],\"warnings\":[],\"nodes\":[]}]");
        String cat = catalog();
        assertEquals(1, count(cat, "data-res-id=\"rpgquest:lily_intro\""), "une seule ligne pour l'id fusionné");
        assertTrue(cat.contains("1 dialogue(s)"), "compteur = 1");
        assertFalse(cat.contains(">Source uniquement</span>"), "état synchronisé : pas de badge");
        assertFalse(cat.contains(">Hors source</span>"));
    }

    // ---- D : création via « Créer un dialogue » visible au retour catalogue (#332) --------

    @Test
    void dialogueCreatedFromEditorShowsUpImmediatelyWithoutAgentCall() throws Exception {
        start();
        String token = csrf(get("/dialogues/new").body());
        HttpResponse<String> save = post("/dialogues/create", "name=" + enc("Lily intro") + "&first_line="
                + enc("Bonjour.") + "&_csrf=" + token);
        assertEquals(303, save.statusCode());
        assertTrue(save.headers().firstValue("Location").orElse("").startsWith("/dialogues/edit/lily_intro"));

        String cat = catalog();
        assertTrue(cat.contains("data-res-id=\"rpgquest:lily_intro\""), "dialogue créé visible sans appel agent");
        assertTrue(cat.contains(">Source uniquement</span>"));
        String yaml = Files.readString(contentRoot.resolve("dialogues/lily_intro.yml"));
        assertTrue(yaml.contains("id: rpgquest:lily_intro"));
        assertTrue(yaml.contains("Bonjour."));
        assertTrue(yaml.contains("- type: CLOSE"));
    }

    // ---- E : un refresh runtime ne fait pas disparaître un dialogue source-only ---------

    @Test
    void runtimeRefreshKeepsSourceOnlyDialogueVisible() throws Exception {
        start();
        writeDialogue("lily_intro", LILY_INTRO_YAML);
        runDialogueList("[" + GUARD_RUNTIME + "]"); // relevé SUCCESS sans lily_intro
        String cat = catalog();
        assertTrue(cat.contains("data-res-id=\"rpgquest:guard\""), "dialogue runtime présent");
        assertTrue(cat.contains("data-res-id=\"rpgquest:lily_intro\""), "source-only toujours là après refresh runtime");
        assertTrue(cat.contains(">Source uniquement</span>"));
        assertTrue(cat.contains("2 dialogue(s)"));
    }

    // ---- F/G : le formulaire de création propose le PNJ par son nom, présélectionné ----------

    @Test
    void createFormOffersExistingNpcByName() throws Exception {
        start();
        runNpcList("[{\"id\":\"lily\",\"displayName\":\"Lily\",\"logicalDefinitionPresent\":true,"
                + "\"enabled\":true,\"role\":\"villager\"}]");
        assertTrue(get("/dialogues/new").body().contains("<option value=\"lily\">Lily</option>"), "par son nom");
        assertTrue(get("/dialogues/new?npc=lily").body().contains("<option value=\"lily\" selected>Lily</option>"),
                "présélectionné depuis sa fiche");
    }

    // ---- H : un PNJ sans dialogue reçoit le sien par son nom, sans rattachement à envoyer --------

    @Test
    void creatingForAnNpcWithoutDialogueUsesItsName() throws Exception {
        start();
        runNpcList("[{\"id\":\"lily\",\"displayName\":\"Lily\",\"logicalDefinitionPresent\":true,"
                + "\"enabled\":true,\"role\":\"villager\"}]");
        String token = csrf(get("/dialogues/new?npc=lily").body());
        HttpResponse<String> save = post("/dialogues/create", "npc=lily&first_line=" + enc("Bonjour.")
                + "&_csrf=" + token);
        assertEquals(303, save.statusCode());
        assertTrue(save.headers().firstValue("Location").orElse("").startsWith("/dialogues/edit/lily"));
        assertTrue(Files.readString(contentRoot.resolve("dialogues/lily.yml")).contains("speaker: \"Lily\""),
                "dit par Lily");
    }

    // ---- I : dialogue source-only sélectionnable depuis un formulaire PNJ --------------

    @Test
    void sourceOnlyDialogueIsSelectableFromNpcForm() throws Exception {
        start();
        writeDialogue("lily_intro", LILY_INTRO_YAML);
        runNpcList("[{\"id\":\"lily\",\"displayName\":\"Lily\",\"logicalDefinitionPresent\":true,"
                + "\"citizensBindingPresent\":false,\"enabled\":true,\"state\":\"NOT_LINKED\",\"warnings\":[]}]");
        String npcs = get("/npcs?agent=" + TestConfig.AGENT_ID).body();
        assertTrue(npcs.contains("value=\"rpgquest:lily_intro\""),
                "dialogue source-only proposé dans le select Dialogue de la fiche PNJ");
    }

    // ---- J : aucun faux état « actif en jeu » -----------------------------------------

    @Test
    void sourceOnlyDialogueNeverClaimsToBeActiveInGame() throws Exception {
        start();
        writeDialogue("lily_intro", LILY_INTRO_YAML);
        String cat = catalog();
        int item = cat.indexOf("data-res-id=\"rpgquest:lily_intro\"");
        String block = cat.substring(item);
        assertTrue(block.contains("pas encore chargé par le serveur DEV"), "dit explicitement : pas encore chargé");
        assertFalse(block.contains("actif en jeu") || block.contains("chargé en jeu.</"),
                "aucune affirmation d'activité runtime");
    }

    // ---- K : un dialogue que l'éditeur ne sait pas relire fidèlement n'est jamais réécrit ------

    @Test
    void aDialogueTheEditorCannotReadFaithfullyIsNeverOverwritten() throws Exception {
        start();
        // Texte de nœud localisé : le moteur l'accepte, l'éditeur ne sait pas le représenter.
        String localized = "id: rpgquest:lily\nstart: start\nnodes:\n  start:\n    speaker: \"Lily\"\n"
                + "    text:\n      default: \"Bonjour\"\n      en: \"Hello\"\n    choices:\n"
                + "      - text: \"Bye\"\n        actions:\n          - type: CLOSE\n";
        writeDialogue("lily", localized);

        String page = get("/dialogues/edit/lily").body();
        assertTrue(page.contains("lecture seule"), "la limite est dite avant toute édition");
        assertFalse(page.contains("value=\"save_publish\""), "aucun bouton d'enregistrement");

        HttpResponse<String> save = post("/dialogues/save", "slug=lily&n_count=1&n0_id=start&n0_speaker=Lily"
                + "&n0_text=" + enc("Bonsoir") + "&n0_c_count=0&_op=save&_csrf=" + csrf(page)
                + "&expected_sha=" + sha("dialogues", "lily"));
        assertEquals(303, save.statusCode(), "renvoyé vers l'éditeur en lecture seule");
        assertEquals(localized, Files.readString(contentRoot.resolve("dialogues/lily.yml")), "fichier intact");
    }

    private String sha(String kind, String slug) throws Exception {
        byte[] bytes = Files.readAllBytes(contentRoot.resolve(kind + "/" + slug + ".yml"));
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest(bytes)) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    // ---- helpers ----------------------------------------------------------------------

    private String catalog() throws Exception {
        String page = get("/dialogues/diagnostic?agent=" + TestConfig.AGENT_ID).body();
        int a = page.indexOf("<h2>Catalogue</h2>");
        return a < 0 ? page : page.substring(a);
    }

    private static String slice(String s, String from, String to) {
        int a = s.indexOf(from);
        if (a < 0) {
            return "";
        }
        int b = s.indexOf(to, a + from.length());
        return b < 0 ? s.substring(a) : s.substring(a, b);
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
    }

    private void writeDialogue(String slug, String yaml) throws Exception {
        Files.writeString(contentRoot.resolve("dialogues/" + slug + ".yml"), yaml);
    }

    private void runDialogueList(String dialoguesJsonArray) throws Exception {
        runList("dialogue.list", "/dialogues", "{\"dialogues\":" + dialoguesJsonArray
                + ",\"loadIssues\":[],\"declaredButMissing\":[],\"total\":1,\"withWarnings\":0,\"nodeTotal\":1}");
    }

    private void runNpcList(String npcsJsonArray) throws Exception {
        runList("npc.list", "/npcs", "{\"npcs\":" + npcsJsonArray + ",\"citizensAvailable\":true,"
                + "\"total\":1,\"withDefinition\":1,\"withoutDefinition\":0,\"bound\":0,\"withWarnings\":0}");
    }

    private void runList(String type, String returnPath, String detailsJson) throws Exception {
        String token = csrf(get(returnPath + "?agent=" + TestConfig.AGENT_ID).body());
        post("/agents/action", "_csrf=" + token + "&type=" + type + "&agent=" + TestConfig.AGENT_ID
                + "&return=" + returnPath);
        HttpResponse<String> poll = client.send(HttpRequest.newBuilder(uri("/agent/v1/actions"))
                .header("Authorization", "Bearer " + TestConfig.AGENT_TOKEN)
                .header("X-Agent-Id", TestConfig.AGENT_ID).GET().build(), HttpResponse.BodyHandlers.ofString());
        Matcher m = Pattern.compile("\"id\"\\s*:\\s*\"([0-9a-f-]{36})\"").matcher(poll.body());
        if (!m.find()) {
            throw new AssertionError("action " + type + " non relevée");
        }
        String id = m.group(1);
        String result = "{\"action_id\":\"" + id + "\",\"status\":\"SUCCESS\",\"value\":\"1\","
                + "\"message\":\"ok\",\"details\":" + detailsJson + "}";
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
        Files.createDirectories(contentRoot.resolve("dialogues"));
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
