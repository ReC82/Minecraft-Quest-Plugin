package com.lodygames.rpgquest.quest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.quest.model.CollectItemObjective;
import com.lodygames.rpgquest.quest.model.CraftItemObjective;
import com.lodygames.rpgquest.quest.model.ExperienceReward;
import com.lodygames.rpgquest.quest.model.ItemReward;
import com.lodygames.rpgquest.quest.model.KillEntityObjective;
import com.lodygames.rpgquest.quest.model.QuestDefinition;
import com.lodygames.rpgquest.quest.model.QuestReward;
import com.lodygames.rpgquest.quest.model.VariableReward;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;

/**
 * Filet de sécurité pour les quêtes livrées dans le jar ({@code src/main/resources/quests/}) :
 * même esprit que {@code BundledDialoguesValidityTest} côté dialogues. Couvre en particulier la
 * chaîne de paliers du Garde (issue #179) : chargement sans erreur, quatre objectifs KILL_ENTITY
 * simultanés par quête, prérequis chaînés palier par palier, entitlement CLAIM_TIER_n correct.
 */
class BundledQuestsValidityTest {

    private static final Path BUNDLED_QUESTS_DIR = Path.of("src", "main", "resources", "quests");

    private final QuestLoader loader = new QuestLoader();

    @Test
    void everyBundledQuestLoadsWithoutIssue() {
        QuestLoadReport report = loader.loadDirectory(BUNDLED_QUESTS_DIR);

        assertTrue(report.issues().isEmpty(), () -> "problèmes de chargement : " + report.issues());
    }

    /**
     * Issue #13, étape 4 : chaque PNJ récurrent de biome (npc_id {@code encounter_*}) donne une quête
     * secondaire modeste, renouvelable avec délai (anti-farm), et son dialogue ne la démarre QUE depuis
     * un bouton « Commencer (j'accepte) » affiché sous {@code QUEST_AVAILABLE} — jamais sans acceptation explicite.
     */
    @Test
    void encounterQuestsAreRenewableWithCooldownAndOnlyStartedFromAnExplicitAccept() {
        Map<NamespacedKey, QuestDefinition> byId = loader.loadDirectory(BUNDLED_QUESTS_DIR).loaded().stream()
                .collect(Collectors.toMap(QuestDefinition::id, q -> q));
        Map<String, String> questByGiver = Map.of(
                "encounter_bandit", "encounter_bandit_spiders",
                "encounter_merchant", "encounter_merchant_cactus",
                "encounter_smith", "encounter_smith_ore");

        questByGiver.forEach((giver, questKey) -> {
            QuestDefinition quest = byId.get(new NamespacedKey("rpgquest", questKey));
            assertTrue(quest != null, () -> "quête manquante : " + questKey);
            assertEquals(giver, quest.giver());
            assertEquals("encounter", quest.category());
            assertTrue(quest.repeatable(), () -> questKey + " doit être renouvelable");
            assertTrue(quest.cooldown().compareTo(java.time.Duration.ofHours(1)) >= 0,
                    () -> questKey + " doit avoir un délai anti-farm");
            assertTrue(quest.prerequisites().isEmpty(), () -> questKey + " : aucune chaîne, quête secondaire");

            org.bukkit.configuration.file.YamlConfiguration dialogue = org.bukkit.configuration.file.YamlConfiguration
                    .loadConfiguration(Path.of("src", "main", "resources", "dialogues", giver + ".yml").toFile());
            org.bukkit.configuration.ConfigurationSection nodes = dialogue.getConfigurationSection("nodes");
            int starts = 0;
            for (String nodeId : nodes.getKeys(false)) {
                for (Map<?, ?> choice : nodes.getMapList(nodeId + ".choices")) {
                    Object actions = choice.get("actions");
                    if (actions != null && actions.toString().contains("START_QUEST")) {
                        starts++;
                        assertEquals("Commencer (j'accepte)", choice.get("text"), giver + " : START_QUEST hors du bouton d'acceptation");
                        assertTrue(actions.toString().contains("rpgquest:" + questKey));
                    }
                }
            }
            assertEquals(1, starts, giver + " : un seul point de démarrage");
            assertTrue(nodes.getMapList("greeting.choices").toString().contains("QUEST_AVAILABLE"),
                    giver + " : l'offre doit être conditionnée par QUEST_AVAILABLE");
        });
    }

