package com.lodygames.rpgquest.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Issue #197 — le type d'entité d'un PNJ Citizens, et la distinction qui compte : « ce n'est pas
 * un joueur » n'est pas la même chose que « on ne sait pas ».
 */
class CitizensNpcTest {

    private static CitizensNpc of(String entityType) {
        return new CitizensNpc(1, UUID.randomUUID(), "Elias", true, "world_hub", 0, 64, 0, 0f, 0f,
                true, true, true, null, null, entityType);
    }

    @Test
    @DisplayName("Un type PLAYER est reconnu, quelle que soit sa casse")
    void aplayerTypeIsRecognised() {
        assertTrue(of("PLAYER").playerType());
        assertTrue(of("player").playerType());
        assertTrue(of("PLAYER").entityTypeKnown());
    }

    @Test
    @DisplayName("Un autre type est connu et n'est pas un joueur")
    void anotherTypeIsKnownAndNotAPlayer() {
        assertFalse(of("VILLAGER").playerType());
        assertTrue(of("VILLAGER").entityTypeKnown());
    }

    @Test
    @DisplayName("Un type absent est INCONNU, et ne doit pas se lire « pas un joueur »")
    void amissingTypeIsUnknownRatherThanNotAPlayer() {
        for (CitizensNpc npc : new CitizensNpc[] {of(null), of(""), of("   ")}) {
            assertFalse(npc.entityTypeKnown(), "un type absent n'est pas connu");
            // playerType() vaut false, mais entityTypeKnown() permet de ne pas en conclure
            // « villageois » : c'est cette paire qui empêche d'affirmer à tort.
            assertFalse(npc.playerType());
        }
    }

    @Test
    @DisplayName("Le constructeur court n'invente aucun type")
    void theShortConstructorInventsNoType() {
        CitizensNpc brief = new CitizensNpc(1, UUID.randomUUID(), "Elias", true);

        assertFalse(brief.entityTypeKnown(), "une entrée brève ne connaît pas le type");
        assertEquals(null, brief.entityType());
    }

    @Test
    @DisplayName("Ajouter les comportements ne perd pas le type")
    void addingBehaviourKeepsTheType() {
        CitizensNpc enriched = of("PLAYER").withBehaviour(null, null);

        assertEquals("PLAYER", enriched.entityType(), "le type survit à l'enrichissement");
    }
}
