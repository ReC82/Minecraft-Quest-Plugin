package com.lodygames.rpgquest.panel.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.panel.content.ContentPackImport;
import com.lodygames.rpgquest.panel.content.ContentPackSchema;
import com.lodygames.rpgquest.panel.content.ContentWorkspace;
import com.lodygames.rpgquest.panel.content.RefData;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Issue #146 — l'atelier IA, avec un fournisseur <strong>bouchon</strong> : aucun test ne sort sur
 * le réseau, ne consomme de jetons, ni n'a besoin d'une clé réelle.
 *
 * <p>Ce qui est vérifié ici n'est pas « l'IA répond bien » — ce serait tester un tiers. C'est :
 * la chaîne complète jusqu'à l'aperçu, le fait qu'elle <strong>n'écrive jamais</strong>, et le
 * comportement sur les sorties que les modèles produisent réellement (enrobées, bavardes,
 * inventant un type, refusant de répondre, dépassant le délai).</p>
 */
class AiContentStudioTest {

    @TempDir
    Path tmp;

    private ContentWorkspace workspace;
    private AiSettingsStore store;
    /** Dernière requête reçue par le bouchon : sert à vérifier ce que l'atelier envoie vraiment. */
    private final AtomicReference<AiProvider.Request> lastRequest = new AtomicReference<>();

    @BeforeEach
    void setUp() throws Exception {
        Path root = tmp.resolve("content");
        for (String kind : ContentWorkspace.KINDS) {
            Files.createDirectories(root.resolve(kind));
        }
        workspace = new ContentWorkspace(root, tmp.resolve("backups"));
        store = new AiSettingsStore(tmp.resolve("panel.db").toString());
    }

    /** Fournisseur bouchon : renvoie ce qu'on lui dit, et mémorise ce qu'on lui a demandé. */
    private final class StubProvider implements AiProvider {
        private final AiProvider.Result canned;

        StubProvider(AiProvider.Result canned) {
            this.canned = canned;
        }

        @Override public String id() {
            return "stub";
        }

        @Override public String label() {
            return "Bouchon de test";
        }

        @Override public String defaultModel() {
            return "stub-1";
        }

        @Override public String defaultBaseUrl() {
            return "https://example.invalid";
        }

        @Override public String keyHelp() {
            return "—";
        }

        @Override public Result testConnection(AiProviderSettings settings) {
            return canned;
        }

        @Override public Result generate(Request request, AiProviderSettings settings) {
            lastRequest.set(request);
            return canned;
        }
    }

    private AiContentStudio studioReturning(AiProvider.Result canned) {
        AiProviderRegistry registry = new AiProviderRegistry(List.of(new StubProvider(canned)));
        store.save(new AiProviderSettings("stub", true, "sk-test-0000", "", "", 4000, 90), "test");
        return new AiContentStudio(registry, store);
    }

    private static AiProvider.Result text(String body) {
        return AiProvider.Result.success(body, "stub-1", 100, 200);
    }

    private static final String VALID_PACK = "format: " + ContentPackSchema.FORMAT + "\n"
            + "schemaVersion: " + ContentPackSchema.SCHEMA_VERSION + "\n"
            + "content:\n"
            + "  quests:\n"
            + "    - id: rpgquest:mines_oubliees\n"
            + "      title: \"<gold>Les mines oubliées</gold>\"\n"
            + "      description: \"Descendre dans le puits.\"\n"
            + "      category: aventure\n"
            + "      icon: LANTERN\n"
            + "      steps:\n"
            + "        - id: creuser\n"
            + "          objectives:\n"
            + "            - type: BREAK_BLOCK\n"
            + "              material: STONE\n"
            + "              amount: 20\n"
            + "      rewards:\n"
            + "        - type: EXPERIENCE\n"
            + "          amount: 25\n";

    private ContentPromptBuilder.QuestRequest request() {
        return new ContentPromptBuilder.QuestRequest("Une quête dans une mine abandonnée.",
                "", "", "aventure", "", "facile", "courte", 1, false, "un peu d'XP", "");
    }

    private AiContentStudio.Generation generate(AiProvider.Result canned) {
        return studioReturning(canned).generate("stub", request(), RefData.empty(), workspace);
    }

    // ---- La chaîne complète --------------------------------------------------------------------

    @Test
    void aValidResponseGoesAllTheWayToAnImportableAnalysis() {
        AiContentStudio.Generation g = generate(text(VALID_PACK));

        assertTrue(g.callOk(), g.error());
        assertNotNull(g.analysis());
        assertTrue(g.analysis().importable(), () -> g.analysis().elements().toString());
        assertEquals(1, g.analysis().elements().size());
        assertEquals(ContentPackImport.Status.NEW, g.analysis().elements().get(0).status());
        assertNull(g.extractionNote(), "une réponse conforme ne doit pas être signalée comme réparée");
        assertTrue(g.problems().isEmpty());
    }

