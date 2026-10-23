package com.lodygames.rpgquest.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.helpers.NOPLogger;

/**
 * Issue #735 — gestion des classements depuis PlugAdmin sur un dossier isolé : le premier
 * enregistrement recopie le catalogue par défaut dans {@code leaderboards.yml}, un nouveau classement
 * naît en brouillon, chaque geste est relu par le vrai chargeur et appliqué tout de suite, un refus
 * ne touche jamais au fichier.
 */
class LeaderboardCatalogEditorTest {

    @TempDir
    Path dir;

    private StatisticsService service;
    private LeaderboardCatalogEditor editor;

    @BeforeEach
    void setUp() {
        service = new StatisticsService(null, dir.resolve("statistics.yml"), null, NOPLogger.NOP_LOGGER);
        service.reloadNow();
        editor = new LeaderboardCatalogEditor(service, NOPLogger.NOP_LOGGER);
    }

    private static Map<String, Object> zombies() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("id", "zz_zombies");
        fields.put("name", "Chasseurs de zombies (test)");
        fields.put("description", "Classement de test");
        fields.put("category", "combat");
        fields.put("icon", "skull");
        fields.put("metric", StatMetric.ENTITY_KILL);
        fields.put("filter", Map.of("entity-family", List.of("zombies")));
        fields.put("period", "weekly");
        fields.put("size", 5);
        fields.put("visible", true);
        fields.put("featured", false);
        return fields;
    }

    private StatBoard board(String id) {
        return service.definition().board(id).orElseThrow();
    }

    @Test
    void theFirstSaveCopiesTheDefaultCatalogueAndCreatesADraft() throws Exception {
        int bundled = service.definition().boards().size();
        assertEquals(StatisticsDefinitionLoader.BoardsSource.BUNDLED, service.lastResult().boardsSource());

        var outcome = editor.save(zombies(), null, true);

        assertTrue(outcome.success(), outcome.message());
        assertEquals("CREATED", outcome.code());
        assertTrue(Files.isRegularFile(dir.resolve("leaderboards.yml")));
        assertFalse(Files.exists(dir.resolve("statistics.yml")), "statistics.yml n'est jamais écrit");
        assertEquals(StatisticsDefinitionLoader.BoardsSource.MANAGED_FILE, service.lastResult().boardsSource());
        assertEquals(bundled + 1, service.definition().boards().size(), "le catalogue par défaut est conservé");
        StatBoard created = board("zz_zombies");
        assertEquals(StatBoard.Status.DRAFT, created.status());
        assertFalse(service.definition().activeBoards().contains(created), "un brouillon n'est jamais calculé");
        assertEquals("Classement de test", created.description());
        assertTrue(created.filter().get("entity-family").contains("HUSK"));
    }

    @Test
    void activatingHidingAndArchivingChangeWhatIsComputedAndPublished() {
        editor.save(zombies(), null, true);

        assertEquals("STATUS", editor.setStatus("zz_zombies", StatBoard.Status.ENABLED).code());
        assertTrue(service.definition().publishedBoards().contains(board("zz_zombies")));

        assertTrue(editor.setFlag("zz_zombies", "visible", false).success());
        assertTrue(service.definition().activeBoards().contains(board("zz_zombies")), "caché : toujours calculé");
        assertFalse(service.definition().publishedBoards().contains(board("zz_zombies")), "mais pas publié");

        assertTrue(editor.setFlag("zz_zombies", "featured", true).success());
        assertTrue(board("zz_zombies").featured());

        assertTrue(editor.setStatus("zz_zombies", StatBoard.Status.ARCHIVED).success());
        assertEquals(StatBoard.Status.ARCHIVED, board("zz_zombies").status(), "archivé : gardé dans le fichier");
        assertFalse(service.definition().activeBoards().contains(board("zz_zombies")));
        assertEquals("UNCHANGED", editor.setStatus("zz_zombies", StatBoard.Status.ARCHIVED).code(), "idempotent");
    }

    @Test
    void updatesKeepStatusAndOrderAndRefuseAStaleForm() throws Exception {
        editor.save(zombies(), null, true);
        editor.setStatus("zz_zombies", StatBoard.Status.ENABLED);
        String version = editor.catalog().find("zz_zombies").version();
        int order = board("zz_zombies").order();

        Map<String, Object> renamed = zombies();
        renamed.put("name", "Zombies (renommé)");
        renamed.put("period", "daily");
        var outcome = editor.save(renamed, version, false);

        assertEquals("UPDATED", outcome.code(), outcome.message());
        assertEquals("Zombies (renommé)", board("zz_zombies").name());
        assertEquals(BoardPeriod.DAILY, board("zz_zombies").period());
        assertEquals(StatBoard.Status.ENABLED, board("zz_zombies").status());
        assertEquals(order, board("zz_zombies").order());
        assertEquals("STALE", editor.save(zombies(), version, false).code(), "version périmée");
        assertEquals("EXISTS", editor.save(zombies(), null, true).code());
        assertEquals("NOT_FOUND", editor.setStatus("absent", StatBoard.Status.ENABLED).code());
    }

    @Test
    void anInvalidBoardIsRefusedWithoutTouchingTheFile() throws Exception {
        editor.save(zombies(), null, true);
        byte[] before = Files.readAllBytes(dir.resolve("leaderboards.yml"));
        Map<String, Object> wrong = zombies();
        wrong.put("id", "zz_wrong");
        wrong.put("metric", StatMetric.FISH_CAUGHT); // ne se filtre pas par famille de monstres

        var outcome = editor.save(wrong, null, true);

        assertEquals("INVALID", outcome.code());
        assertTrue(outcome.message().contains("ne se filtre pas"), outcome.message());
        assertTrue(java.util.Arrays.equals(before, Files.readAllBytes(dir.resolve("leaderboards.yml"))));
        Map<String, Object> event = zombies();
        event.put("id", "zz_event");
        event.put("period", "event");
        event.put("event", "absent");
        assertEquals("INVALID", editor.save(event, null, true).code(), "l'événement doit exister dans statistics.yml");
    }

    @Test
    void duplicateMoveAndTimezone() throws Exception {
        editor.save(zombies(), null, true);
        editor.setFlag("zz_zombies", "featured", true);

        var copy = editor.duplicate("zz_zombies", "zz_copy", null);
        assertTrue(copy.success(), copy.message());
        assertEquals(StatBoard.Status.DRAFT, board("zz_copy").status());
        assertFalse(board("zz_copy").featured(), "une copie n'est jamais mise en avant");
        assertEquals("Chasseurs de zombies (test) (copie)", board("zz_copy").name());

        List<String> before = service.definition().boards().stream().map(StatBoard::id).toList();
        assertEquals("MOVED", editor.move("zz_copy", true).code());
        List<String> after = service.definition().boards().stream().map(StatBoard::id).toList();
        assertEquals(before.indexOf("zz_copy") - 1, after.indexOf("zz_copy"));
        assertEquals("UNCHANGED", editor.move(after.get(0), true).code(), "déjà en tête");

        assertEquals("TIMEZONE", editor.setTimezone("America/New_York").code());
        assertEquals(ZoneId.of("America/New_York"), service.definition().zone());
        assertEquals("INVALID", editor.setTimezone("Mars/Olympus").code());
    }

    @Test
    void aListInStatisticsYmlStaysAuthoritativeAndReadOnly() throws Exception {
        Files.writeString(dir.resolve("statistics.yml"), "leaderboards: [{id: s, name: S, metric: BLOCK_BREAK}]\n");
        service.reloadNow();

        var outcome = editor.save(zombies(), null, true);

        assertEquals("STATISTICS_FILE", outcome.code());
        assertFalse(Files.exists(dir.resolve("leaderboards.yml")));
    }

    @Test
    void anEventInStatisticsYmlCanBeFollowed() throws Exception {
        Files.writeString(dir.resolve("statistics.yml"), """
                events:
                  - {id: halloween, name: Halloween, start: "2026-10-31T00:00:00Z", end: "2026-11-02T00:00:00Z",
                     metrics: [ENTITY_KILL]}
                """);
        service.reloadNow();
        Map<String, Object> event = zombies();
        event.put("period", "event");
        event.put("event", "halloween");

        assertTrue(editor.save(event, null, true).success());
        assertEquals("halloween", board("zz_zombies").event());
    }
}
