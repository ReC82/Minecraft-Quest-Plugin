package com.lodygames.rpgquest.panel.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Issue #143 : génération des identifiants d'étape — jamais de doublon, jamais de renumérotation. */
class QuestStepIdsTest {

    private static QuestDraft withSteps(String... ids) {
        QuestDraft d = new QuestDraft();
        for (String id : ids) {
            d.steps.add(new QuestDraft.Step(id));
        }
        return d;
    }

    @Test
    void firstGeneratedIdIsStep1() {
        assertEquals("step_1", QuestDraft.nextStepId(List.of(), List.of()));
    }

    @Test
    void nextIdIsAboveTheHighestGeneratedOne() {
        assertEquals("step_4", QuestDraft.nextStepId(withSteps("step_1", "step_3").steps, List.of()));
    }

    @Test
    void handWrittenIdsAreKeptAndIgnoredByTheCounter() {
        QuestDraft d = withSteps("intro", "", "boss");
        d.assignMissingStepIds(List.of());
        assertEquals(List.of("intro", "step_1", "boss"), d.stepIds());
    }

    @Test
    void reservedIdsAreNeverReused() {
        assertEquals("step_3", QuestDraft.nextStepId(withSteps("step_1").steps, List.of("step_1", "step_2")));
    }

    @Test
    void severalBlankStepsGetDistinctIds() {
        QuestDraft d = withSteps("", "step_2", "");
        d.assignMissingStepIds(List.of());
        assertEquals(List.of("step_3", "step_2", "step_4"), d.stepIds());
    }

    @Test
    void renamedOrDeletedSavedIdsAreReported() {
        List<Diagnostic> diags = QuestValidator.stepIdChanges(withSteps("step_1", "intro"),
                List.of("step_1", "step_2"));
        assertEquals(1, diags.size());
        assertTrue(diags.get(0).message().contains("step_2"));
    }

    @Test
    void unchangedIdsReportNothing() {
        assertTrue(QuestValidator.stepIdChanges(withSteps("step_2", "step_1"), List.of("step_1", "step_2")).isEmpty());
    }
}
