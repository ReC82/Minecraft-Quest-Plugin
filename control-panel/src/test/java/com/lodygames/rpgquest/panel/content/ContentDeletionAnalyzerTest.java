package com.lodygames.rpgquest.panel.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * Analyse des conséquences d'une suppression (issue #194).
 *
 * <p>Les trois règles du ticket sont figées ici : <strong>jamais de cascade</strong>,
 * <strong>jamais de lien orphelin</strong> (on bloque plutôt que de produire un fichier invalide),
 * et <strong>jamais de progression joueur touchée</strong>.</p>
 */
class ContentDeletionAnalyzerTest {

    @TempDir
    Path tempDir;

    private ContentWorkspace workspace;
    private ContentDeletionAnalyzer analyzer;

    @BeforeEach
    void setUp() throws IOException {
        for (String kind : ContentWorkspace.KINDS) {
            Files.createDirectories(tempDir.resolve(kind));
        }
        workspace = new ContentWorkspace(tempDir, tempDir.resolve("backups"));
        analyzer = new ContentDeletionAnalyzer(workspace, new SourceCatalog(workspace));
    }

    /**
     * Écrit une quête avec le sérialiseur réel du panel. Écrire le YAML à la main donnerait un
     * fichier « presque » canonique que {@code SourceCatalog} marquerait comme non analysable, et
     * le test vérifierait alors autre chose que ce qu'il annonce.
     */
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

    private void writeStory(String slug, String name, List<String> questIds) throws IOException {
        StoryDraft d = new StoryDraft();
        d.id = slug;
        d.name = name;
        d.questIds.addAll(questIds);
        Files.writeString(tempDir.resolve("stories").resolve(slug + ".yml"), StoryYaml.write(d));
    }

    private void writeDialogueReferencing(String slug, String questId) throws IOException {
        String yaml = """
                id: rpgquest:%s
                start: start
                nodes:
                  - id: start
                    speaker: "Garde"
                    text: "Bonjour"
                    choices:
                      - text: "J'accepte"
                        conditions:
                          - type: QUEST_STATE
                            quest: rpgquest:%s
                            state: NOT_STARTED
                        actions:
                          - type: START_QUEST
                            quest: rpgquest:%s
                        next: done
                  - id: done
                    speaker: "Garde"
                    text: "Merci"
                """.formatted(slug, questId, questId);
        Files.writeString(tempDir.resolve("dialogues").resolve(slug + ".yml"), yaml);
    }

    // ---- Cas simple -------------------------------------------------------------------------

    @Test
    @DisplayName("Une quête sans aucune référence est supprimable, et le dit")
    void unreferencedQuestIsDeletable() throws IOException {
        writeQuest("test_solo", "Quête solitaire", List.of());

        DeletionPlan plan = analyzer.analyze("quests", "test_solo", false);

        assertTrue(plan.deletable());
        assertTrue(plan.sourcePresent());
        assertTrue(plan.blockers().isEmpty(), plan.blockers().toString());
        assertTrue(plan.edits().isEmpty());
        assertEquals("Quête solitaire", plan.label(), "la confirmation doit parler du titre humain");
    }

    @Test
    @DisplayName("Un contenu inexistant partout n'est pas « supprimable » : on le dit au lieu d'agir")
    void missingContentIsNotDeletable() {
        DeletionPlan plan = analyzer.analyze("quests", "jamais_existe", false);

        assertFalse(plan.deletable());
        assertTrue(plan.blockers().get(0).reason().contains("n'existe ni dans la source"),
                plan.blockers().toString());
    }

    @Test
    @DisplayName("Un contenu présent seulement sur le serveur reste supprimable, côté serveur")
    void runtimeOnlyContentIsDeletable() {
        DeletionPlan plan = analyzer.analyze("quests", "quete_du_serveur", true);

        assertTrue(plan.deletable());
        assertFalse(plan.sourcePresent());
        assertTrue(plan.runtimePresent());
        assertTrue(plan.notes().stream().anyMatch(n -> n.contains("n'existe que sur le serveur")),
                plan.notes().toString());
    }

    // ---- Nettoyage automatique --------------------------------------------------------------

    @Test
    @DisplayName("Un prérequis d'une autre quête est retiré automatiquement — sans la supprimer")
    void prerequisiteIsCleanedNotCascaded() throws IOException {
        writeQuest("test_base", "Base", List.of());
        writeQuest("test_suite", "Suite", List.of("test_base"));

        DeletionPlan plan = analyzer.analyze("quests", "test_base", false);

        assertTrue(plan.deletable(), plan.blockers().toString());
        assertEquals(1, plan.edits().size());
        DeletionPlan.Edit edit = plan.edits().get(0);
        assertEquals("quests", edit.kind());
        assertEquals("test_suite", edit.slug());
        assertFalse(edit.newYaml().contains("test_base"),
                "le prérequis doit avoir disparu du YAML produit");
        assertTrue(edit.newYaml().contains("Suite"),
                "la quête référençante doit rester intacte par ailleurs");
    }

