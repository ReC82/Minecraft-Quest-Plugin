package com.lodygames.rpgquest.panel.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.panel.agent.AgentStore;
import com.lodygames.rpgquest.panel.audit.InMemoryAuditLog;
import com.lodygames.rpgquest.panel.bridge.BridgeClient;
import com.lodygames.rpgquest.panel.json.Json;
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

    // ---- D : création via /dialogues/new visible au retour catalogue -------------------

    @Test
    void dialogueCreatedFromEditorShowsUpImmediatelyWithoutAgentCall() throws Exception {
        start();
        String token = csrf(get("/dialogues/new").body());
        HttpResponse<String> save = post("/dialogues/save", "id=lily_intro&speaker=Lily&start_text="
                + enc("Bonjour.") + "&text_color=yellow&_csrf=" + token + "&_action=save");
        assertEquals(303, save.statusCode());
        assertTrue(save.headers().firstValue("Location").orElse("").startsWith("/dialogues/edit/lily_intro"));

        String cat = catalog();
        assertTrue(cat.contains("data-res-id=\"rpgquest:lily_intro\""), "dialogue créé visible sans appel agent");
        assertTrue(cat.contains(">Source uniquement</span>"));
        // le YAML écrit est au format canonique du moteur (enrobage couleur appliqué)
        String yaml = Files.readString(contentRoot.resolve("dialogues/lily_intro.yml"));
        assertTrue(yaml.contains("id: rpgquest:lily_intro"));
        assertTrue(yaml.contains("<yellow>Bonjour.</yellow>"));
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

    // ---- F : le formulaire de création propose le PNJ Lily par nom + id ----------------

    @Test
    void createFormOffersExistingNpcByNameAndId() throws Exception {
        start();
        runNpcList("[{\"id\":\"lily\",\"displayName\":\"Lily\",\"logicalDefinitionPresent\":true,"
                + "\"enabled\":true,\"role\":\"villager\"}]");
        String body = get("/dialogues/new").body();
        String datalist = slice(body, "<datalist id=\"dl-npc\">", "</datalist>");
        assertTrue(datalist.contains("value=\"lily\""), "PNJ proposé par id");
        assertTrue(datalist.contains("label=\"Lily\""), "PNJ proposé par nom humain");
    }

    // ---- G : PNJ pré-sélectionné -> locuteur prérempli si vide -------------------------

    @Test
    void preselectedNpcPrefillsSpeakerWhenEmpty() throws Exception {
        start();
        runNpcList("[{\"id\":\"lily\",\"displayName\":\"Lily\",\"logicalDefinitionPresent\":true,"
                + "\"enabled\":true,\"role\":\"villager\"}]");
        String body = get("/dialogues/new?npc=lily").body();
        assertTrue(body.contains("name=\"npc\" value=\"lily\""), "PNJ pré-sélectionné");
        assertTrue(body.contains("name=\"speaker\" value=\"Lily\""), "locuteur prérempli avec le nom du PNJ");
    }

    // ---- H : création avec Lily sélectionnée -> npc.definition.update enfilé ------------

    @Test
    void creatingWithSelectedNpcQueuesNpcDefinitionUpdate() throws Exception {
        start();
        runNpcList("[{\"id\":\"lily\",\"displayName\":\"Lily\",\"logicalDefinitionPresent\":true,"
                + "\"enabled\":true,\"role\":\"villager\"}]");
        String token = csrf(get("/dialogues/new?npc=lily").body());
        HttpResponse<String> save = post("/dialogues/save", "id=lily_intro&npc=lily&speaker=Lily&start_text="
                + enc("Bonjour.") + "&text_color=&_csrf=" + token + "&_action=save");
        assertEquals(303, save.statusCode());
        assertTrue(save.headers().firstValue("Location").orElse("").contains("toast="),
                "redirection avec toast de l'action de rattachement");

        Map<String, Object> actions = Json.parseObject(get("/agents/actions.json?agent=" + TestConfig.AGENT_ID).body());
        assertTrue(((Number) actions.get("pending")).intValue() >= 1, "action de rattachement en attente");

        String poll = client.send(HttpRequest.newBuilder(uri("/agent/v1/actions"))
                .header("Authorization", "Bearer " + TestConfig.AGENT_TOKEN)
                .header("X-Agent-Id", TestConfig.AGENT_ID).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body();
        assertTrue(poll.contains("npc.definition.update"), "action npc.definition.update enfilée");
        assertTrue(poll.contains("rpgquest:lily_intro"), "la définition PNJ pointera vers lily_intro");
        assertTrue(poll.contains("\"lily\""), "cible = PNJ lily");
    }

    // ---- I : dialogue source-only sélectionnable depuis un formulaire PNJ --------------

    @Test
    void sourceOnlyDialogueIsSelectableFromNpcForm() throws Exception {
        start();
        writeDialogue("lily_intro", LILY_INTRO_YAML);
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

    // ---- K : rééditer un dialogue riche ne le réduit jamais à un squelette (#145) -------

    /** Dialogue existant à plusieurs nœuds, avec conditions, actions et embranchements. */
    private static final String RICH_YAML = """
            id: rpgquest:jeff
            start: start
            nodes:
              start:
                speaker: "Jeff"
                text: "<gray>Compris.</gray>"
                choices:
                  - text: "Je vais m'en charger"
                    conditions:
                      - type: QUEST_STATE
                        quest: rpgquest:cleanup
                        state: NOT_STARTED
                    actions:
                      - type: START_QUEST
                        quest: rpgquest:cleanup
                    next: accepted
                  - text: "Pas maintenant"
                    actions:
                      - type: CLOSE
              accepted:
                speaker: "Jeff"
                text: "<green>Merci.</green>"
                choices:
                  - text: "OK"
                    actions:
                      - type: CLOSE
            """;

    @Test
    void editingAnExistingRichDialogueOnlyChangesTheFieldsOfTheForm() throws Exception {
        start();
        writeDialogue("jeff", RICH_YAML);

        String page = get("/dialogues/edit/jeff").body();
        assertTrue(page.contains("name=\"speaker\" value=\"Jeff\""), "locuteur prérempli depuis le fichier");
        assertTrue(page.contains("Conservé à l'identique"), "ce qui n'est pas éditable ici est annoncé");

        String token = csrf(page);
        HttpResponse<String> save = post("/dialogues/save", "slug=jeff&id=jeff&speaker=Jeff&start_text="
                + enc("<gray>Bien reçu.</gray>") + "&text_color=&_csrf=" + token + "&_action=save"
                + "&expectedSha=" + sha("dialogues", "jeff"));
        assertEquals(303, save.statusCode(), save.body());

        String after = Files.readString(contentRoot.resolve("dialogues/jeff.yml"));
        // Seule la réplique du nœud de départ a changé…
        assertTrue(after.contains("text: \"<gray>Bien reçu.</gray>\""), "texte de départ mis à jour");
        assertFalse(after.contains("<gray>Compris.</gray>"), "ancien texte remplacé");
        // …tout le reste est intact : second nœud, choix, condition, action, embranchement.
        assertTrue(after.contains("  accepted:"), "le second nœud survit");
        assertTrue(after.contains("- type: QUEST_STATE"), "la condition survit");
        assertTrue(after.contains("- type: START_QUEST"), "l'action de quête survit");
        assertTrue(after.contains("quest: rpgquest:cleanup"), "la quête référencée survit");
        assertTrue(after.contains("next: accepted"), "l'embranchement survit");
        assertTrue(after.contains("text: \"Je vais m'en charger\""), "le texte du choix survit");
    }

    @Test
    void aDialogueTheEditorCannotReadFaithfullyIsNeverOverwritten() throws Exception {
        start();
        // Texte de nœud localisé : le moteur l'accepte, l'éditeur source ne sait pas le représenter.
        String localized = "id: rpgquest:lily\nstart: start\nnodes:\n  start:\n    speaker: \"Lily\"\n"
                + "    text:\n      default: \"Bonjour\"\n      en: \"Hello\"\n    choices:\n"
                + "      - text: \"Bye\"\n        actions:\n          - type: CLOSE\n";
        writeDialogue("lily", localized);

        String page = get("/dialogues/edit/lily").body();
        assertTrue(page.contains("n'est pas relu fidèlement"), "la limite est dite avant toute édition");

        HttpResponse<String> save = post("/dialogues/save", "slug=lily&id=lily&speaker=Lily&start_text="
                + enc("Bonsoir") + "&text_color=&_csrf=" + csrf(page) + "&_action=save"
                + "&expectedSha=" + sha("dialogues", "lily"));
        assertEquals(200, save.statusCode(), "pas de redirection : enregistrement refusé");
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
        String page = get("/dialogues?agent=" + TestConfig.AGENT_ID).body();
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
