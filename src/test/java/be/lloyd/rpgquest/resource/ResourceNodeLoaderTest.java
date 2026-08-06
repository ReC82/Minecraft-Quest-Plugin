package be.lloyd.rpgquest.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class ResourceNodeLoaderTest {

    private final ResourceNodeLoader loader = new ResourceNodeLoader();

    @Test
    void duplicateIdAcrossFilesRejectsBoth() {
        Map<String, ConfigurationSection> files = new LinkedHashMap<>();
        files.put("a.yml", load(node("rpgquest:duplicate")));
        files.put("b.yml", load(node("rpgquest:duplicate")));

        ResourceNodeLoadReport report = loader.load(files);

        assertEquals(0, report.loaded().size());
        assertEquals(2, report.issues().size());
        assertTrue(report.issues().stream().allMatch(i -> i.message().contains("dupliqué")));
    }

    @Test
    void multipleFilesWithOneInvalidStillLoadTheOthers() {
        Map<String, ConfigurationSection> files = new LinkedHashMap<>();
        files.put("good.yml", load(node("rpgquest:good")));
        files.put("broken.yml", load("""
                id: rpgquest:broken
                active-material: NOT_A_MATERIAL
                depleted-material: STONE
                respawn-seconds: 60
                drops:
                  - material: QUARTZ
                    weight: 1
                    min-amount: 1
                    max-amount: 1
                """));

        ResourceNodeLoadReport report = loader.load(files);

        assertEquals(1, report.loaded().size());
        assertEquals("rpgquest:good", report.loaded().get(0).id().toString());
        assertEquals(1, report.issues().size());
    }

    @Test
    void emptyDirectoryYieldsEmptyReportNotAnError() {
        ResourceNodeLoadReport report = loader.loadDirectory(java.nio.file.Path.of("does-not-exist"));
        assertEquals(0, report.loaded().size());
        assertEquals(0, report.issues().size());
    }

    private String node(String id) {
        return """
                id: %s
                active-material: EMERALD_ORE
                depleted-material: STONE
                respawn-seconds: 300
                drops:
                  - material: QUARTZ
                    weight: 1
                    min-amount: 1
                    max-amount: 1
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