    /**
     * Le test le plus important du lot : la génération <strong>n'écrit rien</strong>. L'IA n'a
     * aucun chemin vers le disque — l'écriture appartient à l'import, après confirmation.
     */
    @Test
    void generatingNeverWritesAnything() throws Exception {
        AiContentStudio.Generation g = generate(text(VALID_PACK));

        assertTrue(g.analysis().importable());
        try (var files = Files.list(tmp.resolve("content/quests"))) {
            assertEquals(0, files.count(), "aucun fichier ne doit être créé par une génération");
        }
    }

    @Test
    void theUsageAndModelAreReportedForAudit() {
        AiContentStudio.Generation g = generate(text(VALID_PACK));

        assertEquals("stub-1", g.model());
        assertEquals("100 entrée / 200 sortie", g.usage());
    }

    // ---- Ce que l'atelier envoie réellement ----------------------------------------------------

    /**
     * L'administrateur n'écrit que son intention : le contrat, le schéma et les références sont
     * joints automatiquement. C'est la valeur ajoutée de l'atelier sur un copier-coller manuel.
     */
    @Test
    void thePromptCarriesTheContractTheSchemaAndTheRealReferences() {
        RefData refs = new RefData(List.of("rpgquest:first_steps"), List.of("guide", "garde"),
                List.of("world_hub"), true, true, true,
                Map.of(), Map.of(), Map.of(), Map.of());
        studioReturning(text(VALID_PACK)).generate("stub", request(), refs, workspace);

        AiProvider.Request sent = lastRequest.get();
        assertNotNull(sent);
        assertTrue(sent.userPrompt().contains("Une quête dans une mine abandonnée."), "l'intention");
        assertTrue(sent.userPrompt().contains("Objectifs disponibles"), "le contrat de #110");
        assertTrue(sent.userPrompt().contains("DISCOVER_WAYPOINT"), "les types réels du moteur");
        assertTrue(sent.userPrompt().contains("json-schema.org"), "le schéma de #110");
        assertTrue(sent.userPrompt().contains("guide, garde"), "les PNJ réellement disponibles");
        assertTrue(sent.userPrompt().contains("rpgquest:first_steps"), "les quêtes existantes");
        assertTrue(sent.systemPrompt().contains("UNIQUEMENT un document YAML"), "la consigne de format");
        assertTrue(sent.systemPrompt().contains("N'invente JAMAIS"), "l'interdiction d'inventer");
    }

    /** Sans relevé de références, l'IA doit être priée de ne rien inventer plutôt que de deviner. */
    @Test
    void withoutAReferenceSurveyThePromptForbidsInventingThem() {
        studioReturning(text(VALID_PACK)).generate("stub", request(), RefData.empty(), workspace);

        assertTrue(lastRequest.get().userPrompt().contains("aucun relevé disponible"),
                lastRequest.get().userPrompt());
    }

    // ---- Sorties réellement produites par les modèles ------------------------------------------

    @Test
    void aFencedResponseIsExtractedAndSignalled() {
        AiContentStudio.Generation g = generate(text("```yaml\n" + VALID_PACK + "```"));

        assertTrue(g.analysis().importable());
        assertNull(g.extractionNote(), "un bloc de code seul reste une réponse propre");
    }

    @Test
    void aChattyResponseIsExtractedAndTheRepairIsSaid() {
        AiContentStudio.Generation g = generate(text(
                "Bien sûr ! Voici la quête demandée :\n\n" + VALID_PACK + "\nBonne aventure !"));

        assertTrue(g.analysis().importable());
        assertNotNull(g.extractionNote(), "le fait d'avoir dû découper doit être dit");
        assertTrue(g.extractionNote().contains("texte avant"), g.extractionNote());
    }

    /** Une réponse qui n'est pas un pack n'est pas devinée : elle échoue avec un motif clair. */
    @Test
    void aResponseThatIsNotAPackFailsWithAReadableReason() {
        AiContentStudio.Generation g = generate(text(
                "Je ne peux pas créer cette quête car la demande est ambiguë."));

        assertTrue(g.callOk(), "l'appel a abouti : c'est le contenu qui ne convient pas");
        assertNotNull(g.extractionNote());
        assertTrue(g.extractionNote().contains("content pack"), g.extractionNote());
        assertFalse(g.analysis().importable());
    }

