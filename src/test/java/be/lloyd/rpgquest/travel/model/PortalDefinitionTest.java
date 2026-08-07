package be.lloyd.rpgquest.travel.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import be.lloyd.rpgquest.quest.model.QuestState;
import org.bukkit.NamespacedKey;
import org.junit.jupiter.api.Test;

class PortalDefinitionTest {

    private PortalDefinition portal(String id, int minX, int minZ, int maxX, int maxZ) {
        return new PortalDefinition(id, "world", minX, 60, minZ, maxX, 63, maxZ,
                null, 3, 5, null, null, null, null, null);
    }

    @Test
    void invertedBoundsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> portal("bad", 10, 10, 0, 0));
    }

    @Test
    void negativeChannelOrCooldownIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new PortalDefinition(
                "p", "world", 0, 0, 0, 1, 1, 1, null, -1, 5, null, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new PortalDefinition(
                "p", "world", 0, 0, 0, 1, 1, 1, null, 3, -1, null, null, null, null, null));
    }

    @Test
    void nonPositiveCostIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new PortalDefinition(
                "p", "world", 0, 0, 0, 1, 1, 1, null, 3, 5, null, null, null, null, 0L));
        assertThrows(IllegalArgumentException.class, () -> new PortalDefinition(
                "p", "world", 0, 0, 0, 1, 1, 1, null, 3, 5, null, null, null, null, -5L));
    }

    @Test
    void questStateDefaultsToCompletedWhenOmitted() {
        NamespacedKey quest = new NamespacedKey("rpgquest", "first_steps");
        PortalDefinition portal = new PortalDefinition(
                "p", "world", 0, 0, 0, 1, 1, 1, null, 3, 5, null, quest, null, null, null);

        assertEquals(QuestState.COMPLETED, portal.requiredQuestState());
    }

    @Test
    void containsIsInclusiveOfBounds() {
        PortalDefinition portal = portal("gate", 0, 0, 10, 10);

        assertTrue(portal.contains("world", 0, 61, 0));
        assertTrue(portal.contains("world", 10, 61, 10));
        assertFalse(portal.contains("world", 11, 61, 0));
        assertFalse(portal.contains("other_world", 0, 61, 0));
    }

    @Test
    void overlapsOnlyWithinTheSameWorld() {
        PortalDefinition a = portal("a", 0, 0, 10, 10);
        PortalDefinition b = portal("b", 5, 5, 15, 15);
        PortalDefinition c = new PortalDefinition("c", "other_world", 0, 60, 0, 10, 63, 10,
                null, 3, 5, null, null, null, null, null);

        assertTrue(a.overlaps(b));
        assertFalse(a.overlaps(c));
    }

    @Test
    void hasDestinationReflectsDestinationId() {
        assertFalse(portal("p", 0, 0, 1, 1).hasDestination());
        PortalDefinition withDestination = new PortalDefinition(
                "p", "world", 0, 0, 0, 1, 1, 1, "village", 3, 5, null, null, null, null, null);
        assertTrue(withDestination.hasDestination());
    }
}
