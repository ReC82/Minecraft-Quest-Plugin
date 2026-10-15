package com.lodygames.rpgquest.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** Issue #57 — règles d'événement dans {@code statistics.yml} : classement, filtre, objectif, récompenses, statut. */
class EventDefinitionLoaderTest {

    private static StatisticsDefinitionLoader.Result load(String yaml) throws Exception {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.loadFromString(yaml);
        return StatisticsDefinitionLoader.load(configuration);
    }

    private static String event(String body) {
        return """
                events:
                  - id: zombie_weekend
                    start: "2026-11-07T18:00:00Z"
                    end: "2026-11-09T22:00:00Z"
                """ + body.indent(4);
    }

    private static void assertRejected(String yaml, String fragment) throws Exception {
        var result = load(yaml);
        assertFalse(result.valid(), "devait être refusé : " + fragment);
        assertTrue(result.errors().stream().anyMatch(error -> error.contains(fragment)), result.errors().toString());
        assertTrue(result.definition().periods().isEmpty(), "jamais appliqué à moitié");
    }

    @Test
    void aZombieWeekendNeedsOnlyAFileEntry() throws Exception {
        var result = load(event("""
                name: "Zombie Weekend"
                metrics: [ENTITY_KILL]
                filter:
                  entity: [zombie, ZOMBIE_VILLAGER]
                rewards:
                  - {rank: 1, entitlement: title_zombie_slayer, label: "Titre Pourfendeur de zombies"}
                  - {threshold: 50, entitlement: badge_zombie_hunter, label: "Badge Chasseur"}
                """));

        assertTrue(result.valid(), result.errors().toString());
        StatPeriod event = result.definition().find("zombie_weekend").orElseThrow();
        assertEquals(StatPeriod.State.PLANNED, event.state());
        assertEquals(EventRanking.HIGHEST_TOTAL, event.rules().ranking());
        assertEquals(Map.of("entity", Set.of("ZOMBIE", "ZOMBIE_VILLAGER")), event.rules().filter(), "noms normalisés");
        assertEquals(List.of(new EventReward(EventReward.Kind.RANK, 1, "title_zombie_slayer", "Titre Pourfendeur de zombies"),
                new EventReward(EventReward.Kind.THRESHOLD, 50, "badge_zombie_hunter", "Badge Chasseur")), event.rules().rewards());
    }

    @Test
    void readsACommunityGoalAndTheDraftAndCancelledStates() throws Exception {
        var result = load("""
                events:
                  - id: community
                    start: "2026-11-01T00:00:00Z"
                    end: "2026-11-08T00:00:00Z"
                    metrics: [FISH_CAUGHT]
                    ranking: COMMUNITY_GOAL
                    goal: 1000
                    rewards:
                      - {community: true, entitlement: cosmetic_fish_hat, label: "Chapeau de pêcheur pour tous"}
                  - {id: draft, status: draft, start: "2026-12-01T00:00:00Z", end: "2026-12-02T00:00:00Z", metrics: [ENTITY_KILL]}
                  - {id: called_off, status: cancelled, start: "2026-12-01T00:00:00Z", end: "2026-12-02T00:00:00Z", metrics: [ENTITY_KILL]}
                """);

        assertTrue(result.valid(), result.errors().toString());
        StatPeriod community = result.definition().find("community").orElseThrow();
        assertTrue(community.rules().communityGoal());
        assertEquals(1000L, community.rules().goal());
        assertEquals(StatPeriod.State.DRAFT, result.definition().find("draft").orElseThrow().state());
        assertEquals(StatPeriod.State.CANCELLED, result.definition().find("called_off").orElseThrow().state());
    }

    @Test
    void aFilterOnAMetricThatCannotBeFilteredIsRefused() throws Exception {
        assertRejected(event("""
                metrics: [RPG_XP_COMBAT]
                filter: {entity: [ZOMBIE]}
                """), "ne se filtre pas par « entity »");
        assertRejected(event("""
                metrics: [ENTITY_KILL, FISH_CAUGHT]
                filter: {entity: [ZOMBIE]}
                """), "FISH_CAUGHT");
    }

