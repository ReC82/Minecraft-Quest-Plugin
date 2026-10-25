package com.lodygames.rpgquest.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.database.ActivityLogRepository;
import com.lodygames.rpgquest.database.DatabaseManager;
import com.lodygames.rpgquest.database.PlayerProfileRepository;
import com.lodygames.rpgquest.database.QuestStatisticsRepository;
import com.lodygames.rpgquest.database.StatisticsRepository;
import com.lodygames.rpgquest.player.PublicProfilePolicy.Visibility;
import com.lodygames.rpgquest.stats.StatMetric;
import com.lodygames.rpgquest.stats.StatisticsDefinition;
import com.lodygames.rpgquest.stats.StatisticsDefinitionLoader;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Issue #734 — {@code statistics.boards} et {@code statistics.catalog} dans l'instantané : fenêtre en
 * cours et précédente, statut sans saison, « suivi depuis », mesures non suivies ; jamais d'UUID.
 */
class BoardCatalogExportTest {

    private static final long TIMEOUT = 10;
    private static final Instant NOW = Instant.parse("2026-10-23T10:00:00Z");

    @TempDir
    Path tempDir;

    private DatabaseManager database;
    private StatisticsRepository statistics;
    private final UUID alice = UUID.fromString("00000000-0000-0000-0000-00000000a11c");

