package com.lodygames.rpgquest.content.publish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.content.reload.ContentReloadService;
import com.lodygames.rpgquest.content.reload.ReloadFamily;
import com.lodygames.rpgquest.dialogue.YamlDialogueEngine;
import com.lodygames.rpgquest.item.YamlCustomItemRegistry;
import com.lodygames.rpgquest.mob.SpecialMobRegistry;
import com.lodygames.rpgquest.npc.YamlNpcEngine;
import com.lodygames.rpgquest.quest.YamlQuestEngine;
import com.lodygames.rpgquest.story.StoryRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.helpers.NOPLogger;

/**
 * Issue #427 — publier un pack d'histoire (le cas Lily) avec les <strong>vrais</strong> moteurs.
 *
 * <p>Sur DEV le 15/10, le pack « Les souvenirs de Lily » a été publié quête par quête, dans l'ordre
 * alphabétique : quatre quêtes sur cinq ont été refusées en {@code INVALID_CONTENT}, sans motif
 * visible. La cause, établie depuis les résultats d'action conservés par le panel : chaque quête de
 * la chaîne cite en prérequis une quête <em>pas encore publiée</em>, et le chargeur de quêtes rejette
 * un prérequis introuvable. Seule la dernière publication, qui complétait la chaîne, est passée.</p>
 *
 * <p>Ces tests reproduisent ce refus, vérifient qu'il est désormais <strong>expliqué</strong>
 * (fichier, motif, quête manquante), puis que la publication du lot entier en une opération passe —
 * ou ne laisse rien derrière elle.</p>
 */
class ContentPackPublishTest {

    @TempDir
    Path dataFolder;

    private YamlQuestEngine questEngine;
    private StoryRegistry storyRegistry;
    private YamlDialogueEngine dialogueEngine;
    private ContentPublishStore store;
    private ContentPublishService service;

    private static final Map<String, String> QUESTS = Map.of(
            "lily_repas", quest("lily_repas", null, "lily"),
            "lily_fleurs", quest("lily_fleurs", "lily_repas", "lily"),
            "lily_ingredients", quest("lily_ingredients", "lily_fleurs", "lily"),
            "lily_tarte", quest("lily_tarte", "lily_ingredients", "lily"),
            "lily_julius", quest("lily_julius", "lily_tarte", "julius"));

    private static final String STORY = """
            id: lily_souvenirs
            name: "Les souvenirs de Lily"
            quests:
              - rpgquest:lily_repas
              - rpgquest:lily_fleurs
              - rpgquest:lily_ingredients
              - rpgquest:lily_tarte
              - rpgquest:lily_julius
            """;

    private static final String DIALOGUE_LILY = """
            id: rpgquest:lily
            start: accueil
            nodes:
              accueil:
                speaker: "Lily"
                text: "Bonjour. Je m'appelle Lily."
                choices:
                  - text: "Tu as l'air affamée. Je peux t'aider ?"
                    conditions:
                      - type: QUEST_AVAILABLE
                        quest: rpgquest:lily_repas
                    actions:
                      - type: START_QUEST
                        quest: rpgquest:lily_repas
                  - text: "Au revoir, Lily"
                    actions:
                      - type: CLOSE
            """;

    private static final String DIALOGUE_JULIUS = """
            id: rpgquest:julius
            start: accueil
            nodes:
              accueil:
                speaker: "Julius"
                text: "Hm ? Je ne crois pas te connaître."
                choices:
                  - text: "Lily m'envoie."
                    conditions:
                      - type: QUEST_STATE
                        quest: rpgquest:lily_julius
                        state: NOT_STARTED
                        negate: true
                    actions:
                      - type: CLOSE
                  - text: "Bonne journée"
                    actions:
                      - type: CLOSE
            """;

    private static String quest(String slug, String prerequisite, String npc) {
        return "id: rpgquest:" + slug + "\n"
                + "title: \"" + slug + "\"\ndescription: \"Quête du pack Lily\"\ncategory: \"lily\"\n"
                + "icon: BREAD\nrepeatable: false\ngiver: lily\n"
                + (prerequisite == null ? "" : "prerequisites:\n  - rpgquest:" + prerequisite + "\n")
                + """
                steps:
                  - id: etape
                    objectives:
                      - type: DELIVER_ITEM_TO_NPC
                        npc: %s
                        material: BREAD
                        amount: 3
                rewards:
                  - type: EXPERIENCE
                    amount: 20
                """.formatted(npc);
    }