    /** Un type d'objectif inventé est attrapé par les validateurs réels, pas par l'IA. */
    @Test
    void anInventedObjectiveTypeIsCaughtByTheRealValidators() {
        AiContentStudio.Generation g = generate(text(VALID_PACK
                .replace("type: BREAK_BLOCK", "type: UTILISER_UN_OBJET")));

        assertFalse(g.analysis().importable());
        assertTrue(g.correctable(), "une sortie invalide doit pouvoir être renvoyée en correction");
        assertTrue(g.problems().stream().anyMatch(p -> p.contains("UTILISER_UN_OBJET")),
                g.problems().toString());
    }

    @Test
    void anUnknownNpcReferenceIsReportedWithoutBlockingTheAnalysis() {
        RefData refs = new RefData(List.of(), List.of("guide"), List.of(), true, true, true,
                Map.of(), Map.of(), Map.of(), Map.of());
        AiContentStudio.Generation g = studioReturning(text(VALID_PACK
                        .replace("category: aventure", "category: aventure\n      giver: inconnu")))
                .generate("stub", request(), refs, workspace);

        ContentPackImport.Element e = g.analysis().elements().get(0);
        assertTrue(e.diagnostics().stream().anyMatch(d -> d.message().contains("inconnu")),
                e.diagnostics().toString());
    }

    // ---- Échecs d'appel ------------------------------------------------------------------------

