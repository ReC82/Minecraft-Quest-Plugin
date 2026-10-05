package com.lodygames.rpgquest.panel.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exécution d'une suppression (issue #194). Ce qui est vérifié ici est l'<strong>ordre des
 * écritures</strong> et le comportement en <strong>échec partiel</strong> : c'est là que la
 * destruction de données se joue, pas dans le chemin heureux.
 */
class ContentDeletionExecutorTest {

    @TempDir
    Path tempDir;

    private ContentWorkspace workspace;
    private ContentDeletionAnalyzer analyzer;
    private ContentDeletionExecutor executor;

    @BeforeEach
    void setUp() throws IOException {
        for (String kind : ContentWorkspace.KINDS) {
            Files.createDirectories(tempDir.resolve(kind));
        }
        workspace = new ContentWorkspace(tempDir, tempDir.resolve("backups"));
        analyzer = new ContentDeletionAnalyzer(workspace, new SourceCatalog(workspace));
        executor = new ContentDeletionExecutor(workspace);
    }

    private void writeQuest(String slug, String title, List<String> prerequisites) throws IOException {
        QuestDraft d = new QuestDraft();
        d.id = slug;
        d.title = title;
        d.description = "Quête de test.";
        d.category = "tutorial";
        d.icon = "BOOK";
        d.prerequisites.addAll(prerequisites);
        QuestDraft.Step step = new QuestDraft.Step("step1");
        step.objectives.add(new java.util.LinkedHashMap<>(java.util.Map.of(
                "kind", "KILL_ENTITY", "entity", "ZOMBIE", "amount", "1")));
        d.steps.add(step);
        Files.writeString(tempDir.resolve("quests").resolve(slug + ".yml"), QuestYaml.write(d));
    }

    @Test
    @DisplayName("Chemin complet : références nettoyées, fichier supprimé, tout sauvegardé avant")
    void happyPathCleansThenDeletes() throws IOException {
        writeQuest("test_base", "Base", List.of());
        writeQuest("test_suite", "Suite", List.of("test_base"));

        ContentDeletionExecutor.Result result =
                executor.apply(analyzer.analyze("quests", "test_base", false));

        assertTrue(result.ok(), result.message());
        assertEquals("DELETED", result.code());
        assertFalse(Files.exists(tempDir.resolve("quests").resolve("test_base.yml")));
        assertTrue(Files.exists(tempDir.resolve("quests").resolve("test_suite.yml")),
                "la quête référençante ne doit JAMAIS être supprimée");
        assertFalse(Files.readString(tempDir.resolve("quests").resolve("test_suite.yml"))
                .contains("test_base"), "la référence doit avoir été retirée");

        // Les deux fichiers touchés doivent être sauvegardés, sous un même horodatage.
        assertNotNull(result.backupDir());
        Path backups = Path.of(result.backupDir()).resolve("quests");
        assertTrue(Files.exists(backups.resolve("test_base.yml")), "sauvegarde du fichier supprimé");
        assertTrue(Files.exists(backups.resolve("test_suite.yml")), "sauvegarde du fichier réécrit");
    }

    @Test
    @DisplayName("Les sauvegardes vivent HORS des dossiers de contenu : jamais relues comme contenu")
    void backupsLiveOutsideContentDirectories() throws IOException {
        writeQuest("test_solo", "Solo", List.of());

        ContentDeletionExecutor.Result result =
                executor.apply(analyzer.analyze("quests", "test_solo", false));

        assertTrue(result.ok(), result.message());
        assertEquals(0, new SourceCatalog(workspace).quests().size(),
                "une sauvegarde ne doit jamais réapparaître dans le catalogue source");
        assertFalse(Path.of(result.backupDir()).startsWith(tempDir.resolve("quests")));
    }

    @Test
    @DisplayName("Un plan bloqué n'écrit RIEN : ni réécriture, ni suppression")
    void blockedPlanWritesNothing() throws IOException {
        writeQuest("test_cible", "Cible", List.of());
        // Un dialogue référençant la quête bloque le plan.
        Files.writeString(tempDir.resolve("dialogues").resolve("test_garde.yml"),
                "id: test_garde\nstart: start\nnodes:\n  - id: start\n    text: \"x\"\n"
                        + "    choices:\n      - text: \"ok\"\n        actions:\n"
                        + "          - type: START_QUEST\n            quest: rpgquest:test_cible\n");
        String before = Files.readString(tempDir.resolve("quests").resolve("test_cible.yml"));

        ContentDeletionExecutor.Result result =
                executor.apply(analyzer.analyze("quests", "test_cible", false));

        assertFalse(result.ok());
        assertEquals("BLOCKED", result.code());
        assertEquals(before, Files.readString(tempDir.resolve("quests").resolve("test_cible.yml")));
        assertFalse(Files.exists(tempDir.resolve("backups")),
                "un plan bloqué ne doit même pas créer de sauvegarde");
    }

    @Test
    @DisplayName("Si le fichier a changé depuis l'aperçu, la suppression est refusée et tout est restauré")
    void staleShaAbortsAndRollsBack() throws IOException {
        writeQuest("test_base", "Base", List.of());
        writeQuest("test_suite", "Suite", List.of("test_base"));
        DeletionPlan plan = analyzer.analyze("quests", "test_base", false);

        // Quelqu'un modifie le fichier visé entre l'aperçu et la confirmation.
        Files.writeString(tempDir.resolve("quests").resolve("test_base.yml"),
                Files.readString(tempDir.resolve("quests").resolve("test_base.yml"))
                        + "\n# modifié entre-temps\n");
        String suiteBefore = Files.readString(tempDir.resolve("quests").resolve("test_suite.yml"));

        ContentDeletionExecutor.Result result = executor.apply(plan);

        assertFalse(result.ok());
        assertEquals("CONFLICT", result.code());
        assertTrue(Files.exists(tempDir.resolve("quests").resolve("test_base.yml")),
                "le fichier visé doit rester en place");
        assertEquals(suiteBefore, Files.readString(tempDir.resolve("quests").resolve("test_suite.yml")),
                "le nettoyage déjà fait doit avoir été annulé");
        assertTrue(result.rollbackNotes().stream().anyMatch(n -> n.startsWith("Restauré")),
                result.rollbackNotes().toString());
    }

    @Test
    @DisplayName("Un contenu seulement côté serveur : rien à écrire dans la source, et c'est dit")
    void runtimeOnlyNeedsNoSourceWrite() {
        ContentDeletionExecutor.Result result =
                executor.apply(analyzer.analyze("quests", "test_serveur", true));

        assertTrue(result.ok(), result.message());
        assertEquals("DELETED", result.code());
        assertEquals(null, result.deletedPath());
        assertTrue(result.message().contains("côté serveur"), result.message());
    }

    @Test
    @DisplayName("Une sauvegarde est restaurable telle quelle : le contenu revient à l'identique")
    void backupRestoresExactContent() throws IOException {
        writeQuest("test_solo", "Solo", List.of());
        String original = Files.readString(tempDir.resolve("quests").resolve("test_solo.yml"));

        ContentDeletionExecutor.Result result =
                executor.apply(analyzer.analyze("quests", "test_solo", false));
        assertTrue(result.ok());

        Path backup = Path.of(result.backupDir()).resolve("quests").resolve("test_solo.yml");
        Files.copy(backup, tempDir.resolve("quests").resolve("test_solo.yml"));

        assertEquals(original, Files.readString(tempDir.resolve("quests").resolve("test_solo.yml")));
    }
}
