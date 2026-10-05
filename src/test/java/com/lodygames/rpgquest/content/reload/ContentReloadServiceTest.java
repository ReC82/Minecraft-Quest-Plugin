package com.lodygames.rpgquest.content.reload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.dialogue.YamlDialogueEngine;
import com.lodygames.rpgquest.item.YamlCustomItemRegistry;
import com.lodygames.rpgquest.mob.SpecialMobRegistry;
import com.lodygames.rpgquest.npc.YamlNpcEngine;
import com.lodygames.rpgquest.quest.YamlQuestEngine;
import com.lodygames.rpgquest.story.StoryRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.helpers.NOPLogger;

/**
 * Issue #131 — le service central de rechargement.
 *
 * <p>Ce qui est testé ici n'est pas « est-ce que ça recharge » mais <strong>est-ce que ça refuse de
 * recharger quand il le faut</strong>. Le danger réel est documenté dans le service : {@code
 * reload()} remplace l'ensemble actif par les fichiers <em>valides</em>, donc un fichier devenu
 * invalide ne provoque pas d'erreur — il fait <strong>disparaître sa définition du runtime</strong>.
 * Une quête active pour des joueurs peut s'évaporer. Les tests ci-dessous figent le fait qu'on
 * n'applique rien dans ce cas.</p>
 *
 * <p>Aucun mock : les six registres réels lisent de vrais fichiers dans un dossier temporaire. Un
 * double aurait validé ma lecture du comportement des loaders plutôt que ce comportement.</p>
 */
class ContentReloadServiceTest {

    @TempDir
    Path tmp;

    private YamlQuestEngine questEngine;
    private StoryRegistry storyRegistry;
    private YamlDialogueEngine dialogueEngine;
    private YamlNpcEngine npcEngine;
    private YamlCustomItemRegistry itemRegistry;
    private SpecialMobRegistry mobRegistry;
    private ContentReloadService service;
    private final List<Set<ReloadFamily>> cacheInvalidations = new CopyOnWriteArrayList<>();

    private Path questsDir;
    private Path storiesDir;
    private Path dialoguesDir;
    private Path npcsDir;
    private Path itemsDir;
    private Path mobsDir;

    @BeforeEach
    void setUp() throws Exception {
        questsDir = Files.createDirectories(tmp.resolve("quests"));
        storiesDir = Files.createDirectories(tmp.resolve("stories"));
        dialoguesDir = Files.createDirectories(tmp.resolve("dialogues"));
        npcsDir = Files.createDirectories(tmp.resolve("npcs"));
        itemsDir = Files.createDirectories(tmp.resolve("items"));
        mobsDir = Files.createDirectories(tmp.resolve("mobs"));

        questEngine = new YamlQuestEngine(questsDir, NOPLogger.NOP_LOGGER);
        storyRegistry = new StoryRegistry(storiesDir, NOPLogger.NOP_LOGGER);
        dialogueEngine = new YamlDialogueEngine(dialoguesDir, NOPLogger.NOP_LOGGER, List.of());
        npcEngine = new YamlNpcEngine(npcsDir, NOPLogger.NOP_LOGGER);
        itemRegistry = new YamlCustomItemRegistry(itemsDir, NOPLogger.NOP_LOGGER);
        mobRegistry = new SpecialMobRegistry(mobsDir, NOPLogger.NOP_LOGGER);

        service = new ContentReloadService(questEngine, storyRegistry, dialogueEngine, npcEngine,
                itemRegistry, mobRegistry, NOPLogger.NOP_LOGGER, cacheInvalidations::add);
    }

    // ---- Fixtures -------------------------------------------------------------------------

    private void writeQuest(String fileName, String id, String prerequisite, String giver) throws IOException {
        StringBuilder yaml = new StringBuilder();
        yaml.append("id: ").append(id).append('\n');
        yaml.append("title: \"Titre\"\ndescription: \"Description\"\ncategory: test\nrepeatable: false\n");
        if (prerequisite != null) {
            yaml.append("prerequisites:\n  - ").append(prerequisite).append('\n');
        }
        if (giver != null) {
            yaml.append("giver: ").append(giver).append('\n');
        }
        yaml.append("""
                steps:
                  - id: kill_step
                    objectives:
                      - type: KILL_ENTITY
                        entity: ZOMBIE
                        amount: 1

                rewards:
                  - type: EXPERIENCE
                    amount: 10
                """);
        Files.writeString(questsDir.resolve(fileName), yaml.toString());
    }

