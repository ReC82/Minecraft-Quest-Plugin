package com.lodygames.rpgquest.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** Issue #54 — validation de {@code statistics.yml} : jamais un fichier appliqué à moitié. */
class StatisticsDefinitionLoaderTest {

    private static final String TESTER = "00000000-0000-0000-0000-0000000000aa";

    private static StatisticsDefinitionLoader.Result load(String yaml) throws Exception {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.loadFromString(yaml);
        return StatisticsDefinitionLoader.load(configuration);
    }

    @Test
    void readsSeasonsEventsAndExclusions() throws Exception {
        var result = load("""
                seasons:
                  - id: season_01
                    name: "Saison 1"
                    start: "2026-11-01T00:00:00+01:00"
                    end: 2027-02-01T00:00:00Z
                    metrics: [RPG_XP_GLOBAL, RPG_XP_COMBAT]
                events:
                  - id: zombie_weekend_2026_11
                    start: "2026-11-07T18:00:00Z"
                    end: "2026-11-09T22:00:00Z"
                    metrics: [RPG_XP_COMBAT]
                exclusions:
                  - player: %s
                    scopes: [season_01]
                """.formatted(TESTER));

        assertTrue(result.valid(), result.errors().toString());
        StatisticsDefinition definition = result.definition();
        assertEquals(2, definition.periods().size());
        StatPeriod season = definition.find("season_01").orElseThrow();
        assertEquals(Instant.parse("2026-10-31T23:00:00Z"), season.start(), "fuseau explicite converti en UTC");
        assertEquals(Instant.parse("2027-02-01T00:00:00Z"), season.end(), "date YAML non entre guillemets acceptée");
        assertEquals("zombie_weekend_2026_11", definition.find("zombie_weekend_2026_11").orElseThrow().name(),
                "sans nom, l'identifiant est affiché");
        assertEquals(Set.of(UUID.fromString(TESTER)), definition.excludedFrom("season_01"));
        assertEquals(Set.of(), definition.excludedFrom("lifetime"));
        assertEquals(StatisticsDefinition.DEFAULT_EXCLUDED_XP_REASONS, definition.excludedXpReasons());
    }

    @Test
    void anExclusionWithoutScopesCoversEverythingIncludingLifetime() throws Exception {
        var result = load("exclusions:\n  - player: " + TESTER + "\n");

        assertTrue(result.valid(), result.errors().toString());
        assertEquals(Set.of(UUID.fromString(TESTER)), result.definition().excludedFrom("lifetime"));
        assertEquals(Set.of(UUID.fromString(TESTER)), result.definition().excludedFrom("any_event"));
    }

    @Test
    void overlappingSeasonsAreRefusedButOverlappingEventsAreFine() throws Exception {
        var seasons = load("""
                seasons:
                  - {id: s1, start: "2026-11-01T00:00:00Z", end: "2026-12-01T00:00:00Z", metrics: [RPG_XP_GLOBAL]}
                  - {id: s2, start: "2026-11-15T00:00:00Z", end: "2027-01-01T00:00:00Z", metrics: [RPG_XP_GLOBAL]}
                """);
        var events = load("""
                events:
                  - {id: e1, start: "2026-11-01T00:00:00Z", end: "2026-12-01T00:00:00Z", metrics: [RPG_XP_COMBAT]}
                  - {id: e2, start: "2026-11-15T00:00:00Z", end: "2027-01-01T00:00:00Z", metrics: [RPG_XP_COMBAT]}
                """);

        assertFalse(seasons.valid());
        assertTrue(seasons.definition().periods().isEmpty(), "fichier invalide = aucune période");
        assertTrue(events.valid(), events.errors().toString());
    }

    @Test
    void refusesWhatCannotBeComputedExactly() throws Exception {
        assertFalse(load("""
                seasons:
                  - {id: s1, start: "2026-11-01T00:00:00Z", end: "2026-12-01T00:00:00Z", metrics: [QUESTS_COMPLETED]}
                """).valid(), "les quêtes n'ont pas d'historique horodaté : lifetime seulement");
        assertFalse(load("""
                events:
                  - {id: e1, start: "2026-11-01T00:00:00", end: "2026-12-01T00:00:00Z", metrics: [RPG_XP_COMBAT]}
                """).valid(), "date sans fuseau refusée");
        assertFalse(load("""
                events:
                  - {id: e1, start: "2026-12-01T00:00:00Z", end: "2026-11-01T00:00:00Z", metrics: [RPG_XP_COMBAT]}
                """).valid(), "fin avant début refusée");
        assertFalse(load("""
                events:
                  - {id: e1, start: "2026-11-01T00:00:00Z", end: "2026-12-01T00:00:00Z", metrics: [KILLS]}
                """).valid(), "métrique inconnue refusée");
        assertFalse(load("""
                events:
                  - {id: E1, start: "2026-11-01T00:00:00Z", end: "2026-12-01T00:00:00Z", metrics: [RPG_XP_COMBAT]}
                """).valid(), "identifiant hors format refusé");
        assertFalse(load("exclusions:\n  - player: Alice\n").valid(), "un pseudo n'est pas une identité");
        assertFalse(load("exclusions:\n  - player: " + TESTER + "\n    scopes: [unknown]\n").valid());
    }

    @Test
    void anEmptyFileDefinesNothing() throws Exception {
        var result = load("");

        assertTrue(result.valid());
        assertTrue(result.definition().periods().isEmpty());
    }
}
