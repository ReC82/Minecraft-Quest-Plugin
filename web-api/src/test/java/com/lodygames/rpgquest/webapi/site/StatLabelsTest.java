package com.lodygames.rpgquest.webapi.site;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Unités accordées à la valeur sur les classements et la fiche (#56) : « 1 quête », jamais « 1 quêtes ». */
class StatLabelsTest {

    @Test
    void unitAgreesWithTheValue() {
        assertEquals("quête", StatLabels.unit("QUESTS_COMPLETED", 1));
        assertEquals("quêtes", StatLabels.unit("QUESTS_COMPLETED", 2));
        assertEquals("ville", StatLabels.unit("CITY_DISCOVERED", 0));
        assertEquals("XP", StatLabels.unit("RPG_XP_GLOBAL", 1));
        assertEquals("waypoints", StatLabels.unit("WAYPOINT_DISCOVERED", null));
    }

    @Test
    void completedQuestsOnTheProfileAgreeWithTheCount() {
        assertEquals("quête terminée", PlayersPageHandler.questsDone(1));
        assertEquals("quêtes terminées", PlayersPageHandler.questsDone(12));
    }
}
