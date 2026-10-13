package com.lodygames.rpgquest.panel.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.panel.agent.AgentActionRow;
import com.lodygames.rpgquest.panel.agent.AgentActionStatus;
import com.lodygames.rpgquest.panel.agent.AgentStore;
import com.lodygames.rpgquest.panel.audit.InMemoryAuditLog;
import com.lodygames.rpgquest.panel.bridge.BridgeClient;
import com.lodygames.rpgquest.panel.content.ContentWorkspace;
import com.lodygames.rpgquest.panel.content.DialogueYaml;
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
 * Issue #332 — les dialogues, version simple, sur un vrai serveur HTTP : liste, création, édition
 * « Le personnage dit… / Le joueur peut répondre… / Ensuite… », proposition de quête, mise en jeu,
 * erreurs, suppression, et conservation d'un dialogue complexe.
 */
class DialogueEditorTest {

    private static final String LUCIEN = """
            # Dialogue RPGQuest — généré via le Control Panel (squelette éditable).
            # L'édition fine (nœuds, choix conditionnels, actions) passera par l'éditeur dédié.
            id: rpgquest:lucien
            start: start
            nodes:
              start:
                speaker: "Lucien"
                text: "Salut !"
                choices:
                  - text: "Au revoir"
                    actions:
                      - type: CLOSE
            """;

    private static final String COMPLEX = """
            id: rpgquest:sage
            start: hello
            nodes:
              hello:
                speaker: "Sage"
                text: "<gold>Bonjour</gold> voyageur."
                choices:
                  - text: "Une quête ?"
                    conditions:
                      - type: QUEST_STATE
                        quest: rpgquest:herbes
                        state: NOT_STARTED
                      - type: HAS_ITEM
                        negate: false
                        material: STONE
                        amount: 3
                    actions:
                      - type: GIVE_ITEM
                        material: BREAD
                        amount: 2
                      - type: START_QUEST
                        quest: rpgquest:herbes
                      - type: CLOSE
                  - text: "Je reviens"
                    conditions:
                      - type: HAS_PENDING_DELIVERY
                        negate: true
                    actions:
                      - type: CLOSE
                      - type: SET_VARIABLE
                        key: "vu_sage"
                        value: "true"
                  - text: "Raconte"
                    next: story
              story:
                speaker: "Sage"
                text: "Il était une fois…"
                choices:
                  - text: "Retour"
                    next: hello
            """;

    private static final String NPCS = "{\"npcs\":["
            + "{\"id\":\"lucien\",\"displayName\":\"Lucien\",\"logicalDefinitionPresent\":true,"
            + "\"definedDialogueId\":\"rpgquest:lucien\",\"dialogueId\":\"rpgquest:lucien\"},"
            + "{\"id\":\"mira\",\"displayName\":\"Mira\",\"logicalDefinitionPresent\":true,"
            + "\"definedDialogueId\":\"\",\"dialogueId\":\"\"},"
            + "{\"id\":\"bob\",\"displayName\":\"Bob\",\"logicalDefinitionPresent\":true,"
            + "\"definedDialogueId\":\"rpgquest:bob_old\",\"dialogueId\":\"rpgquest:bob_old\"}],"
            + "\"definedIds\":[\"lucien\",\"mira\",\"bob\"]}";

    private static final String QUESTS = "{\"quests\":["
            + "{\"id\":\"rpgquest:herbes\",\"title\":\"Herbes du marais\"},"
            + "{\"id\":\"rpgquest:pain\",\"title\":\"Le pain de Lucien\"}]}";

    @TempDir
    Path tmp;

    private PanelApp app;
    private AgentStore store;
    private Path content;
    private int port;
    private HttpClient client;
    private final Map<String, String> jar = new LinkedHashMap<>();

    @AfterEach
    void tearDown() {
        if (app != null) {
            app.stop();
        }
    }

    // ---- Liste -------------------------------------------------------------------------------

