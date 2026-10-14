package com.lodygames.rpgquest.encounter.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QuestCatalogStoreTest {

    @TempDir
    Path dir;

    private static QuestCatalog sample() {
        return new QuestCatalog("bandit", "Contrats \"du\" brigand : # bois", "Desc", true, 2, List.of(
                new QuestCatalog.Entry("rpgquest:spiders", 10, true),
                new QuestCatalog.Entry("rpgquest:loot", 3, false)));
    }

    @Test
    void createWritesAFileThatReadsBackIdentically() {
        QuestCatalogStore store = new QuestCatalogStore(dir);
        assertEquals("CREATED", store.create(sample()).code());
        assertEquals(sample(), store.find("bandit").orElseThrow());
        assertEquals("EXISTS", store.create(sample()).code(), "jamais d'écrasement par création");
    }

    @Test
    void updateReplacesAndDeleteBacksUpFirst() throws Exception {
        QuestCatalogStore store = new QuestCatalogStore(dir.resolve("quest-catalogs"));
        store.create(sample());
        QuestCatalog changed = new QuestCatalog("bandit", "Autre", null, false, 0,
                List.of(new QuestCatalog.Entry("rpgquest:loot", 1, true)));
        assertEquals("UPDATED", store.update(changed).code());
        assertEquals(changed, store.find("bandit").orElseThrow());

        QuestCatalogStore.Result deleted = store.delete("bandit", dir.resolve("backups"));
        assertTrue(deleted.ok());
        assertFalse(store.find("bandit").isPresent());
        try (var files = Files.list(dir.resolve("backups"))) {
            assertEquals(1, files.count());
        }
        assertEquals("ABSENT", store.delete("bandit", dir.resolve("backups")).code());
        assertEquals("NOT_FOUND", store.update(changed).code());
    }

    @Test
    void theParserAccumulatesErrorsAndNormalizesQuestIds() throws Exception {
        YamlConfiguration ok = new YamlConfiguration();
        ok.loadFromString("""
                id: c1
                display_name: "Un"
                entries:
                  - quest: spiders
                  - quest: other:loot
                    weight: 5
                    enabled: false
                """);
        QuestCatalog c = new QuestCatalogParser().parse("c1.yml", ok).catalog();
        assertEquals(List.of(new QuestCatalog.Entry("rpgquest:spiders", 10, true),
                new QuestCatalog.Entry("other:loot", 5, false)), c.entries());
        assertEquals(1, c.avoidRecent());

        YamlConfiguration bad = new YamlConfiguration();
        bad.loadFromString("""
                id: "Bad Id"
                entries:
                  - quest: "??"
                  - quest: a
                    weight: 0
                  - quest: a
                avoid_recent: 99
                """);
        var result = new QuestCatalogParser().parse("bad.yml", bad);
        assertFalse(result.isSuccess());
        assertTrue(result.issues().size() >= 5, () -> result.issues().toString());
    }

    @Test
    void duplicateIdsAcrossFilesAreAllRejected() throws Exception {
        Files.writeString(dir.resolve("a.yml"), "id: same\ndisplay_name: A\n");
        Files.writeString(dir.resolve("b.yml"), "id: same\ndisplay_name: B\n");
        Files.writeString(dir.resolve("c.yml"), "id: other\ndisplay_name: C\n");
        QuestCatalogLoadReport report = new QuestCatalogLoader().loadDirectory(dir);
        assertEquals(List.of("other"), report.loaded().stream().map(QuestCatalog::id).toList());
        assertEquals(2, report.issues().size());
    }

    @Test
    void theBundledExampleIsValid() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        try (var in = getClass().getResourceAsStream("/quest-catalogs/encounter_side_quests.yml")) {
            yaml.loadFromString(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
        var result = new QuestCatalogParser().parse("encounter_side_quests.yml", yaml);
        assertTrue(result.isSuccess(), () -> result.issues().toString());
        assertEquals(3, result.catalog().entries().size());
    }
}
