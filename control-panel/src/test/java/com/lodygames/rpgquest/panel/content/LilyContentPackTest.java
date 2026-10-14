package com.lodygames.rpgquest.panel.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.panel.agent.AgentActionCatalog;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Issue #211 — test d'acceptation « Les souvenirs de Lily » : un seul fichier JSON, deux personnages
 * logiques, une story de cinq quêtes et deux dialogues, importés par le pipeline existant en une
 * confirmation. Tom ({@link TomContentPackTest}) reste le test minimal de non-régression.
 */
class LilyContentPackTest {

    private static final List<String> QUESTS = List.of(
            "lily_repas", "lily_fleurs", "lily_ingredients", "lily_tarte", "lily_julius");

    @TempDir
    Path tmp;

    private ContentWorkspace workspace;
    private String pack;

    @BeforeEach
    void setUp() throws Exception {
        Path root = tmp.resolve("content");
        for (String kind : ContentWorkspace.KINDS) {
            Files.createDirectories(root.resolve(kind));
        }
        workspace = new ContentWorkspace(root, tmp.resolve("backups"));
        pack = StoryKit.lilyPack();
    }

    private static long count(ContentPackImport.Analysis a, String family, ContentPackImport.Status status) {
        return a.elements().stream().filter(e -> e.family().equals(family) && e.status() == status).count();
    }

    @Test
    void theLilyPackIsImportableAndEveryInternalReferenceResolves() {
        ContentPackImport.Analysis a = ContentPackImport.analyze(pack, workspace, RefData.empty(), Map.of());

        assertTrue(a.readable(), a.envelope().toString());
        assertTrue(a.importable(), () -> a.elements().toString());
        assertEquals(5, count(a, "quests", ContentPackImport.Status.NEW));
        assertEquals(1, count(a, "stories", ContentPackImport.Status.NEW));
        assertEquals(2, count(a, "dialogues", ContentPackImport.Status.NEW));
        assertEquals(2, count(a, "npcs", ContentPackImport.Status.NEW), "Lily et Julius, personnages logiques");
        // Hors relevé serveur, seuls des INFO « impossible de vérifier » sont admis : aucune
        // erreur ni aucun avertissement.
        for (ContentPackImport.Element e : a.elements()) {
            assertTrue(e.diagnostics().stream().allMatch(d -> d.level() == Diagnostic.Level.INFO),
                    e.key() + " : " + e.diagnostics());
        }
        assertTrue(a.envelope().isEmpty(), a.envelope().toString());
    }

    @Test
    void oneConfirmationWritesTheWholeStoryInCanonicalFormAndPreparesBothCharacters() {
        ContentPackImport.Analysis a = ContentPackImport.analyze(pack, workspace, RefData.empty(), Map.of());
        List<ContentPackImport.WriteOutcome> out = ContentPackImport.apply(a, workspace);
        assertTrue(out.stream().allMatch(o -> o.result() == null || o.result().ok()), out.toString());

        for (String quest : QUESTS) {
            assertTrue(QuestYaml.read(workspace.read("quests", quest).orElseThrow().text()).ok(), quest);
        }
        String story = workspace.read("stories", "lily_souvenirs").orElseThrow().text();
        assertTrue(StoryYaml.read(story).ok(), story);
        int last = -1;
        for (String quest : QUESTS) {
            int at = story.indexOf("rpgquest:" + quest);
            assertTrue(at > last, "ordre de la story : " + quest + "\n" + story);
            last = at;
        }
        String tarte = workspace.read("quests", "lily_tarte").orElseThrow().text();
        assertTrue(tarte.contains("rpgquest:lily_ingredients"), "déblocage conservé : " + tarte);
        assertTrue(tarte.contains("COAL") && tarte.contains("TALK_TO_NPC"), tarte);
        assertTrue(workspace.read("quests", "lily_julius").orElseThrow().text().contains("npc: julius"));
        for (String dialogue : List.of("lily", "julius")) {
            String text = workspace.read("dialogues", dialogue).orElseThrow().text();
            assertTrue(DialogueYaml.read(text).ok(), () -> DialogueYaml.read(text).problems().toString());
        }
        String lily = workspace.read("dialogues", "lily").orElseThrow().text();
        assertTrue(lily.contains("QUEST_AVAILABLE") && lily.contains("DELIVER_QUEST_ITEMS"), lily);

        List<Map<String, String>> creations = ContentPackImport.npcCreations(a, pack);
        assertEquals(2, creations.size());
        for (Map<String, String> c : creations) {
            var v = AgentActionCatalog.validate("npc.definition.create", c);
            assertTrue(v.valid(), v.error());
            assertEquals("rpgquest:" + v.params().get("npc_id"), v.params().get("dialogue_id"));
            assertFalse(c.keySet().stream().anyMatch(k -> k.contains("citizens") || k.contains("world")),
                    "aucune liaison ni position importée : " + c);
        }
    }

