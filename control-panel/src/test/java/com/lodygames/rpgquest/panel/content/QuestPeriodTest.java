package com.lodygames.rpgquest.panel.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Issue #15 : un modèle de quête quotidienne/hebdomadaire ({@code period:}) survit à une édition
 * dans l'éditeur guidé, et le validateur applique les mêmes règles que le moteur.
 */
class QuestPeriodTest {

    private static QuestDraft quest(String period) {
        QuestDraft d = new QuestDraft();
        d.id = "daily_demo_bucheron";
        d.title = "Bois du jour";
        d.description = "Desc";
        d.category = "daily";
        d.icon = "OAK_LOG";
        d.period = period;
        QuestDraft.Step step = new QuestDraft.Step("couper");
        Map<String, String> objective = new LinkedHashMap<>();
        objective.put("kind", "BREAK_BLOCK");
        objective.put("material", "OAK_LOG");
        objective.put("amount", "16");
        step.objectives.add(objective);
        d.steps.add(step);
        return d;
    }

    private static List<String> errorFields(QuestDraft d) {
        return QuestValidator.validate(d, RefData.empty()).stream()
                .filter(x -> x.level() == Diagnostic.Level.ERROR).map(Diagnostic::field).toList();
    }

    @Test
    void periodIsWrittenAsARepeatableQuestAndReadBackIdentically() {
        String yaml = QuestYaml.write(quest("daily"));
        assertTrue(yaml.contains("repeatable: true\nperiod: daily\n"), yaml);
        assertEquals(List.of(), QuestYaml.roundTripProblems(yaml), yaml);
        assertEquals("daily", QuestYaml.read(yaml).draft().period);
    }

    @Test
    void aHandWrittenTemplateWithoutRepeatableKeepsItsPeriod() {
        QuestDraft read = QuestYaml.read("""
                id: rpgquest:weekly_x
                title: "Semaine"
                description: "Desc"
                category: weekly
                period: weekly
                steps:
                  - id: s
                    objectives:
                      - type: KILL_ENTITY
                        entity: ZOMBIE
                        amount: 3
                """).draft();
        assertEquals("weekly", read.period);
        assertTrue(read.repeatable, "le moteur la rend répétable d'office");
        assertTrue(QuestYaml.write(read).contains("period: weekly"));
    }

    @Test
    void anOrdinaryQuestWritesNoPeriod() {
        assertFalse(QuestYaml.write(quest("")).contains("period"));
    }

    @Test
    void validatorFollowsTheEngineRules() {
        assertFalse(errorFields(quest("daily")).contains("period"));
        assertTrue(errorFields(quest("monthly")).contains("period"));
        QuestDraft withCooldown = quest("weekly");
        withCooldown.cooldown = "20h";
        assertTrue(errorFields(withCooldown).contains("cooldown"));
        QuestDraft withPrerequisite = quest("daily");
        withPrerequisite.prerequisites.add("first_steps");
        assertTrue(errorFields(withPrerequisite).contains("prerequisites"));
    }
}