    @Test
    void malformedFilterValuesAreRefused() throws Exception {
        assertRejected(event("""
                metrics: [ENTITY_KILL]
                filter: {entity: ["zombie; DROP TABLE"]}
                """), "valeur de filtre");
        assertRejected(event("""
                metrics: [ENTITY_KILL]
                filter: {special-mob: [ZOMBIE]}
                """), "id namespacé");
    }

    @Test
    void lowestTotalIsRefusedForCountersWhereAnAbsentPlayerWouldWin() throws Exception {
        assertRejected(event("""
                metrics: [ENTITY_KILL]
                ranking: LOWEST_TOTAL
                """), "un joueur absent gagnerait");
    }

    @Test
    void aCommunityGoalNeedsAPositiveGoalAndOneMetric() throws Exception {
        assertRejected(event("""
                metrics: [ENTITY_KILL]
                ranking: COMMUNITY_GOAL
                """), "demande « goal »");
        assertRejected(event("""
                metrics: [ENTITY_KILL]
                ranking: COMMUNITY_GOAL
                goal: -3
                """), "entier positif");
        assertRejected(event("""
                metrics: [ENTITY_KILL, FISH_CAUGHT]
                ranking: COMMUNITY_GOAL
                goal: 10
                """), "une seule métrique");
        assertRejected(event("""
                metrics: [ENTITY_KILL]
                goal: 10
                """), "COMMUNITY_GOAL");
    }

    @Test
    void rewardsMustBeDeclaredCompletely() throws Exception {
        assertRejected(event("""
                metrics: [ENTITY_KILL]
                rewards: [{rank: 1, label: "Titre"}]
                """), "« entitlement » obligatoire");
        assertRejected(event("""
                metrics: [ENTITY_KILL]
                rewards: [{rank: 1, threshold: 3, entitlement: a, label: "x"}]
                """), "exactement un critère");
        assertRejected(event("""
                metrics: [ENTITY_KILL]
                rewards: [{community: true, entitlement: a, label: "x"}]
                """), "community: true demande");
        assertRejected(event("""
                metrics: [ENTITY_KILL]
                rewards: [{rank: 1, entitlement: a}]
                """), "« label » obligatoire");
    }

    @Test
    void unknownRankingAndStatusAreRefused() throws Exception {
        assertRejected(event("""
                metrics: [ENTITY_KILL]
                ranking: FASTEST
                """), "« ranking » inconnu");
        assertRejected(event("""
                metrics: [ENTITY_KILL]
                status: paused
                """), "« status » vaut");
    }

    @Test
    void seasonsKeepTheirSimpleRules() throws Exception {
        assertRejected("""
                seasons:
                  - id: s1
                    start: "2026-11-01T00:00:00Z"
                    end: "2026-12-01T00:00:00Z"
                    metrics: [ENTITY_KILL]
                    filter: {entity: [ZOMBIE]}
                """, "réservé aux événements");
    }

    @Test
    void aDraftOrCancelledSeasonIsNeverTheCurrentOne() throws Exception {
        var result = load("""
                seasons:
                  - {id: s1, start: "2026-10-01T00:00:00Z", end: "2026-11-01T00:00:00Z", metrics: [RPG_XP_GLOBAL]}
                  - {id: s2, status: cancelled, start: "2026-11-01T00:00:00Z", end: "2026-12-01T00:00:00Z", metrics: [RPG_XP_GLOBAL]}
                  - {id: s3, status: draft, start: "2026-12-01T00:00:00Z", end: "2027-01-01T00:00:00Z", metrics: [RPG_XP_GLOBAL]}
                """);

        assertTrue(result.valid(), result.errors().toString());
        assertEquals("s1", result.definition().currentSeason(java.time.Instant.parse("2026-11-15T00:00:00Z"))
                .orElseThrow().id());
    }
}