    @Test
    void theListShowsReadableNamesTheCharacterAndOneRefresh() throws Exception {
        start();
        String page = get("/dialogues").body();
        assertTrue(page.contains("Dialogue de Lucien"), "nom lisible");
        assertTrue(page.contains("Créer un dialogue"));
        assertEquals(1, count(page, "action=\"/dialogues/refresh\""), "une seule actualisation");
        assertTrue(page.contains("href=\"/dialogues/edit/lucien\""));
        assertTrue(page.contains("Détails techniques"), "le diagnostic est à part");
        for (String jargon : List.of("nœud", "YAML", "canonique", "runtime", "rpgquest:lucien")) {
            assertFalse(page.contains(jargon), "pas de jargon : " + jargon);
        }
    }

    @Test
    void theOldTechnicalPageStaysReachable() throws Exception {
        start();
        String page = get("/dialogues/diagnostic").body();
        assertTrue(page.contains("détails techniques"), "l'ancien écran reste disponible");
    }

    // ---- Éditeur -----------------------------------------------------------------------------

    @Test
    void theEditorSaysWhatTheCharacterSaysTheRepliesAndWhatHappensNext() throws Exception {
        start();
        String page = get("/dialogues/edit/lucien").body();
        for (String label : List.of("Le personnage dit…", "Le joueur peut répondre…", "Ensuite…",
                "Terminer la conversation", "Proposer une quête", "Salut !", "Au revoir", "Aperçu de la conversation")) {
            assertTrue(page.contains(label), "libellé attendu : " + label);
        }
        String form = formHtml(page);
        for (String jargon : List.of("nœud", "YAML", "canonique", "runtime", "START_QUEST</", "branche")) {
            assertFalse(form.contains(jargon), "pas de jargon dans le parcours : " + jargon);
        }
        assertTrue(page.contains("href=\"/npcs?focus=lucien\""), "le personnage est nommé et cliquable");
    }

    @Test
    void offeringAQuestIsChosenByTitleAndShowsInThePreview() throws Exception {
        start();
        String page = get("/dialogues/edit/lucien").body();
        assertTrue(page.contains(">Herbes du marais</option>"), "les quêtes par leur titre");
        Map<String, String> f = formFields(page);
        f.put("n0_c0_qa", "START_QUEST");
        f.put("n0_c0_qid", "rpgquest:herbes");
        HttpResponse<String> saved = post("/dialogues/save", f, "save");
        assertEquals(303, saved.statusCode());

        String yaml = Files.readString(content.resolve("dialogues/lucien.yml"));
        assertTrue(yaml.indexOf("type: START_QUEST") < yaml.indexOf("type: CLOSE"), yaml);
        assertTrue(yaml.contains("quest: rpgquest:herbes"));
        String after = get(saved.headers().firstValue("Location").orElseThrow()).body();
        assertTrue(after.contains("propose la quête « Herbes du marais »"), "visible dans l'aperçu");
        assertTrue(after.contains("Enregistré"), "l'enregistrement est dit");
        assertTrue(actions("content.publish").isEmpty(), "« Enregistrer » ne met pas en jeu");
    }

    @Test
    void addingARepliesThenANewLineKeepsLinksConsistent() throws Exception {
        start();
        Map<String, String> f = formFields(get("/dialogues/edit/lucien").body());
        String withReply = post("/dialogues/save", f, "addreply:0").body();
        assertTrue(withReply.contains("Modifications non enregistrées"), "le geste n'écrit rien");
        assertEquals(LUCIEN, Files.readString(content.resolve("dialogues/lucien.yml")), "fichier intact");

        Map<String, String> g = formFields(withReply);
        g.put("n0_c1_text", "Tu vends quoi ?");
        g.put("n0_c1_next", "__new");
        String needsText = post("/dialogues/save", g, "save").body();
        assertTrue(needsText.contains("Écrivez ce que dit le personnage."), "message près de la nouvelle réplique");
        assertTrue(needsText.contains("value=\"Tu vends quoi ?\""), "la saisie est conservée");

        Map<String, String> h = formFields(needsText);
        h.put("n1_text", "Du pain frais !");
        assertEquals(303, post("/dialogues/save", h, "save").statusCode());
        var d = DialogueYaml.read(Files.readString(content.resolve("dialogues/lucien.yml"))).draft();
        assertEquals(2, d.nodes.size());
        assertEquals("r2", d.nodes.get(0).choices.get(1).next, "la réponse mène à la nouvelle réplique");
        assertEquals("Lucien", d.nodes.get(1).speaker, "dite par le même personnage");
    }

