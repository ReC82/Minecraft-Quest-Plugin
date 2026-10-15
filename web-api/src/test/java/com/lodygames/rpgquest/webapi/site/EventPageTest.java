package com.lodygames.rpgquest.webapi.site;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.webapi.privacy.PublicPlayerData;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Issue #57 — affichage public d'un événement : règles, objectif commun, récompenses, compte à rebours. */
class EventPageTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> relayed(Map<String, Object> event) {
        Map<String, Object> statistics = PublicPlayerData.statistics(Map.of("events", List.of(event)));
        return ((List<Map<String, Object>>) statistics.get("events")).get(0);
    }

    private static final Map<String, Object> ZOMBIES = Map.of("id", "zombie_weekend", "name", "Zombie Weekend",
            "start", "2026-11-07T18:00:00Z", "end", "2026-11-09T22:00:00Z", "status", "ACTIVE",
            "ranking", "COMMUNITY_GOAL",
            "filter", Map.of("entity", List.of("ZOMBIE", "ZOMBIE_VILLAGER")),
            "community", Map.of("goal", 500, "total", 620, "reached", false, "uuid", "secret"),
            "rewards", List.of(Map.of("kind", "COMMUNITY", "label", "Chapeau pour tous", "entitlement", "secret_key"),
                    Map.of("kind", "RANK", "value", 3, "label", "Titre <b>héros</b>")),
            "leaderboards", Map.of());

    @Test
    void thePortalRelaysOnlyThePublicRules() {
        Map<String, Object> event = relayed(ZOMBIES);

        assertEquals("COMMUNITY_GOAL", event.get("ranking"));
        assertEquals(Map.of("entity", List.of("ZOMBIE", "ZOMBIE_VILLAGER")), event.get("filter"));
        assertEquals(Map.of("goal", 500L, "total", 620L, "reached", true), event.get("community"),
                "recalculé, jamais une clé inconnue");
        assertFalse(event.toString().contains("secret"), "ni clé d'avantage ni clé inconnue relayée");
    }

    @Test
    void rulesAndRewardsAreExplainedAndEscaped() {
        String html = LeaderboardsPageHandler.rules(relayed(ZOMBIES));

        assertTrue(html.contains("Objectif commun"), html);
        assertTrue(html.contains("Monstres concernés : zombie, zombie villager"), html);
        assertTrue(html.contains("Récompense si l&#39;objectif commun est atteint : Chapeau pour tous"), html);
        assertTrue(html.contains("Récompense du top 3 : Titre &lt;b&gt;héros&lt;/b&gt;"), html);
    }

    @Test
    void theCommunityBarIsCappedAtTheGoal() {
        String html = LeaderboardsPageHandler.community(relayed(ZOMBIES));

        assertTrue(html.contains("<progress max=\"500\" value=\"500\">"), html);
        assertTrue(html.contains("620 / 500"), html);
        assertTrue(html.contains("Objectif atteint"), html);
        assertEquals("", LeaderboardsPageHandler.community(Map.of("id", "x")));
    }

    @Test
    void theCountdownTargetsTheStartThenTheEnd() {
        Map<String, Object> event = relayed(ZOMBIES);
        Instant before = Instant.parse("2026-11-06T16:30:00Z");
        Instant during = Instant.parse("2026-11-09T21:15:00Z");

        assertEquals("<p><strong>Commence dans 1 j 1 h</strong></p>",
                LeaderboardsPageHandler.countdown("SCHEDULED", event, before));
        assertEquals("<p><strong>Se termine dans 45 min</strong></p>",
                LeaderboardsPageHandler.countdown("ACTIVE", event, during));
        assertEquals("", LeaderboardsPageHandler.countdown("ARCHIVED", event, during));
        assertEquals("", StatLabels.remaining("2026-11-09T22:00:00Z", Instant.parse("2026-11-10T00:00:00Z")));
    }

    @Test
    void newMetricsAndTheCancelledStatusHaveFrenchLabels() {
        assertEquals("Monstres vaincus", StatLabels.title("ENTITY_KILL"));
        assertEquals("prises", StatLabels.unit("FISH_CAUGHT"));
        assertEquals("Annulé", StatLabels.status("CANCELLED"));
    }
}