    private void writeStory(String fileName, String id, String questId) throws IOException {
        Files.writeString(storiesDir.resolve(fileName), """
                id: %s
                name: "Une histoire"
                quests:
                  - %s
                """.formatted(id, questId));
    }

    private void writeNpc(String fileName, String id, String dialogueId) throws IOException {
        StringBuilder yaml = new StringBuilder();
        yaml.append("id: ").append(id).append('\n');
        yaml.append("display_name: \"Un PNJ\"\nenabled: true\n");
        if (dialogueId != null) {
            yaml.append("dialogue: ").append(dialogueId).append('\n');
        }
        Files.writeString(npcsDir.resolve(fileName), yaml.toString());
    }

    private void writeDialogue(String fileName, String id, String startsQuestId) throws IOException {
        StringBuilder yaml = new StringBuilder();
        yaml.append("id: ").append(id).append('\n');
        yaml.append("start: greeting\nnodes:\n  greeting:\n    speaker: \"PNJ\"\n    text: \"Bonjour\"\n");
        yaml.append("    choices:\n");
        if (startsQuestId != null) {
            yaml.append("      - text: \"Accepter\"\n        actions:\n          - type: START_QUEST\n")
                    .append("            quest: ").append(startsQuestId).append('\n');
        }
        yaml.append("      - text: \"Au revoir\"\n        actions:\n          - type: CLOSE\n");
        Files.writeString(dialoguesDir.resolve(fileName), yaml.toString());
    }

    private List<String> questIds() {
        List<String> ids = new ArrayList<>();
        questEngine.quests().forEach(quest -> ids.add(quest.id().toString()));
        return ids;
    }

    // ---- Aperçu : ne touche à rien ---------------------------------------------------------

    @Test
    void aPreviewNeverTouchesTheRuntime() throws Exception {
        writeQuest("a.yml", "rpgquest:alpha", null, null);
        questEngine.reload();
        writeQuest("b.yml", "rpgquest:beta", null, null); // ajouté APRÈS le chargement

        ContentReloadService.ReloadResult preview = service.preview(Set.of(ReloadFamily.QUESTS));

        assertFalse(preview.applied(), "un aperçu n'applique jamais");
        assertEquals("PREVIEW_OK", preview.code());
        assertEquals(2, preview.totalLoaded(), "l'aperçu voit bien les deux fichiers");
        assertEquals(List.of("rpgquest:alpha"), questIds(), "mais le runtime n'a pas changé");
    }

    @Test
    void anEmptyRequestIsRefusedWithoutDoingAnything() {
        ContentReloadService.ReloadResult result = service.reload(Set.of());

        assertFalse(result.applied());
        assertEquals("NOTHING_REQUESTED", result.code());
    }

    // ---- Le cas critique : contenu invalide -------------------------------------------------

    @Test
    void anInvalidFileNeverSilentlyRemovesAnAlreadyLoadedDefinition() throws Exception {
        writeQuest("a.yml", "rpgquest:alpha", null, null);
        writeQuest("b.yml", "rpgquest:beta", null, null);
        questEngine.reload();
        assertEquals(2, questEngine.quests().size());

        // « beta » devient illisible : un reload naïf chargerait « alpha » seule et ferait
        // disparaître « beta » du runtime sans la moindre erreur visible.
        Files.writeString(questsDir.resolve("b.yml"), "ceci n'est pas: [un yaml de quête valide\n");

        ContentReloadService.ReloadResult result = service.reload(Set.of(ReloadFamily.QUESTS));

        assertFalse(result.applied(), "rien ne doit être appliqué");
        assertEquals("INVALID_CONTENT", result.code());
        assertTrue(result.totalIssues() > 0, "l'erreur doit être rapportée");
        assertEquals(2, questEngine.quests().size(), "le runtime précédent est intégralement conservé");
        assertTrue(questIds().contains("rpgquest:beta"), "y compris la définition devenue invalide");
        assertTrue(result.message().contains("runtime précédent est"), result.message());
    }

