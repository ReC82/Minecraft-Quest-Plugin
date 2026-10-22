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
import com.lodygames.rpgquest.panel.content.DialogueDraft;
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
    void theGuideUsesTheLabelsTheEditorReallyShowsAndIsOneClickAway() throws Exception {
        // Issue #729 : le guide décrit l'interface actuelle, pas un ancien écran. Chaque libellé
        // du parcours normal doit exister dans la page ET être nommé tel quel dans le guide.
        start();
        String page = get("/dialogues/edit/lucien").body();
        String guide = com.lodygames.rpgquest.panel.docs.DocLibrary.load().bySlug("dialogues").orElseThrow().markdown();
        for (String label : List.of("Début de la conversation", "Le personnage dit…", "Le joueur peut répondre…",
                "Réponse du joueur", "Ensuite…", "Terminer la conversation", "Une nouvelle réplique…", "Et aussi…",
                "Ajouter une réponse", "Supprimer cette réponse", "Ajouter une réplique", "Réglages de la réplique",
                "Réglages du dialogue", "Enregistrer et mettre en jeu", "Aperçu de la conversation")) {
            assertTrue(page.contains(label), "libellé de l'éditeur : " + label);
            assertTrue(guide.contains(label), "le guide nomme : " + label);
        }
        assertTrue(page.contains("href=\"" + DialoguePages.GUIDE_HREF + "\""), "aide depuis l'éditeur");
        assertTrue(get("/dialogues").body().contains("href=\"" + DialoguePages.GUIDE_HREF + "\""), "aide depuis la liste");
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
        // Issue #730 : la nouvelle réplique a d'office une sortie, sinon le jeu refuserait le dialogue.
        assertEquals(1, d.nodes.get(1).choices.size());
        assertEquals("Au revoir.", d.nodes.get(1).choices.get(0).text);
        assertTrue(d.nodes.get(1).choices.get(0).close, "elle termine la conversation");
    }

    // ---- Issue #730 : une réplique sans réponse ----------------------------------------------

    /** Le parcours de Musty : réplique 2 sans réponse, « Enregistrer et mettre en jeu ». */
    @Test
    void aLineWithoutAnyReplyIsRefusedBeforeAnyWriteAndCanBeRepaired() throws Exception {
        start();
        Map<String, String> f = formFields(get("/dialogues/edit/lucien").body());
        String withReply = post("/dialogues/save", f, "addreply:0").body();
        Map<String, String> g = formFields(withReply);
        g.put("n0_c1_text", "Tu vends quoi ?");
        g.put("n0_c1_next", "__new");
        Map<String, String> h = formFields(post("/dialogues/save", g, "save").body());
        h.put("n1_text", "Du pain frais !");
        // On retire la réponse posée d'office : la réplique 2 n'a plus aucune sortie.
        String emptied = post("/dialogues/save", h, "delreply:1:0").body();
        assertTrue(unescape(emptied).contains("Cette réplique n'a pas encore de réponse"), "dit avant d'enregistrer");
        assertFalse(emptied.contains("la conversation s'arrête après cette réplique"), "l'ancienne promesse a disparu");
        assertTrue(emptied.contains(DialoguePages.END_HELP_HREF), "lien vers l'explication");

        String refused = post("/dialogues/save", formFields(emptied), "save_publish").body();
        String text = unescape(refused);
        assertTrue(text.contains("Enregistrement impossible : la réplique 2 n'a pas de sortie. Ajoutez une réponse "
                + "qui termine la conversation."), "message clair, réplique nommée comme dans l'éditeur");
        assertTrue(text.contains("Cette réplique n'a pas de sortie"), "et près de la réplique");
        assertTrue(refused.contains(DialoguePages.END_HELP_HREF));
        assertFalse(text.contains("inexistant"), "aucune erreur en cascade sur une réplique bien présente");
        assertTrue(refused.contains("value=\"Du pain frais !\"") || refused.contains("Du pain frais !"),
                "la saisie est conservée");
        assertEquals(LUCIEN, Files.readString(content.resolve("dialogues/lucien.yml")), "rien d'écrit");
        assertTrue(actions("content.publish").isEmpty(), "rien d'envoyé au serveur");

        // Réparation : une réponse ajoutée termine la conversation par défaut.
        Map<String, String> fix = formFields(post("/dialogues/save", formFields(refused), "addreply:1").body());
        fix.put("n1_c0_text", "Merci, au revoir.");
        assertEquals(303, post("/dialogues/save", fix, "save_publish").statusCode());
        var saved = DialogueYaml.read(Files.readString(content.resolve("dialogues/lucien.yml"))).draft();
        assertEquals(1, saved.nodes.get(1).choices.size());
        assertTrue(saved.nodes.get(1).choices.get(0).close);
        assertEquals(1, actions("content.publish").size(), "une seule mise en jeu, une fois réparé");
    }

    /** Un fichier source déjà invalide (écrit hors de l'éditeur) n'est pas mis en jeu non plus. */
    @Test
    void anInvalidSourceIsNeverSentToTheServerAloneOrInABatch() throws Exception {
        start();
        String broken = """
                id: rpgquest:musty_test
                start: start
                nodes:
                  start:
                    speaker: "Musty"
                    text: "Salut !"
                    choices:
                      - text: "Tu vends quoi ?"
                        next: r2
                  r2:
                    speaker: "Musty"
                    text: "Du pain."
                """;
        Files.writeString(content.resolve("dialogues/musty_test.yml"), broken);
        String token = csrf(get("/dialogues/edit/musty_test").body());

        HttpResponse<String> one = post("/dialogues/publish", Map.of("_csrf", token, "agent", TestConfig.AGENT_ID,
                "slug", "musty_test", "expected_sha", sha256(broken)), null);
        String where = java.net.URLDecoder.decode(one.headers().firstValue("Location").orElse(""), StandardCharsets.UTF_8);
        assertTrue(where.contains("Mise en jeu impossible : la réplique 2 n'a pas de sortie"), where);

        Map<String, String> batch = new LinkedHashMap<>();
        batch.put("_csrf", token);
        batch.put("agent", TestConfig.AGENT_ID);
        batch.put("type", "content.publish.batch");
        batch.put("return", "/content/import");
        batch.put("count", "2");
        batch.put("kind_0", "dialogues");
        batch.put("id_0", "lucien");
        batch.put("expected_dev_sha_0", "");
        batch.put("expected_source_sha_0", sha256(LUCIEN));
        batch.put("kind_1", "dialogues");
        batch.put("id_1", "musty_test");
        batch.put("expected_dev_sha_1", "");
        batch.put("expected_source_sha_1", sha256(broken));
        batch.put("confirm", "true");
        HttpResponse<String> lot = post("/agents/action", batch, null);
        String lotWhere = java.net.URLDecoder.decode(lot.headers().firstValue("Location").orElse(""), StandardCharsets.UTF_8);
        assertTrue(lotWhere.contains("dialogues/musty_test : Mise en jeu impossible"), lotWhere);

        assertTrue(actions("content.publish").isEmpty() && actions("content.publish.batch").isEmpty(),
                "ni seul ni en lot : le lot entier est refusé");
        assertEquals(broken, Files.readString(content.resolve("dialogues/musty_test.yml")), "la source reste telle quelle");
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

        Map<String, String> f = formFields(page);
        f.put("n1_text", "Il était une fois, au marais…");
        assertEquals(303, post("/dialogues/save", f, "save").statusCode());
        DialogueDraft expected = DialogueYaml.read(COMPLEX).draft();
        expected.nodes.get(1).text = "Il était une fois, au marais…";
        assertEquals(DialogueYaml.write(expected), Files.readString(content.resolve("dialogues/sage.yml")),
                "conditions, effets, ordre et fermeture conservés");
    }

    /** Issue #373 : enregistrer sans rien changer n'écrit rien — commentaires et mise en forme restent. */
    @Test
    void savingWithoutAnyChangeLeavesTheFileUntouched() throws Exception {
        start();
        Files.writeString(content.resolve("dialogues/sage.yml"), COMPLEX);
        HttpResponse<String> res = post("/dialogues/save", formFields(get("/dialogues/edit/sage").body()), "save");
        assertEquals(303, res.statusCode());
        assertTrue(res.headers().firstValue("Location").orElse("").contains("ok="), "« aucune modification » est dit");
        assertEquals(COMPLEX, Files.readString(content.resolve("dialogues/sage.yml")));
    }

    /**
     * Issue #373 : le VRAI dialogue du Garde (scalaires {@code text: >}, commentaires, 22 répliques).
     * L'éditeur n'en montrait que deux, et l'enregistrement sans changement était refusé.
     */
    @Test
    void theRealGuardDialogueOpensWholeAndSavesUnchanged() throws Exception {
        Path source = Path.of("..", "src", "main", "resources", "dialogues", "guard.yml");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isRegularFile(source), "guard.yml introuvable");
        start();
        String original = Files.readString(source);
        Files.writeString(content.resolve("dialogues/guard.yml"), original);
        String page = get("/dialogues/edit/guard").body();
        assertFalse(page.contains("Réplique introuvable"), "toutes les suites sont résolues");
        assertFalse(page.contains("lecture seule"), "le fichier est éditable");
        Map<String, String> f = formFields(page);
        assertEquals("22", f.get("n_count"), "les 22 répliques sont dans le formulaire");
        assertTrue(page.contains("Le Garde — Palier 5"), "une réplique écrite en « text: > » s'affiche");

        HttpResponse<String> res = post("/dialogues/save", f, "save");
        assertEquals(303, res.statusCode(), "aucune erreur à l'enregistrement sans changement");
        assertEquals(original, Files.readString(content.resolve("dialogues/guard.yml")), "fichier intact, commentaires compris");

        // Une vraie modification : le fichier est réécrit sans rien perdre d'autre.
        f.put("n0_text", "<white>Bienvenue, voyageur.</white>");
        assertEquals(303, post("/dialogues/save", f, "save").statusCode());
        DialogueDraft after = DialogueYaml.read(Files.readString(content.resolve("dialogues/guard.yml"))).draft();
        DialogueDraft before = DialogueYaml.read(original).draft();
        before.nodes.get(0).text = "<white>Bienvenue, voyageur.</white>";
        assertEquals(DialogueYaml.write(before), DialogueYaml.write(after));
        assertEquals(22, after.nodes.size());
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

    // ---- Issue #731 : reprise après un refus ou un conflit ----------------------------------------

    /**
     * Parcours du propriétaire (Musty) : la mise en jeu a écrit le fichier sur DEV puis le moteur l'a
     * refusé (ancien comportement serveur). La correction qui suit, dans le même éditeur et sans F5,
     * repart de l'empreinte laissée par CETTE tentative : pas de faux conflit.
     */
    @Test
    void aCorrectionAfterAWrittenButRefusedPublishIsNotAConflict() throws Exception {
        start();
        Instant t0 = Instant.now();
        devState("lucien", sha256(LUCIEN), t0);
        Map<String, String> f = formFields(get("/dialogues/edit/lucien").body());
        f.put("n0_text", "Version refusée");
        post("/dialogues/save", f, "save_publish");
        AgentActionRow first = actions("content.publish").get(0);
        assertEquals(sha256(LUCIEN), first.params().get("expected_dev_sha"));
        String written = sha256("refusé par le moteur");
        publishResult(first.id(), false, "RELOAD_FAILED", sha256(LUCIEN), written, t0.plusSeconds(2));

        String failed = get("/dialogues/edit/lucien").body();
        assertTrue(failed.contains("Mise en jeu échouée"), "un refus n'est jamais présenté comme un succès");
        assertFalse(failed.contains("À jour en jeu"));
        assertFalse(failed.contains("Le serveur a une autre version"), "notre propre tentative n'est pas un conflit");

        Map<String, String> g = formFields(failed);
        g.put("n0_text", "Version corrigée");
        post("/dialogues/save", g, "save_publish");
        List<AgentActionRow> pubs = actions("content.publish");
        assertEquals(2, pubs.size(), "la correction part sans détour");
        AgentActionRow second = pubs.stream().filter(a -> !a.id().equals(first.id())).findFirst().orElseThrow();
        assertEquals(written, second.params().get("expected_dev_sha"), "référence = ce que la tentative a laissé");
        assertTrue(Files.readString(content.resolve("dialogues/lucien.yml")).contains("Version corrigée"),
                "le brouillon corrigé est enregistré");
    }

    /** Nouveau comportement serveur : le fichier refusé est remis en place, la référence ne bouge pas. */
    @Test
    void aRefusedPublishThatPutDevBackKeepsTheSameReference() throws Exception {
        start();
        Instant t0 = Instant.now();
        devState("lucien", sha256(LUCIEN), t0);
        Map<String, String> f = formFields(get("/dialogues/edit/lucien").body());
        f.put("n0_text", "Version refusée");
        post("/dialogues/save", f, "save_publish");
        AgentActionRow first = actions("content.publish").get(0);
        publishResult(first.id(), false, "RELOAD_FAILED", sha256(LUCIEN), sha256(LUCIEN), t0.plusSeconds(2));

        Map<String, String> g = formFields(get("/dialogues/edit/lucien").body());
        g.put("n0_text", "Version corrigée");
        post("/dialogues/save", g, "save_publish");
        AgentActionRow second = actions("content.publish").stream().filter(a -> !a.id().equals(first.id()))
                .findFirst().orElseThrow();
        assertEquals(sha256(LUCIEN), second.params().get("expected_dev_sha"));
    }

    /**
     * Une VRAIE modification concurrente garde sa protection : parcours guidé (comparer, choisir),
     * aucune mise en jeu automatique, brouillon conservé.
     */
    @Test
    void aRealConcurrentChangeShowsTheGuidedChoiceAndNeverOverwrites() throws Exception {
        start();
        Instant t0 = Instant.now();
        devState("lucien", sha256(LUCIEN), t0);
        Map<String, String> f = formFields(get("/dialogues/edit/lucien").body());
        f.put("n0_text", "Ma version");
        post("/dialogues/save", f, "save_publish");
        AgentActionRow first = actions("content.publish").get(0);
        String theirs = sha256("modifié à la main sur le serveur");
        publishResult(first.id(), false, "CONFLICT", theirs, theirs, t0.plusSeconds(2));

        String page = get("/dialogues/edit/lucien").body();
        assertTrue(page.contains("Le serveur a une autre version de ce dialogue"), page);
        assertTrue(page.contains("votre version enregistrée ici est gardée"));
        assertTrue(page.contains("href=\"/dialogues/diagnostic?focus=lucien\""), "comparer d'abord");
        assertTrue(page.contains("/docs/dialogues-depannage#le-serveur-a-une-autre-version-de-ce-dialogue"));
        assertFalse(page.contains("Réessayer</button>"), "pas de réessai aveugle");
        assertFalse(page.contains("action=\"/dialogues/publish\""), "pas de mise en jeu directe");

        Map<String, String> g = formFields(page);
        g.put("n0_text", "Ma version, encore");
        HttpResponse<String> res = post("/dialogues/save", g, "save_publish");
        String location = java.net.URLDecoder.decode(res.headers().firstValue("Location").orElse(""),
                StandardCharsets.UTF_8);
        assertTrue(location.contains("rien n'a été remplacé"), location);
        assertEquals(1, actions("content.publish").size(), "aucune nouvelle mise en jeu");
        assertTrue(Files.readString(content.resolve("dialogues/lucien.yml")).contains("Ma version, encore"),
                "le brouillon est gardé");
    }

    // ---- Recharger depuis DEV (issue #732) -------------------------------------------------------

    private static final String LUCIEN_DEV = LUCIEN.replace("Salut !", "Bonjour depuis DEV !");

    private String readIdOf(HttpResponse<String> res) {
        String location = res.headers().firstValue("Location").orElse("");
        Matcher m = Pattern.compile("[?&]reload=([^&#]+)").matcher(location);
        assertTrue(m.find(), location);
        return java.net.URLDecoder.decode(m.group(1), StandardCharsets.UTF_8);
    }

    private static Map<String, String> applyForm(String page) {
        int at = page.indexOf("value=\"apply\"");
        assertTrue(at > 0, "le remplacement est proposé");
        return formFieldsOf(page.substring(page.lastIndexOf("<form", at)), "/dialogues/reload");
    }

    /** Le texte, tel quel ou avec ses apostrophes échappées par le rendu. */
    private static boolean shows(String page, String text) {
        return page.contains(text) || page.contains(text.replace("'", "&#39;"));
    }

    private List<Path> backups(String suffix) throws Exception {
        Path root = tmp.resolve("content-backups");
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (var walk = Files.walk(root)) {
            return walk.filter(p -> p.getFileName().toString().endsWith(suffix)).toList();
        }
    }

    /** État propre : un clic lit DEV (sans rien écrire), un second remplace, avec sauvegarde. */
    @Test
    void reloadWithoutLocalChangesReadsDevThenReplacesOnlyOnClickWithABackup() throws Exception {
        start();
        devState("lucien", sha256(LUCIEN), Instant.now().minusSeconds(60));
        String page = get("/dialogues/edit/lucien").body();
        assertTrue(page.contains("Recharger depuis DEV"), "bouton visible sans passer par les détails techniques");
        assertTrue(page.contains("form=\"dlg-editor-form\""), "il envoie le formulaire de l'éditeur");

        HttpResponse<String> res = post("/dialogues/save", formFields(page), "reload");
        assertEquals(303, res.statusCode());
        String readId = readIdOf(res);
        assertEquals(1, actions("content.dev.read").size(), "une lecture du fichier DEV");
        assertEquals("lucien", actions("content.dev.read").get(0).params().get("id"));
        assertEquals(LUCIEN, Files.readString(content.resolve("dialogues/lucien.yml")), "rien d'écrit à la lecture");

        String pending = get("/dialogues/edit/lucien?reload=" + readId).body();
        assertTrue(pending.contains("Lecture de la version DEV en cours"), pending);
        assertTrue(pending.contains("data-pa-track-action=\"" + readId + "\""), "la page se met à jour seule");

        recordDevRead("lucien", LUCIEN_DEV);
        String ready = get("/dialogues/edit/lucien?reload=" + readId).body();
        assertTrue(ready.contains("Version DEV lue"), ready);
        assertTrue(ready.contains("différente"));
        assertTrue(ready.contains("Remplacer ma version par celle de DEV"));
        assertTrue(ready.contains("href=\"/dialogues/diagnostic?focus=lucien\""), "comparer est proposé");
        assertEquals(LUCIEN, Files.readString(content.resolve("dialogues/lucien.yml")), "toujours rien d'écrit");

        HttpResponse<String> done = post("/dialogues/reload", applyForm(ready), null);
        assertEquals(303, done.statusCode());
        assertTrue(done.headers().firstValue("Location").orElse("").contains("reloaded="));
        assertEquals(LUCIEN_DEV, Files.readString(content.resolve("dialogues/lucien.yml")), "octet pour octet");
        List<Path> saved = backups("lucien.yml");
        assertEquals(1, saved.size(), "la version remplacée est sauvegardée");
        assertEquals(LUCIEN, Files.readString(saved.get(0)));
        assertTrue(actions("content.publish").isEmpty(), "rien n'est mis en jeu");
        assertTrue(actions("content.publish.batch").isEmpty());

        String after = get(done.headers().firstValue("Location").orElseThrow()).body();
        assertTrue(shows(after, "Version DEV rechargée dans l'éditeur"), after);
        assertTrue(formHtml(after).contains("Bonjour depuis DEV !"), "l'éditeur montre la version DEV");

        // Rejouer le même formulaire ne réécrit rien : la version d'ici a changé depuis.
        HttpResponse<String> replay = post("/dialogues/reload", applyForm(ready), null);
        assertTrue(replay.headers().firstValue("Location").orElse("").contains("err="));
        assertEquals(1, backups("lucien.yml").size());
    }

    /** Modifications non enregistrées : on demande d'abord ; rien n'est perdu sans case cochée. */
    @Test
    void unsavedChangesAreNeverDroppedWithoutAnExplicitConfirmation() throws Exception {
        start();
        Map<String, String> f = formFields(get("/dialogues/edit/lucien").body());
        f.put("n0_text", "Mon brouillon précieux");

        HttpResponse<String> ask = post("/dialogues/save", f, "reload");
        assertEquals(200, ask.statusCode(), "aucune redirection : la saisie est réaffichée");
        assertTrue(ask.body().contains("Vous avez des modifications non enregistrées"), ask.body());
        assertTrue(formHtml(ask.body()).contains("Mon brouillon précieux"), "le brouillon est toujours là");
        assertTrue(formHtml(ask.body()).contains("value=\"reload_cancel\""), "Annuler est dans le formulaire");
        assertTrue(actions("content.dev.read").isEmpty(), "rien n'est lu avant le choix");

        HttpResponse<String> cancel = post("/dialogues/save", formFields(ask.body()), "reload_cancel");
        assertTrue(shows(cancel.body(), "Rien n'a été rechargé"));
        assertTrue(formHtml(cancel.body()).contains("Mon brouillon précieux"));

        HttpResponse<String> unchecked = post("/dialogues/save", formFields(ask.body()), "reload_confirm");
        assertTrue(shows(unchecked.body(), "cochez d'abord la case"), unchecked.body());
        assertTrue(formHtml(unchecked.body()).contains("Mon brouillon précieux"));
        assertTrue(actions("content.dev.read").isEmpty());

        Map<String, String> confirmed = formFields(ask.body());
        confirmed.put("reload_discard", "1");
        HttpResponse<String> go = post("/dialogues/save", confirmed, "reload_confirm");
        assertEquals(303, go.statusCode());
        readIdOf(go);
        assertEquals(1, actions("content.dev.read").size());
        List<Path> kept = backups("lucien.brouillon.yml");
        assertEquals(1, kept.size(), "une copie du brouillon est gardée");
        assertTrue(Files.readString(kept.get(0)).contains("Mon brouillon précieux"));
        assertEquals(LUCIEN, Files.readString(content.resolve("dialogues/lucien.yml")),
                "la version enregistrée n'est pas touchée par la lecture");
    }

    /** Erreur de lecture ou fichier absent : message clair, éditeur et fichier intacts. */
    @Test
    void aReadErrorOrAMissingFileLeavesTheEditorUntouched() throws Exception {
        start();
        String readId = readIdOf(post("/dialogues/save", formFields(get("/dialogues/edit/lucien").body()), "reload"));
        store.recordResult(readId, TestConfig.AGENT_ID, AgentActionStatus.FAILED, "IO", "Lecture refusée par le serveur",
                "{\"action_id\":\"" + readId + "\",\"status\":\"FAILED\"}", Instant.now());
        String failed = get("/dialogues/edit/lucien?reload=" + readId).body();
        assertTrue(failed.contains("Lecture impossible"), failed);
        assertTrue(shows(failed, "Rien n'a changé dans l'éditeur"));
        assertFalse(failed.contains("value=\"apply\""), "rien à remplacer");
        assertTrue(failed.contains("Relire le serveur DEV"), "on peut réessayer");
        assertEquals(LUCIEN, Files.readString(content.resolve("dialogues/lucien.yml")));

        Map<String, String> again = formFieldsOf(failed.substring(failed.indexOf("id=\"dlg-reload\"")), "/dialogues/reload");
        String second = readIdOf(post("/dialogues/reload", again, null));
        store.recordResult(second, TestConfig.AGENT_ID, AgentActionStatus.SUCCESS, "absent", "ok",
                "{\"action_id\":\"" + second + "\",\"status\":\"SUCCESS\",\"details\":{\"kind\":\"dialogues\","
                        + "\"slug\":\"lucien\",\"present\":false,\"tooLarge\":false,\"sha256\":\"\",\"text\":\"\"}}",
                Instant.now());
        String absent = get("/dialogues/edit/lucien?reload=" + second).body();
        assertTrue(shows(absent, "n'a pas ce dialogue"), absent);
        assertEquals(LUCIEN, Files.readString(content.resolve("dialogues/lucien.yml")));
        assertTrue(backups(".yml").isEmpty(), "aucune sauvegarde ni écriture");

        // Un identifiant de lecture qui ne concerne pas ce dialogue n'affiche rien.
        assertFalse(get("/dialogues/edit/lucien?reload=inconnu").body().contains("id=\"dlg-reload\""));
    }

    /**
     * Parcours Musty : mise en jeu refusée (INVALID_CONTENT), puis vrai conflit ; on recharge DEV,
     * on réapplique sa correction et la mise en jeu repart sans rester bloquée.
     */
    @Test
    void afterARefusedThenConflictingPublishTheOwnerReloadsFixesAndRetries() throws Exception {
        start();
        Instant t0 = Instant.now().minusSeconds(120);
        devState("lucien", sha256(LUCIEN), t0);
        Map<String, String> f = formFields(get("/dialogues/edit/lucien").body());
        f.put("n0_text", "Version invalide");
        post("/dialogues/save", f, "save_publish");
        AgentActionRow first = actions("content.publish").get(0);
        publishResult(first.id(), false, "INVALID_CONTENT", sha256(LUCIEN), sha256(LUCIEN), t0.plusSeconds(2));

        Map<String, String> g = formFields(get("/dialogues/edit/lucien").body());
        g.put("n0_text", "Version corrigée");
        post("/dialogues/save", g, "save_publish");
        AgentActionRow second = actions("content.publish").stream().filter(a -> !a.id().equals(first.id()))
                .findFirst().orElseThrow();
        publishResult(second.id(), false, "CONFLICT", sha256(LUCIEN_DEV), sha256(LUCIEN_DEV), t0.plusSeconds(4));

        String conflict = get("/dialogues/edit/lucien").body();
        assertTrue(conflict.contains("Le serveur a une autre version de ce dialogue"), conflict);
        int box = conflict.indexOf("dlg-conflict");
        assertTrue(conflict.indexOf("Recharger depuis DEV", box) > 0, "le conflit propose de recharger");

        String readId = readIdOf(post("/dialogues/save", formFields(conflict), "reload"));
        recordDevRead("lucien", LUCIEN_DEV);
        String ready = get("/dialogues/edit/lucien?reload=" + readId).body();
        post("/dialogues/reload", applyForm(ready), null);
        assertEquals(LUCIEN_DEV, Files.readString(content.resolve("dialogues/lucien.yml")));
        assertEquals(1, backups("lucien.yml").size(), "« Version corrigée » est sauvegardée");

        String reloaded = get("/dialogues/edit/lucien?reloaded=" + readId).body();
        assertFalse(reloaded.contains("Le serveur a une autre version de ce dialogue"), "le conflit est résolu");

        Map<String, String> h = formFields(reloaded);
        h.put("n0_text", "Version corrigée, réappliquée");
        post("/dialogues/save", h, "save_publish");
        List<AgentActionRow> pubs = actions("content.publish");
        assertEquals(3, pubs.size(), "la mise en jeu repart");
        AgentActionRow third = pubs.stream().filter(a -> !a.id().equals(first.id()) && !a.id().equals(second.id()))
                .findFirst().orElseThrow();
        assertEquals(sha256(LUCIEN_DEV), third.params().get("expected_dev_sha"),
                "référence = la version DEV reprise, pas l'ancienne");
    }

    /** Fichier DEV rechargé mais relevé du jeu différent : les deux états sont dits séparément. */
    @Test
    void aDevFileThatTheGameHasNotLoadedIsSaidSeparately() throws Exception {
        start();
        String readId = readIdOf(post("/dialogues/save", formFields(get("/dialogues/edit/lucien").body()), "reload"));
        recordDevRead("lucien", LUCIEN_DEV);
        survey("{\"dialogues\":[{\"id\":\"rpgquest:lucien\",\"key\":\"lucien\",\"startNodeId\":\"start\","
                + "\"nodes\":[{\"id\":\"start\",\"speaker\":\"Lucien\",\"text\":\"Salut !\",\"choices\":["
                + "{\"text\":\"Au revoir\",\"nextNodeId\":\"\",\"actions\":[{\"kind\":\"CLOSE\"}],"
                + "\"conditions\":[]}]}]}]}");
        String ready = get("/dialogues/edit/lucien?reload=" + readId).body();
        assertTrue(ready.contains("une ancienne version est toujours en jeu"), ready);
        post("/dialogues/reload", applyForm(ready), null);
        String after = get("/dialogues/edit/lucien?reloaded=" + readId).body();
        assertTrue(after.contains("Version DEV rechargée dans l"), after);
        assertTrue(after.contains("une ancienne version est toujours en jeu"), "fichier DEV ≠ version en jeu");
    }

    private void devState(String slug, String sha, Instant at) {
        String id = store.createAction(TestConfig.AGENT_ID, "content.dev.state", Map.of(), "owner");
        String details = "{\"files\":[{\"kind\":\"dialogues\",\"slug\":\"" + slug + "\",\"sha256\":\"" + sha
                + "\",\"bytes\":10}],\"runtimeIds\":{\"dialogues\":[\"rpgquest:" + slug + "\"]},\"runtimeHash\":\"h\"}";
        store.recordResult(id, TestConfig.AGENT_ID, AgentActionStatus.SUCCESS, "", "ok",
                "{\"action_id\":\"" + id + "\",\"status\":\"SUCCESS\",\"details\":" + details + "}", at);
    }

    private void publishResult(String id, boolean ok, String code, String before, String after, Instant at) {
        String details = "{\"ok\":" + ok + ",\"code\":\"" + code + "\",\"kind\":\"dialogues\",\"slug\":\"lucien\","
                + "\"devShaBefore\":\"" + before + "\",\"devShaAfter\":\"" + after + "\",\"runtimeConfirmed\":false}";
        store.recordResult(id, TestConfig.AGENT_ID, ok ? AgentActionStatus.SUCCESS : AgentActionStatus.FAILED, code,
                "Refus " + code, "{\"action_id\":\"" + id + "\",\"status\":\"FAILED\",\"details\":" + details + "}", at);
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

    // ---- Issue #430 : dialogue présent seulement sur le serveur, listes de quêtes -----------

    private static final String BANDIT_SURVEY = "{\"dialogues\":[{\"id\":\"rpgquest:brigand_test\","
            + "\"startNodeId\":\"hello\",\"nodes\":[{\"id\":\"hello\",\"speaker\":\"Brigand\",\"text\":\"Salut.\","
            + "\"choices\":[{\"text\":\"Du travail ?\",\"nextNodeId\":null,\"actions\":[{\"type\":\"START_QUEST\","
            + "\"quest\":\"rpgquest:herbes\"}],\"conditions\":[]}]}]}]}";

    private static final String BANDIT_DEV = "# Dialogue jetable de test (#430) — commentaire conservé\n"
            + "id: rpgquest:brigand_test\nstart: hello\nentries:\n  - node: hello\n    conditions:\n"
            + "      - type: QUEST_STATE\n        quest: rpgquest:herbes\n        state: COMPLETED\nnodes:\n  hello:\n"
            + "    speaker: \"Brigand\"\n    text: \"Salut. Herbes du marais, ça te dit ?\"\n    variants:\n"
            + "      - \"Encore toi.\"\n    choices:\n      - text: \"Du travail ?\"\n        conditions:\n"
            + "          - type: QUEST_AVAILABLE\n            quest: rpgquest:herbes\n        actions:\n"
            + "          - type: START_QUEST\n            quest: rpgquest:herbes\n          - type: CLOSE\n";

    private static final String CATALOG_SURVEY = "{\"available\":true,\"catalogs\":[{\"id\":\"brigands\","
            + "\"displayName\":\"Quêtes des brigands\",\"description\":\"\",\"enabled\":true,\"avoidRecent\":1,"
            + "\"entries\":[{\"quest\":\"rpgquest:herbes\",\"weight\":10,\"enabled\":true,\"exists\":true,"
            + "\"title\":\"Herbes du marais\"},{\"quest\":\"rpgquest:pain\",\"weight\":10,\"enabled\":true,"
            + "\"exists\":true,\"title\":\"Le pain de Lucien\"}],\"usedBy\":[\"forest_test\"],\"warnings\":[]},"
            + "{\"id\":\"vieille\",\"displayName\":\"Vieille liste\",\"enabled\":false,\"entries\":[],"
            + "\"usedBy\":[],\"warnings\":[]}],"
            + "\"issues\":{},\"quests\":[],\"warnings\":[],\"templates\":[{\"id\":\"forest_test\","
            + "\"displayName\":\"Brigand de test\",\"npcId\":\"brigand_test\",\"enabled\":true,"
            + "\"questCatalogs\":[\"brigands\"],\"offersCatalog\":false}]}";

    private void recordDevRead(String slug, String text) {
        // L'agent répond à la lecture demandée par la page, s'il y en a une ; sinon à une nouvelle.
        String id = actions("content.dev.read").stream().filter(a -> !a.status().terminal()).map(AgentActionRow::id)
                .findFirst().orElseGet(() -> store.createAction(TestConfig.AGENT_ID, "content.dev.read",
                        Map.of("kind", "dialogues", "id", slug), "owner"));
        String sha = sha256(text);
        String details = "{\"kind\":\"dialogues\",\"slug\":\"" + slug + "\",\"present\":true,\"tooLarge\":false,"
                + "\"sha256\":\"" + sha + "\",\"text\":" + json(text) + "}";
        store.recordResult(id, TestConfig.AGENT_ID, AgentActionStatus.SUCCESS, "present", "ok",
                "{\"action_id\":\"" + id + "\",\"status\":\"SUCCESS\",\"details\":" + details + "}", Instant.now());
    }

    private static String sha256(String text) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String json(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    @Test
    void aServerOnlyDialogueIsCopiedByteForByteWithoutTouchingTheGame() throws Exception {
        start();
        survey(BANDIT_SURVEY);
        record("quest.catalog.list", CATALOG_SURVEY);
        String page = get("/dialogues/edit/brigand_test").body();
        assertTrue(page.contains("Rendre ce dialogue modifiable"), "la copie est proposée");
        assertTrue(page.contains("PNJ récurrent : "), "le modèle de rencontre est nommé");
        assertTrue(page.contains("Brigand de test"));
        assertFalse(page.contains("Aucun personnage n'utilise ce dialogue"), "le vrai usage est dit");
        assertFalse(page.contains("Copier dans PlugAdmin pour le modifier"), "pas avant la lecture");

        Map<String, String> read = formFieldsOf(page, "/dialogues/import");
        assertEquals("read", read.get("_op"));
        assertEquals(303, post("/dialogues/import", read, null).statusCode());
        assertEquals(1, actions("content.dev.read").size(), "une lecture du fichier DEV, rien d'autre");
        assertEquals("brigand_test", actions("content.dev.read").get(0).params().get("id"));

        recordDevRead("brigand_test", BANDIT_DEV);
        String ready = get("/dialogues/edit/brigand_test").body();
        assertTrue(ready.contains("Copier dans PlugAdmin pour le modifier"), ready);
        assertTrue(ready.contains(sha256(BANDIT_DEV).substring(0, 12)), "l'empreinte est montrée");
        int at = ready.indexOf("value=\"import\"");
        Map<String, String> copy = formFieldsOf(ready.substring(ready.lastIndexOf("<form", at)), "/dialogues/import");
        HttpResponse<String> done = post("/dialogues/import", copy, null);
        assertEquals(303, done.statusCode());
        assertEquals(BANDIT_DEV, Files.readString(content.resolve("dialogues/brigand_test.yml")),
                "octet pour octet : commentaire, entrées, variantes, conditions et actions");
        assertTrue(actions("content.publish").isEmpty(), "rien n'est publié");
        assertTrue(actions("content.publish.batch").isEmpty(), "rien n'est publié");

        String editable = get("/dialogues/edit/brigand_test").body();
        assertFalse(editable.contains("Rendre ce dialogue modifiable"));
        assertTrue(formHtml(editable).contains("name=\"n0_c0_qa\""), "l'éditeur simple s'ouvre");
        assertFalse(formHtml(editable).contains("name=\"n0_c0_qa\" data-switch=\"dlg-n0-c0-q\" disabled"),
                "modifiable");
        assertTrue(editable.contains("À vérifier") || editable.contains("Ce PNJ a une liste de quêtes"),
                "les passages liés à la quête précise sont signalés");

        // Une seconde copie n'écrase jamais la source.
        Files.writeString(content.resolve("dialogues/brigand_test.yml"), "id: rpgquest:brigand_test\n# modifié\n");
        HttpResponse<String> again = post("/dialogues/import", copy, null);
        assertTrue(again.headers().firstValue("Location").orElse("").contains("err="));
        assertEquals("id: rpgquest:brigand_test\n# modifié\n",
                Files.readString(content.resolve("dialogues/brigand_test.yml")), "aucun écrasement");
    }

    @Test
    void aCopyIsRefusedWhenTheServerFileChangedSinceItWasShown() throws Exception {
        start();
        survey(BANDIT_SURVEY);
        recordDevRead("brigand_test", BANDIT_DEV);
        String ready = get("/dialogues/edit/brigand_test").body();
        int at = ready.indexOf("value=\"import\"");
        Map<String, String> copy = formFieldsOf(ready.substring(ready.lastIndexOf("<form", at)), "/dialogues/import");
        recordDevRead("brigand_test", BANDIT_DEV + "# changé sur DEV\n");
        HttpResponse<String> refused = post("/dialogues/import", copy, null);
        assertTrue(refused.headers().firstValue("Location").orElse("").contains("err="));
        assertFalse(Files.exists(content.resolve("dialogues/brigand_test.yml")), "rien n'est écrit");
    }

    @Test
    void aReplyCanOfferAPreciseQuestOrOneFromAListChosenByName() throws Exception {
        start();
        record("quest.catalog.list", CATALOG_SURVEY.replace("brigand_test", "lucien"));
        String page = get("/dialogues/edit/lucien").body();
        assertTrue(page.contains(">Proposer une quête précise</option>"));
        assertTrue(page.contains(">Proposer une quête au hasard dans une liste</option>"));
        assertTrue(page.contains(">Quêtes des brigands</option>"), "les listes par leur nom");
        assertTrue(page.contains(">Vieille liste (désactivée)</option>"));
        assertFalse(formHtml(page).contains(">brigands</option>"), "pas d'identifiant brut");

        Map<String, String> f = formFields(page);
        f.put("n0_c0_qa", "OFFER_CATALOG_QUEST");
        f.put("n0_c0_qcat", "brigands");
        assertEquals(303, post("/dialogues/save", f, "save").statusCode());
        String yaml = Files.readString(content.resolve("dialogues/lucien.yml"));
        assertTrue(yaml.contains("type: OFFER_CATALOG_QUEST"), yaml);
        assertTrue(yaml.contains("catalog: brigands"), yaml);
        assertTrue(yaml.indexOf("OFFER_CATALOG_QUEST") < yaml.indexOf("type: CLOSE"), yaml);
        String after = get("/dialogues/edit/lucien").body();
        assertTrue(after.contains("propose une quête au hasard dans la liste « Quêtes des brigands »"), "aperçu");
        assertTrue(actions("content.publish").isEmpty(), "« Enregistrer » ne met pas en jeu");

        f = formFields(after);
        f.put("n0_c0_qcat", "vieille");
        post("/dialogues/save", f, "save");
        assertTrue(get("/dialogues/edit/lucien").body().contains("est désactivée"), "liste inactive signalée");
    }

    @Test
    void theCatalogPageLeadsToTheDialogueOfEachRecurringNpc() throws Exception {
        start();
        record("quest.catalog.list", CATALOG_SURVEY);
        String page = get("/quests/catalogs?agent=" + TestConfig.AGENT_ID + "&focus=brigands").body();
        assertTrue(page.contains("href=\"/dialogues/edit/brigand_test\""), page.length() + "");
        assertTrue(page.contains("Configurer le dialogue de ce PNJ"));
    }

    /**
     * Issue #465 — exporter un dialogue existant pour une IA, le réimporter modifié par l'import
     * #109, relire les changements de sens, puis remplacer explicitement le brouillon. Copie jetable
     * {@code zz465_sage} ; rien n'est publié.
     */
    @Test
    void aDialogueExportedForAnAiComesBackThroughTheImportWithReadableChanges() throws Exception {
        start();
        Files.writeString(content.resolve("dialogues/zz465_sage.yml"), COMPLEX.replace("rpgquest:sage", "rpgquest:zz465_sage"));
        record("quest.catalog.list", CATALOG_SURVEY.replace("brigand_test", "zz465_sage"));

        assertTrue(get("/dialogues/edit/zz465_sage").body().contains("href=\"/dialogues/ai/zz465_sage\""),
                "le geste est proposé sur la fiche");
        String page = get("/dialogues/ai/zz465_sage").body();
        assertTrue(page.contains("Prompt prêt à copier"));
        assertTrue(page.contains("Quêtes des brigands"), "la liste du PNJ récurrent est dans le contexte");
        assertTrue(page.contains("rpgquest:herbes — Herbes du marais"), "les quêtes citées, avec leur titre");
        HttpResponse<String> dl = get("/dialogues/ai/zz465_sage?download=pack");
        assertTrue(dl.headers().firstValue("Content-Disposition").orElse("").contains("lodyquests-dialogue-zz465_sage-ia.yaml"));
        String pack = dl.body();
        assertTrue(pack.contains("baseElement: dialogues/zz465_sage"), pack);

        String modified = pack.replace("Il était une fois…", "Autrefois, dans ces bois…")
                .replace("- type: HAS_PENDING_DELIVERY\n", "- type: OFFER_CATALOG_QUEST\n                  catalog: pirates\n");
        String token = csrf(get("/content/import").body());
        String refused = post("/content/import", Map.of("pack", modified, "_csrf", token), null).body();
        assertTrue(refused.contains("inconnue du serveur"), "liste inventée refusée");

        String ok = pack.replace("Il était une fois…", "Autrefois, dans ces bois…");
        String analysed = post("/content/import", Map.of("pack", ok, "_csrf", token), null).body();
        assertTrue(analysed.contains("Changements dans le dialogue"), "diff de sens");
        assertTrue(analysed.contains("avant : Il était une fois…") && analysed.contains("après : Autrefois, dans ces bois…"));
        assertTrue(analysed.contains("Remplacer l"), "remplacement explicite proposé");
        assertTrue(Files.readString(content.resolve("dialogues/zz465_sage.yml")).contains("Il était une fois"),
                "rien d'écrit avant la confirmation");

        post("/content/import", Map.of("pack", ok, "_csrf", token, "decision.dialogues/zz465_sage", "REPLACE",
                "confirm", "true"), null);
        assertTrue(Files.readString(content.resolve("dialogues/zz465_sage.yml")).contains("Autrefois, dans ces bois"));
        assertTrue(actions("content.publish").isEmpty() && actions("content.publish.batch").isEmpty(),
                "le brouillon change, rien n'est mis en jeu");
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
