package com.lodygames.rpgquest.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.quest.model.LocalizedText;
import com.lodygames.rpgquest.quest.model.QuestDefinition;
import com.lodygames.rpgquest.quest.model.QuestState;
import com.lodygames.rpgquest.quest.model.QuestStep;
import com.lodygames.rpgquest.quest.model.TalkToNpcObjective;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.junit.jupiter.api.Test;

/** Issue #13 — lignes du journal propres aux quêtes de rencontre renouvelables. Pur JUnit. */
class JournalReplayTextTest {

    private static QuestDefinition quest(String category, String giver, boolean repeatable, Duration cooldown) {
        return new QuestDefinition(NamespacedKey.fromString("rpgquest:demo"), LocalizedText.of("Démo"),
                LocalizedText.of("d"), category, Material.BOOK, repeatable, false, List.of(),
                List.of(new QuestStep("s1", List.of(new TalkToNpcObjective("guide")))), List.of(), Map.of(),
                giver, cooldown);
    }

    @Test
    void encounterIsRecognisedByCategoryOrByGiver() {
        assertTrue(JournalReplayText.isEncounter(quest("encounter", null, true, Duration.ZERO)));
        assertTrue(JournalReplayText.isEncounter(quest("Encounter ", null, true, Duration.ZERO)));
        assertTrue(JournalReplayText.isEncounter(quest("side", "encounter_bandit", true, Duration.ZERO)));
        assertFalse(JournalReplayText.isEncounter(quest("tutorial", "guide", false, Duration.ZERO)));
        assertEquals(Optional.empty(), JournalReplayText.encounterTag(quest("tutorial", "guide", false, Duration.ZERO)));
    }

    @Test
    void replayLineOnlyConcernsCompletedRepeatableQuests() {
        QuestDefinition encounter = quest("encounter", "encounter_bandit", true, Duration.ofHours(20));
        assertEquals(Optional.empty(), JournalReplayText.replayLine(encounter, QuestState.ACTIVE, Duration.ZERO));
        assertEquals(Optional.empty(), JournalReplayText.replayLine(
                quest("tutorial", null, false, Duration.ZERO), QuestState.COMPLETED, Duration.ZERO));

        assertTrue(JournalReplayText.replayLine(encounter, QuestState.COMPLETED, Duration.ofMinutes(200))
                .orElseThrow().contains("3 h 20 min"));
        assertTrue(JournalReplayText.replayLine(encounter, QuestState.COMPLETED, Duration.ZERO)
                .orElseThrow().contains("PNJ de rencontre"));
        assertTrue(JournalReplayText.replayLine(encounter, QuestState.COMPLETED, null)
                .orElseThrow().contains("Peut être refaite"));
    }

    @Test
    void cadenceIsShownOnlyForRepeatableQuestsWithADelay() {
        assertTrue(JournalReplayText.cadenceLine(quest("encounter", null, true, Duration.ofHours(20)))
                .orElseThrow().contains("toutes les 20 h"));
        assertEquals(Optional.empty(), JournalReplayText.cadenceLine(quest("encounter", null, true, Duration.ZERO)));
    }
}
