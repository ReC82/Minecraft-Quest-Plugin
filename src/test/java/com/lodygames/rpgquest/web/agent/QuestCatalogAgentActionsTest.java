package com.lodygames.rpgquest.web.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.encounter.catalog.QuestCatalog;
import com.lodygames.rpgquest.encounter.model.EncounterTemplate;
import com.lodygames.rpgquest.quest.model.KillEntityObjective;
import com.lodygames.rpgquest.quest.model.LocalizedText;
import com.lodygames.rpgquest.quest.model.QuestDefinition;
import com.lodygames.rpgquest.quest.model.QuestStep;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;

/** Actions {@code quest.catalog.*} (issue #369) : validation des champs, délégation, contrôles de la vue. */
class QuestCatalogAgentActionsTest {

    private final List<QuestCatalog> saved = new ArrayList<>();
    private final List<String> deleted = new ArrayList<>();
    private final List<String> assigned = new ArrayList<>();
    private boolean saveOk = true;

    private final StubAgentActions actions = new StubAgentActions() {
        @Override
        public CompletableFuture<MutationResult> questCatalogSave(QuestCatalog catalog, boolean create) {
            saved.add(catalog);
            return CompletableFuture.completedFuture(MutationResult.of(saveOk, create ? "CREATED" : "UPDATED", "ok"));
        }

        @Override
        public CompletableFuture<MutationResult> questCatalogAssign(String catalogId, Set<String> templateIds) {
            assigned.add(catalogId + ":" + String.join(",", templateIds));
            return CompletableFuture.completedFuture(MutationResult.of(true, "ASSIGNED", "ok"));
        }

        @Override
        public CompletableFuture<MutationResult> questCatalogDelete(String catalogId, boolean detach) {
            deleted.add(catalogId + ":" + detach);
            return CompletableFuture.completedFuture(MutationResult.of(true, "DELETED", "ok"));
        }
    };

    private final AgentActionExecutor executor = new AgentActionExecutor(
            ref -> CompletableFuture.completedFuture(Optional.empty()),
            (uuid, key) -> CompletableFuture.completedFuture(Optional.empty()), actions);

    private AgentActionOutcome run(String type, Map<String, String> params) {
        return executor.execute(new AgentAction("a1", type, params)).join();
    }

    @Test
    void formFieldsBecomeAnOrderedCatalog() {
        AgentActionOutcome outcome = run("quest.catalog.create", Map.of("id", "bandit", "display_name", "Contrats",
                "avoid_recent", "2", "entries", "spiders|5|on, rpgquest:loot||off ,other:x|"));

        assertEquals(AgentActionOutcome.SUCCESS, outcome.status(), outcome.message());
        assertEquals(new QuestCatalog("bandit", "Contrats", null, true, 2, List.of(
                new QuestCatalog.Entry("rpgquest:spiders", 5, true),
                new QuestCatalog.Entry("rpgquest:loot", 10, false),
                new QuestCatalog.Entry("other:x", 10, true))), saved.get(0));
    }

    /** Issue #414 — les modèles ne sont fixés que sur demande explicite, et après un catalogue écrit. */
    @Test
    void templatesAreAssignedOnlyWhenAskedAndAfterASuccessfulSave() {
        run("quest.catalog.update", Map.of("id", "bandit", "display_name", "C", "entries", "a"));
        assertTrue(assigned.isEmpty(), "sans assign_templates, les modèles ne sont pas touchés");

        AgentActionOutcome outcome = run("quest.catalog.create", Map.of("id", "forest", "display_name", "Forêt",
                "entries", "a", "assign_templates", "true", "templates", "forest_bandit,mountain_smith"));
        assertEquals(AgentActionOutcome.SUCCESS, outcome.status(), outcome.message());
        assertEquals(List.of("forest:forest_bandit,mountain_smith"), assigned);

        run("quest.catalog.update", Map.of("id", "forest", "display_name", "Forêt", "entries", "a",
                "assign_templates", "true", "templates", ""));
        assertEquals("forest:", assigned.get(1), "liste vide = retiré de tous les modèles");

        saveOk = false;
        run("quest.catalog.update", Map.of("id", "forest", "display_name", "Forêt", "entries", "a",
                "assign_templates", "true", "templates", "forest_bandit"));
        assertEquals(2, assigned.size(), "catalogue refusé : aucun modèle modifié");
        assertFalse(AgentActionOutcome.SUCCESS.equals(run("quest.catalog.update", Map.of("id", "forest",
                "display_name", "F", "assign_templates", "true", "templates", "Bad Id")).status()));
    }