    @BeforeEach
    void setUp() throws Exception {
        for (String dir : List.of("quests", "stories", "dialogues", "npcs", "items", "mobs")) {
            Files.createDirectories(dataFolder.resolve(dir));
        }
        // Lily et Julius existent déjà sur DEV (« PNJ réutilisés » du pack).
        Files.writeString(dataFolder.resolve("npcs/lily.yml"),
                "id: lily\ndisplay_name: \"Lily\"\nenabled: true\n");
        Files.writeString(dataFolder.resolve("npcs/julius.yml"),
                "id: julius\ndisplay_name: \"Julius\"\nenabled: true\n");
        questEngine = new YamlQuestEngine(dataFolder.resolve("quests"), NOPLogger.NOP_LOGGER);
        storyRegistry = new StoryRegistry(dataFolder.resolve("stories"), NOPLogger.NOP_LOGGER);
        dialogueEngine = new YamlDialogueEngine(dataFolder.resolve("dialogues"), NOPLogger.NOP_LOGGER,
                List.of());
        YamlNpcEngine npcEngine = new YamlNpcEngine(dataFolder.resolve("npcs"), NOPLogger.NOP_LOGGER);
        npcEngine.reload();
        ContentReloadService reload = new ContentReloadService(questEngine, storyRegistry,
                dialogueEngine, npcEngine,
                new YamlCustomItemRegistry(dataFolder.resolve("items"), NOPLogger.NOP_LOGGER),
                new SpecialMobRegistry(dataFolder.resolve("mobs"), NOPLogger.NOP_LOGGER),
                NOPLogger.NOP_LOGGER, families -> { });
        store = new ContentPublishStore(dataFolder, dataFolder.resolve("content-backups"));
        service = new ContentPublishService(store, new ReloadServiceApplier(reload));
    }

    private List<ContentPublishService.BatchItem> pack() {
        List<ContentPublishService.BatchItem> items = new ArrayList<>();
        // Ordre volontairement défavorable : celui des refus du 15/10 (alphabétique).
        for (String slug : List.of("lily_fleurs", "lily_ingredients", "lily_julius", "lily_repas",
                "lily_tarte")) {
            items.add(new ContentPublishService.BatchItem(PublishKind.QUESTS, slug, QUESTS.get(slug),
                    "", "rpgquest:" + slug));
        }
        items.add(new ContentPublishService.BatchItem(PublishKind.STORIES, "lily_souvenirs", STORY,
                "", "lily_souvenirs"));
        items.add(new ContentPublishService.BatchItem(PublishKind.DIALOGUES, "lily", DIALOGUE_LILY,
                "", "rpgquest:lily"));
        items.add(new ContentPublishService.BatchItem(PublishKind.DIALOGUES, "julius",
                DIALOGUE_JULIUS, "", "rpgquest:julius"));
        return items;
    }

    private static ContentPublishService.BatchItemOutcome item(
            ContentPublishService.BatchOutcome outcome, String slug) {
        return outcome.items().stream().filter(i -> i.slug().equals(slug)).findFirst().orElseThrow();
    }

    // ---- Le défaut du 15/10, reproduit puis expliqué ----------------------------------------

    @Test
    void publishingOneQuestAtATimeReproducesTheLilyRefusalAndNamesItsCause() {
        ContentPublishService.PublishOutcome first = service.publish(PublishKind.QUESTS,
                "lily_fleurs", QUESTS.get("lily_fleurs"), "", "rpgquest:lily_fleurs");

        assertFalse(first.ok());
        assertEquals("RELOAD_FAILED", first.code());
        assertEquals("INVALID_CONTENT", first.reloadCode());
        // Le motif est désormais nommé : quel fichier, quoi, quelle quête manque.
        assertTrue(first.message().contains("lily_fleurs.yml"), first.message());
        assertTrue(first.message().contains("prérequis introuvable"), first.message());
        assertTrue(first.message().contains("rpgquest:lily_repas"), first.message());
        assertFalse(first.reloadDetail().contentIssues().isEmpty());
        assertTrue(first.reloadDetail().contentIssues().get(0).startsWith("quests/lily_fleurs.yml"));
    }