    @Test
    void guardTierChainHasFiveSequentialQuests() {
        Map<NamespacedKey, QuestDefinition> byId = loader.loadDirectory(BUNDLED_QUESTS_DIR).loaded().stream()
                .collect(Collectors.toMap(QuestDefinition::id, q -> q));

        for (int tier = 1; tier <= 5; tier++) {
            NamespacedKey id = new NamespacedKey("rpgquest", "guard_tier" + tier);
            QuestDefinition quest = byId.get(id);
            assertTrue(quest != null, () -> "quête manquante : " + id);

            assertEquals("guard", quest.giver(), "chaque palier doit être donné par le Garde : " + id);
            assertEquals(false, quest.repeatable(), "un palier ne doit pas être rejouable : " + id);

            if (tier == 1) {
                assertTrue(quest.prerequisites().isEmpty(), "le palier 1 ne doit avoir aucun prérequis");
            } else {
                assertEquals(List.of(new NamespacedKey("rpgquest", "guard_tier" + (tier - 1))),
                        quest.prerequisites(), "le palier " + tier + " doit exiger le palier précédent");
            }

            assertEquals(1, quest.steps().size(), "une seule étape attendue pour le palier " + tier);
            List<KillEntityObjective> objectives = quest.steps().get(0).objectives().stream()
                    .map(o -> (KillEntityObjective) o)
                    .toList();
            int expectedAmount = 5 * (int) Math.pow(2, tier - 1);
            assertEquals(java.util.Set.of(EntityType.SPIDER, EntityType.ZOMBIE, EntityType.SKELETON, EntityType.CREEPER),
                    objectives.stream().map(KillEntityObjective::entity).collect(Collectors.toSet()),
                    "les quatre menaces attendues pour le palier " + tier);
            int currentTier = tier;
            for (KillEntityObjective objective : objectives) {
                assertEquals(expectedAmount, objective.amount(),
                        () -> "quantité attendue pour " + objective.entity() + " au palier " + currentTier);
            }

            String expectedVariable = "CLAIM_TIER_" + tier;
            boolean grantsEntitlement = quest.rewards().stream()
                    .anyMatch(r -> r instanceof VariableReward v
                            && v.key().equals(expectedVariable) && v.value().equals("true"));
            assertTrue(grantsEntitlement, "le palier " + tier + " doit accorder l'entitlement " + expectedVariable);
        }
    }

    /**
     * Issue #34 : la quête de test de Tania, restaurée depuis la définition chargée sur DEV (absente
     * du dépôt). Même id technique, mêmes étapes, objectifs et récompenses que DEV — seuls le donneur
     * et le nom visible (« Tania » au lieu de « Help ») changent.
     */
    @Test
    void helpRecolteIsRestoredIdenticalToTheDevDefinition() {
        QuestDefinition quest = loader.loadDirectory(BUNDLED_QUESTS_DIR).loaded().stream()
                .filter(q -> q.id().equals(new NamespacedKey("rpgquest", "help_recolte")))
                .findFirst().orElseThrow();

        assertEquals("test", quest.category());
        assertEquals(Material.OAK_LOG, quest.icon());
        assertTrue(quest.repeatable());
        assertTrue(quest.prerequisites().isEmpty());
        assertEquals("help", quest.giver());
        assertEquals(List.of("ramasser_bois", "fabriquer_planches"),
                quest.steps().stream().map(step -> step.id()).toList());
        assertEquals(List.of(new CollectItemObjective(Material.OAK_LOG, 5)), quest.steps().get(0).objectives());
        assertEquals(List.of(new CraftItemObjective(Material.OAK_PLANKS, 4)), quest.steps().get(1).objectives());
        assertEquals(List.of(new ExperienceReward(25), new ItemReward(Material.BREAD, 3)), quest.rewards());

        assertTrue(quest.title().base().contains("Tania"), quest.title().base());
        assertTrue(quest.description().base().contains("%npc_name:help%"), quest.description().base());
        assertTrue(!(quest.title().base() + quest.description().base()).contains("Help"), "plus de « Help » visible");
    }
}