    @Test
    void anInvalidFileInOneFamilyBlocksTheWholeRequest() throws Exception {
        writeQuest("a.yml", "rpgquest:alpha", null, null);
        writeNpc("n.yml", "garde", null);
        questEngine.reload();
        npcEngine.reload();
        writeNpc("bad.yml", "", null); // id vide : invalide

        ContentReloadService.ReloadResult result =
                service.reload(Set.of(ReloadFamily.QUESTS, ReloadFamily.NPCS));

        // Une demande d'administration est un tout : on n'applique pas « la moitié qui marche ».
        assertFalse(result.applied());
        assertEquals("INVALID_CONTENT", result.code());
        assertEquals(1, npcEngine.definitions().size(), "PNJ inchangés");
    }

    @Test
    void theInvalidatorIsNeverCalledWhenNothingIsApplied() throws Exception {
        writeQuest("a.yml", "rpgquest:alpha", null, null);
        questEngine.reload();
        Files.writeString(questsDir.resolve("a.yml"), "pas: [valide\n");

        service.reload(Set.of(ReloadFamily.QUESTS));

        assertTrue(cacheInvalidations.isEmpty(),
                "invalider les caches alors que rien n'a changé ferait recalculer pour rien");
    }

    // ---- Références croisées ----------------------------------------------------------------

    @Test
    void aStoryPointingAtAnUnknownQuestBlocksTheReload() throws Exception {
        writeStory("s.yml", "chapitre_1", "rpgquest:inexistante");

        ContentReloadService.ReloadResult result = service.reload(Set.of(ReloadFamily.STORIES));

        assertFalse(result.applied());
        assertEquals("BROKEN_REFERENCES", result.code());
        assertTrue(result.referenceErrors().stream().anyMatch(e -> e.contains("inexistante")),
                () -> result.referenceErrors().toString());
        assertTrue(result.suggestedFamilies().contains(ReloadFamily.QUESTS),
                "le panel doit pouvoir proposer de recharger les quêtes CONJOINTEMENT");
    }

    @Test
    void reloadingTheLinkedFamiliesTogetherSucceedsWhereOneAloneFails() throws Exception {
        // Le cas d'usage réel du ticket : une quête ET la story qui la cite, ajoutées ensemble.
        writeQuest("a.yml", "rpgquest:alpha", null, null);
        writeStory("s.yml", "chapitre_1", "rpgquest:alpha");

        ContentReloadService.ReloadResult alone = service.reload(Set.of(ReloadFamily.STORIES));
        assertEquals("BROKEN_REFERENCES", alone.code(), "la quête n'est pas encore dans le runtime");

        ContentReloadService.ReloadResult together =
                service.reload(Set.of(ReloadFamily.STORIES, ReloadFamily.QUESTS));

        assertTrue(together.applied(), together.message());
        assertEquals("APPLIED", together.code());
        assertEquals(1, storyRegistry.stories().size());
        assertEquals(1, questEngine.quests().size());
    }

    /**
     * Constat d'audit : le chargeur de quêtes valide <strong>déjà</strong> les prérequis à l'échelle
     * du dossier (« prérequis introuvable … quête absente ou elle-même rejetée »). Une référence
     * intra-famille est donc attrapée plus tôt, en {@code INVALID_CONTENT}. C'est une garantie plus
     * forte que la mienne, et ce test la fige : si elle disparaissait, le filet de références
     * croisées prendrait le relais, mais le diagnostic serait moins précis.
     */
    @Test
    void anUnknownPrerequisiteIsAlreadyCaughtByTheQuestLoaderItself() throws Exception {
        writeQuest("a.yml", "rpgquest:alpha", "rpgquest:jamais_ecrite", null);

        ContentReloadService.ReloadResult result = service.reload(Set.of(ReloadFamily.QUESTS));

        assertFalse(result.applied());
        assertEquals("INVALID_CONTENT", result.code());
        assertTrue(result.families().get(0).messages().stream()
                        .anyMatch(m -> m.contains("prérequis introuvable")),
                () -> result.families().get(0).messages().toString());
    }

