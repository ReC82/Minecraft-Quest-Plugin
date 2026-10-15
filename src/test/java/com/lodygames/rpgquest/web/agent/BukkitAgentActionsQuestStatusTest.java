package com.lodygames.rpgquest.web.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.lodygames.rpgquest.quest.model.DeliverItemToNpcObjective;
import com.lodygames.rpgquest.quest.model.KillEntityObjective;
import com.lodygames.rpgquest.quest.model.LocalizedText;
import com.lodygames.rpgquest.quest.model.QuestDefinition;
import com.lodygames.rpgquest.quest.model.QuestStep;
import com.lodygames.rpgquest.quest.progress.ObjectiveProgressView;
import com.lodygames.rpgquest.quest.progress.QuestStepProgressView;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

/**
 * Issue #79 — {@code quest.player.status} : chaque ligne de progression porte son objectif
 * structuré, rapproché de la définition de l'étape courante dans le même ordre.
 */
class BukkitAgentActionsQuestStatusTest {

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private static QuestDefinition quest() {
        return new QuestDefinition(
                new NamespacedKey("rpgquest", "crystal_hunt"),
                LocalizedText.of("La chasse aux cristaux"),
                LocalizedText.of("Description"),
                "story",
                Material.BOOK,
                false,
                false,
                List.of(),
                List.of(new QuestStep("hunt", List.of(
                        new KillEntityObjective(EntityType.SPIDER, 5),
                        new DeliverItemToNpcObjective("guard", Material.LEATHER, 3)))),
                List.of(),
                Map.of(),
                null);
    }

    @Test
    void eachProgressLineCarriesItsStructuredObjectiveAndState() {
        QuestStepProgressView view = new QuestStepProgressView("hunt", List.of(
                new ObjectiveProgressView("Tuer SPIDER", 2, 5),
                new ObjectiveProgressView("Rapporter LEATHER à guard", 3, 3)));

        List<AgentActions.ObjectiveState> states = BukkitAgentActions.objectiveStates(quest(), view);

        assertEquals(2, states.size());
        AgentActions.ObjectiveState kill = states.get(0);
        assertEquals("Tuer SPIDER", kill.description(), "le champ legacy reste émis");
        assertEquals("KILL_ENTITY", kill.detail().kind());
        assertEquals("SPIDER", kill.detail().target());
        assertEquals(5, kill.detail().amount());
        assertEquals("IN_PROGRESS", kill.state());

        AgentActions.ObjectiveState deliver = states.get(1);
        assertEquals("DELIVER_ITEM_TO_NPC", deliver.detail().kind());
        assertEquals("LEATHER", deliver.detail().target());
        assertEquals("guard", deliver.detail().npc());
        assertEquals("COMPLETED", deliver.state());
    }

    @Test
    void misalignedStepKeepsLinesWithoutStructureRatherThanMislabellingThem() {
        QuestStepProgressView unknownStep = new QuestStepProgressView("removed_step", List.of(
                new ObjectiveProgressView("Tuer SPIDER", 0, 5)));
        AgentActions.ObjectiveState line = BukkitAgentActions.objectiveStates(quest(), unknownStep).get(0);
        assertNull(line.detail());
        assertEquals("NOT_STARTED", line.state());

        QuestStepProgressView wrongCount = new QuestStepProgressView("hunt", List.of(
                new ObjectiveProgressView("Tuer SPIDER", 1, 5)));
        assertNull(BukkitAgentActions.objectiveStates(quest(), wrongCount).get(0).detail());
    }
}
