package be.lloyd.rpgquest.zone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class ZoneLoaderTest {

    private final ZoneLoader loader = new ZoneLoader();

    @Test
    void duplicateIdAcrossFilesRejectsBoth() {
        Map<String, ConfigurationSection> files = new LinkedHashMap<>();
        files.put("a.yml", load(zone("duplicate", 0, 0)));
        files.put("b.yml", load(zone("duplicate", 100, 100)));

        ZoneLoadReport report = loader.load(files);

        assertEquals(0, report.loaded().size());
        assertEquals(2, report.issues().size());
        assertTrue(report.issues().stream().allMatch(i -> i.message().contains("dupliqué")));
    }

    @Test
    void overlappingZonesInTheSameWorldRejectTheSecondOne() {
        Map<String, ConfigurationSection> files = new LinkedHashMap<>();
        files.put("a.yml", load(zone("first", 0, 0)));
        files.put("b.yml", load(zone("second", 5, 5))); // même monde, chevauche "first" (rayon 10 chacune)

        ZoneLoadReport report = loader.load(files);

        assertEquals(1, report.loaded().size());
        assertEquals("first", report.loaded().get(0).id());
        assertEquals(1, report.issues().size());
        assertTrue(report.issues().get(0).message().contains("chevauche"));
    }

    @Test
    void nonOverlappingZonesBothLoad() {
        Map<String, ConfigurationSection> files = new LinkedHashMap<>();
        files.put("a.yml", load(zone("first", 0, 0)));
        files.put("b.yml", load(zone("second", 100, 100)));

        ZoneLoadReport report = loader.load(files);

        assertEquals(2, report.loaded().size());
        assertEquals(0, report.issues().size());
    }

    @Test
    void multipleFilesWithOneInvalidStillLoadTheOthers() {
        Map<String, ConfigurationSection> files = new LinkedHashMap<>();
        files.put("good.yml", load(zone("good", 0, 0)));
        files.put("broken.yml", load("id: broken\nworld: world\n"));

        ZoneLoadReport report = loader.load(files);

        assertEquals(1, report.loaded().size());
        assertEquals("good", report.loaded().get(0).id());
        assertTrue(report.issues().size() >= 1, "broken.yml doit être rejeté avec au moins un problème");
        assertTrue(report.issues().stream().allMatch(i -> i.file().equals("broken.yml")));
    }

    private String zone(String id, int centerX, int centerZ) {
        int radius = 10;
        return """
                id: %s
                world: world
                min: {x: %d, y: 0, z: %d}
                max: {x: %d, y: 255, z: %d}
                """.formatted(id, centerX - radius, centerZ - radius, centerX + radius, centerZ + radius);
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