    @Test
    @DisplayName("Une quête est retirée de la chaîne d'une story quand il en reste d'autres")
    void storyChainIsTrimmed() throws IOException {
        writeQuest("test_a", "A", List.of());
        writeQuest("test_b", "B", List.of());
        writeStory("test_saga", "Saga de test", List.of("test_a", "test_b"));

        DeletionPlan plan = analyzer.analyze("quests", "test_a", false);

        assertTrue(plan.deletable(), plan.blockers().toString());
        DeletionPlan.Edit edit = plan.edits().stream()
                .filter(e -> "stories".equals(e.kind())).findFirst().orElseThrow();
        assertFalse(edit.newYaml().contains("test_a"));
        assertTrue(edit.newYaml().contains("test_b"), "l'autre quête doit rester dans la chaîne");
    }

    // ---- Blocages ---------------------------------------------------------------------------

    @Test
    @DisplayName("BLOQUE si la story n'enchaînait que cette quête : une chaîne vide est invalide")
    void blocksWhenStoryWouldBecomeEmpty() throws IOException {
        writeQuest("test_seule", "Seule", List.of());
        writeStory("test_mono", "Mono", List.of("test_seule"));

        DeletionPlan plan = analyzer.analyze("quests", "test_seule", false);

        assertFalse(plan.deletable());
        DeletionPlan.Blocker blocker = plan.blockers().get(0);
        assertTrue(blocker.reason().contains("sans aucune quête"), blocker.reason());
        assertTrue(blocker.where().contains("test_mono.yml"), blocker.where());
        assertTrue(blocker.hint().contains("Ajouter une autre quête"), blocker.hint());
        assertTrue(plan.edits().isEmpty(), "aucune modification ne doit être préparée si ça bloque");
    }

    @Test
    @DisplayName("BLOQUE si un dialogue référence la quête, en citant le fichier et les lignes")
    void blocksOnDialogueReference() throws IOException {
        writeQuest("test_parlee", "Parlée", List.of());
        writeDialogueReferencing("test_garde", "test_parlee");

        DeletionPlan plan = analyzer.analyze("quests", "test_parlee", false);

        assertFalse(plan.deletable());
        DeletionPlan.Blocker blocker = plan.blockers().stream()
                .filter(b -> b.where().contains("dialogues")).findFirst().orElseThrow();
        assertTrue(blocker.reason().contains("test_garde"), blocker.reason());
        assertTrue(blocker.reason().matches(".*lignes \\d+ et \\d+.*"),
                "les deux lignes (condition et action) doivent être citées : " + blocker.reason());
        assertTrue(blocker.hint().contains("éditeur"), blocker.hint());
    }

    @Test
    @DisplayName("Un dialogue qui référence une AUTRE quête ne bloque pas")
    void unrelatedDialogueDoesNotBlock() throws IOException {
        writeQuest("test_cible", "Cible", List.of());
        writeDialogueReferencing("test_garde", "une_autre_quete");

        DeletionPlan plan = analyzer.analyze("quests", "test_cible", false);

        assertTrue(plan.deletable(), plan.blockers().toString());
    }

    @Test
    @DisplayName("BLOQUE si un fichier qui mentionne la quête n'a pas pu être analysé")
    void blocksOnUnparseableReferencingFile() throws IOException {
        writeQuest("test_cible", "Cible", List.of());
        // Fichier volontairement non canonique : SourceCatalog le livrera avec parseOk=false. On
        // le vérifie d'abord, sinon ce test pourrait passer pour la mauvaise raison.
        // « steps » contenant une chaîne au lieu d'un objet : QuestYaml signale « étape mal
        // formée », donc parseOk = false, tout en mentionnant bien l'identifiant visé.
        Files.writeString(tempDir.resolve("quests").resolve("test_casse.yml"),
                "id: test_casse\nprerequisites:\n  - rpgquest:test_cible\nsteps:\n  - juste-du-texte\n");
        assertFalse(new SourceCatalog(workspace).quests().stream()
                        .filter(q -> "test_casse".equals(q.slug()))
                        .findFirst().orElseThrow().parseOk(),
                "précondition du test : ce fichier doit être non analysable");

        DeletionPlan plan = analyzer.analyze("quests", "test_cible", false);

        assertFalse(plan.deletable());
        assertTrue(plan.blockers().stream().anyMatch(b -> b.reason().contains("n'a pas pu être")),
                plan.blockers().toString());
    }

    @Test
    @DisplayName("BLOQUE si l'espace de travail est en lecture seule")
    void blocksWhenWorkspaceReadOnly() throws IOException {
        writeQuest("test_lecture", "Lecture", List.of());
        Path dir = tempDir.resolve("quests");
        dir.toFile().setWritable(false);
        try {
            DeletionPlan plan = analyzer.analyze("quests", "test_lecture", false);

            assertFalse(plan.deletable());
            assertTrue(plan.blockers().stream().anyMatch(b -> b.reason().contains("lecture seule")),
                    plan.blockers().toString());
        } finally {
            dir.toFile().setWritable(true);
        }
    }