    // ---- Le pack en une seule opération ---------------------------------------------------

    @Test
    void theWholePackIsPublishedInOneOperationWhateverTheOrder() {
        ContentPublishService.BatchOutcome outcome = service.publishBatch(pack(), false);

        assertTrue(outcome.ok(), outcome.message());
        assertEquals("PUBLISHED", outcome.code());
        assertTrue(outcome.synchronized_());
        assertEquals(8, outcome.items().size());
        outcome.items().forEach(i -> {
            assertEquals("CREATED", i.status(), i.slug());
            assertTrue(i.runtimeConfirmed(), i.slug());
        });
        assertEquals(5, questEngine.quests().size());
        assertEquals(1, storyRegistry.stories().size());
        assertEquals(2, dialogueEngine.dialogues().size());
    }

    @Test
    void republishingTheSamePackIsIdempotent() {
        ContentPublishService.BatchOutcome first = service.publishBatch(pack(), false);
        assertTrue(first.ok(), first.message());

        List<ContentPublishService.BatchItem> again = new ArrayList<>();
        for (ContentPublishService.BatchItem i : pack()) {
            again.add(new ContentPublishService.BatchItem(i.kind(), i.slug(), i.yaml(),
                    store.sha256(i.kind(), i.slug()), i.expectedId()));
        }
        ContentPublishService.BatchOutcome second = service.publishBatch(again, false);

        assertTrue(second.ok(), second.message());
        assertEquals("ALREADY_PUBLISHED", second.code());
        assertEquals(5, questEngine.quests().size());
    }

    @Test
    void aDryRunJudgesThePackWithoutLeavingAnythingBehind() {
        ContentPublishService.BatchOutcome outcome = service.publishBatch(pack(), true);

        assertTrue(outcome.ok(), outcome.message());
        assertEquals("CHECKED", outcome.code());
        assertTrue(outcome.dryRun());
        assertTrue(outcome.filesRestored());
        assertTrue(questEngine.quests().isEmpty());
        for (ContentPublishService.BatchItem i : pack()) {
            assertFalse(store.exists(i.kind(), i.slug()), i.slug());
        }
    }

    // ---- Tout ou rien ---------------------------------------------------------------------

    @Test
    void aBrokenPrerequisiteRefusesThePackAndRemovesEveryWrittenFile() {
        List<ContentPublishService.BatchItem> items = new ArrayList<>(pack());
        items.set(0, new ContentPublishService.BatchItem(PublishKind.QUESTS, "lily_fleurs",
                quest("lily_fleurs", "lily_inexistante", "lily"), "", "rpgquest:lily_fleurs"));

        ContentPublishService.BatchOutcome outcome = service.publishBatch(items, false);

        assertFalse(outcome.ok());
        assertEquals("INVALID_CONTENT", outcome.code());
        assertTrue(outcome.filesRestored());
        ContentPublishService.BatchItemOutcome fleurs = item(outcome, "lily_fleurs");
        assertEquals("REJECTED_BY_ENGINE", fleurs.status());
        assertTrue(fleurs.problems().get(0).contains("rpgquest:lily_inexistante"), fleurs.problems().toString());
        // Rien n'est resté : ni fichier, ni quête en jeu.
        for (ContentPublishService.BatchItem i : items) {
            assertFalse(store.exists(i.kind(), i.slug()), i.slug());
        }
        assertTrue(questEngine.quests().isEmpty());
    }

