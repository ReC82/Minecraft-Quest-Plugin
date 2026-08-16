package com.lodygames.rpgquest.quest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.quest.model.ObjectiveType;
import com.lodygames.rpgquest.quest.model.QuestDefinition;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * {@code docs/manual-tests/quests/} contient une quête par type d'objectif implémenté, destinée à
 * être copiée manuellement dans {@code plugins/RPGQuest/quests/} le temps d'un test sur un serveur
 * réel (voir {@code docs/MANUAL_TEST_PLAN.md}). Ce test garantit que ce pack reste chargeable et
 * couvre bien exactement les 7 types, même si le format de quête ou {@link ObjectiveType} évolue.
 */
class ManualTestQuestPackTest {

    private static final Path MANUAL_TEST_QUESTS_DIR = Path.of("docs", "manual-tests", "quests");

    private final QuestLoader loader = new QuestLoader();

    @Test
    void everyManualTestQuestLoadsWithoutError() {
        QuestLoadReport report = loader.loadDirectory(MANUAL_TEST_QUESTS_DIR);

        assertTrue(report.issues().isEmpty(),
                () -> "le pack de quêtes de test manuel doit rester chargeable sans erreur : " + report.issues());
        assertEquals(ObjectiveType.values().length, report.loaded().size(),
                "une quête de test par type d'objectif implémenté, ni plus ni moins");
    }

    @Test
    void everyManualTestQuestIdIsClearlyMarkedAsATestQuest() {
        QuestLoadReport report = loader.loadDirectory(MANUAL_TEST_QUESTS_DIR);

        for (QuestDefinition quest : report.loaded()) {
            assertTrue(quest.id().getKey().startsWith("test_"),
                    () -> "id de quête de test attendu avec le préfixe « test_ », obtenu : " + quest.id());
        }
    }

    @Test
    void everyObjectiveTypeIsCoveredExactlyOnce() {
        QuestLoadReport report = loader.loadDirectory(MANUAL_TEST_QUESTS_DIR);

        Set<ObjectiveType> covered = report.loaded().stream()
                .flatMap(quest -> quest.steps().stream())
                .flatMap(step -> step.objectives().stream())
                .map(objective -> objective.type())
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(ObjectiveType.class)));

        assertEquals(EnumSet.allOf(ObjectiveType.class), covered,
                "chaque type d'objectif réellement implémenté doit avoir sa quête de test manuel, aucun type inventé");
    }
}
