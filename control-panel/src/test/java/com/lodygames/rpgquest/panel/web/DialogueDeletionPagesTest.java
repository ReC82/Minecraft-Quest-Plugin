package com.lodygames.rpgquest.panel.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.panel.content.ContentDeletionAnalyzer;
import com.lodygames.rpgquest.panel.content.ContentWorkspace;
import com.lodygames.rpgquest.panel.content.DeletionPlan;
import com.lodygames.rpgquest.panel.content.QuestDraft;
import com.lodygames.rpgquest.panel.content.QuestYaml;
import com.lodygames.rpgquest.panel.content.RuntimeDialogueInventory;
import com.lodygames.rpgquest.panel.content.SourceCatalog;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Issue #248 — plan et aperçu de suppression d'un dialogue, avec le relevé du serveur.
 *
 * <p>Le cas réel guide ces tests : {@code rpgquest:help}, hors source, sans PNJ, qui démarre
 * {@code help_recolte}.</p>
 */
class DialogueDeletionPagesTest {

    private static final Instant NOW = Instant.parse("2026-10-12T12:00:00Z");

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

    private static Map<String, Object> dialogue(String id, List<String> npcs, List<String> starts,
                                                String opens) {
        List<Object> actions = opens == null ? List.of()
                : List.of(Map.of("kind", "OPEN_DIALOGUE", "target", opens));
        return Map.of("id", id, "linkedNpcIds", npcs, "startsQuestIds", starts,
                "referencedQuestIds", starts,
                "nodes", List.of(Map.of("id", "start", "choices", List.of(Map.of("actions", actions)))));
    }

    @SafeVarargs
    private static RuntimeDialogueInventory fresh(Map<String, Object>... dialogues) {
        return RuntimeDialogueInventory.from(Map.of("dialogues", List.of(dialogues)),
                NOW.minus(Duration.ofMinutes(1)));
    }

    private DeletionPlan plan(String slug, RuntimeDialogueInventory inventory) {
        return pages.plan("dialogues", slug, inventory.find(slug).isPresent(), inventory.available(),
                inventory, NOW);
    }

    private static final Map<String, Object> HELP =
            dialogue("rpgquest:help", List.of(), List.of("rpgquest:help_recolte"), null);

    // ---- Le cas réel -------------------------------------------------------------------------

    @Test
    @DisplayName("help : hors source, sans PNJ, démarre help_recolte — supprimable côté serveur seul")
    void theRealHelpCaseIsDeletableRuntimeOnly() {
        DeletionPlan plan = plan("help", fresh(HELP));

        assertTrue(plan.deletable(), plan.blockers().toString());
        assertFalse(plan.sourcePresent());
        assertTrue(plan.runtimePresent());
        String html = pages.preview(plan, "jeton", null, "");
        assertTrue(html.contains("Supprimer le dialogue"), html);
        assertTrue(html.contains("Hors source"), html);
        assertTrue(html.contains("help_recolte"), "la référence sortante est montrée");
        assertTrue(html.contains("NE sont PAS supprimées"), html);
        assertTrue(html.contains("Aucun PNJ RPGQuest lié"), html);
        assertTrue(html.contains("hub-guides"), "les guides de Hub : vérifiés par le serveur");
        assertTrue(html.contains("name=\"" + ContentDeletionPages.CONFIRM_FIELD + "\""), html);
        assertTrue(html.contains("action=\"/dialogues/delete\""), html);
    }

    @Test
    @DisplayName("Une suppression serveur seule ne touche pas la source et réussit")
    void runtimeOnlyApplyTouchesNoFile() {
        DeletionPlan plan = plan("help", fresh(HELP));
        var result = pages.apply(plan, "help");
        assertTrue(result.ok(), result.message());
        assertEquals(null, result.deletedPath());
    }

    // ---- Refus -------------------------------------------------------------------------------

    @Test
    @DisplayName("Sans relevé du serveur : « vérification incomplète », jamais « aucune référence »")
    void noSurveyBlocksAsIncomplete() {
        DeletionPlan plan = plan("help", RuntimeDialogueInventory.unavailable());

        assertFalse(plan.deletable());
        String html = pages.preview(plan, "jeton", null, "<form id=\"rafraichir\"></form>");
        assertTrue(html.contains("Vérification incomplète"), html);
        assertTrue(html.contains("jamais été fait"), html);
        assertTrue(html.contains("id=\"rafraichir\""), "le geste qui débloque est proposé");
        assertFalse(html.contains("name=\"" + ContentDeletionPages.CONFIRM_FIELD + "\""));
    }

    @Test
    @DisplayName("Un relevé périmé (plus de 15 min) bloque aussi")
    void aStaleSurveyBlocks() {
        var stale = RuntimeDialogueInventory.from(Map.of("dialogues", List.of(HELP)),
                NOW.minus(Duration.ofMinutes(40)));
        DeletionPlan plan = plan("help", stale);
        assertFalse(plan.deletable());
        assertTrue(plan.blockers().get(0).reason().contains("40 min"), plan.blockers().toString());
    }

    @Test
    @DisplayName("Un dialogue lié à un PNJ est refusé, et le PNJ est nommé")
    void aLinkedNpcBlocks() {
        DeletionPlan plan = plan("jo", fresh(dialogue("rpgquest:jo", List.of("jo"), List.of(), null)));
        assertFalse(plan.deletable());
        assertTrue(plan.blockers().stream().anyMatch(b -> b.reason().contains("« jo »")),
                plan.blockers().toString());
    }

