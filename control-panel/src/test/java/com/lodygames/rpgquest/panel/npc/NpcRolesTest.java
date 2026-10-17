package com.lodygames.rpgquest.panel.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.panel.agent.AgentActionCatalog;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Issue #127 — rôle métier libre, normalisé vers l'identifiant que le plugin accepte déjà. */
class NpcRolesTest {

    @Test
    void aSuggestedLabelGivesItsIdentifier() {
        assertEquals(Optional.of("cartographe"), NpcRoles.normalize("Cartographe"));
        assertEquals(Optional.of("teleporteur"), NpcRoles.normalize("  téléporteur "));
        assertEquals(Optional.of("quest_giver"), NpcRoles.normalize("Donneur de quête"));
    }

    @Test
    void freeTextIsFoldedToALowercaseSlug() {
        assertEquals(Optional.of("maitre_d_armes"), NpcRoles.normalize("Maître d'armes"));
        assertEquals(Optional.of("chef-cuisinier"), NpcRoles.normalize("Chef-Cuisinier"));
        assertEquals(Optional.of("quest_giver"), NpcRoles.normalize("quest_giver"), "anciennes valeurs inchangées");
    }

    @Test
    void emptyMeansNoRoleAndUnusableInputIsRefusedNotTruncated() {
        assertEquals(Optional.of(""), NpcRoles.normalize("   "));
        assertEquals(Optional.of(""), NpcRoles.normalize(null));
        assertTrue(NpcRoles.normalize("!!!").isEmpty());
        assertTrue(NpcRoles.normalize("a".repeat(NpcRoles.MAX_LENGTH + 1)).isEmpty());
        assertEquals(Optional.of("a".repeat(NpcRoles.MAX_LENGTH)), NpcRoles.normalize("A".repeat(NpcRoles.MAX_LENGTH)));
    }

    @Test
    void labelsAreAccentedForSuggestionsAndReadableOtherwise() {
        assertEquals("Téléporteur", NpcRoles.label("teleporteur"));
        assertEquals("Donneur de quête", NpcRoles.label("quest_giver"));
        assertEquals("Maitre d armes", NpcRoles.label("maitre_d_armes"));
        assertEquals("", NpcRoles.label(null));
    }

    @Test
    void everySuggestionRoundTripsThroughTheStoredFormat() {
        for (NpcRoles.Role r : NpcRoles.suggestions()) {
            assertTrue(NpcRoles.ROLE_ID.matcher(r.id()).matches(), r.id());
            assertEquals(Optional.of(r.id()), NpcRoles.normalize(r.label()), r.label());
            assertEquals(r.label(), NpcRoles.label(r.id()));
        }
    }

    @Test
    void theDefinitionActionStoresTheNormalizedRole() {
        var ok = AgentActionCatalog.validate("npc.definition.update",
                Map.of("npc_id", "mira_cartographer", "display_name", "Mira la Cartographe", "role", "Cartographe"));
        assertTrue(ok.valid(), ok.error());
        assertEquals("cartographe", ok.params().get("role"));

        var none = AgentActionCatalog.validate("npc.definition.update",
                Map.of("npc_id", "mira_cartographer", "display_name", "Mira", "role", ""));
        assertTrue(none.valid());
        assertTrue(!none.params().containsKey("role"), "vide = aucun rôle");

        var bad = AgentActionCatalog.validate("npc.definition.create",
                Map.of("npc_id", "mira_cartographer", "display_name", "Mira", "role", "???"));
        assertTrue(!bad.valid());
    }
}