    @Test
    @DisplayName("BLOQUE si aucune sauvegarde n'est possible : pas de suppression sans sauvegarde")
    void blocksWithoutBackupDirectory() throws IOException {
        writeQuest("test_sans_backup", "Sans sauvegarde", List.of());
        ContentWorkspace noBackup = new ContentWorkspace(tempDir);
        ContentDeletionAnalyzer local =
                new ContentDeletionAnalyzer(noBackup, new SourceCatalog(noBackup));

        DeletionPlan plan = local.analyze("quests", "test_sans_backup", false);

        assertFalse(plan.deletable());
        assertTrue(plan.blockers().stream().anyMatch(b -> b.reason().contains("sauvegarde")),
                plan.blockers().toString());
    }

    @Test
    @DisplayName("Seules les quêtes et les stories sont supprimables ici")
    void refusesOtherKinds() {
        DeletionPlan plan = analyzer.analyze("dialogues", "test_garde", true);

        assertFalse(plan.deletable());
        assertTrue(plan.blockers().get(0).reason().contains("Seules les quêtes et les stories"),
                plan.blockers().toString());
    }

    // ---- Stories ----------------------------------------------------------------------------

    @Test
    @DisplayName("Supprimer une story ne supprime pas les quêtes qu'elle enchaînait, et le dit")
    void deletingStoryKeepsItsQuests() throws IOException {
        writeQuest("test_a", "A", List.of());
        writeQuest("test_b", "B", List.of());
        writeStory("test_saga", "Saga", List.of("test_a", "test_b"));

        DeletionPlan plan = analyzer.analyze("stories", "test_saga", false);

        assertTrue(plan.deletable(), plan.blockers().toString());
        assertTrue(plan.edits().isEmpty(), "rien ne référence une story : aucun nettoyage");
        assertTrue(plan.notes().stream().anyMatch(n -> n.contains("2 quête(s) enchaînée(s)")
                        && n.contains("ne sont pas supprimées")),
                plan.notes().toString());
    }

    // ---- Politiques annoncées ---------------------------------------------------------------

    @Test
    @DisplayName("La politique de progression joueur est annoncée, toujours, et sans ambiguïté")
    void playerProgressPolicyIsAlwaysStated() throws IOException {
        writeQuest("test_solo", "Solo", List.of());

        DeletionPlan plan = analyzer.analyze("quests", "test_solo", false);

        String note = plan.notes().stream()
                .filter(n -> n.contains("progression"))
                .findFirst().orElseThrow();
        assertTrue(note.contains("Aucune progression de joueur n'est effacée"), note);
        assertTrue(note.contains("éditoriale"), note);
        assertTrue(note.contains("Réinitialiser"), "la distinction avec le reset doit être dite");
    }

    @Test
    @DisplayName("Un exemple embarqué prévient de sa réapparition au redémarrage")
    void bundledExampleWarnsAboutReappearance() throws IOException {
        writeQuest("first_steps", "Premiers pas", List.of());

        DeletionPlan plan = analyzer.analyze("quests", "first_steps", false);

        assertTrue(plan.bundledExample());
        assertTrue(plan.notes().stream().anyMatch(n -> n.contains("recrée au démarrage")),
                plan.notes().toString());
        assertTrue(plan.deletable(),
                "c'est un avertissement, pas un blocage : on doit pouvoir retirer un exemple");
    }

    @Test
    @DisplayName("Sans relevé du serveur, on ne PRÉTEND PAS qu'il ne connaît pas le contenu")
    void absentListingIsNotPresentedAsKnowledge() throws IOException {
        writeQuest("test_solo", "Solo", List.of());

        DeletionPlan sansReleve = analyzer.analyze("quests", "test_solo", false, false);

        String note = sansReleve.notes().stream()
                .filter(n -> n.contains("relevé"))
                .findFirst().orElseThrow();
        assertTrue(note.contains("impossible de savoir"), note);
        assertTrue(note.contains("réapparaîtra"), "la conséquence doit être dite : " + note);
        assertFalse(sansReleve.notes().stream()
                        .anyMatch(n -> n.contains("ne connaît pas cette quête")),
                "affirmer une absence jamais vérifiée serait faux");
    }

    @Test
    @DisplayName("Le runtime est traité explicitement : présent ⇒ suppression serveur annoncée")
    void runtimePresenceIsStated() throws IOException {
        writeQuest("test_solo", "Solo", List.of());

        DeletionPlan present = analyzer.analyze("quests", "test_solo", true);
        DeletionPlan absent = analyzer.analyze("quests", "test_solo", false);

        assertTrue(present.notes().stream().anyMatch(n -> n.contains("suppression côté serveur")),
                present.notes().toString());
        assertTrue(absent.notes().stream().anyMatch(n -> n.contains("rien à supprimer côté serveur")),
                absent.notes().toString());
    }
}
