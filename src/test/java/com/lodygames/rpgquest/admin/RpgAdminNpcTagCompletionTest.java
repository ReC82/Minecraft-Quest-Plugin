package com.lodygames.rpgquest.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Issue #66 : tab-complétion de {@code /rpgadmin npc tag <id> [--force]} sur le registre canonique. */
class RpgAdminNpcTagCompletionTest {

    private static RpgAdminCommand command(List<String> canonicalIds) {
        // Seule la tab-complétion « npc tag » est exercée : aucun service n'est sollicité.
        RpgAdminCommand command = new RpgAdminCommand(
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null);
        command.setNpcCanonicalIds(() -> canonicalIds);
        return command;
    }

    private static List<String> complete(RpgAdminCommand command, String... args) {
        return command.onTabComplete(null, null, "rpgadmin", args);
    }

    @Test
    void theIdArgumentCompletesFromTheCanonicalRegistry() {
        RpgAdminCommand command = command(List.of("guide", "guard", "jo"));

        assertEquals(List.of("guard", "guide", "jo"), complete(command, "npc", "tag", ""));
        assertEquals(List.of("guard"), complete(command, "npc", "tag", "gua"));
        assertEquals(List.of("guard", "guide"), complete(command, "npc", "TAG", "G"));
    }

    @Test
    void theRegistryIsReadAtEachCompletionSoReloadsAreFollowed() {
        List<String> registry = new java.util.ArrayList<>(List.of("guard"));
        RpgAdminCommand command = command(registry);
        registry.add("lucien");

        assertEquals(List.of("lucien"), complete(command, "npc", "tag", "l"));
    }

    @Test
    void theForceFlagIsOfferedAfterTheId() {
        RpgAdminCommand command = command(List.of("guard"));

        assertEquals(List.of("--force"), complete(command, "npc", "tag", "garde", "--"));
        assertEquals(List.of(), complete(command, "npc", "tag", "garde", "x"));
    }
}
