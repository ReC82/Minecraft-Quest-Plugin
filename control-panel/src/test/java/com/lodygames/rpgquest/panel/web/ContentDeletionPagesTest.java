package com.lodygames.rpgquest.panel.web;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.panel.authz.Permission;
import com.lodygames.rpgquest.panel.authz.Role;
import com.lodygames.rpgquest.panel.content.ContentDeletionAnalyzer;
import com.lodygames.rpgquest.panel.content.ContentWorkspace;
import com.lodygames.rpgquest.panel.content.DeletionPlan;
import com.lodygames.rpgquest.panel.content.QuestDraft;
import com.lodygames.rpgquest.panel.content.QuestYaml;
import com.lodygames.rpgquest.panel.content.SourceCatalog;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Aperçu, confirmation et garde-fous d'autorisation de la suppression (issue #194).
 *
 * <p>Le point le plus important ici n'est pas le rendu : c'est qu'<strong>aucun chemin n'existe
 * pour supprimer sans avoir vu les conséquences et retapé l'identifiant</strong>.</p>
 */
class ContentDeletionPagesTest {

    @TempDir
    Path tempDir;

    private ContentWorkspace workspace;
    private ContentDeletionPages pages;

    @BeforeEach
    void setUp() throws IOException {
        for (String kind : ContentWorkspace.KINDS) {
            Files.createDirectories(tempDir.resolve(kind));
        }
        workspace = new ContentWorkspace(tempDir, tempDir.resolve("backups"));
        pages = new ContentDeletionPages(workspace,
                new ContentDeletionAnalyzer(workspace, new SourceCatalog(workspace)));
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

    // ---- Confirmation ----------------------------------------------------------------------

    @Test
    @DisplayName("Seul l'identifiant exact confirme : ni « oui », ni vide, ni un autre id")
    void onlyTheExactIdConfirms() throws IOException {
        writeQuest("test_solo", "Solo", List.of());
        DeletionPlan plan = pages.plan("quests", "test_solo", false);

        assertTrue(ContentDeletionPages.confirmationMatches(plan, "test_solo"));
        assertTrue(ContentDeletionPages.confirmationMatches(plan, "  TEST_SOLO  "),
                "espaces et casse tolérés : c'est le geste qui compte, pas la dactylographie");
        assertFalse(ContentDeletionPages.confirmationMatches(plan, "oui"));
        assertFalse(ContentDeletionPages.confirmationMatches(plan, ""));
        assertFalse(ContentDeletionPages.confirmationMatches(plan, null));
        assertFalse(ContentDeletionPages.confirmationMatches(plan, "test_autre"));
    }

    @Test
    @DisplayName("Une confirmation erronée n'écrit RIEN et ne renvoie aucun résultat")
    void wrongConfirmationIsANoOp() throws IOException {
        writeQuest("test_solo", "Solo", List.of());
        String before = Files.readString(tempDir.resolve("quests").resolve("test_solo.yml"));

        assertNull(pages.apply("quests", "test_solo", false, "oui"));

        assertTrue(Files.exists(tempDir.resolve("quests").resolve("test_solo.yml")));
        org.junit.jupiter.api.Assertions.assertEquals(before,
                Files.readString(tempDir.resolve("quests").resolve("test_solo.yml")));
    }

    // ---- Aperçu ----------------------------------------------------------------------------

    @Test
    @DisplayName("L'aperçu annonce où le contenu existe, ce qui sera nettoyé, et ce qui ne bougera pas")
    void previewShowsConsequences() throws IOException {
        writeQuest("test_base", "Base", List.of());
        writeQuest("test_suite", "Suite", List.of("test_base"));

        String html = pages.preview("quests", "test_base", true, "jeton", null);

        assertTrue(html.contains("Où ce contenu existe"), html);
        assertTrue(html.contains("Références qui seront nettoyées"), html);
        assertTrue(html.contains("test_suite"), "le fichier impacté doit être nommé");
        assertTrue(html.contains("réécrits"), "il faut dire qu'ils sont réécrits, pas supprimés");
        // Les notes passent par Http.esc : l'apostrophe est échappée, on cherche donc un
        // fragment sans apostrophe plutôt que la phrase brute.
        assertTrue(html.contains("Aucune progression de joueur"), html);
        assertTrue(html.contains("éditoriale"), html);
        assertTrue(html.contains("retaper l'identifiant exact"), "la confirmation doit être exigée");
        assertTrue(html.contains("name=\"" + ContentDeletionPages.CONFIRM_FIELD + "\""), html);
    }

    @Test
    @DisplayName("Un plan bloqué n'affiche AUCUN formulaire de confirmation : rien à cliquer")
    void blockedPreviewHasNoConfirmForm() throws IOException {
        writeQuest("test_cible", "Cible", List.of());
        Files.writeString(tempDir.resolve("dialogues").resolve("test_garde.yml"),
                "id: test_garde\nstart: start\nnodes:\n  - id: start\n    text: \"x\"\n"
                        + "    choices:\n      - text: \"ok\"\n        actions:\n"
                        + "          - type: START_QUEST\n            quest: rpgquest:test_cible\n");

        String html = pages.preview("quests", "test_cible", false, "jeton", null);

        assertTrue(html.contains("Points bloquants"), html);
        assertTrue(html.contains("test_garde"), "le dialogue bloquant doit être nommé");
        assertFalse(html.contains("name=\"" + ContentDeletionPages.CONFIRM_FIELD + "\""),
                "aucun formulaire de confirmation ne doit exister tant que ça bloque");
        assertTrue(html.contains("Suppression impossible en l'état"), html);
    }

    @Test
    @DisplayName("Un exemple embarqué est signalé dans l'aperçu, avec sa conséquence au redémarrage")
    void previewWarnsAboutBundledExample() throws IOException {
        writeQuest("first_steps", "Premiers pas", List.of());

        String html = pages.preview("quests", "first_steps", false, "jeton", null);

        assertTrue(html.contains("exemples "), html);
        assertTrue(html.contains("recrée au démarrage"), html);
        assertTrue(html.contains("reconstruire et redéployer"), html);
    }

    @Test
    @DisplayName("Le titre humain est affiché, pas seulement le nom de fichier")
    void previewNamesTheContent() throws IOException {
        writeQuest("test_solo", "La quête du test", List.of());

        String html = pages.preview("quests", "test_solo", false, "jeton", null);

        assertTrue(html.contains("La quête du test"), html);
        assertTrue(html.contains("test_solo"), "l'identifiant technique reste visible");
    }

    @Test
    @DisplayName("Un message d'erreur passé est affiché, sans masquer l'aperçu")
    void previewKeepsContextOnError() throws IOException {
        writeQuest("test_solo", "Solo", List.of());

        String html = pages.preview("quests", "test_solo", false, "jeton",
                "Confirmation incorrecte : il faut retaper exactement l'identifiant.");

        assertTrue(html.contains("Confirmation incorrecte"), html);
        assertTrue(html.contains("Où ce contenu existe"), "l'aperçu doit rester visible");
    }

    // ---- Autorisation ----------------------------------------------------------------------

    @Test
    @DisplayName("La suppression a une permission DÉDIÉE, refusée à l'éditeur de contenu")
    void deletionPermissionIsDedicated() {
        assertTrue(Role.OWNER.has(Permission.CONTENT_DELETE), "le propriétaire a tout");
        assertTrue(Role.ADMIN.has(Permission.CONTENT_DELETE));

        assertFalse(Role.CONTENT_EDITOR.has(Permission.CONTENT_DELETE),
                "écrire du contenu est réversible, le détruire ne l'est pas de la même façon");
        assertTrue(Role.CONTENT_EDITOR.has(Permission.QUEST_CONTENT_WRITE),
                "il doit malgré tout pouvoir éditer");
        assertFalse(Role.TESTER.has(Permission.CONTENT_DELETE));
        assertFalse(Role.BUILDER.has(Permission.CONTENT_DELETE));
        assertFalse(Role.READ_ONLY.has(Permission.CONTENT_DELETE));
    }

    // ---- Résultat --------------------------------------------------------------------------

    @Test
    @DisplayName("Le résultat cite la sauvegarde et l'état de la suppression côté serveur")
    void resultCitesBackupAndRuntime() throws IOException {
        writeQuest("test_solo", "Solo", List.of());
        var result = pages.apply("quests", "test_solo", true, "test_solo");

        String html = pages.result("quests", "test_solo", result, true);

        assertTrue(html.contains("Sauvegarde"), html);
        assertTrue(html.contains(result.backupDir()), "le chemin exact doit être cité");
        assertTrue(html.contains("copie <strong>serveur</strong>"), html);
        assertTrue(html.contains("asynchrone"), "l'attente côté serveur doit être expliquée");
    }
}
