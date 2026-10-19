package com.lodygames.rpgquest.panel.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Issue #169 (19/10) — joueurs connectés pour le sélecteur « Près de ». */
class OnlinePlayersTest {

    private static final Instant EARLY = Instant.parse("2026-10-19T09:00:00Z");
    private static final Instant LATE = Instant.parse("2026-10-19T09:38:00Z");

    private static OnlinePlayers.Reading catalog(Instant at) {
        return new OnlinePlayers.Reading(Map.of("players", List.of(
                Map.of("uuid", "u1", "name", "LoDyMcFly", "online", true, "world", "wild"),
                Map.of("uuid", "u2", "name", "Rondoudou9000", "online", false),
                Map.of("uuid", "u3", "name", "Bob", "online", true, "world", "world_hub"))), at);
    }

    @Test
    void playersPageCatalogAloneFeedsTheSelector() {
        OnlinePlayers o = OnlinePlayers.of(catalog(LATE), null, "wild");
        assertTrue(o.loaded());
        assertEquals(List.of("LoDyMcFly", "Bob"), o.names(), "hors ligne écarté");
        assertEquals(List.of(new OnlinePlayers.Player("LoDyMcFly", "wild")), o.inWild());
        assertEquals(List.of(new OnlinePlayers.Player("Bob", "world_hub")), o.outsideWild());
        assertEquals(LATE, o.at());
    }

    @Test
    void theMostRecentReadingWins() {
        OnlinePlayers.Reading list = new OnlinePlayers.Reading(Map.of("players", List.of()), EARLY);
        assertEquals(2, OnlinePlayers.of(catalog(LATE), list, "wild").players().size());
        OnlinePlayers.Reading fresh = new OnlinePlayers.Reading(Map.of("players", List.of(
                Map.of("name", "Alice", "world", "WILD"))), LATE.plusSeconds(60));
        OnlinePlayers o = OnlinePlayers.of(catalog(LATE), fresh, "wild");
        assertEquals(List.of("Alice"), o.names(), "player.list plus récent : tous connectés");
        assertEquals(1, o.inWild().size(), "casse du nom de monde ignorée");
    }

    @Test
    void noReadingMeansNotLoaded() {
        OnlinePlayers o = OnlinePlayers.of(null, null, "wild");
        assertFalse(o.loaded());
        assertTrue(o.players().isEmpty());
    }

    @Test
    void unknownWildWorldKeepsEveryOnlinePlayerSelectable() {
        OnlinePlayers o = OnlinePlayers.of(catalog(LATE), null, "");
        assertFalse(o.wildKnown());
        assertEquals(2, o.inWild().size());
        assertTrue(o.outsideWild().isEmpty());
    }
}
