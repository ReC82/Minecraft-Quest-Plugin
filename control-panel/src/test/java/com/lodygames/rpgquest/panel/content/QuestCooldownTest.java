package com.lodygames.rpgquest.panel.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Issue #13 : délai de ré-acceptation « cooldown: » dans l'éditeur guidé de quêtes. */
class QuestCooldownTest {

    private static QuestDraft quest(boolean repeatable, String cooldown) {
        QuestDraft d = new QuestDraft();
        d.id = "encounter_bandit_spiders";
        d.title = "Les toiles du sous-bois";
        d.description = "Desc";
        d.category = "encounter";
        d.icon = "STRING";
        d.repeatable = repeatable;
        d.cooldown = cooldown;
        QuestDraft.Step step = new QuestDraft.Step("clear_spiders");
        Map<String, String> objective = new LinkedHashMap<>();
        objective.put("kind", "KILL_ENTITY");
        objective.put("entity", "SPIDER");
        objective.put("amount", "6");
        step.objectives.add(objective);
        d.steps.add(step);
        return d;
    }

    private static boolean hasCooldownError(QuestDraft d) {
        return QuestValidator.validate(d, RefData.empty()).stream()
                .anyMatch(x -> "cooldown".equals(x.field()) && x.level() == Diagnostic.Level.ERROR);
    }

    @Test
    void cooldownIsWrittenAndReadBackIdentically() {
        String yaml = QuestYaml.write(quest(true, "20h"));
        assertTrue(yaml.contains("cooldown: \"20h\""), yaml);
        assertEquals(List.of(), QuestYaml.roundTripProblems(yaml), yaml);
        assertEquals("20h", QuestYaml.read(yaml).draft().cooldown);
    }

    @Test
    void noCooldownWritesNoLine() {
        String yaml = QuestYaml.write(quest(true, ""));
        assertFalse(yaml.contains("cooldown"), yaml);
        assertEquals(List.of(), QuestYaml.roundTripProblems(yaml), yaml);
    }

    @Test
    void validatorFollowsTheEngineRules() {
        assertFalse(hasCooldownError(quest(true, "20h")));
        assertFalse(hasCooldownError(quest(true, "3600")));
        assertFalse(hasCooldownError(quest(false, "")));
        assertTrue(hasCooldownError(quest(true, "vingt heures")));
        assertTrue(hasCooldownError(quest(false, "20h")), "délai sur une quête non répétable");
    }
}