    @Test
    void invalidFormsAreRejectedBeforeAnyWrite() {
        assertFalse(AgentActionOutcome.SUCCESS.equals(run("quest.catalog.create",
                Map.of("id", "bandit", "display_name", "X", "entries", "a|0|on")).status()));
        assertFalse(AgentActionOutcome.SUCCESS.equals(run("quest.catalog.update",
                Map.of("id", "Bad Id", "display_name", "X")).status()));
        assertFalse(AgentActionOutcome.SUCCESS.equals(run("quest.catalog.create",
                Map.of("id", "bandit", "display_name", "X", "entries", "a,a")).status()), "doublon refusé");
        assertTrue(saved.isEmpty());
    }

    @Test
    void deleteForwardsTheDetachConfirmation() {
        run("quest.catalog.delete", Map.of("id", "bandit"));
        run("quest.catalog.delete", Map.of("id", "bandit", "detach", "true"));
        assertFalse(AgentActionOutcome.SUCCESS.equals(run("quest.catalog.delete", Map.of()).status()));
        assertEquals(List.of("bandit:false", "bandit:true"), deleted);
    }

    @Test
    void theViewFlagsMissingQuestsUnknownCatalogsAndDialoguesWithoutOffer() {
        QuestCatalog catalog = new QuestCatalog("bandit", "Contrats", null, true, 1, List.of(
                new QuestCatalog.Entry("rpgquest:spiders", 10, true),
                new QuestCatalog.Entry("rpgquest:ghost", 10, true)));
        EncounterTemplate user = template("forest_bandit", "encounter_bandit", List.of("bandit", "nope"));
        EncounterTemplate plain = template("desert_merchant", "encounter_merchant", List.of());

        AgentActions.QuestCatalogListView view = QuestCatalogViews.build(List.of(catalog), List.of(),
                List.of(quest("spiders")), List.of(user, plain), npc -> false);

        AgentActions.QuestCatalogSummary row = view.catalogs().get(0);
        assertEquals(List.of("forest_bandit"), row.usedBy());
        assertTrue(row.entries().get(0).exists());
        assertEquals("Toiles", row.entries().get(0).title(), "titre sans balises MiniMessage");
        assertEquals("20 h", row.entries().get(0).cooldown());
        assertFalse(row.entries().get(1).exists());
        assertTrue(row.warnings().stream().anyMatch(w -> w.contains("rpgquest:ghost")));
        assertTrue(view.warnings().stream().anyMatch(w -> w.contains("« nope »")));
        assertTrue(view.warnings().stream().anyMatch(w -> w.contains("OFFER_CATALOG_QUEST")));
        assertTrue(view.warnings().stream().anyMatch(w -> w.contains("desert_merchant")));
        assertEquals("rpgquest:spiders", view.quests().get(0).id());
        assertEquals(2, view.templates().size(), "modèles listés pour le choix des PNJ (#414)");
        assertEquals(List.of("bandit", "nope"), view.templates().get(0).questCatalogs());
        assertFalse(view.templates().get(0).offersCatalog());
    }

    private static EncounterTemplate template(String id, String npcId, List<String> catalogs) {
        return new EncounterTemplate(id, id, null, npcId, null, null, Set.of("minecraft:forest"), List.of(), 10, 0,
                null, true, catalogs);
    }

    private static QuestDefinition quest(String key) {
        return new QuestDefinition(new NamespacedKey("rpgquest", key), LocalizedText.of("<gold>Toiles</gold>"),
                LocalizedText.of("Desc"), "encounter", Material.STRING, true, false, List.of(),
                List.of(new QuestStep("s", List.of(new KillEntityObjective(EntityType.SPIDER, 6)))), List.of(),
                Map.of(), "encounter_bandit", Duration.ofHours(20));
    }
}