    @Test
    @DisplayName("Un dialogue ouvert par un autre (OPEN_DIALOGUE) est refusé")
    void anIncomingOpenDialogueBlocks() {
        DeletionPlan plan = plan("help", fresh(HELP,
                dialogue("rpgquest:guide", List.of("guide"), List.of(), "rpgquest:help")));
        assertFalse(plan.deletable());
        assertTrue(plan.blockers().stream().anyMatch(b -> b.reason().contains("rpgquest:guide")),
                plan.blockers().toString());
    }

    @Test
    @DisplayName("Un dialogue SOURCE qui l'ouvre bloque aussi, même s'il n'est pas encore publié")
    void aSourceOpenerBlocks() throws IOException {
        Files.writeString(tempDir.resolve("dialogues").resolve("accueil.yml"),
                "id: rpgquest:accueil\nstart: a\nnodes:\n  a:\n    text: x\n    choices:\n"
                        + "      - text: aide\n        actions:\n          - type: OPEN_DIALOGUE\n"
                        + "            dialogue: rpgquest:help\n");
        DeletionPlan plan = plan("help", fresh(HELP));
        assertFalse(plan.deletable());
        assertTrue(plan.blockers().stream().anyMatch(b -> b.where().contains("accueil.yml")),
                plan.blockers().toString());
    }

    @Test
    @DisplayName("Rien ni dans la source ni sur le serveur : rien à supprimer")
    void nothingAnywhere() {
        assertFalse(plan("fantome", fresh(HELP)).deletable());
    }

    // ---- Source -------------------------------------------------------------------------------

    @Test
    @DisplayName("Un dialogue source est supprimé après sauvegarde")
    void aSourceDialogueIsDeletedWithBackup() throws IOException {
        Path file = tempDir.resolve("dialogues").resolve("help.yml");
        Files.writeString(file, "id: rpgquest:help\nstart: a\n");
        DeletionPlan plan = plan("help", fresh(HELP));

        assertTrue(plan.deletable(), plan.blockers().toString());
        var result = pages.apply(plan, "help");
        assertTrue(result.ok(), result.message());
        assertFalse(Files.exists(file));
        assertTrue(result.backupDir() != null && Files.exists(Path.of(result.backupDir())));
    }

    @Test
    @DisplayName("L'exemple embarqué guard est signalé avec sa pierre tombale")
    void theBundledGuardIsAnnounced() {
        DeletionPlan plan = plan("guard", fresh(dialogue("rpgquest:guard", List.of(), List.of(), null)));
        assertTrue(plan.bundledExample());
        assertTrue(pages.preview(plan, "jeton", null, "").contains("pierre tombale"));
    }

    // ---- Quêtes : la référence serveur ---------------------------------------------------------

    private void writeQuest(String slug, String title) throws IOException {
        QuestDraft d = new QuestDraft();
        d.id = slug;
        d.title = title;
        d.description = "Quête de test.";
        d.category = "tutorial";
        d.icon = "BOOK";
        QuestDraft.Step step = new QuestDraft.Step("step1");
        step.objectives.add(new java.util.LinkedHashMap<>(java.util.Map.of(
                "kind", "KILL_ENTITY", "entity", "ZOMBIE", "amount", "1")));
        d.steps.add(step);
        Files.writeString(tempDir.resolve("quests").resolve(slug + ".yml"), QuestYaml.write(d));
    }

    @Test
    @DisplayName("#194 corrigé : une quête démarrée par un dialogue SERVEUR hors source est bloquée")
    void aQuestStartedByARuntimeOnlyDialogueIsBlocked() throws IOException {
        writeQuest("help_recolte", "Récolte");
        DeletionPlan plan = pages.plan("quests", "help_recolte", true, true, fresh(HELP), NOW);

        assertFalse(plan.deletable(), "avant #248, l'aperçu disait « aucune référence »");
        assertTrue(plan.blockers().stream().anyMatch(b -> b.reason().contains("rpgquest:help")
                && b.reason().contains("hors source")), plan.blockers().toString());
    }

    @Test
    @DisplayName("Une quête ne se supprime plus sur un relevé des dialogues absent")
    void aQuestDeletionWithoutDialogueSurveyIsIncomplete() throws IOException {
        writeQuest("solo", "Solo");
        DeletionPlan plan = pages.plan("quests", "solo", false, true,
                RuntimeDialogueInventory.unavailable(), NOW);
        assertFalse(plan.deletable());
        assertTrue(plan.blockers().get(0).reason().contains("Vérification incomplète"));
    }

    @Test
    @DisplayName("Une quête sans référence se supprime toujours avec un relevé frais")
    void aFreeQuestStaysDeletable() throws IOException {
        writeQuest("solo", "Solo");
        assertTrue(pages.plan("quests", "solo", false, true, fresh(HELP), NOW).deletable());
    }

    @Test
    @DisplayName("Le titre MiniMessage d'une quête est rendu, pas affiché en balises")
    void miniMessageTitleIsRendered() throws IOException {
        writeQuest("doree", "<gold>Quête dorée</gold>");
        String html = pages.preview(pages.plan("quests", "doree", false, true, fresh(), NOW),
                "jeton", null, "");
        assertFalse(html.contains("&lt;gold&gt;"), "balise affichée littéralement");
        assertTrue(html.contains("Quête dorée"), html);
    }
}
