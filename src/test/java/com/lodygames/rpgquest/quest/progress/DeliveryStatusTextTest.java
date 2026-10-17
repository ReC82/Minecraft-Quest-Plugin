package com.lodygames.rpgquest.quest.progress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.bukkit.Material;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

/** Issue #467 — {@code %delivery_status%} juste après une remise. */
class DeliveryStatusTextTest {

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private static final DeliveryOutcome COMPLETED = DeliveryOutcome.of(DeliveryOutcome.Status.DELIVERED,
            List.of(new DeliveryLine(Material.BREAD, 3, 3, 3), new DeliveryLine(Material.APPLE, 4, 4, 4)), true);

    @Test
    void withoutARecentDeliveryNothingChanges() {
        assertEquals(DeliveryStatusText.NOTHING_EXPECTED, DeliveryStatusText.render(List.of(), null));
    }

    @Test
    void aDeliveryThatEmptiedTheExpectationsShowsItsOwnSummary() {
        String text = DeliveryStatusText.render(List.of(), COMPLETED);

        assertTrue(text.contains("3/3") && text.contains("4/4"), text);
        assertTrue(text.endsWith(DeliveryStatusText.ALL_DELIVERED), text);
        assertTrue(!text.contains(DeliveryStatusText.NOTHING_EXPECTED), text);
    }

    @Test
    void anythingStillExpectedTakesPrecedence() {
        List<DeliveryLine> next = List.of(new DeliveryLine(Material.POPPY, 0, 3));
        assertEquals(DeliveryStatusText.render(next), DeliveryStatusText.render(next, COMPLETED));
    }

    @Test
    void anIncompleteOutcomeNeverClaimsEverythingWasDelivered() {
        DeliveryOutcome partial = DeliveryOutcome.of(DeliveryOutcome.Status.NOTHING_USEFUL,
                List.of(new DeliveryLine(Material.BREAD, 1, 3)), false);
        assertEquals(DeliveryStatusText.NOTHING_EXPECTED, DeliveryStatusText.render(List.of(), partial));
    }

    // ---- Issue #475 ----------------------------------------------------------------------------

    @Test
    void theHintIsEmptyWhenNothingIsExpectedSoAGreetingStaysUnchanged() {
        assertEquals("", DeliveryStatusText.hint(List.of(), false));
        assertEquals("", DeliveryStatusText.hint(List.of(new DeliveryLine(Material.STICK, 4, 4)), false));
    }

    @Test
    void theHintListsWhatIsMissingAndWhetherThePlayerCarriesSomeOfIt() {
        List<DeliveryLine> pending = List.of(new DeliveryLine(Material.STICK, 0, 4), new DeliveryLine(Material.LEATHER, 2, 5));

        String without = DeliveryStatusText.hint(pending, false);
        assertTrue(without.startsWith("<newline>") && without.contains("0/4") && without.contains("2/5")
                && without.endsWith(DeliveryStatusText.NOTHING_CARRIED), without);
        assertTrue(DeliveryStatusText.hint(pending, true).endsWith(DeliveryStatusText.CARRIES_USEFUL));
    }

    @Test
    void aRefusedDeliveryIsExplainedInTheDialogueNotOnlyInTheChat() {
        List<DeliveryLine> pending = List.of(new DeliveryLine(Material.STICK, 0, 4));
        DeliveryOutcome refused = DeliveryOutcome.of(DeliveryOutcome.Status.NOTHING_USEFUL, pending, false);

        String text = DeliveryStatusText.render(pending, false, refused, null);

        assertTrue(text.startsWith(DeliveryStatusText.NOTHING_USEFUL) && text.contains("0/4"), text);
    }

    @Test
    void aCompletedQuestIsAnnouncedAfterTheDeliveredAmounts() {
        DeliveryOutcome done = new DeliveryOutcome(DeliveryOutcome.Status.DELIVERED,
                List.of(new DeliveryLine(Material.STICK, 4, 4, 1)), true,
                List.of(new org.bukkit.NamespacedKey("rpgquest", "kit_tier3")));

        String text = DeliveryStatusText.render(List.of(), false, done, id -> "Amélioration du kit obtenue : palier 3.");

        assertTrue(text.startsWith(DeliveryStatusText.JUST_DELIVERED + "<white>1 × <lang:item.minecraft.stick>"), text);
        assertTrue(text.contains(DeliveryStatusText.ALL_DELIVERED), text);
        assertTrue(text.endsWith("Amélioration du kit obtenue : palier 3."), text);
    }
}