    @Test
    void anEmptyReplyIsRefusedNearItsFieldAndNothingIsLost() throws Exception {
        start();
        Map<String, String> f = formFields(get("/dialogues/edit/lucien").body());
        f.put("n0_text", "Bonjour, toi !");
        f.put("n0_c0_text", "");
        String page = post("/dialogues/save", f, "save").body();
        assertTrue(page.contains("Écrivez ce que dit le joueur, ou supprimez cette réponse."), "près du champ");
        assertTrue(page.contains("Bonjour, toi !"), "le texte modifié est réaffiché");
        assertTrue(unescape(page).contains("Rien n'a été enregistré"));
        assertEquals(LUCIEN, Files.readString(content.resolve("dialogues/lucien.yml")));
    }

    @Test
    void aComplexDialoguePassesThroughTheEditorWithoutLosingAnything() throws Exception {
        start();
        Files.writeString(content.resolve("dialogues/sage.yml"), COMPLEX);
        String page = get("/dialogues/edit/sage").body();
        assertTrue(page.contains("Cette réponse n'apparaît que si…"));
        assertTrue(page.contains("Inverser : seulement si ce n'est pas le cas"));
        assertTrue(page.contains("Conditions et autres effets (3)"), "deux conditions et un don de pain");

        assertEquals(303, post("/dialogues/save", formFields(page), "save").statusCode());
        String expected = DialogueYaml.write(DialogueYaml.read(COMPLEX).draft());
        assertEquals(expected, Files.readString(content.resolve("dialogues/sage.yml")),
                "conditions, effets, ordre et fermeture conservés");
    }

    @Test
    void removingALineSaysWhichRepliesNowEndTheConversation() throws Exception {
        start();
        Files.writeString(content.resolve("dialogues/sage.yml"), COMPLEX);
        Map<String, String> f = formFields(get("/dialogues/edit/sage").body());
        String page = post("/dialogues/save", f, "delnode:1").body();
        assertTrue(page.contains("1 réponse(s) qui y menaient terminent maintenant la conversation"), "dit, pas silencieux");
    }

    // ---- Mise en jeu ---------------------------------------------------------------------------

    @Test
    void saveAndPublishSendsOnePublicationAndNeverClaimsSuccessEarly() throws Exception {
        start();
        Map<String, String> f = formFields(get("/dialogues/edit/lucien").body());
        f.put("n0_text", "Salut, voyageur !");
        HttpResponse<String> res = post("/dialogues/save", f, "save_publish");
        assertEquals(303, res.statusCode());
        List<AgentActionRow> pubs = actions("content.publish");
        assertEquals(1, pubs.size());
        assertEquals("dialogues", pubs.get(0).params().get("kind"));
        assertEquals("lucien", pubs.get(0).params().get("id"));

        String pending = get("/dialogues/edit/lucien").body();
        assertTrue(pending.contains("Mise en jeu en cours…"), "en cours, pas réussi");
        assertFalse(pending.contains("À jour en jeu"));

        store.recordResult(pubs.get(0).id(), TestConfig.AGENT_ID, AgentActionStatus.FAILED, "",
                "Le serveur a refusé le dialogue", "{}", Instant.now());
        String failed = get("/dialogues/edit/lucien").body();
        assertTrue(failed.contains("Mise en jeu échouée"), "l'échec est dit");
        assertTrue(failed.contains("Le serveur a refusé le dialogue"));
        assertTrue(failed.contains(">Réessayer</button>") || failed.contains("Réessayer</button>"), "et on peut réessayer");
    }

    // ---- Création ------------------------------------------------------------------------------

    @Test
    void creatingForACharacterWithoutDialogueUsesItsOwnNameAndNeedsNoLink() throws Exception {
        start();
        String form = get("/dialogues/new?npc=mira").body();
        assertTrue(form.contains("Quel personnage parle ?"));
        HttpResponse<String> res = post("/dialogues/create", Map.of("_csrf", csrf(form), "npc", "mira",
                "first_line", "Bienvenue à la carte !"), null);
        assertEquals(303, res.statusCode());
        assertTrue(res.headers().firstValue("Location").orElse("").startsWith("/dialogues/edit/mira"));
        String yaml = Files.readString(content.resolve("dialogues/mira.yml"));
        assertTrue(yaml.contains("speaker: \"Mira\"") && yaml.contains("Bienvenue à la carte !"));
        assertTrue(actions("npc.definition.update").isEmpty(), "le jeu le trouve par son nom");
    }

