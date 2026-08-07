package com.lodygames.rpgquest.travel;

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

class DestinationLoaderTest {

    private final DestinationLoader loader = new DestinationLoader();

    @TempDir
    Path tempDir;

    @Test
    void duplicateIdAcrossFilesRejectsBoth() {
        Map<String, ConfigurationSection> files = new LinkedHashMap<>();
        files.put("a.yml", load(destination("duplicate")));
        files.put("b.yml", load(destination("duplicate")));

        DestinationLoadReport report = loader.load(files);

        assertEquals(0, report.loaded().size());
        assertEquals(2, report.issues().size());
        assertTrue(report.issues().stream().allMatch(i -> i.message().contains("dupliqué")));
    }

    @Test
    void multipleFilesWithOneInvalidStillLoadTheOthers() {
        Map<String, ConfigurationSection> files = new LinkedHashMap<>();
        files.put("good-a.yml", load(destination("good_a")));
        files.put("broken.yml", load("world: world\n"));
        files.put("good-b.yml", load(destination("good_b")));

        DestinationLoadReport report = loader.load(files);

        assertEquals(2, report.loaded().size());
        assertTrue(report.issues().stream().allMatch(issue -> issue.file().equals("broken.yml")));
    }

    @Test
    void loadDirectoryReturnsEmptyReportWhenDirectoryIsMissing() {
        DestinationLoadReport report = loader.loadDirectory(tempDir.resolve("does-not-exist"));
        assertEquals(0, report.loaded().size());
        assertEquals(0, report.issues().size());
    }

    @Test
    void loadDirectoryReadsRealFilesFromDisk() throws Exception {
        Files.writeString(tempDir.resolve("first.yml"), destination("from_disk"));

        DestinationLoadReport report = loader.loadDirectory(tempDir);

        assertEquals(1, report.loaded().size());
        assertEquals("from_disk", report.loaded().get(0).id());
    }

    private String destination(String id) {
        return """
                id: %s
                world: world
                x: 0
                y: 65
                z: 0
                """.formatted(id);
    }

    private ConfigurationSection load(String yaml) {
        return YamlConfiguration.loadConfiguration(new StringReader(yaml));
    }
}