    @Test
    void aQuestWithAnUnknownGiverBlocksTheReloadAndNamesTheNpcFamily() throws Exception {
        writeQuest("a.yml", "rpgquest:alpha", null, "pnj_absent");

        ContentReloadService.ReloadResult result = service.reload(Set.of(ReloadFamily.QUESTS));

        assertEquals("BROKEN_REFERENCES", result.code());
        assertTrue(result.suggestedFamilies().contains(ReloadFamily.NPCS));
    }

    @Test
    void anNpcPointingAtAnUnknownDialogueBlocksTheReload() throws Exception {
        writeNpc("n.yml", "garde", "rpgquest:dialogue_absent");

        ContentReloadService.ReloadResult result = service.reload(Set.of(ReloadFamily.NPCS));

        assertEquals("BROKEN_REFERENCES", result.code());
        assertTrue(result.suggestedFamilies().contains(ReloadFamily.DIALOGUES));
    }

    @Test
    void anNpcDialogueWithoutNamespaceIsResolvedAsRpgquest() throws Exception {
        writeDialogue("d.yml", "rpgquest:accueil", null);
        writeNpc("n.yml", "garde", "accueil"); // clé courte, sans namespace

        ContentReloadService.ReloadResult result =
                service.reload(Set.of(ReloadFamily.NPCS, ReloadFamily.DIALOGUES));

        assertTrue(result.applied(), () -> result.message() + " " + result.referenceErrors());
    }

    @Test
    void aDialogueStartingAnUnknownQuestBlocksTheReload() throws Exception {
        writeDialogue("d.yml", "rpgquest:accueil", "rpgquest:quete_absente");

        ContentReloadService.ReloadResult result = service.reload(Set.of(ReloadFamily.DIALOGUES));

        assertEquals("BROKEN_REFERENCES", result.code());
        assertTrue(result.referenceErrors().stream().anyMatch(e -> e.contains("quête inconnue")),
                () -> result.referenceErrors().toString());
        assertTrue(result.suggestedFamilies().contains(ReloadFamily.QUESTS));
    }

    @Test
    void aFamilyNotBeingReloadedIsJudgedOnItsRealRuntimeState() throws Exception {
        // La quête est DÉJÀ chargée ; on ne recharge que les stories. La référence est donc valide
        // même si le dossier des quêtes changeait entre-temps.
        writeQuest("a.yml", "rpgquest:alpha", null, null);
        questEngine.reload();
        writeStory("s.yml", "chapitre_1", "rpgquest:alpha");
        Files.delete(questsDir.resolve("a.yml")); // le fichier disparaît, mais le runtime la garde

        ContentReloadService.ReloadResult result = service.reload(Set.of(ReloadFamily.STORIES));

        assertTrue(result.applied(), () -> result.message() + " " + result.referenceErrors());
        assertEquals(1, questEngine.quests().size(), "les quêtes n'ont pas été touchées");
    }

    // ---- Application ------------------------------------------------------------------------

    @Test
    void aValidReloadIsAppliedAndReportsPerFamilyCounts() throws Exception {
        writeQuest("a.yml", "rpgquest:alpha", null, null);
        writeQuest("b.yml", "rpgquest:beta", null, null);
        writeDialogue("d.yml", "rpgquest:accueil", null);

        ContentReloadService.ReloadResult result =
                service.reload(Set.of(ReloadFamily.QUESTS, ReloadFamily.DIALOGUES));

        assertTrue(result.applied(), result.message());
        assertEquals("APPLIED", result.code());
        assertEquals(2, questEngine.quests().size());
        assertEquals(1, dialogueEngine.dialogues().size());
        assertEquals(2, result.families().size());
        assertTrue(result.families().stream().allMatch(ContentReloadService.FamilyOutcome::clean));
        assertTrue(result.durationMillis() >= 0);
    }

