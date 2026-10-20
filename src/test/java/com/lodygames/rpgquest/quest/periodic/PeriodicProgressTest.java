package com.lodygames.rpgquest.quest.periodic;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.lodygames.rpgquest.database.PeriodicQuestAssignmentRepository;
import com.lodygames.rpgquest.database.PeriodicQuestAssignmentRepository.Assignment;
import com.lodygames.rpgquest.quest.model.QuestPeriod;
import com.lodygames.rpgquest.quest.model.QuestState;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.bukkit.NamespacedKey;
import org.junit.jupiter.api.Test;

/** Issue #15 (retour TC-350) : avancement calculé sur les attributions réelles de la période. */
class PeriodicProgressTest {

    private static final NamespacedKey WOOD = new NamespacedKey("rpgquest", "daily_wood");
    private static final NamespacedKey HUNT = new NamespacedKey("rpgquest", "daily_hunt");
    private static final NamespacedKey MINE = new NamespacedKey("rpgquest", "daily_mine");

    private static Assignment assignment(int slot, NamespacedKey quest, String status) {
        return new Assignment("daily", "2026-10-20", slot, quest.toString(), status, Instant.EPOCH);
    }

    @Test
    void countsCompletedAgainstTheRealAssignmentsOfThePeriod() {
        PeriodicProgress progress = PeriodicProgress.of(QuestPeriod.DAILY,
                List.of(assignment(0, WOOD, PeriodicQuestAssignmentRepository.STARTED),
                        assignment(1, HUNT, PeriodicQuestAssignmentRepository.STARTED)),
                // MINE est un modèle terminé lors d'une AUTRE période : il ne compte pas.
                Map.of(WOOD, QuestState.COMPLETED, HUNT, QuestState.ACTIVE, MINE, QuestState.COMPLETED));

        assertEquals(2, progress.total());
        assertEquals(List.of(HUNT), progress.toDo());
        assertEquals(List.of(WOOD), progress.completed());
        assertEquals("Quotidiennes : 1/2 terminée", progress.shortSummary());
        assertEquals("Quotidiennes : 1/2 terminée — 1 restante", progress.summary());
    }

    @Test
    void anAssignmentNotYetStartedIsNeverCompletedEvenWithAStaleState() {
        PeriodicProgress progress = PeriodicProgress.of(QuestPeriod.DAILY,
                List.of(assignment(0, WOOD, PeriodicQuestAssignmentRepository.ASSIGNED)),
                Map.of(WOOD, QuestState.COMPLETED));

        assertEquals(List.of(WOOD), progress.toDo());
        assertEquals(QuestState.NOT_STARTED, progress.stateOf(WOOD));
        assertEquals("Quotidiennes : 0/1 terminée — 1 restante", progress.summary());
    }

    @Test
    void wordingAgreesWithTheCounts() {
        PeriodicProgress allDone = PeriodicProgress.of(QuestPeriod.WEEKLY,
                List.of(assignment(0, WOOD, PeriodicQuestAssignmentRepository.STARTED),
                        assignment(1, HUNT, PeriodicQuestAssignmentRepository.STARTED)),
                Map.of(WOOD, QuestState.COMPLETED, HUNT, QuestState.COMPLETED));
        assertEquals("Hebdomadaires : 2/2 terminées — aucune restante", allDone.summary());

        PeriodicProgress none = PeriodicProgress.of(QuestPeriod.WEEKLY,
                List.of(assignment(0, WOOD, PeriodicQuestAssignmentRepository.STARTED),
                        assignment(1, HUNT, PeriodicQuestAssignmentRepository.STARTED)),
                Map.of());
        assertEquals("Hebdomadaires : 0/2 terminée — 2 restantes", none.summary());

        assertEquals("Quotidiennes : aucune quête",
                PeriodicProgress.of(QuestPeriod.DAILY, List.of(), Map.of()).summary());
    }
}
