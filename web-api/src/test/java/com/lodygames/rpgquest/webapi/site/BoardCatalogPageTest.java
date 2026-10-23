package com.lodygames.rpgquest.webapi.site;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.webapi.privacy.PublicPlayerData;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Issue #734 — page « Tous les classements » : liste blanche du catalogue, filtres de période et de
 * thème, bornes en heure de Bruxelles, provenance, période précédente, mesures non suivies, démo.
 */
class BoardCatalogPageTest {

    private static final Map<String, Object> ZOMBIES = Map.ofEntries(
            Map.entry("id", "zombies_week"), Map.entry("name", "Chasseurs <zombies>"), Map.entry("category", "combat"),
            Map.entry("metric", "ENTITY_KILL"), Map.entry("period", "weekly"),
            Map.entry("filter", Map.of("entity-family", List.of("zombies"))), Map.entry("featured", true),
            Map.entry("size", 10), Map.entry("status", "ACTIVE"), Map.entry("trackedSince", "2026-10-14T10:00:00Z"),
            Map.entry("uuid", "00000000-0000-0000-0000-00000000a11c"),
            Map.entry("window", Map.of("key", "2026-W43", "start", "2026-10-18T22:00:00Z", "end", "2026-10-25T23:00:00Z")),
            Map.entry("leaderboard", List.of(Map.of("name", "Alice", "value", 4, "rank", 1, "uuid", "x"),
                    Map.of("name", "Bob", "value", 4, "rank", 1))),
            Map.entry("previous", Map.of("key", "2026-W42", "start", "2026-10-11T22:00:00Z", "end", "2026-10-18T22:00:00Z",
                    "leaderboard", List.of(Map.of("name", "Carol", "value", 2, "rank", 1)))));
    private static final Map<String, Object> SEASON = Map.of("id", "season_kills", "name", "Saison", "category", "combat",
            "metric", "ENTITY_KILL", "period", "season", "status", "NO_SEASON", "leaderboard", List.of());
    private static final Map<String, Object> WAYPOINTS = Map.of("id", "waypoints_total", "name", "Waypoints",
            "category", "exploration", "metric", "WAYPOINT_DISCOVERED", "period", "lifetime", "status", "ACTIVE",
            "window", Map.of("key", "lifetime"), "leaderboard", List.of());
    private static final Map<String, Object> CATALOG = Map.of("timezone", "Europe/Brussels",
            "families", Map.of("entity-family", List.of(Map.of("id", "zombies", "label", "Zombies", "size", 8))),
            "planned", List.of(Map.of("id", "PLAY_TIME", "reason", "Temps de jeu : pas encore mesuré.")));

    private static Map<String, Object> relayed() {
        return PublicPlayerData.statistics(Map.of("boards", List.of(ZOMBIES, SEASON, WAYPOINTS), "catalog", CATALOG));
    }

    @SuppressWarnings("unchecked")
    @Test
    void thePortalRelaysOnlyThePublicFieldsOfABoard() {
        Map<String, Object> board = ((List<Map<String, Object>>) relayed().get("boards")).get(0);

        assertFalse(board.toString().contains("a11c"), "aucun UUID relayé");
        assertFalse(board.toString().contains("uuid"));
        assertEquals(Map.of("entity-family", List.of("zombies")), board.get("filter"));
        assertEquals(List.of(Map.of("name", "Alice", "value", 4, "rank", 1), Map.of("name", "Bob", "value", 4, "rank", 1)),
                board.get("leaderboard"));
        assertEquals("2026-W42", ((Map<String, Object>) board.get("previous")).get("key"));
    }

    @Test
    void theCatalogueShowsBoundsProvenanceTiesPreviousWindowAndPlannedMetrics() {
        String html = BoardCatalogPage.render(relayed(), SiteRoutes.LIVE, "", "");

        assertTrue(html.contains("Chasseurs &lt;zombies&gt;"), "titre échappé");
        assertTrue(html.contains("Cette semaine — du 19 octobre 2026 au 25 octobre 2026"), html);
        assertTrue(html.contains("Cibles : Zombies"), "libellé de la famille");
        assertTrue(html.contains("Mesure suivie depuis le 14 octobre 2026"), html);
        assertEquals(2, html.substring(0, html.indexOf("<details>")).split("class=\"p1 ").length - 1,
                "deux ex aequo sur la marche d'or");
        assertTrue(html.contains("<summary>La semaine dernière (du 12 octobre 2026 au 18 octobre 2026)</summary>"), html);
        assertTrue(html.contains("Aucune saison en cours"), "jamais de classement de saison inventé");
        assertTrue(html.contains("Mesure prête, pas encore de score enregistré."), "pas de date inventée");
        assertTrue(html.contains("Temps de jeu : pas encore mesuré."), html);
    }

    @Test
    void periodAndThemeFiltersKeepEachOther() {
        String weekly = BoardCatalogPage.render(relayed(), SiteRoutes.LIVE, "semaine", "");
        assertTrue(weekly.contains("Chasseurs &lt;zombies&gt;"));
        assertFalse(weekly.contains(">Waypoints<"), "un classement « depuis toujours » est masqué");
        assertTrue(weekly.contains("href=\"/classements/tous?periode=semaine&amp;categorie=combat\""), weekly);

        String exploration = BoardCatalogPage.render(relayed(), SiteRoutes.LIVE, "", "exploration");
        assertFalse(exploration.contains("Chasseurs"));
        assertTrue(exploration.contains(">Waypoints<"), exploration);
        assertEquals("", BoardCatalogPage.periodFromSlug("annee"), "période inconnue = toutes");
    }

    @Test
    void theDemoCatalogueIsLabelledAndUsesOnlyFictionalPlayers() {
        Map<String, Object> statistics = PublicPlayerData.statistics(DemoData.state(Instant.now()).get("statistics"));
        String html = BoardCatalogPage.render(statistics, SiteRoutes.DEMO, "", "");

        assertTrue(html.contains("Aperçu avec des joueurs fictifs"), "bandeau de démonstration");
        assertTrue(html.contains("(démo)"));
        assertTrue(html.contains("Demo_"));
        assertTrue(html.contains("href=\"/demo/classements/tous?periode=semaine\""), html);
    }

    @Test
    void windowLabelsUseTheCommunityTimezone() {
        assertEquals("le 23 octobre 2026", StatLabels.window("2026-10-22T22:00:00Z", "2026-10-23T22:00:00Z"));
        assertEquals("du 1 octobre 2026 au 31 octobre 2026", StatLabels.window("2026-09-30T22:00:00Z", "2026-10-31T23:00:00Z"));
        assertEquals("", StatLabels.window("nope", null));
        assertEquals("Blocs cassés", StatLabels.title("BLOCK_BREAK"));
        assertEquals("waypoints", StatLabels.unit("WAYPOINT_DISCOVERED"));
        assertEquals("exploration", StatLabels.category("CITY_DISCOVERED"));
    }
}