    /** Un timeout ne modifie aucune donnée, et le dit. C'est un critère d'acceptation du ticket. */
    @Test
    void aTimeoutChangesNothingAndIsReported() throws Exception {
        AiContentStudio.Generation g = generate(
                AiProvider.Result.failure("Délai dépassé après 90 s. Rien n'a été enregistré."));

        assertFalse(g.callOk());
        assertNull(g.analysis());
        assertTrue(g.error().contains("Délai dépassé"));
        try (var files = Files.list(tmp.resolve("content/quests"))) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void aRefusedKeyIsReportedAsSuch() {
        AiContentStudio.Generation g = generate(AiProvider.Result.failure(
                "HTTP 401 — Clé API refusée par le fournisseur."));

        assertFalse(g.callOk());
        assertTrue(g.error().contains("401"));
    }

    @Test
    void anUnknownProviderIsRefusedWithoutCallingAnything() {
        AiContentStudio.Generation g = studioReturning(text(VALID_PACK))
                .generate("inexistant", request(), RefData.empty(), workspace);

        assertFalse(g.callOk());
        assertTrue(g.error().contains("Fournisseur inconnu"), g.error());
        assertNull(lastRequest.get(), "aucun appel ne doit avoir été tenté");
    }

    @Test
    void aDisabledProviderIsRefusedWithoutCallingAnything() {
        AiProviderRegistry registry = new AiProviderRegistry(List.of(new StubProvider(text(VALID_PACK))));
        store.save(new AiProviderSettings("stub", false, "sk-test", "", "", 4000, 90), "test");

        AiContentStudio.Generation g = new AiContentStudio(registry, store)
                .generate("stub", request(), RefData.empty(), workspace);

        assertFalse(g.callOk());
        assertTrue(g.error().contains("désactivé"), g.error());
        assertNull(lastRequest.get());
    }

    @Test
    void aProviderWithoutAKeyIsRefusedWithoutCallingAnything() {
        AiProviderRegistry registry = new AiProviderRegistry(List.of(new StubProvider(text(VALID_PACK))));
        store.save(new AiProviderSettings("stub", true, "", "", "", 4000, 90), "test");

        AiContentStudio.Generation g = new AiContentStudio(registry, store)
                .generate("stub", request(), RefData.empty(), workspace);

        assertFalse(g.callOk());
        assertTrue(g.error().contains("Aucune clé"), g.error());
        assertNull(lastRequest.get());
    }

    // ---- Correction ----------------------------------------------------------------------------

    /**
     * La correction renvoie à l'IA sa propre sortie <strong>et</strong> les diagnostics réels : sans
     * cela, elle repartirait de zéro et reproduirait souvent la même erreur.
     */
    @Test
    void theCorrectionPromptCarriesThePreviousOutputAndTheRealDiagnostics() {
        String broken = VALID_PACK.replace("type: BREAK_BLOCK", "type: UTILISER_UN_OBJET");
        AiContentStudio studio = studioReturning(text(VALID_PACK));
        AiContentStudio.Generation first = studio.generate("stub", request(), RefData.empty(), workspace);

        studio.correct(new AiContentStudio.Correction(request(), "stub", broken,
                List.of("Type d'objectif inconnu « UTILISER_UN_OBJET ».")), RefData.empty(), workspace);

        String prompt = lastRequest.get().userPrompt();
        assertTrue(prompt.contains("CORRECTION DEMANDÉE"), prompt.substring(0, 200));
        assertTrue(prompt.contains("UTILISER_UN_OBJET"), "le diagnostic réel");
        assertTrue(prompt.contains("ton document précédent".toUpperCase(java.util.Locale.ROOT))
                        || prompt.contains("Ton document précédent"),
                "la sortie précédente doit être renvoyée");
        assertNotNull(first);
    }

    // ---- Dialogues et stories ------------------------------------------------------------------

    private static final String VALID_DIALOGUE_PACK = "format: " + ContentPackSchema.FORMAT + "\n"
            + "schemaVersion: " + ContentPackSchema.SCHEMA_VERSION + "\n"
            + "content:\n"
            + "  dialogues:\n"
            + "    - id: tc265_mineur\n"
            + "      start: accueil\n"
            + "      nodes:\n"
            + "        accueil:\n"
            + "          speaker: \"Vieux mineur\"\n"
            + "          text: \"Tu viens pour le puits ?\"\n"
            + "          choices:\n"
            + "            - text: \"Raconte.\"\n"
            + "              conditions:\n"
            + "                - type: QUEST_STATE\n"
            + "                  quest: rpgquest:first_steps\n"
            + "                  state: COMPLETED\n"
            + "                  negate: true\n"
            + "              actions:\n"
            + "                - type: CLOSE\n";

    /** Même dialogue, deux nœuds réels : sert à vérifier N, N-1 et N+1 sans ambiguïté (#224). */
    private static final String TWO_NODE_DIALOGUE_PACK = "format: " + ContentPackSchema.FORMAT + "\n"
            + "schemaVersion: " + ContentPackSchema.SCHEMA_VERSION + "\n"
            + "content:\n"
            + "  dialogues:\n"
            + "    - id: tc265_mineur\n"
            + "      start: accueil\n"
            + "      nodes:\n"
            + "        accueil:\n"
            + "          speaker: \"Vieux mineur\"\n"
            + "          text: \"Tu viens pour le puits ?\"\n"
            + "          choices:\n"
            + "            - text: \"Raconte.\"\n"
            + "              next: details\n"
            + "        details:\n"
            + "          speaker: \"Vieux mineur\"\n"
            + "          text: \"Il s'est effondré l'an dernier.\"\n"
            + "          choices:\n"
            + "            - text: \"Je vois.\"\n"
            + "              actions:\n"
            + "                - type: CLOSE\n";

    private static final String VALID_STORY_PACK = "format: " + ContentPackSchema.FORMAT + "\n"
            + "schemaVersion: " + ContentPackSchema.SCHEMA_VERSION + "\n"
            + "content:\n"
            + "  stories:\n"
            + "    - id: tc265_debuts\n"
            + "      name: \"Les débuts\"\n"
            + "      quests:\n"
            + "        - rpgquest:first_steps\n";

    private ContentPromptBuilder.DialogueRequest dialogueRequest() {
        return new ContentPromptBuilder.DialogueRequest(
                "Le mineur propose la quête du puits, et félicite qui l'a déjà finie.",
                "tc265_mineur", "<gold>Vieux mineur</gold>", "bourru", 1,
                "rpgquest:first_steps", "pas de combat");
    }

    private ContentPromptBuilder.StoryRequest storyRequest() {
        return new ContentPromptBuilder.StoryRequest("L'arrivée d'un nouveau joueur.",
                "tc265_debuts", "<gold>Les débuts</gold>",
                "rpgquest:first_steps, rpgquest:mines_oubliees", "");
    }

    @Test
    void aValidDialogueGoesAllTheWayToAnImportableAnalysis() {
        AiContentStudio.Generation g = studioReturning(text(VALID_DIALOGUE_PACK))
                .generate("stub", dialogueRequest(), RefData.empty(), workspace);

        assertEquals(ContentPromptBuilder.Kind.DIALOGUE, g.kind());
        assertNotNull(g.analysis());
        assertTrue(g.analysis().importable(), () -> "éléments : " + g.analysis().elements());
        assertTrue(g.problems().isEmpty(), () -> g.problems().toString());
    }

    @Test
    void aValidStoryGoesAllTheWayToAnImportableAnalysis() {
        RefData refs = RefData.empty().plus(List.of("rpgquest:first_steps"), List.of(), Map.of());

        AiContentStudio.Generation g = studioReturning(text(VALID_STORY_PACK))
                .generate("stub", storyRequest(), refs, workspace);

        assertEquals(ContentPromptBuilder.Kind.STORY, g.kind());
        assertTrue(g.analysis().importable(), () -> "éléments : " + g.analysis().elements());
    }

    /** Même garantie structurelle pour les deux nouvelles familles : aucun chemin vers le disque. */
    @Test
    void generatingADialogueOrAStoryNeverWritesAnything() throws Exception {
        AiContentStudio studio = studioReturning(text(VALID_DIALOGUE_PACK));
        studio.generate("stub", dialogueRequest(), RefData.empty(), workspace);
        studioReturning(text(VALID_STORY_PACK))
                .generate("stub", storyRequest(), RefData.empty(), workspace);

        for (String kind : List.of("dialogues", "stories", "quests")) {
            try (var files = Files.list(tmp.resolve("content/" + kind))) {
                assertEquals(0, files.count(), kind + " : rien ne doit être écrit");
            }
        }
    }

    /**
     * Le dialogue a besoin de consignes que la quête n'a pas : la convention « id de dialogue = id
     * du PNJ », la forme en map des nœuds, et l'interdiction d'inventer une action. Les trois sont
     * des erreurs que les modèles commettent spontanément.
     */
    @Test
    void theDialoguePromptCarriesTheDialogueSpecificRules() {
        studioReturning(text(VALID_DIALOGUE_PACK))
                .generate("stub", dialogueRequest(), RefData.empty(), workspace);

        String system = lastRequest.get().systemPrompt();
        String user = lastRequest.get().userPrompt();

        // #225 — la règle ne dit plus « l'id du dialogue EST celui du PNJ » : c'est faux dès qu'une
        // définition en déclare un autre, et c'est ce qui créait un second dialogue concurrent.
        assertTrue(system.contains("celui qui t'est IMPOSÉ"), system);
        assertTrue(system.contains("ne le déduis pas du nom du PNJ"), system);
        assertTrue(system.contains("N'invente JAMAIS un type d'action ni de condition"), system);
        assertTrue(system.contains("N'EST PAS AFFICHÉ"), "le piège des conditions doit être dit");
        assertTrue(user.contains("« dialogues »"), "la section cible");
        assertTrue(user.contains("tc265_mineur"), "le PNJ porteur demandé");
        assertTrue(user.contains("<gold>Vieux mineur</gold>"), "le locuteur stylé, tel quel");
        assertTrue(user.contains("QUEST_STATE"), "le vocabulaire des conditions");
        assertTrue(user.contains("GIVE_STARTER_KIT"), "et celui des actions, au complet");
        assertTrue(user.contains("negate"), "le champ commun aux conditions");
        assertTrue(user.contains("map"), "la forme en map des nœuds : " + user.contains("nodes"));
    }

    @Test
    void theStoryPromptForbidsInventingQuestsAndRepeatsTheRequestedOrder() {
        RefData refs = RefData.empty().plus(List.of("rpgquest:first_steps"), List.of(), Map.of());

        studioReturning(text(VALID_STORY_PACK))
                .generate("stub", storyRequest(), refs, workspace);

        String system = lastRequest.get().systemPrompt();
        String user = lastRequest.get().userPrompt();

        assertTrue(system.contains("ENCHAÎNEMENT ORDONNÉ DE QUÊTES EXISTANTES"), system);
        assertTrue(system.contains("N'invente JAMAIS une quête"), system);
        assertTrue(system.contains("Ne crée AUCUNE section « quests »"), system);
        assertTrue(user.contains("« stories »"), "la section cible");
        assertTrue(user.contains("rpgquest:mines_oubliees"), "les quêtes imposées, dans l'ordre");
        assertTrue(user.indexOf("rpgquest:first_steps") < user.indexOf("rpgquest:mines_oubliees"),
                "l'ordre demandé doit être conservé");
    }

    /** Une demande de story sans quête imposée doit tout de même interdire d'en inventer. */
    @Test
    void aStoryWithoutImposedQuestsStillRefusesInvention() {
        studioReturning(text(VALID_STORY_PACK)).generate("stub",
                new ContentPromptBuilder.StoryRequest("Un fil libre.", "", "", "", ""),
                RefData.empty(), workspace);

        String user = lastRequest.get().userPrompt();
        assertTrue(user.contains("UNIQUEMENT parmi elles"), user.substring(0, 400));
    }

    /** La correction reste dans la même famille : sinon l'IA repartirait sur une quête. */
    @Test
    void theCorrectionStaysInTheSameFamily() {
        AiContentStudio studio = studioReturning(text(VALID_DIALOGUE_PACK));

        AiContentStudio.Generation g = studio.correct(
                new AiContentStudio.Correction(dialogueRequest(), "stub", VALID_DIALOGUE_PACK,
                        List.of("Type d'action inconnu « TELEPORTER ».")),
                RefData.empty(), workspace);

        assertEquals(ContentPromptBuilder.Kind.DIALOGUE, g.kind());
        assertTrue(lastRequest.get().userPrompt().contains("« dialogues »"),
                "la demande de correction doit rappeler la section");
        assertTrue(lastRequest.get().systemPrompt().contains("Produis UN seul dialogue"),
                "et les règles de la famille");
    }

    /** Les trois familles passent par la même mécanique : un échec d'appel reste un échec propre. */
    @Test
    void aFailedCallOnAnyFamilyReportsTheKindAndWritesNothing() {
        AiProvider.Result failure = AiProvider.Result.failure("quota dépassé");

        AiContentStudio.Generation g = studioReturning(failure)
                .generate("stub", storyRequest(), RefData.empty(), workspace);

        assertEquals(ContentPromptBuilder.Kind.STORY, g.kind());
        assertFalse(g.callOk());
        assertNull(g.analysis());
        assertEquals(List.of("quota dépassé"), g.problems());
    }

    // ---- #222 : la correction transporte réellement tout ---------------------------------------

    /**
     * Le défaut exact du ticket : la correction repartait sans les consignes d'origine, donc le
     * modèle réparait l'erreur signalée en perdant l'identifiant imposé — et la proposition était
     * refusée pour une <em>autre</em> raison, ce qui donnait l'impression que le bouton ne servait à
     * rien.
     */
    @Test
    void theCorrectionPromptRepeatsTheOriginalInstructions() {
        ContentPromptBuilder.QuestRequest original = new ContentPromptBuilder.QuestRequest(
                "Une quête dans une mine abandonnée.", "<gold>Les mines oubliées</gold>",
                "rpgquest:tc265_ai_securiser_environs", "aventure", "guide", "facile", "courte",
                3, true, "un peu d'XP", "pas de combat");

        studioReturning(text(VALID_PACK)).correct(new AiContentStudio.Correction(original, "stub",
                VALID_PACK, List.of("Identifiant « testia:securiser_environs » inutilisable.")),
                RefData.empty(), workspace);

        String prompt = lastRequest.get().userPrompt();
        assertTrue(prompt.contains("CORRECTION DEMANDÉE"), prompt.substring(0, 200));
        assertTrue(prompt.contains("testia:securiser_environs"), "le diagnostic réel");
        assertTrue(prompt.contains("rpgquest:tc265_ai_securiser_environs"),
                "l'identifiant imposé doit rester imposé");
        assertTrue(prompt.contains("<gold>Les mines oubliées</gold>"), "le titre imposé");
        assertTrue(prompt.contains("pas de combat"), "les contraintes libres");
        assertTrue(prompt.contains("Une quête dans une mine abandonnée."), "l'intention d'origine");
        assertTrue(prompt.contains("toujours impératives"), "et le fait qu'elles le restent");
    }

    /** Une correction ne change jamais de fournisseur en route : c'est celui du contexte. */
    @Test
    void theCorrectionKeepsTheChosenProviderAndModel() {
        AiContentStudio.Generation g = studioReturning(text(VALID_PACK)).correct(
                new AiContentStudio.Correction(request(), "stub", VALID_PACK,
                        List.of("Un problème.")),
                RefData.empty(), workspace);

        assertEquals("stub", g.providerId());
        assertEquals("stub-1", g.model());
    }

    /** Une correction valide revient d'elle-même jusqu'à un aperçu acceptable. */
    @Test
    void aValidCorrectionComesBackAsAnAcceptableProposal() {
        String broken = VALID_PACK.replace("type: BREAK_BLOCK", "type: UTILISER_UN_OBJET");

        AiContentStudio.Generation g = studioReturning(text(VALID_PACK)).correct(
                new AiContentStudio.Correction(request(), "stub", broken,
                        List.of("Type d'objectif inconnu « UTILISER_UN_OBJET ».")),
                RefData.empty(), workspace);

        assertTrue(g.callOk(), g.error());
        assertTrue(g.acceptable(), () -> g.problems().toString());
    }

    /** Une correction encore invalide ressort avec ses nouveaux diagnostics, et reste corrigeable. */
    @Test
    void aStillInvalidCorrectionReportsTheNewDiagnostics() {
        String stillBroken = VALID_PACK.replace("type: EXPERIENCE", "type: DONNER_UN_CADEAU");

        AiContentStudio.Generation g = studioReturning(text(stillBroken)).correct(
                new AiContentStudio.Correction(request(), "stub", VALID_PACK,
                        List.of("Un premier problème.")),
                RefData.empty(), workspace);

        assertFalse(g.acceptable());
        assertTrue(g.correctable(), "le bouton doit rester disponible");
        assertTrue(g.problems().stream().anyMatch(p -> p.contains("DONNER_UN_CADEAU")),
                g.problems().toString());
        assertFalse(g.problems().contains("Un premier problème."),
                "les diagnostics affichés sont ceux de la NOUVELLE proposition");
    }

    /** Une erreur d'API pendant une correction reste visible, et n'écrit rien. */
    @Test
    void anApiErrorDuringACorrectionIsVisibleAndWritesNothing() throws Exception {
        AiContentStudio.Generation g = studioReturning(
                AiProvider.Result.failure("HTTP 401 — Clé API refusée par le fournisseur.")).correct(
                new AiContentStudio.Correction(request(), "stub", VALID_PACK,
                        List.of("Un problème.")),
                RefData.empty(), workspace);

        assertFalse(g.callOk());
        assertTrue(g.error().contains("401"), g.error());
        try (var files = Files.list(tmp.resolve("content/quests"))) {
            assertEquals(0, files.count());
        }
    }

    /** Un contexte de correction incomplet n'est pas « usable » : la route refuse d'appeler l'IA. */
    @Test
    void anIncompleteCorrectionContextIsRefusedBeforeCalling() {
        assertFalse(new AiContentStudio.Correction(request(), "stub", "", List.of("x")).usable(),
                "sans la sortie précédente");
        assertFalse(new AiContentStudio.Correction(request(), "stub", VALID_PACK, List.of()).usable(),
                "sans diagnostic");
        assertFalse(new AiContentStudio.Correction(null, "stub", VALID_PACK, List.of("x")).usable(),
                "sans la demande d'origine");
        assertTrue(new AiContentStudio.Correction(request(), "stub", VALID_PACK, List.of("x")).usable());
    }

    // ---- #223 / #224 : le backend vérifie ce qui a été imposé ----------------------------------

    /** L'identifiant imposé est vérifié sur la proposition, pas seulement espéré dans le prompt. */
    @Test
    void anImposedQuestIdThatTheModelRenamedIsRefused() {
        ContentPromptBuilder.QuestRequest imposed = new ContentPromptBuilder.QuestRequest(
                "Une quête.", "", "rpgquest:tc265_securiser", "aventure", "", "", "", 1, false,
                "", "");

        AiContentStudio.Generation g = studioReturning(text(VALID_PACK))
                .generate("stub", imposed, RefData.empty(), workspace);

        assertTrue(g.callOk());
        assertTrue(g.analysis().importable(), "le document est valide : c'est la demande qui ne l'est pas");
        assertFalse(g.acceptable(), "une proposition hors demande ne doit pas être enregistrable");
        assertEquals(1, g.unmet().size(), g.unmet().toString());
        assertTrue(g.unmet().get(0).contains("tc265_securiser"), g.unmet().toString());
        assertTrue(g.unmet().get(0).contains("mines_oubliees"), g.unmet().toString());
        assertTrue(g.correctable(), "l'écart doit être corrigeable via #222");
        assertTrue(g.problems().get(0).contains("tc265_securiser"),
                "l'écart passe en tête des problèmes renvoyés à l'IA");
    }

    /** Les deux écritures du même identifiant sont la même réponse — pas un écart. */
    @Test
    void anImposedQuestIdIsAcceptedInEitherWriting() {
        for (String imposedId : List.of("mines_oubliees", "rpgquest:mines_oubliees")) {
            ContentPromptBuilder.QuestRequest imposed = new ContentPromptBuilder.QuestRequest(
                    "Une quête.", "", imposedId, "aventure", "", "", "", 1, false, "", "");

            AiContentStudio.Generation g = studioReturning(text(VALID_PACK))
                    .generate("stub", imposed, RefData.empty(), workspace);

            assertTrue(g.unmet().isEmpty(), imposedId + " : " + g.unmet());
            assertTrue(g.acceptable(), imposedId);
        }
    }

    /** Aucun identifiant imposé = rien à vérifier : l'IA reste libre de proposer le sien. */
    @Test
    void withoutAnImposedIdNothingIsChecked() {
        AiContentStudio.Generation g = generate(text(VALID_PACK));

        assertTrue(g.unmet().isEmpty());
        assertTrue(g.acceptable());
    }

    private AiContentStudio.Generation dialogueWithNodeCount(String pack, int requested) {
        return studioReturning(text(pack)).generate("stub",
                new ContentPromptBuilder.DialogueRequest("Le mineur parle.", "tc265_mineur",
                        "", "bourru", requested, "", ""),
                RefData.empty(), workspace);
    }

    /** Le cas réel de #224 : 5 demandés, 4 produits, proposition acceptée. Plus maintenant. */
    @Test
    void aDialogueWithTooFewNodesIsRefusedWithTheExactCount() {
        AiContentStudio.Generation g = dialogueWithNodeCount(VALID_DIALOGUE_PACK, 2);

        assertTrue(g.analysis().importable(), "le dialogue est valide : c'est le compte qui ne l'est pas");
        assertFalse(g.acceptable());
        assertEquals(1, g.unmet().size(), g.unmet().toString());
        assertTrue(g.unmet().get(0).startsWith("2 nœuds demandés, 1 généré"), g.unmet().get(0));
        assertTrue(g.correctable());
    }

    @Test
    void aDialogueWithTooManyNodesIsRefusedToo() {
        AiContentStudio.Generation g = dialogueWithNodeCount(TWO_NODE_DIALOGUE_PACK, 1);

        assertFalse(g.acceptable());
        assertTrue(g.unmet().get(0).startsWith("1 nœuds demandés, 2 générés"), g.unmet().get(0));
    }

    @Test
    void aDialogueWithExactlyTheRequestedNodesIsAccepted() {
        assertTrue(dialogueWithNodeCount(VALID_DIALOGUE_PACK, 1).acceptable());
        assertTrue(dialogueWithNodeCount(TWO_NODE_DIALOGUE_PACK, 2).acceptable());
    }

    /** {@code 0} laisse le modèle décider : c'est le comportement historique, et il est préservé. */
    @Test
    void zeroNodesLetsTheModelDecide() {
        assertTrue(dialogueWithNodeCount(VALID_DIALOGUE_PACK, 0).acceptable());
        assertTrue(dialogueWithNodeCount(TWO_NODE_DIALOGUE_PACK, 0).acceptable());
    }

    /** La borne du formulaire est aussi celle de la contrainte : au-delà, elle est ramenée au max. */
    @Test
    void theRequestedNodeCountIsClampedToTheFormMaximum() {
        ContentPromptBuilder.DialogueRequest beyond = new ContentPromptBuilder.DialogueRequest(
                "x", "d", "", "", 99, "", "");

        assertEquals(ContentPromptBuilder.DialogueRequest.MAX_NODES, beyond.nodeCount());
        AiContentStudio.Generation g = dialogueWithNodeCount(VALID_DIALOGUE_PACK,
                ContentPromptBuilder.DialogueRequest.MAX_NODES);
        assertTrue(g.unmet().get(0).startsWith(
                ContentPromptBuilder.DialogueRequest.MAX_NODES + " nœuds demandés, 1 généré"),
                g.unmet().get(0));
    }

    /** Le prompt doit dire que le nombre est impératif, pas souhaité. */
    @Test
    void theDialoguePromptSaysTheNodeCountIsMandatory() {
        dialogueWithNodeCount(VALID_DIALOGUE_PACK, 5);

        String user = lastRequest.get().userPrompt();
        assertTrue(user.contains("Nombre de nœuds IMPÉRATIF : exactement 5"), user);
        assertTrue(user.contains("recompté après ta réponse"), user);
    }

    /** Un écart de contrainte n'écrit rien non plus — même garantie que partout ailleurs. */
    @Test
    void anUnmetConstraintWritesNothing() throws Exception {
        dialogueWithNodeCount(VALID_DIALOGUE_PACK, 7);

        for (String kind : List.of("dialogues", "stories", "quests")) {
            try (var files = Files.list(tmp.resolve("content/" + kind))) {
                assertEquals(0, files.count(), kind);
            }
        }
    }

    // ---- Fournisseurs utilisables --------------------------------------------------------------

    @Test
    void onlyEnabledProvidersWithAKeyAreOfferedToTheUser() {
        AiProviderRegistry registry = new AiProviderRegistry(List.of(new StubProvider(text(VALID_PACK))));
        AiContentStudio studio = new AiContentStudio(registry, store);

        assertTrue(studio.usableProviders().isEmpty(), "ni activé ni pourvu d'une clé");

        store.save(new AiProviderSettings("stub", true, "", "", "", 4000, 90), "test");
        assertTrue(studio.usableProviders().isEmpty(), "activé mais sans clé");

        store.save(new AiProviderSettings("stub", true, "sk-x", "", "", 4000, 90), "test");
        assertEquals(1, studio.usableProviders().size());
    }
}