    @Test
    void creatingForACharacterThatHasOneAssociatesTheNewDialogue() throws Exception {
        start();
        String form = get("/dialogues/new?npc=bob").body();
        HttpResponse<String> res = post("/dialogues/create", Map.of("_csrf", csrf(form), "npc", "bob",
                "name", "Bob au port", "first_line", "Ahoy !"), null);
        assertEquals(303, res.statusCode());
        assertTrue(Files.exists(content.resolve("dialogues/bob_au_port.yml")));
        List<AgentActionRow> link = actions("npc.definition.update");
        assertEquals(1, link.size());
        assertEquals("rpgquest:bob_au_port", link.get(0).params().get("dialogue_id"));
    }

    @Test
    void creatingWithoutAFirstLineSaysWhatToFix() throws Exception {
        start();
        String form = get("/dialogues/new").body();
        String page = post("/dialogues/create", Map.of("_csrf", csrf(form), "name", "Test", "first_line", ""), null).body();
        assertTrue(page.contains("Écrivez ce que dit le personnage en premier."));
    }

    // ---- Suppression ---------------------------------------------------------------------------

    @Test
    void theDeletePageIsShortNamesTheCharactersAndOffersCancel() throws Exception {
        start();
        survey("{\"dialogues\":[{\"id\":\"rpgquest:lucien\",\"key\":\"lucien\",\"linkedNpcIds\":[\"lucien\"],"
                + "\"startsQuestIds\":[],\"referencedQuestIds\":[],\"nodes\":[]}],\"loadIssues\":[],"
                + "\"declaredButMissing\":[]}");
        String linked = unescape(get("/dialogues/delete?slug=lucien").body());
        assertTrue(linked.contains("Personnages qui l'utilisent"));
        assertTrue(linked.contains("href=\"/npcs?focus=lucien\">Lucien</a>"), "lien vers le personnage");
        assertFalse(linked.contains(">Supprimer</button>"), "pas de bouton tant qu'il est utilisé");
        assertTrue(linked.contains(">Annuler</a>"));

        Files.writeString(content.resolve("dialogues/sage.yml"), COMPLEX);
        String free = get("/dialogues/delete?slug=sage").body();
        assertTrue(free.contains("Je veux supprimer ce dialogue"));
        assertTrue(free.contains("Supprimer</button>") && free.contains(">Annuler</a>"));

        Map<String, String> confirm = formFieldsOf(free, "/dialogues/delete");
        confirm.remove("confirm_ack");
        String refused = post("/dialogues/delete", confirm, null).body();
        assertTrue(refused.contains("Cochez « Je veux supprimer ce dialogue »"));
        assertTrue(Files.exists(content.resolve("dialogues/sage.yml")));

        confirm.put("confirm_ack", "1");
        String done = post("/dialogues/delete", confirm, null).body();
        assertTrue(done.contains("est supprimé"), done.length() + "");
        assertFalse(Files.exists(content.resolve("dialogues/sage.yml")));
    }

    // ---- Harnais -------------------------------------------------------------------------------