    @Test
    void anInvalidDialogueRestoresTheFilesThatAlreadyExistedOnDev() throws Exception {
        assertTrue(service.publishBatch(pack(), false).ok());
        String before = store.read(PublishKind.QUESTS, "lily_repas").orElseThrow();

        List<ContentPublishService.BatchItem> items = new ArrayList<>();
        items.add(new ContentPublishService.BatchItem(PublishKind.QUESTS, "lily_repas",
                QUESTS.get("lily_repas").replace("amount: 20", "amount: 99"),
                store.sha256(PublishKind.QUESTS, "lily_repas"), "rpgquest:lily_repas"));
        items.add(new ContentPublishService.BatchItem(PublishKind.DIALOGUES, "julius",
                "id: rpgquest:julius\nstart: nulle_part\nnodes: {}\n",
                store.sha256(PublishKind.DIALOGUES, "julius"), "rpgquest:julius"));

        ContentPublishService.BatchOutcome outcome = service.publishBatch(items, false);

        assertFalse(outcome.ok());
        assertEquals("REJECTED_BY_ENGINE", item(outcome, "julius").status());
        assertEquals("RESTORED", item(outcome, "lily_repas").status());
        assertEquals(before, store.read(PublishKind.QUESTS, "lily_repas").orElseThrow());
        assertEquals(DIALOGUE_JULIUS, store.read(PublishKind.DIALOGUES, "julius").orElseThrow());
        assertEquals(5, questEngine.quests().size());
        assertEquals(2, dialogueEngine.dialogues().size());
    }

    @Test
    void aFileCreatedOnDevSinceTheAnalysisIsAConflictAndNothingIsWritten() throws Exception {
        Files.writeString(dataFolder.resolve("quests/lily_tarte.yml"), QUESTS.get("lily_tarte"));

        ContentPublishService.BatchOutcome outcome = service.publishBatch(pack(), false);

        assertFalse(outcome.ok());
        assertEquals("CONFLICT", outcome.code());
        assertEquals("CONFLICT", item(outcome, "lily_tarte").status());
        assertFalse(store.exists(PublishKind.QUESTS, "lily_repas"));
        assertTrue(questEngine.quests().isEmpty());
    }

    @Test
    void anUnrelatedInvalidFileOnDevIsReportedAsSuch() throws Exception {
        Files.writeString(dataFolder.resolve("quests/autre.yml"), "id: rpgquest:autre\ntitle: \"x\"\n");

        ContentPublishService.BatchOutcome outcome = service.publishBatch(pack(), false);

        assertFalse(outcome.ok());
        assertEquals("INVALID_CONTENT", outcome.code());
        assertTrue(outcome.otherProblems().stream().anyMatch(p -> p.startsWith("quests/autre.yml")),
                outcome.otherProblems().toString());
        outcome.items().forEach(i -> assertTrue(i.problems().isEmpty(), i.slug()));
        assertFalse(store.exists(PublishKind.QUESTS, "lily_repas"));
    }

    @Test
    void aWriteFailureMidwayRestoresWhatWasAlreadyWritten() throws Exception {
        // Un dossier non vide à l'emplacement du 4e fichier : l'écriture y échoue forcément.
        Files.createDirectories(dataFolder.resolve("quests/lily_repas.yml/bloque"));

        ContentPublishService.BatchOutcome outcome = service.publishBatch(pack(), false);

        assertFalse(outcome.ok());
        assertEquals("WRITE_FAILED", outcome.code());
        assertTrue(outcome.filesRestored());
        assertFalse(store.exists(PublishKind.QUESTS, "lily_fleurs"));
        assertFalse(store.exists(PublishKind.QUESTS, "lily_ingredients"));
        assertTrue(questEngine.quests().isEmpty());
    }

    @Test
    void theStoryAloneIsRefusedWhenItsQuestsAreMissingAndSaysWhich() {
        ContentPublishService.BatchOutcome outcome = service.publishBatch(List.of(
                new ContentPublishService.BatchItem(PublishKind.STORIES, "lily_souvenirs", STORY, "",
                        "lily_souvenirs")), false);

        assertFalse(outcome.ok());
        assertFalse(store.exists(PublishKind.STORIES, "lily_souvenirs"));
        String all = String.join(" ", outcome.otherProblems()) + " "
                + String.join(" ", item(outcome, "lily_souvenirs").problems());
        assertTrue(all.contains("lily_repas"), outcome.message() + " / " + all);
    }

    @Test
    void reloadFamiliesStayTheRealOnes() {
        ContentPublishService.BatchOutcome outcome = service.publishBatch(pack(), false);
        assertTrue(outcome.families().contains(ReloadFamily.QUESTS.wire()));
        assertTrue(outcome.families().contains(ReloadFamily.DIALOGUES.wire()));
    }
}