    @Test
    void familiesAreAppliedInDependencyOrderWhateverTheRequestOrder() {
        // L'ordre d'application ne doit pas dépendre de l'ordre de la demande : un ensemble non
        // ordonné passerait les dialogues avant les quêtes une fois sur deux.
        ContentReloadService.ReloadResult result =
                service.reload(Set.of(ReloadFamily.DIALOGUES, ReloadFamily.ITEMS, ReloadFamily.QUESTS));

        assertEquals(List.of(ReloadFamily.ITEMS, ReloadFamily.QUESTS, ReloadFamily.DIALOGUES),
                result.families().stream().map(ContentReloadService.FamilyOutcome::family).toList());
    }

    @Test
    void theCacheInvalidatorIsCalledExactlyOnceWithTheAppliedFamilies() throws Exception {
        writeQuest("a.yml", "rpgquest:alpha", null, null);

        service.reload(Set.of(ReloadFamily.QUESTS));

        assertEquals(1, cacheInvalidations.size());
        assertEquals(Set.of(ReloadFamily.QUESTS), cacheInvalidations.get(0));
    }

    // ---- Empreinte du runtime ---------------------------------------------------------------

    @Test
    void theRuntimeHashChangesWhenContentChangesAndIsStableOtherwise() throws Exception {
        String before = service.runtimeHash();
        assertEquals(before, service.runtimeHash(), "empreinte stable sans changement");

        writeQuest("a.yml", "rpgquest:alpha", null, null);
        service.reload(Set.of(ReloadFamily.QUESTS));
        String after = service.runtimeHash();

        assertNotEquals(before, after, "le panel doit pouvoir CONSTATER le changement");
        assertEquals(12, after.length(), "empreinte courte et lisible");
    }

    @Test
    void theHashDoesNotDependOnFileNamesOrOrder() throws Exception {
        writeQuest("a.yml", "rpgquest:alpha", null, null);
        writeQuest("b.yml", "rpgquest:beta", null, null);
        service.reload(Set.of(ReloadFamily.QUESTS));
        String first = service.runtimeHash();

        // Même contenu logique, fichiers renommés : l'empreinte décrit le RUNTIME, pas le disque.
        Files.delete(questsDir.resolve("a.yml"));
        Files.delete(questsDir.resolve("b.yml"));
        writeQuest("z.yml", "rpgquest:beta", null, null);
        writeQuest("y.yml", "rpgquest:alpha", null, null);
        service.reload(Set.of(ReloadFamily.QUESTS));

        assertEquals(first, service.runtimeHash());
    }

    // ---- Single-flight ----------------------------------------------------------------------

    @Test
    void aSecondConcurrentReloadIsRefusedNotQueued() throws Exception {
        writeQuest("a.yml", "rpgquest:alpha", null, null);
        // Un invalidateur qui se bloque laisse l'opération « en cours » le temps du test.
        List<ContentReloadService.ReloadResult> concurrent = new CopyOnWriteArrayList<>();
        ContentReloadService blocking = new ContentReloadService(questEngine, storyRegistry,
                dialogueEngine, npcEngine, itemRegistry, mobRegistry, NOPLogger.NOP_LOGGER,
                families -> concurrent.add(serviceHolder[0].reload(Set.of(ReloadFamily.QUESTS))));
        serviceHolder[0] = blocking;

        ContentReloadService.ReloadResult first = blocking.reload(Set.of(ReloadFamily.QUESTS));

        assertTrue(first.applied(), first.message());
        assertEquals(1, concurrent.size(), "le rechargement imbriqué a bien été tenté");
        assertFalse(concurrent.get(0).applied(), "et il a été refusé");
        assertEquals("BUSY", concurrent.get(0).code());
    }

    /** Permet à l'invalidateur de rappeler le service en cours de construction. */
    private final ContentReloadService[] serviceHolder = new ContentReloadService[1];