    private void start() throws Exception {
        content = tmp.resolve("content");
        for (String kind : ContentWorkspace.KINDS) {
            Files.createDirectories(content.resolve(kind));
        }
        Files.writeString(content.resolve("dialogues/lucien.yml"), LUCIEN);
        String db = tmp.resolve("cp.db").toString();
        store = new AgentStore(db);
        app = new PanelApp(TestConfig.withContentDir(db, "http://127.0.0.1:1/admin/v1", content.toString()),
                new InMemoryAuditLog(), new BridgeClient(Duration.ofMillis(300), Duration.ofMillis(400)), store);
        port = app.start();
        client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        jar.clear();
        String token = csrf(get("/login").body());
        send(HttpRequest.newBuilder(uri("/login"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("username=" + TestConfig.OWNER_USERNAME + "&password="
                        + URLEncoder.encode(TestConfig.OWNER_PASSWORD, StandardCharsets.UTF_8) + "&_csrf=" + token)));
        record("npc.list", NPCS);
        record("quest.list", QUESTS);
    }

    private void record(String type, String details) {
        String id = store.createAction(TestConfig.AGENT_ID, type, Map.of(), "owner");
        store.recordResult(id, TestConfig.AGENT_ID, AgentActionStatus.SUCCESS, "", "ok",
                "{\"action_id\":\"" + id + "\",\"status\":\"SUCCESS\",\"details\":" + details + "}", Instant.now());
    }

    private void survey(String details) {
        record("dialogue.list", details);
    }

    private List<AgentActionRow> actions(String type) {
        return store.recentActions(TestConfig.AGENT_ID, 200).stream().filter(a -> a.type().equals(type)).toList();
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }

    private static String formHtml(String page) {
        int start = page.indexOf("action=\"/dialogues/save\"");
        return page.substring(start, page.indexOf("</form>", start));
    }

    private static Map<String, String> formFields(String page) {
        return formFieldsOf(page, "/dialogues/save");
    }

    /** Ce que le navigateur enverrait : champs non désactivés, cases cochées, option choisie. */
    private static Map<String, String> formFieldsOf(String page, String action) {
        int start = page.indexOf("action=\"" + action + "\"");
        String f = page.substring(start, page.indexOf("</form>", start));
        Map<String, String> out = new LinkedHashMap<>();
        Matcher in = Pattern.compile("<input([^>]*)>").matcher(f);
        while (in.find()) {
            String a = in.group(1);
            String name = attr(a, "name");
            String type = attr(a, "type");
            if (name == null || a.contains(" disabled") || "submit".equals(type)) {
                continue;
            }
            if ("checkbox".equals(type) && !a.contains(" checked")) {
                continue;
            }
            out.put(name, unescape(attr(a, "value") == null ? "" : attr(a, "value")));
        }
        Matcher ta = Pattern.compile("(?s)<textarea([^>]*)>(.*?)</textarea>").matcher(f);
        while (ta.find()) {
            String name = attr(ta.group(1), "name");
            if (name != null && !ta.group(1).contains(" disabled")) {
                out.put(name, unescape(ta.group(2)));
            }
        }
        Matcher se = Pattern.compile("(?s)<select([^>]*)>(.*?)</select>").matcher(f);
        while (se.find()) {
            String name = attr(se.group(1), "name");
            if (name == null || se.group(1).contains(" disabled")) {
                continue;
            }
            Matcher opt = Pattern.compile("<option value=\"([^\"]*)\"([^>]*)>").matcher(se.group(2));
            String value = null;
            String first = null;
            while (opt.find()) {
                first = first == null ? opt.group(1) : first;
                if (opt.group(2).contains("selected")) {
                    value = opt.group(1);
                }
            }
            out.put(name, unescape(value != null ? value : first == null ? "" : first));
        }
        return out;
    }

    private static String attr(String attrs, String name) {
        Matcher m = Pattern.compile("\\s" + name + "=\"([^\"]*)\"").matcher(attrs);
        return m.find() ? m.group(1) : null;
    }

    private static String unescape(String s) {
        return s.replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&amp;", "&");
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

    private HttpResponse<String> post(String p, Map<String, String> form, String op) throws Exception {
        StringBuilder body = new StringBuilder();
        for (Map.Entry<String, String> e : form.entrySet()) {
            body.append(body.length() == 0 ? "" : "&").append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8))
                    .append('=').append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }
        if (op != null) {
            body.append("&_op=").append(URLEncoder.encode(op, StandardCharsets.UTF_8));
        }
        return send(HttpRequest.newBuilder(uri(p))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())));
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
            String[] parts = sc.split(";", 2);
            int eq = parts[0].indexOf('=');
            String n = parts[0].substring(0, eq).trim();
            String v = parts[0].substring(eq + 1).trim();
            if (sc.toLowerCase().contains("max-age=0") || v.isEmpty()) {
                jar.remove(n);
            } else {
                jar.put(n, v);
            }
        }
        return res;
    }
}
