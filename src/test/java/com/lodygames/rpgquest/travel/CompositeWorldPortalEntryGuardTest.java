package com.lodygames.rpgquest.travel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.travel.model.WorldPortalDefinition;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/**
 * {@link CompositeWorldPortalEntryGuard} : ET logique, ordre respecté, arrêt au premier refus.
 * JUnit pur — les gardes sont des lambdas, aucun monde/joueur réel nécessaire.
 */
class CompositeWorldPortalEntryGuardTest {

    private static final WorldPortalDefinition PORTAL = new WorldPortalDefinition(
            "hub_to_x", "world_hub", 0, 0, 0, 4, 4, 4, "x", true);

    @Test
    void allowsWhenEveryGuardAllows() {
        CompositeWorldPortalEntryGuard composite = new CompositeWorldPortalEntryGuard(List.of(
                (p, portal) -> true, (p, portal) -> true));
        assertTrue(composite.allowEntry(null, PORTAL));
    }

    @Test
    void refusesAndStopsAtTheFirstGuardThatRefuses() {
        List<String> calls = new ArrayList<>();
        CompositeWorldPortalEntryGuard composite = new CompositeWorldPortalEntryGuard(List.of(
                (Player p, WorldPortalDefinition portal) -> {
                    calls.add("first");
                    return false;
                },
                (Player p, WorldPortalDefinition portal) -> {
                    calls.add("second");
                    return true;
                }));

        assertFalse(composite.allowEntry(null, PORTAL));
        assertEquals(List.of("first"), calls, "le second garde ne doit jamais être consulté après un refus");
    }

    @Test
    void refusesWhenALaterGuardRefuses() {
        List<String> calls = new ArrayList<>();
        CompositeWorldPortalEntryGuard composite = new CompositeWorldPortalEntryGuard(List.of(
                (Player p, WorldPortalDefinition portal) -> {
                    calls.add("first");
                    return true;
                },
                (Player p, WorldPortalDefinition portal) -> {
                    calls.add("second");
                    return false;
                }));

        assertFalse(composite.allowEntry(null, PORTAL));
        assertEquals(List.of("first", "second"), calls);
    }

    @Test
    void anEmptyCompositeAllowsEverything() {
        assertTrue(new CompositeWorldPortalEntryGuard(List.of()).allowEntry(null, PORTAL));
    }
}