    @Test
    void aPreviewIsAllowedEvenWhileAReloadIsRunning() throws Exception {
        writeQuest("a.yml", "rpgquest:alpha", null, null);
        List<ContentReloadService.ReloadResult> nested = new CopyOnWriteArrayList<>();
        ContentReloadService blocking = new ContentReloadService(questEngine, storyRegistry,
                dialogueEngine, npcEngine, itemRegistry, mobRegistry, NOPLogger.NOP_LOGGER,
                families -> nested.add(serviceHolder[0].preview(Set.of(ReloadFamily.QUESTS))));
        serviceHolder[0] = blocking;

        blocking.reload(Set.of(ReloadFamily.QUESTS));

        // Un aperçu ne touche aucun ensemble actif : rien ne justifie de le refuser.
        assertEquals(1, nested.size());
        assertEquals("PREVIEW_OK", nested.get(0).code());
    }

    // ---- Familles ----------------------------------------------------------------------------

    @Test
    void anUnknownFamilyTokenIsRejectedRatherThanPartiallyUnderstood() {
        assertTrue(ReloadFamily.parseCsv("quests,inconnue").isEmpty(),
                "interpréter à moitié une demande de rechargement serait pire que la refuser");
        assertTrue(ReloadFamily.parseCsv("").isEmpty());
        assertTrue(ReloadFamily.parseCsv(null).isEmpty());
    }

    @Test
    void familyTokensAreParsedAndReorderedByDependency() {
        Set<ReloadFamily> parsed = ReloadFamily.parseCsv("dialogues, QUESTS , items").orElseThrow();

        assertEquals(List.of(ReloadFamily.ITEMS, ReloadFamily.QUESTS, ReloadFamily.DIALOGUES),
                List.copyOf(parsed));
    }

    @Test
    void allFamiliesAreReloadableTogetherOnAnEmptyServer() {
        ContentReloadService.ReloadResult result = service.reload(ReloadFamily.all());

        assertTrue(result.applied(), result.message());
        assertEquals(ReloadFamily.values().length, result.families().size());
        assertEquals(0, result.totalIssues());
    }

    // ---- Les trois états exigés par le ticket ----------------------------------------------

    @Test
    void thePreviewExposesTheIdsPresentOnTheServerDisk() throws Exception {
        writeQuest("a.yml", "rpgquest:alpha", null, null);
        writeQuest("b.yml", "rpgquest:beta", null, null);

        ContentReloadService.ReloadResult preview = service.preview(Set.of(ReloadFamily.QUESTS));

        // C'est ce qui rend les trois états distinguables : le panel peut comparer cette liste
        // (= publié sur le serveur) à ce que le runtime expose (= chargé en jeu).
        assertEquals(List.of("rpgquest:alpha", "rpgquest:beta"), preview.families().get(0).ids());
    }

    @Test
    void aContentNeverPublishedIsAbsentFromThePreviewIds() throws Exception {
        writeQuest("a.yml", "rpgquest:alpha", null, null);

        ContentReloadService.ReloadResult preview = service.preview(Set.of(ReloadFamily.QUESTS));

        // « rpgquest:jamais_publiee » existe peut-être dans la source AWS ; elle n'est pas ici,
        // donc aucun rechargement ne la fera apparaître — il faut un déploiement.
        assertFalse(preview.families().get(0).ids().contains("rpgquest:jamais_publiee"));
    }

    @Test
    void theIdsAfterApplicationDescribeTheRuntime() throws Exception {
        writeQuest("a.yml", "rpgquest:alpha", null, null);

        ContentReloadService.ReloadResult applied = service.reload(Set.of(ReloadFamily.QUESTS));

        assertTrue(applied.applied());
        assertEquals(List.of("rpgquest:alpha"), applied.families().get(0).ids());
        assertEquals(questIds(), applied.families().get(0).ids(), "identique à l'état réellement chargé");
    }

    @Test
    void idsAreSortedSoTwoIdenticalRuntimesCompareEqual() throws Exception {
        writeQuest("z.yml", "rpgquest:zeta", null, null);
        writeQuest("a.yml", "rpgquest:alpha", null, null);

        ContentReloadService.ReloadResult result = service.reload(Set.of(ReloadFamily.QUESTS));

        assertEquals(List.of("rpgquest:alpha", "rpgquest:zeta"), result.families().get(0).ids());
    }
}
