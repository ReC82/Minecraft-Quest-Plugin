package com.lodygames.rpgquest.panel.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.panel.agent.AgentStore;
import com.lodygames.rpgquest.panel.audit.InMemoryAuditLog;
import com.lodygames.rpgquest.panel.authz.Permission;
import com.lodygames.rpgquest.panel.authz.PermissionService;
import com.lodygames.rpgquest.panel.authz.Role;
import com.lodygames.rpgquest.panel.bridge.BridgeClient;
import com.lodygames.rpgquest.panel.content.RefData;
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
 * Éditeur guidé de quêtes / stories (#46) — bout en bout HTTP : rendu du formulaire, auth,
 * ajout de ligne par round-trip serveur, enregistrement whitelisté, conflit de hash, id
 * traversant refusé, CSRF, permission.
 */
class ContentEditorPagesTest {

    @TempDir
    Path tmp;

    private PanelApp app;
    private int port;
    private HttpClient client;
    private final Map<String, String> jar = new LinkedHashMap<>();
    private Path contentRoot;

    @AfterEach
    void tearDown() {
        if (app != null) {
            app.stop();
        }
    }

    // ---- tests ----------------------------------------------------------------------

    @Test
    void newQuestFormRendersGuidedSections() throws Exception {
        start(true);
        String body = get("/quests/new").body();
        assertTrue(body.contains("Créer une quête"));
        assertTrue(body.contains("Général") && body.contains("Objectifs") && body.contains("Récompenses"));
        assertTrue(body.contains("name=\"_csrf\""), "jeton CSRF injecté");
        assertTrue(body.contains("<datalist id=\"dl-entity\""), "datalist entités présente");
        assertTrue(body.contains("value=\"add_obj:0\""), "bouton ajouter un objectif");
        assertFalse(body.contains("%CSRF%"), "placeholder CSRF remplacé");
    }

    @Test
    void anonymousIsRedirectedToLogin() throws Exception {
        start(true);
        jar.clear();
        HttpResponse<String> res = get("/quests/new");
        assertEquals(303, res.statusCode());
        assertTrue(res.headers().firstValue("Location").orElse("").contains("/login"));
    }

    @Test
    void addObjectiveButtonAddsARow() throws Exception {
        start(true);
        String token = csrf(get("/quests/new").body());
        String form = baseQuestForm(token) + "&_action=add_obj:0";
        String body = post("/quests/save", form).body();
        assertTrue(body.contains("obj.0.1.kind"), "un second objectif a été ajouté");
    }

    // ---- #46 : validation de brouillon vs validation finale ------------------------------

    @Test
    void draftActionButtonsBypassHtmlRequiredValidation() throws Exception {
        start(true);
        String body = get("/quests/new").body();
        // Chaque bouton porteur d'une action de brouillon désactive la validation HTML.
        Matcher m = Pattern.compile("<button[^>]*name=\"_action\"[^>]*>").matcher(body);
        int seen = 0;
        while (m.find()) {
            seen++;
            assertTrue(m.group().contains("formnovalidate"),
                    "bouton d'action sans formnovalidate : " + m.group());
        }
        assertTrue(seen >= 4, "plusieurs boutons _action attendus");
    }

    @Test
    void structuralActionsWorkWithAnEmptyForm() throws Exception {
        start(true);
        String token = csrf(get("/quests/new").body());
        // formulaire quasi vide (aucun champ requis rempli) + action structurelle
        String base = "id=&title=&description=&category=&icon=&step.0.id=&obj.0.0.kind=KILL_ENTITY&_csrf=" + token;
        for (String act : new String[] {"add_step", "add_obj:0", "add_reward", "del_reward:0",
                "del_obj:0:0", "del_step:0"}) {
            HttpResponse<String> res = post("/quests/save", base + "&rew.0.kind=EXPERIENCE&_action=" + act);
            assertEquals(200, res.statusCode(), "action " + act + " doit aboutir même formulaire incomplet");
            assertFalse(res.body().contains("class=\"diag-list\""),
                    "action " + act + " ne déclenche pas la validation métier finale");
            assertFalse(res.body().contains("Aucune anomalie détectée"),
                    "action " + act + " n'exécute pas le bloc de vérification");
        }
    }

    @Test
    void actionButtonsCarryAScrollAnchor() throws Exception {
        start(true);
        String body = get("/quests/new").body();
        assertTrue(body.contains("formaction=\"/quests/save#"), "ancre de scroll sur les actions");
        assertTrue(body.contains("id=\"step-0\""), "id stable d'étape");
        assertTrue(body.contains("id=\"sec-rewards\""), "id stable de section récompenses");
    }

    @Test
    void objectiveTypeDrivesVisibleFieldsAndHelp() throws Exception {
        start(true);
        String token = csrf(get("/quests/new").body());
        // On bascule l'objectif 0 sur CRAFT_ITEM sans rien remplir d'autre.
        String form = "id=&title=&description=&category=&icon=&step.0.id=step_1"
                + "&obj.0.0.kind=CRAFT_ITEM&obj.0.0._was=KILL_ENTITY&_csrf=" + token + "&_action=refresh";
        String body = post("/quests/save", form).body();
        // Le jeu de champs CRAFT_ITEM est visible et actif ; celui de KILL_ENTITY est présent mais masqué.
        assertTrue(body.contains("data-kind=\"CRAFT_ITEM\">"), "fieldset CRAFT_ITEM visible");
        assertTrue(body.contains("data-kind=\"KILL_ENTITY\" hidden>"), "fieldset KILL_ENTITY masqué");
        assertTrue(body.contains("name=\"obj.0.0.material\""), "champ matériau propre à CRAFT_ITEM");
        assertTrue(body.contains("Fabriquer N exemplaires"), "aide cohérente avec CRAFT_ITEM");
        // L'input entité de KILL_ENTITY existe mais est désactivé (non soumis, non validé).
        assertTrue(Pattern.compile("name=\"obj\\.0\\.0\\.entity\"[^>]*disabled").matcher(body).find(),
                "champ entité de l'autre type désactivé");
    }

    @Test
    void rewardItemShowsItemAndQuantityNeverXpAmount() throws Exception {
        start(true);
        String token = csrf(get("/quests/new").body());
        String form = baseQuestForm(token)
                + "&rew.0.kind=ITEM&rew.0._was=EXPERIENCE&_action=refresh";
        String body = post("/quests/save", form).body();
        assertTrue(body.contains("data-kind=\"ITEM\">"), "fieldset ITEM visible");
        assertTrue(body.contains("data-kind=\"EXPERIENCE\" hidden>"), "fieldset EXPERIENCE masqué");
        assertTrue(body.contains("name=\"rew.0.material\""), "récompense ITEM = objet");
        assertTrue(body.contains("name=\"rew.0.amount\""), "récompense ITEM = quantité");
        // Le champ entier XP de EXPERIENCE est présent mais désactivé (bloc masqué), jamais soumis
        // comme quantité d'objet.
        assertTrue(Pattern.compile("data-kind=\"EXPERIENCE\" hidden>.*?name=\"rew\\.0\\.amount\"[^>]*disabled",
                Pattern.DOTALL).matcher(body).find(), "le champ XP du bloc EXPERIENCE est désactivé");
    }

    @Test
    void changingRewardTypeDropsPreviousTypeValues() throws Exception {
        start(true);
        String token = csrf(get("/quests/new").body());
        // EXPERIENCE amount=100 puis bascule ITEM : 100 ne doit pas ressortir en quantité d'objet.
        String form = baseQuestForm(token)
                + "&rew.0.kind=ITEM&rew.0.amount=100&rew.0._was=EXPERIENCE&_action=validate";
        String body = post("/quests/save", form).body();
        assertTrue(body.contains("type: ITEM"), "aperçu YAML de la récompense ITEM");
        assertFalse(body.contains("amount: 100"), "la valeur XP précédente n'est pas conservée en douce");
    }

    @Test
    void categoryFieldUsesCategoryListNotNpcList() throws Exception {
        start(true);
        String body = get("/quests/new").body();
        assertTrue(body.contains("<datalist id=\"dl-category\">"), "datalist des catégories présente");
        assertTrue(body.contains("<option value=\"tutorial\">"), "catégorie curée proposée");
        assertTrue(Pattern.compile("name=\"category\"[^>]*list=\"dl-category\"").matcher(body).find(),
                "le champ catégorie pointe sur dl-category");
    }

    @Test
    void lookupFieldsAreSearchableCombos() throws Exception {
        start(true);
        String body = get("/quests/new").body();
        assertTrue(body.contains("class=\"combo\" data-combo"), "champ enveloppé pour la recherche");
        assertTrue(body.contains("<option value=\"ZOMBIE\" label=\"Zombie\">"), "libellé humain sur l'entité");
    }

    @Test
    void validObjectiveAndRewardSaveToSource() throws Exception {
        start(true);
        String token = csrf(get("/quests/new").body());
        String form = baseQuestForm(token)
                + "&rew.0.kind=EXPERIENCE&rew.0.amount=100"
                + "&_action=save";
        HttpResponse<String> res = post("/quests/save", form);
        assertEquals(303, res.statusCode());
        assertEquals("/quests/edit/test_quest?saved=1", res.headers().firstValue("Location").orElse(""));

        Path file = contentRoot.resolve("quests/test_quest.yml");
        assertTrue(Files.exists(file), "fichier créé dans la source");
        String yaml = Files.readString(file);
        assertTrue(yaml.contains("id: rpgquest:test_quest"));
        assertTrue(yaml.contains("type: KILL_ENTITY") && yaml.contains("entity: SPIDER") && yaml.contains("amount: 5"));
        assertTrue(yaml.contains("type: EXPERIENCE"));

        // ré-ouverture : le formulaire repart du fichier
        String reopened = get("/quests/edit/test_quest").body();
        assertTrue(reopened.contains("Modifier une quête"));
        assertTrue(reopened.contains("value=\"SPIDER\""));
    }

    @Test
    void saveIsRejectedWhenValidationHasErrors() throws Exception {
        start(true);
        String token = csrf(get("/quests/new").body());
        // titre vide -> ERREUR bloquante
        String form = "id=broken_quest&title=&description=x&category=x&icon=BOOK"
                + "&step.0.id=step_1&obj.0.0.kind=KILL_ENTITY&obj.0.0.entity=SPIDER&obj.0.0.amount=5"
                + "&_csrf=" + token + "&_action=save";
        HttpResponse<String> res = post("/quests/save", form);
        assertEquals(200, res.statusCode());
        assertTrue(res.body().contains("erreur") || res.body().contains("ERREUR"));
        assertFalse(Files.exists(contentRoot.resolve("quests/broken_quest.yml")), "aucun fichier écrit");
    }

    @Test
    void staleHashIsDetectedAsConflict() throws Exception {
        start(true);
        // 1) créer
        String token = csrf(get("/quests/new").body());
        post("/quests/save", baseQuestForm(token) + "&_action=save");
        Path file = contentRoot.resolve("quests/test_quest.yml");
        assertTrue(Files.exists(file));

        // 2) ouvrir l'éditeur -> récupère expectedSha
        String editBody = get("/quests/edit/test_quest").body();
        String expectedSha = attr(editBody, "expectedSha");
        String token2 = csrf(editBody);

        // 3) le fichier change sous nos pieds
        Files.writeString(file, Files.readString(file) + "\n# modif externe\n");

        // 4) tentative d'enregistrement avec l'ancien hash
        String form = "id=test_quest&title=Titre&description=desc&category=tutorial&icon=BOOK"
                + "&step.0.id=step_1&obj.0.0.kind=KILL_ENTITY&obj.0.0.entity=CREEPER&obj.0.0.amount=3"
                + "&slug=test_quest&expectedSha=" + expectedSha + "&_csrf=" + token2 + "&_action=save";
        HttpResponse<String> res = post("/quests/save", form);
        assertEquals(200, res.statusCode());
        assertTrue(res.body().toLowerCase().contains("conflit"), "conflit de version signalé");
        assertTrue(Files.readString(file).contains("modif externe"), "fichier non écrasé");
    }

    @Test
    void traversingIdIsRefused() throws Exception {
        start(true);
        String token = csrf(get("/quests/new").body());
        String form = "id=" + URLEncoder.encode("../../evil", StandardCharsets.UTF_8)
                + "&title=T&description=d&category=c&icon=BOOK"
                + "&step.0.id=step_1&obj.0.0.kind=KILL_ENTITY&obj.0.0.entity=SPIDER&obj.0.0.amount=1"
                + "&_csrf=" + token + "&_action=save";
        HttpResponse<String> res = post("/quests/save", form);
        assertEquals(200, res.statusCode());
        assertTrue(res.body().contains("invalide"));
        assertFalse(Files.exists(tmp.resolve("evil.yml")));
        assertFalse(Files.exists(contentRoot.resolve("evil.yml")));
    }

    @Test
    void badCsrfIsRejected() throws Exception {
        start(true);
        HttpResponse<String> res = post("/quests/save",
                baseQuestForm("not-the-real-token") + "&_action=save");
        assertEquals(403, res.statusCode());
    }

    @Test
    void readOnlyWorkspaceShowsBannerAndBlocksSave() throws Exception {
        start(false); // pas de sous-dossier quests/ -> non inscriptible
        String body = get("/quests/new").body();
        assertTrue(body.contains("lecture seule"));
        String token = csrf(body);
        HttpResponse<String> res = post("/quests/save", baseQuestForm(token) + "&_action=save");
        assertEquals(200, res.statusCode());
        assertFalse(Files.exists(contentRoot.resolve("quests/test_quest.yml")));
    }

    @Test
    void storyEditorCreatesOrderedChain() throws Exception {
        start(true);
        String token = csrf(get("/stories/new").body());
        String form = "id=test_story&name=Histoire+d%27essai&q.0=premiers_pas&q.1=first_steps"
                + "&_csrf=" + token + "&_action=save";
        HttpResponse<String> res = post("/stories/save", form);
        assertEquals(303, res.statusCode());
        assertEquals("/stories/edit/test_story?saved=1", res.headers().firstValue("Location").orElse(""));
        String yaml = Files.readString(contentRoot.resolve("stories/test_story.yml"));
        assertTrue(yaml.contains("id: test_story"));
        assertTrue(yaml.indexOf("premiers_pas") < yaml.indexOf("first_steps"), "ordre conservé");
    }

    @Test
    void readOnlyRoleLacksContentWritePermission() {
        PermissionService p = new PermissionService();
        assertFalse(p.can(Role.READ_ONLY.name(), Permission.QUEST_CONTENT_WRITE));
        assertTrue(p.can(Role.OWNER.name(), Permission.QUEST_CONTENT_WRITE));
        assertTrue(p.can(Role.CONTENT_EDITOR.name(), Permission.STORY_CONTENT_WRITE));
    }

    // ---- #46 passe UX : brouillon jamais bloqué, scroll, combos ---------------------------

    /** Slice du seul {@code <form class="editor" ...>...</form>} de la page (là où « required » nuirait). */
    private static String editorForm(String html) {
        int a = html.indexOf("<form method=\"post\" action=\"/quests/save\" class=\"editor\"");
        if (a < 0) {
            a = html.indexOf("<form method=\"post\" action=\"/stories/save\" class=\"editor\"");
        }
        int b = html.indexOf("</form>", a);
        return a < 0 || b < 0 ? html : html.substring(a, b);
    }

    @Test
    void editorFormDisablesNativeValidationAndEmitsNoRequiredAttribute() throws Exception {
        start(true);
        String form = editorForm(get("/quests/new").body());
        assertTrue(form.contains("class=\"editor\" novalidate"), "le <form> de l'éditeur porte novalidate");
        assertFalse(Pattern.compile("<(input|select|textarea)\\b[^>]*\\brequired\\b").matcher(form).find(),
                "aucun contrôle du formulaire éditeur ne porte l'attribut HTML required");
        // Le marqueur visuel d'obligation reste (accessibilité / lisibilité).
        assertTrue(form.contains("<span aria-hidden=\"true\">*</span>"), "le marqueur « * » reste affiché");
    }

    @Test
    void storyEditorFormAlsoDisablesNativeValidation() throws Exception {
        start(true);
        String form = editorForm(get("/stories/new").body());
        assertTrue(form.contains("class=\"editor\" novalidate"));
        assertFalse(Pattern.compile("<(input|select|textarea)\\b[^>]*\\brequired\\b").matcher(form).find());
    }

    @Test
    void everyStructuralActionButtonCarriesANonEmptyScrollAnchor() throws Exception {
        start(true);
        String body = post("/quests/save", baseQuestForm(csrf(get("/quests/new").body()))
                + "&rew.0.kind=EXPERIENCE&_action=add_step").body();
        Matcher m = Pattern.compile(
                "<button[^>]*name=\"_action\" value=\"(add_step|add_obj:0|add_reward|del_step:0|del_obj:0:0|del_reward:0|mv_[a-z]+:0:(?:up|down))\"[^>]*>")
                .matcher(body);
        int seen = 0;
        while (m.find()) {
            seen++;
            assertTrue(m.group().contains("formaction=\"/quests/save#") && !m.group().contains("save#\""),
                    "action structurelle sans ancre de scroll : " + m.group());
        }
        assertTrue(seen >= 4, "plusieurs boutons structurels attendus (" + seen + ")");
    }

    @Test
    void deletingARowWorksEvenWhenItsRequiredMarkedFieldsAreEmpty() throws Exception {
        start(true);
        String token = csrf(get("/quests/new").body());
        // 2 récompenses, la seconde totalement vide -> corbeille sur la seconde.
        String form = baseQuestForm(token)
                + "&rew.0.kind=EXPERIENCE&rew.0.amount=10"
                + "&rew.1.kind=VARIABLE&rew.1.key=&rew.1.value="
                + "&_action=del_reward:1";
        HttpResponse<String> res = post("/quests/save", form);
        assertEquals(200, res.statusCode());
        assertFalse(res.body().contains("name=\"rew.1.kind\""), "la 2e récompense (vide) a été supprimée");
        assertTrue(res.body().contains("name=\"rew.0.kind\""), "la 1re récompense est conservée");
        assertFalse(res.body().contains("class=\"diag-list\""), "aucune validation métier déclenchée par la suppression");
    }

    @Test
    void eachObjectiveTypeRendersOnlyItsOwnFieldSetVisible() throws Exception {
        start(true);
        String token = csrf(get("/quests/new").body());
        for (String kind : new String[] {"KILL_ENTITY", "COLLECT_ITEM", "CRAFT_ITEM", "BREAK_BLOCK",
                "PLACE_BLOCK", "TALK_TO_NPC", "REACH_LOCATION"}) {
            String form = "id=&title=&description=&category=&icon=&step.0.id=step_1"
                    + "&obj.0.0.kind=" + kind + "&obj.0.0._was=KILL_ENTITY&_csrf=" + token + "&_action=refresh";
            String body = post("/quests/save", form).body();
            assertTrue(body.contains("data-kind=\"" + kind + "\">"), kind + " : jeu de champs visible");
            for (String other : new String[] {"KILL_ENTITY", "REACH_LOCATION", "TALK_TO_NPC"}) {
                if (!other.equals(kind)) {
                    assertTrue(body.contains("data-kind=\"" + other + "\" hidden>"),
                            kind + " : le jeu de champs " + other + " est masqué");
                }
            }
        }
    }

    @Test
    void eachRewardTypeRendersOnlyItsOwnFieldSetVisible() throws Exception {
        start(true);
        String token = csrf(get("/quests/new").body());
        for (String kind : new String[] {"EXPERIENCE", "ITEM", "VARIABLE", "COMMAND"}) {
            String body = post("/quests/save", baseQuestForm(token)
                    + "&rew.0.kind=" + kind + "&rew.0._was=EXPERIENCE&_action=refresh").body();
            assertTrue(body.contains("data-kind=\"" + kind + "\">"), kind + " visible");
            for (String other : new String[] {"EXPERIENCE", "ITEM", "VARIABLE", "COMMAND"}) {
                if (!other.equals(kind)) {
                    assertTrue(body.contains("data-kind=\"" + other + "\" hidden>"), kind + " : " + other + " masqué");
                }
            }
        }
    }

    @Test
    void changingObjectiveTypeClearsTheIncompatiblePreviousValues() throws Exception {
        start(true);
        String token = csrf(get("/quests/new").body());
        // KILL_ENTITY entity=SPIDER amount=5 -> bascule COLLECT_ITEM : SPIDER (entité) doit disparaître.
        String form = "id=&title=&description=&category=&icon=&step.0.id=step_1"
                + "&obj.0.0.kind=COLLECT_ITEM&obj.0.0.entity=SPIDER&obj.0.0.amount=5"
                + "&obj.0.0._was=KILL_ENTITY&_csrf=" + token + "&_action=validate";
        String body = post("/quests/save", form).body();
        assertTrue(body.contains("type: COLLECT_ITEM"), "aperçu YAML du nouveau type");
        assertFalse(body.contains("entity: SPIDER"), "la valeur entité de l'ancien type n'est pas conservée");
    }

    @Test
    void npcDatalistCarriesHumanLabelsWhenKnown() {
        RefData ref = new RefData(java.util.List.of(), java.util.List.of("guard", "jo"), java.util.List.of(),
                true, true, false, java.util.Map.of("guard", "Garde"));
        String dl = ContentEditorPages.sharedDatalists(ref);
        assertTrue(dl.contains("<datalist id=\"dl-npc\">"), "datalist PNJ présente");
        assertTrue(dl.contains("<option value=\"guard\" label=\"Garde\">"), "libellé humain sur le PNJ « guard »");
        assertTrue(dl.contains("<option value=\"jo\">"), "PNJ sans nom connu = id seul, sans label vide");
    }

    @Test
    void finalValidationStillRejectsInvalidAmountAndShowsPreview() throws Exception {
        start(true);
        String token = csrf(get("/quests/new").body());
        String form = "id=amount_zero&title=T&description=d&category=tutorial&icon=BOOK&step.0.id=step_1"
                + "&obj.0.0.kind=KILL_ENTITY&obj.0.0.entity=SPIDER&obj.0.0.amount=0"
                + "&_csrf=" + token + "&_action=validate";
        String body = post("/quests/save", form).body();
        assertTrue(body.contains("class=\"diag-list\""), "la validation finale s'exécute sur « Vérifier »");
        assertTrue(body.toLowerCase().contains("erreur") || body.contains("ERREUR"), "amount=0 signalé");
        assertTrue(body.contains("Fichier généré"), "l'aperçu YAML reste affiché");
    }

    // ---- helpers ----------------------------------------------------------------------

    private String baseQuestForm(String csrf) {
        return "id=test_quest&title=" + URLEncoder.encode("Quête d'essai", StandardCharsets.UTF_8)
                + "&description=" + URLEncoder.encode("Une quête de test.", StandardCharsets.UTF_8)
                + "&category=tutorial&icon=BOOK"
                + "&step.0.id=step_1"
                + "&obj.0.0.kind=KILL_ENTITY&obj.0.0.entity=SPIDER&obj.0.0.amount=5"
                + "&_csrf=" + csrf;
    }

    private void start(boolean withSubdirs) throws Exception {
        contentRoot = tmp.resolve("content");
        Files.createDirectories(contentRoot);
        if (withSubdirs) {
            Files.createDirectories(contentRoot.resolve("quests"));
            Files.createDirectories(contentRoot.resolve("stories"));
        }
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
                + URLEncoder.encode(TestConfig.OWNER_PASSWORD, StandardCharsets.UTF_8) + "&_csrf=" + token);
    }

    private static String csrf(String html) {
        Matcher m = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"").matcher(html);
        if (!m.find()) {
            throw new IllegalStateException("pas de jeton _csrf dans la page");
        }
        return m.group(1);
    }

    private static String attr(String html, String name) {
        Matcher m = Pattern.compile("name=\"" + Pattern.quote(name) + "\" value=\"([^\"]*)\"").matcher(html);
        if (!m.find()) {
            throw new IllegalStateException("champ « " + name + " » absent");
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
