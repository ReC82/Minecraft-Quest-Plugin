package com.lodygames.rpgquest.quest.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

/**
 * Issue #180 : la bossbar de la quête suivie et {@code /quest} nomment le PNJ par son nom affiché
 * (« Parler à Lily »), comme le journal (#475) et l'annonce des objectifs (#70).
 */
class QuestObjectiveDescribeTest {

    private static final Function<String, Optional<String>> NAMES =
            id -> Optional.ofNullable(Map.of("lily", "Lily", "julius", "Julius").get(id));

    @Test
    void aKnownNpcIsNamedByItsDisplayName() {
        assertEquals("Parler à Julius", QuestObjective.describe(new TalkToNpcObjective("julius"), NAMES));
        assertEquals("Remettre COAL à Lily",
                QuestObjective.describe(new DeliverItemToNpcObjective("lily", Material.COAL, 4), NAMES));
    }

    @Test
    void anUnknownNpcKeepsTheHistoricalText() {
        assertEquals(QuestObjective.describe(new TalkToNpcObjective("woodcutter_bob")),
                QuestObjective.describe(new TalkToNpcObjective("woodcutter_bob"), NAMES));
    }

    @Test
    void otherObjectivesAreUnchanged() {
        CollectItemObjective collect = new CollectItemObjective(Material.PUMPKIN, 1);
        assertEquals(QuestObjective.describe(collect), QuestObjective.describe(collect, NAMES));
    }
}
