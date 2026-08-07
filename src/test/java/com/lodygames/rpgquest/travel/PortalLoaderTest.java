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

class PortalLoaderTest {

    private final PortalLoader loader = new PortalLoader();

    @TempDir
    Path tempDir;

    @Test
    void duplicateIdAcrossFilesRejectsBoth() {
        Map<String, ConfigurationSection> files = new LinkedHashMap<>();
        files.put("a.yml", load(portal("duplicate", 0, 0)));
        files.put("b.yml", load(portal("duplicate", 100, 100)));

        PortalLoadReport report = loader.load(files);

        assertEquals(0, report.loaded().size());
        assertEquals(2, report.issues().size());
        assertTrue(report.issues().stream().allMatch(i -> i.message().contains("dupliqué")));
    }

    @Test
    void overlappingPortalsInTheSameWorldAreRejected() {
        Map<String, ConfigurationSection> files = new LinkedHashMap<>();
        files.put("a.yml", load(portal("a", 0, 0)));
        files.put("b.yml", load(portal("b", 1, 1)));

        PortalLoadReport report = loader.load(files);

        assertEquals(1, report.loaded().size());
        assertTrue(report.issues().stream().anyMatch(i -> i.message().contains("chevauche")));
    }

    @Test
    void multipleFilesWithOneInvalidStillLoadTheOthers() {
        Map<String, ConfigurationSection> files = new LinkedHashMap<>();
        files.put("good-a.yml", load(portal("good_a", 0, 0)));
        files.put("broken.yml", load("world: world\n"));
        files.put("good-b.yml", load(portal("good_b", 100, 100)));

        PortalLoadReport report = loader.load(files);

        assertEquals(2, report.loaded().size());
        assertTrue(report.issues().stream().allMatch(issue -> issue.file().equals("broken.yml")));
    }

    @Test
    void loadDirectoryReturnsEmptyReportWhenDirectoryIsMissing() {
        PortalLoadReport report = loader.loadDirectory(tempDir.resolve("does-not-exist"));
        assertEquals(0, report.loaded().size());
        assertEquals(0, report.issues().size());
    }

    @Test
    void loadDirectoryReadsRealFilesFromDisk() throws Exception {
        Files.writeString(tempDir.resolve("first.yml"), portal("from_disk", 0, 0));

        PortalLoadReport report = loader.loadDirectory(tempDir);

        assertEquals(1, report.loaded().size());
        assertEquals("from_disk", report.loaded().get(0).id());
    }

    private String portal(String id, int minX, int minZ) {
        return """
                id: %s
                world: world
                min: {x: %d, y: 60, z: %d}
                max: {x: %d, y: 63, z: %d}
                """.formatted(id, minX, minZ, minX + 2, minZ + 2);
    }

    private ConfigurationSection load(String yaml) {
        return YamlConfiguration.loadConfiguration(new StringReader(yaml));
    }
}
