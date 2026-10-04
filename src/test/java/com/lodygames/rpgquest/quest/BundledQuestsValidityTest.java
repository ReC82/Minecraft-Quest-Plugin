package com.lodygames.rpgquest.quest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.quest.model.KillEntityObjective;
import com.lodygames.rpgquest.quest.model.QuestDefinition;
import com.lodygames.rpgquest.quest.model.QuestReward;
import com.lodygames.rpgquest.quest.model.VariableReward;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
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
}
