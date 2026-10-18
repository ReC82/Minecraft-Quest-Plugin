package com.lodygames.rpgquest.mob;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class SpecialMobLoaderTest {

    private final SpecialMobLoader loader = new SpecialMobLoader();

    @Test
    void duplicateIdAcrossFilesRejectsBoth() {
        Map<String, ConfigurationSection> files = new LinkedHashMap<>();
        files.put("a.yml", load(mob("rpgquest:duplicate")));
        files.put("b.yml", load(mob("rpgquest:duplicate")));

        SpecialMobLoadReport report = loader.load(files);

        assertEquals(0, report.loaded().size());
        assertEquals(2, report.issues().size());
        assertTrue(report.issues().stream().allMatch(i -> i.message().contains("dupliqué")));
    }

    @Test
    void multipleFilesWithOneInvalidStillLoadTheOthers() {
        Map<String, ConfigurationSection> files = new LinkedHashMap<>();
        files.put("good.yml", load(mob("rpgquest:good")));
        files.put("broken.yml", load("""
                id: rpgquest:broken
                entity-type: NOT_AN_ENTITY
                name: "Broken"
                spawn-chance: 0.1
                """));

        SpecialMobLoadReport report = loader.load(files);

        assertEquals(1, report.loaded().size());
        assertEquals("rpgquest:good", report.loaded().get(0).id().toString());
        assertEquals(1, report.issues().size());
    }

    @Test
    void reloadingWithDifferentContentReplacesPreviousDefinitions() {
        Map<String, ConfigurationSection> first = new LinkedHashMap<>();
        first.put("a.yml", load(mob("rpgquest:first")));
        SpecialMobLoadReport firstReport = loader.load(first);
        assertEquals(1, firstReport.loaded().size());
        assertEquals("rpgquest:first", firstReport.loaded().get(0).id().toString());

        Map<String, ConfigurationSection> second = new LinkedHashMap<>();
        second.put("a.yml", load(mob("rpgquest:second")));
        SpecialMobLoadReport secondReport = loader.load(second);
        assertEquals(1, secondReport.loaded().size());
        assertEquals("rpgquest:second", secondReport.loaded().get(0).id().toString());
    }

    @Test
    void settingsFilesOfTheMobsDirectoryAreNeverReadAsProfiles(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws Exception {
        // Issue #169 : sur DEV, « Profils rejetés : community-bosses.yml : « id » est obligatoire… ».
        java.nio.file.Files.writeString(dir.resolve("community-bosses.yml"), "enabled: true\ninterval-minutes: 15\n");
        java.nio.file.Files.writeString(dir.resolve("spawn-settings.yml"), "enabled: true\nchance: 1.0\n");
        java.nio.file.Files.writeString(dir.resolve("good.yml"), mob("rpgquest:good"));

        SpecialMobLoadReport report = loader.loadDirectory(dir);

        assertEquals(1, report.loaded().size());
        assertEquals(0, report.issues().size(), report.issues().toString());
        assertTrue(SpecialMobLoader.isReservedFileName("Community-Bosses.yml"));
    }

    @Test
    void missingDirectoryYieldsEmptyReportNotAnError() {
        SpecialMobLoadReport report = loader.loadDirectory(java.nio.file.Path.of("does-not-exist"));
        assertEquals(0, report.loaded().size());
        assertEquals(0, report.issues().size());
    }

    private String mob(String id) {
        return """
                id: %s
                entity-type: ZOMBIE
                name: "Test Mob"
                spawn-chance: 0.1
                """.formatted(id);
    }

    private ConfigurationSection load(String yaml) {
        YamlConfiguration configuration = new YamlConfiguration();
        try {
            configuration.load(new StringReader(yaml));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return configuration;
    }
}
