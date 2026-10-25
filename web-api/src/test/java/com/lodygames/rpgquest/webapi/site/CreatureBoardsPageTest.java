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
 * Issue #734 (priorité du 25/10/2026) — page « Classements par créature » : liste blanche des types,
 * un classement par type réellement vaincu, mob spécial à part, filtres période / catégorie /
 * recherche sans JavaScript, types sans joueur résumés, démo étiquetée.
 */
class CreatureBoardsPageTest {

    private static Map<String, Object> target(String key, String label, String category, List<Map<String, Object>> rows) {
        return Map.of("key", key, "label", label, "category", category, "trackedSince", "2026-10-16T15:50:48Z",
                "leaderboard", rows, "uuid", "00000000-0000-0000-0000-00000000a11c");
    }

    private static final Map<String, Object> SPECIAL = Map.of("key", "special:rpgquest:roi", "label", "Roi <squelette>",
            "category", "special", "base", "Squelette", "trackedSince", "2026-10-20T10:00:00Z",
            "leaderboard", List.of(Map.of("name", "Alice", "value", 1, "rank", 1)));

    private static Map<String, Object> board(String id, String period, List<Map<String, Object>> targets) {
        return Map.ofEntries(Map.entry("id", id), Map.entry("name", "Par type " + period), Map.entry("category", "combat"),
                Map.entry("metric", "ENTITY_KILL"), Map.entry("period", period), Map.entry("split", "entity"),
                Map.entry("size", 10), Map.entry("status", "ACTIVE"),
                Map.entry("window", Map.of("key", "2026-W43", "start", "2026-10-18T22:00:00Z", "end", "2026-10-25T23:00:00Z")),
                Map.entry("leaderboard", List.of()), Map.entry("targets", targets),
                Map.entry("previous", Map.of("key", "2026-W42", "start", "2026-10-11T22:00:00Z", "end", "2026-10-18T22:00:00Z",
                        "targets", List.of(target("ZOMBIE", "Zombie", "hostile",
                                List.of(Map.of("name", "Carol", "value", 7, "rank", 1)))))));
    }

    private static Map<String, Object> relayed() {
        Map<String, Object> week = board("by_type_week", "weekly", List.of(
                SPECIAL,
                target("ZOMBIE", "Zombie", "hostile", List.of(Map.of("name", "Bob", "value", 2, "rank", 1, "uuid", "x"),
                        Map.of("name", "Alice", "value", 1, "rank", 2))),
                target("SPIDER", "Araignée", "hostile", List.of(Map.of("name", "Alice", "value", 3, "rank", 1))),
                target("HUSK", "Zombie momifié", "hostile", List.of()),
                target("PIG", "Cochon", "passive", List.of(Map.of("name", "Bob", "value", 1, "rank", 1)))));
        Map<String, Object> lifetime = board("by_type_total", "lifetime", List.of(
                target("ZOMBIE", "Zombie", "hostile", List.of(Map.of("name", "Bob", "value", 40, "rank", 1)))));
        Map<String, Object> plain = Map.of("id", "mobs_week", "name", "Monstres", "category", "combat",
                "metric", "ENTITY_KILL", "period", "weekly", "status", "ACTIVE", "leaderboard", List.of());
        return PublicPlayerData.statistics(Map.of("boards", List.of(plain, week, lifetime)));
    }

    @SuppressWarnings("unchecked")
    @Test
    void thePortalRelaysOnlyThePublicFieldsOfEachCreature() {
        Map<String, Object> board = ((List<Map<String, Object>>) relayed().get("boards")).get(1);

        assertEquals("entity", board.get("split"));
        List<Map<String, Object>> targets = (List<Map<String, Object>>) board.get("targets");
        assertEquals(5, targets.size());
        assertEquals("Squelette", targets.get(0).get("base"));
        assertFalse(board.toString().contains("uuid"), "aucun UUID relayé, ni par type ni par ligne");
        Map<String, Object> previous = (Map<String, Object>) board.get("previous");
        assertEquals(1, ((List<?>) previous.get("targets")).size());
    }