    @Test
    void reimportingTheSamePackCreatesNothingTwice() {
        ContentPackImport.apply(ContentPackImport.analyze(pack, workspace, RefData.empty(), Map.of()), workspace);
        RefData server = new RefData(List.of(), List.of("lily", "julius"), List.of(), false, true, false);

        ContentPackImport.Analysis again = ContentPackImport.analyze(pack, workspace, server, Map.of());

        assertEquals(5, count(again, "quests", ContentPackImport.Status.UNCHANGED));
        assertEquals(2, count(again, "dialogues", ContentPackImport.Status.UNCHANGED));
        assertEquals(2, count(again, "npcs", ContentPackImport.Status.SKIPPED));
        assertFalse(again.importable(), "rien à écrire : aucune confirmation proposée");
        assertTrue(ContentPackImport.npcCreations(again, pack).isEmpty(), "aucun personnage en double");
    }

    /** Sur DEV, Lily et Julius existent déjà (liés à Citizens) : ils sont réutilisés, pas recréés. */
    @Test
    void charactersAlreadyOnTheServerAreReusedAndNeverModified() {
        RefData server = new RefData(List.of(), List.of("lily", "julius"), List.of(), false, true, false);

        ContentPackImport.Analysis a = ContentPackImport.analyze(pack, workspace, server, Map.of());

        assertTrue(a.importable());
        assertEquals(2, count(a, "npcs", ContentPackImport.Status.SKIPPED));
        assertTrue(ContentPackImport.npcCreations(a, pack).isEmpty());
    }

    @Test
    void anExistingDifferentQuestIsACollisionThatBlocksTheWholeImport() throws Exception {
        Files.writeString(tmp.resolve("content/quests/lily_fleurs.yml"),
                "id: rpgquest:lily_fleurs\ntitle: \"autre\"\n");

        ContentPackImport.Analysis a = ContentPackImport.analyze(pack, workspace, RefData.empty(), Map.of());

        assertEquals(1, a.conflicts());
        assertFalse(a.importable(), "aucune écriture partielle possible tant que la collision est ouverte");
    }

    @Test
    void aFailedWriteLeavesNoPartialStory() throws Exception {
        ContentPackImport.Analysis a = ContentPackImport.analyze(pack, workspace, RefData.empty(), Map.of());
        // Le dialogue de Julius apparaît entre l'analyse et la confirmation : son écriture est
        // refusée, donc tout ce que cet import a déjà écrit doit être défait.
        Files.writeString(tmp.resolve("content/dialogues/julius.yml"), "id: rpgquest:julius\n");

        List<ContentPackImport.WriteOutcome> out = ContentPackImport.apply(a, workspace);

        assertTrue(out.stream().anyMatch(o -> o.result() != null && !o.result().ok()));
        for (String quest : QUESTS) {
            assertFalse(workspace.exists("quests", quest), quest + " défaite");
        }
        assertFalse(workspace.exists("stories", "lily_souvenirs"));
        assertFalse(workspace.exists("dialogues", "lily"));
        assertTrue(ContentPackImport.npcCreations(a, pack).size() == 2,
                "les créations ne partent qu'après une écriture complète — c'est l'appelant qui le vérifie");
    }

    @Test
    void theEditorialPreviewShowsTheStoryInOrderWithObjectivesAndRewards() {
        ContentPackSummary.Summary s = ContentPackSummary.of(pack);

        assertEquals(1, s.stories().size());
        assertEquals(QUESTS.stream().map(q -> "rpgquest:" + q).toList(), s.stories().get(0).questIds());
        assertTrue(s.standaloneQuests().isEmpty());
        ContentPackSummary.Quest repas = s.quests().get("rpgquest:lily_repas");
        assertTrue(repas.objectives().contains("Étape 1 — Rapporter des objets à un PNJ : 3 × BREAD → lily"),
                repas.objectives().toString());
        assertTrue(repas.rewards().contains("Expérience : 20"), repas.rewards().toString());
        assertEquals(List.of("rpgquest:lily_repas"), s.quests().get("rpgquest:lily_fleurs").prerequisites());
        assertEquals(2, s.npcs().size());
        assertEquals(5, s.dialogues().stream().filter(d -> d.id().equals("rpgquest:lily")).findFirst()
                .orElseThrow().startsQuests().size(), "Lily propose les cinq quêtes");
    }

    /** Le squelette vierge est lui-même valide : l'IA part d'un fichier que l'import accepte. */
    @Test
    void theBlankPackIsAValidStartingPoint() {
        String blank = StoryKit.content("modele-pack-vierge.json").orElseThrow();

        ContentPackImport.Analysis a = ContentPackImport.analyze(blank, workspace, RefData.empty(), Map.of());

        assertTrue(a.importable(), () -> a.elements().toString());
        for (ContentPackImport.Element e : a.elements()) {
            assertFalse(Diagnostic.hasError(e.diagnostics()), e.key() + " : " + e.diagnostics());
        }
    }
}