    @BeforeEach
    void setUp() throws Exception {
        database = new DatabaseManager(tempDir.resolve("test.db"));
        database.initialize().get(TIMEOUT, TimeUnit.SECONDS);
        statistics = new StatisticsRepository(database);
        new PlayerProfileRepository(database).findOrCreate(alice, "Alice").get(TIMEOUT, TimeUnit.SECONDS);
        ActivityLogRepository journal = new ActivityLogRepository(database);
        journal.record(new ActivityLogRepository.Activity("kill:1", alice, StatMetric.ENTITY_KILL, "ZOMBIE", null, 1,
                Instant.parse("2026-10-20T10:00:00Z"))).get(TIMEOUT, TimeUnit.SECONDS);
        journal.record(new ActivityLogRepository.Activity("kill:2", alice, StatMetric.ENTITY_KILL, "HUSK", null, 1,
                Instant.parse("2026-10-14T10:00:00Z"))).get(TIMEOUT, TimeUnit.SECONDS);
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    private static StatisticsDefinition definition(String yaml) throws Exception {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.loadFromString(yaml);
        var result = StatisticsDefinitionLoader.load(configuration);
        assertTrue(result.valid(), String.join("\n", result.errors()));
        return result.definition();
    }

    @SuppressWarnings("unchecked")
    @Test
    void boardsCarryTheirWindowsRowsAndTrackedSinceDate() throws Exception {
        StatisticsDefinition stats = definition("""
                leaderboards:
                  - {id: zombies_week, name: "Zombies", category: combat, icon: zombie, metric: ENTITY_KILL,
                     filter: {entity-family: [zombies]}, period: weekly, featured: true}
                  - {id: season_kills, name: "Saison", metric: ENTITY_KILL, period: season}
                  - {id: quests, name: "Quêtes", category: aventure, metric: QUESTS_COMPLETED}
                """);
        var catalog = BoardCatalogExport.gather(statistics, stats, NOW, id -> false, Visibility.PUBLIC,
                List.of(new QuestStatisticsRepository.Row("Alice", 3))).get(TIMEOUT, TimeUnit.SECONDS);

        List<Map<String, Object>> boards = BoardCatalogExport.boards(catalog);
        Map<String, Object> zombies = boards.get(0);
        assertEquals("zombies_week", zombies.get("id"));
        assertEquals("weekly", zombies.get("period"));
        assertEquals(Map.of("entity-family", List.of("zombies")), zombies.get("filter"));
        assertEquals("ACTIVE", zombies.get("status"));
        assertEquals(Map.of("key", "2026-W43", "start", "2026-10-18T22:00:00Z", "end", "2026-10-25T23:00:00Z"),
                zombies.get("window"));
        assertEquals(List.of(Map.of("name", "Alice", "value", 1L, "rank", 1)), zombies.get("leaderboard"));
        Map<String, Object> previous = (Map<String, Object>) zombies.get("previous");
        assertEquals("2026-W42", previous.get("key"));
        assertEquals(List.of(Map.of("name", "Alice", "value", 1L, "rank", 1)), previous.get("leaderboard"));
        assertEquals("2026-10-14T10:00:00Z", zombies.get("trackedSince"));

        assertEquals("NO_SEASON", boards.get(1).get("status"), "aucune saison : rien d'inventé");
        assertEquals(List.of(), boards.get(1).get("leaderboard"));
        assertEquals(List.of(Map.of("name", "Alice", "value", 3L, "rank", 1)), boards.get(2).get("leaderboard"));
        assertFalse(boards.toString().contains(alice.toString()), "aucun UUID publié");
    }

    @SuppressWarnings("unchecked")
    @Test
    void theCatalogListsTrackedAndPlannedMetrics() throws Exception {
        var catalog = BoardCatalogExport.gather(statistics, definition("leaderboards: []"), NOW, id -> false,
                Visibility.PUBLIC, List.of()).get(TIMEOUT, TimeUnit.SECONDS);
        Map<String, Object> exported = BoardCatalogExport.catalog(catalog);

        assertEquals("Europe/Brussels", exported.get("timezone"));
        List<Map<String, Object>> metrics = (List<Map<String, Object>>) exported.get("metrics");
        Map<String, Object> kills = metrics.stream().filter(m -> m.get("id").equals(StatMetric.ENTITY_KILL)).findFirst().orElseThrow();
        assertEquals("2026-10-14T10:00:00Z", kills.get("trackedSince"));
        Map<String, Object> fish = metrics.stream().filter(m -> m.get("id").equals(StatMetric.FISH_CAUGHT)).findFirst().orElseThrow();
        assertNull(fish.get("trackedSince"));
        assertTrue(((List<?>) exported.get("planned")).toString().contains("PLAY_TIME"));
        Map<String, Object> families = (Map<String, Object>) exported.get("families");
        assertTrue(families.get("entity-family").toString().contains("zombies"));
        assertTrue(families.get("block-family").toString().contains("ores"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void aSplitBoardPublishesOneRankingPerCreatureTypeActuallyKilled() throws Exception {
        UUID bob = UUID.fromString("00000000-0000-0000-0000-000000000b0b");
        new PlayerProfileRepository(database).findOrCreate(bob, "Bob").get(TIMEOUT, TimeUnit.SECONDS);
        ActivityLogRepository journal = new ActivityLogRepository(database);
        journal.record(new ActivityLogRepository.Activity("kill:3", bob, StatMetric.ENTITY_KILL, "ZOMBIE", null, 1,
                Instant.parse("2026-10-21T10:00:00Z"))).get(TIMEOUT, TimeUnit.SECONDS);
        journal.record(new ActivityLogRepository.Activity("kill:4", bob, StatMetric.ENTITY_KILL, "ZOMBIE", null, 1,
                Instant.parse("2026-10-21T11:00:00Z"))).get(TIMEOUT, TimeUnit.SECONDS);
        journal.record(new ActivityLogRepository.Activity("kill:5", alice, StatMetric.ENTITY_KILL, "ZOMBIE",
                "rpgquest:roi_zombie", 1, Instant.parse("2026-10-22T10:00:00Z"))).get(TIMEOUT, TimeUnit.SECONDS);
        journal.record(new ActivityLogRepository.Activity("kill:6", alice, StatMetric.ENTITY_KILL, "PIG", null, 1,
                Instant.parse("2026-10-22T11:00:00Z"))).get(TIMEOUT, TimeUnit.SECONDS);
        StatisticsDefinition stats = definition("""
                leaderboards:
                  - {id: by_type, name: "Par type", category: combat, metric: ENTITY_KILL, split: entity}
                  - {id: by_type_week, name: "Par type, semaine", metric: ENTITY_KILL, split: entity, period: weekly}
                  - {id: hostile_by_type, name: "Hostiles", metric: ENTITY_KILL, split: entity,
                     filter: {entity-family: [hostile]}}
                """);
        var catalog = BoardCatalogExport.gather(statistics, stats, NOW, id -> false, Visibility.PUBLIC, List.of(),
                id -> id.equals("rpgquest:roi_zombie") ? "Roi zombie" : id).get(TIMEOUT, TimeUnit.SECONDS);
        List<Map<String, Object>> boards = BoardCatalogExport.boards(catalog);

        Map<String, Object> lifetime = boards.get(0);
        assertEquals("entity", lifetime.get("split"));
        assertNull(lifetime.get("leaderboard"), "un classement découpé n'a pas de liste unique");
        List<Map<String, Object>> targets = (List<Map<String, Object>>) lifetime.get("targets");
        assertEquals(List.of("special:rpgquest:roi_zombie", "ZOMBIE", "HUSK", "PIG"),
                targets.stream().map(t -> t.get("key")).toList(), "mob spécial à part, puis hostiles, puis non hostiles");
        assertEquals("Roi zombie", targets.get(0).get("label"));
        assertEquals("special", targets.get(0).get("category"));
        assertEquals("Zombie", targets.get(0).get("base"));
        assertEquals("Zombie momifié", targets.get(2).get("label"), "tri alphabétique français dans la catégorie");
        assertEquals("hostile", targets.get(1).get("category"));
        assertEquals("passive", targets.get(3).get("category"));
        assertEquals(List.of(Map.of("name", "Bob", "value", 2L, "rank", 1), Map.of("name", "Alice", "value", 1L, "rank", 2)),
                targets.get(1).get("leaderboard"), "le zombie spécial ne compte pas comme un zombie");
        assertEquals("2026-10-20T10:00:00Z", targets.get(1).get("trackedSince"));

        Map<String, Object> week = boards.get(1);
        List<Map<String, Object>> weekTargets = (List<Map<String, Object>>) week.get("targets");
        Map<String, Object> husk = weekTargets.stream().filter(t -> "HUSK".equals(t.get("key"))).findFirst().orElseThrow();
        assertEquals(List.of(), husk.get("leaderboard"), "type déjà rencontré, personne cette semaine");
        Map<String, Object> previous = (Map<String, Object>) week.get("previous");
        assertEquals("2026-W42", previous.get("key"));
        Map<String, Object> previousHusk = ((List<Map<String, Object>>) previous.get("targets")).stream()
                .filter(t -> "HUSK".equals(t.get("key"))).findFirst().orElseThrow();
        assertEquals(List.of(Map.of("name", "Alice", "value", 1L, "rank", 1)), previousHusk.get("leaderboard"));

        List<Map<String, Object>> hostile = (List<Map<String, Object>>) boards.get(2).get("targets");
        assertFalse(hostile.stream().anyMatch(t -> "PIG".equals(t.get("key"))), "le filtre du classement s'applique");
        assertFalse(boards.toString().contains(alice.toString()) || boards.toString().contains(bob.toString()),
                "aucun UUID publié");
    }

    @Test
    void splitIsOnlyAcceptedForCreatureKills() throws Exception {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.loadFromString("""
                leaderboards:
                  - {id: blocks, name: "Blocs", metric: BLOCK_BREAK, split: entity}
                  - {id: kills, name: "Kills", metric: ENTITY_KILL, split: biome}
                """);
        var result = StatisticsDefinitionLoader.load(configuration);
        assertFalse(result.valid());
        assertEquals(2, result.errors().stream().filter(error -> error.contains("« split »")).count(),
                String.join("\n", result.errors()));
    }
}
