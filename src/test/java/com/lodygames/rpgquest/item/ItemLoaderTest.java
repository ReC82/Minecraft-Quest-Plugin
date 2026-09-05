package com.lodygames.rpgquest.item;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ItemLoaderTest {

    private final ItemLoader loader = new ItemLoader();

    @TempDir
    Path tempDir;

    @Test
    void duplicateIdAcrossFilesRejectsBoth() {
        Map<String, ConfigurationSection> files = new LinkedHashMap<>();
        files.put("a.yml", load(item("rpgquest:duplicate")));
        files.put("b.yml", load(item("rpgquest:duplicate")));

        ItemLoadReport report = loader.load(files);

        assertEquals(0, report.loaded().size());
        assertEquals(2, report.issues().size());
        assertTrue(report.issues().stream().allMatch(i -> i.message().contains("dupliqué")));
    }

    @Test
    void multipleFilesWithOneInvalidStillLoadTheOthers() {
        Map<String, ConfigurationSection> files = new LinkedHashMap<>();
        files.put("good-a.yml", load(item("rpgquest:good_a")));
        files.put("broken.yml", load("""
                id: rpgquest:broken
                type: NOT_A_REAL_TYPE
                material: STONE
                name: "Test"
                """));
        files.put("good-b.yml", load(item("rpgquest:good_b")));

        ItemLoadReport report = loader.load(files);

        assertEquals(2, report.loaded().size());
        assertTrue(report.loaded().stream().anyMatch(i -> i.id().toString().equals("rpgquest:good_a")));
        assertTrue(report.loaded().stream().anyMatch(i -> i.id().toString().equals("rpgquest:good_b")));
        assertTrue(report.issues().stream().allMatch(issue -> issue.file().equals("broken.yml")),
                () -> "toutes les erreurs doivent venir du fichier fautif : " + report.issues());
    }

    @Test
    void loadDirectoryReturnsEmptyReportWhenDirectoryIsMissing() {
        ItemLoadReport report = loader.loadDirectory(tempDir.resolve("does-not-exist"));

        assertEquals(0, report.loaded().size());
        assertEquals(0, report.issues().size());
    }

    @Test
    void loadDirectoryReadsRealFilesFromDisk() throws Exception {
        Files.writeString(tempDir.resolve("first.yml"), item("rpgquest:from_disk"));

        ItemLoadReport report = loader.loadDirectory(tempDir);

        assertEquals(1, report.loaded().size());
        assertEquals("rpgquest:from_disk", report.loaded().get(0).id().toString());
    }

    private String item(String id) {
        return """
                id: %s
                type: RESOURCE
                material: STONE
                name: "Test"
                """.formatted(id);
    }

    private ConfigurationSection load(String yaml) {
        return YamlConfiguration.loadConfiguration(new StringReader(yaml));
    }
}