    @Test
    void eachCreatureWithPlayersHasItsOwnRankingAndQuietOnesAreSummarised() {
        String html = CreatureBoardsPage.render(relayed(), SiteRoutes.LIVE, "semaine", "", "");

        assertTrue(html.contains("id=\"creature-zombie\""));
        assertTrue(html.contains("id=\"creature-spider\""));
        assertTrue(html.contains("id=\"creature-special-rpgquest-roi\""), "mob spécial à part");
        assertTrue(html.contains("Roi &lt;squelette&gt;"), "nom échappé");
        assertTrue(html.contains("variante de Squelette"));
        assertTrue(html.contains("2 créatures") && html.contains("1 créature<"), "unité accordée");
        assertTrue(html.contains("suivi depuis le 16 octobre 2026"));
        assertTrue(html.contains("Déjà vaincus, mais personne sur cette période : Zombie momifié."));
        assertFalse(html.contains("id=\"creature-husk\""), "aucune carte vide");
        assertTrue(html.contains("La semaine dernière") && html.contains("Carol"), "période précédente");
        assertTrue(html.contains("Toutes (5)") && html.contains("Mobs spéciaux (1)") && html.contains("Non hostiles (1)"));
        assertTrue(html.contains("Ce qui est compté"));
    }

    @Test
    void categoryAndAccentInsensitiveSearchNarrowTheList() {
        String hostile = CreatureBoardsPage.render(relayed(), SiteRoutes.LIVE, "semaine", "passive", "");
        assertTrue(hostile.contains("id=\"creature-pig\""));
        assertFalse(hostile.contains("id=\"creature-zombie\""));

        String search = CreatureBoardsPage.render(relayed(), SiteRoutes.LIVE, "semaine", "", "ARAIGNEE");
        assertTrue(search.contains("id=\"creature-spider\""));
        assertFalse(search.contains("id=\"creature-zombie\""));
        assertTrue(search.contains("value=\"ARAIGNEE\""), "la recherche reste dans le champ");

        String byBase = CreatureBoardsPage.render(relayed(), SiteRoutes.LIVE, "semaine", "", "squel");
        assertTrue(byBase.contains("id=\"creature-special-rpgquest-roi\""), "trouvé par sa créature de base");

        String none = CreatureBoardsPage.render(relayed(), SiteRoutes.LIVE, "semaine", "", "<wither>");
        assertTrue(none.contains("Aucune créature ne correspond à ce choix."));
        assertFalse(none.contains("<wither>"), "recherche échappée");
    }

    @Test
    void thePeriodTabsSwitchBetweenTheSplitBoards() {
        String html = CreatureBoardsPage.render(relayed(), SiteRoutes.LIVE, "toujours", "", "");
        assertTrue(html.contains("40 créatures"));
        assertTrue(html.contains("href=\"/classements/creatures?periode=semaine\""));
        assertTrue(html.contains("aria-current=\"page\">Depuis toujours"));
        String unknown = CreatureBoardsPage.render(relayed(), SiteRoutes.LIVE, "nimporte", "", "");
        assertTrue(unknown.contains("aria-current=\"page\">Cette semaine"), "période inconnue : la première publiée");
    }

    @Test
    void theCatalogueLinksToTheCreaturePageInsteadOfAnEmptyCard() {
        String html = BoardCatalogPage.render(relayed(), SiteRoutes.LIVE, "", "");
        assertTrue(html.contains("href=\"/classements/creatures\""));
        assertFalse(html.contains("id=\"classement-by_type_week\""));
        assertTrue(html.contains("id=\"classement-mobs_week\""));
    }

    @Test
    void withoutSplitBoardsThePageSaysSoAndTheDemoIsLabelled() {
        String empty = CreatureBoardsPage.render(PublicPlayerData.statistics(Map.of("boards", List.of())),
                SiteRoutes.LIVE, "", "", "");
        assertTrue(empty.contains("Aucun classement par créature publié pour le moment."));

        String demo = CreatureBoardsPage.render(PublicPlayerData.statistics(DemoData.state(Instant.now()).get("statistics")),
                SiteRoutes.DEMO, "", "", "");
        assertTrue(demo.contains(DemoData.BANNER));
        assertTrue(demo.contains("Roi squelette (démo)"));
        assertTrue(demo.contains("Déjà vaincus, mais personne sur cette période : Ender Dragon."));
        assertTrue(demo.contains("href=\"/demo/classements/tous\""));
    }
}
