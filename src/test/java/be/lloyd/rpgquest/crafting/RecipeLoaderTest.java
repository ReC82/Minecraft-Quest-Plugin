package be.lloyd.rpgquest.crafting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class RecipeLoaderTest {

    private final RecipeLoader loader = new RecipeLoader();

    @Test
    void duplicateIdAcrossFilesRejectsBoth() {
        Map<String, ConfigurationSection> files = new LinkedHashMap<>();
        files.put("a.yml", load(recipe("rpgquest:duplicate")));
        files.put("b.yml", load(recipe("rpgquest:duplicate")));

        RecipeLoadReport report = loader.load(files);

        assertEquals(0, report.loaded().size());
        assertEquals(2, report.issues().size());
        assertTrue(report.issues().stream().allMatch(i -> i.message().contains("dupliqué")));
    }

    @Test
    void multipleFilesWithOneInvalidStillLoadTheOthers() {
        Map<String, ConfigurationSection> files = new LinkedHashMap<>();
        files.put("good.yml", load(recipe("rpgquest:good")));
        files.put("broken.yml", load("""
                id: rpgquest:broken
                type: NOT_A_TYPE
                result:
                  material: STICK
                  amount: 1
                """));

        RecipeLoadReport report = loader.load(files);

        assertEquals(1, report.loaded().size());
        assertEquals("rpgquest:good", report.loaded().get(0).id().toString());
        assertEquals(1, report.issues().size());
    }

    private String recipe(String id) {
        return """
                id: %s
                type: SHAPELESS
                result:
                  material: STICK
                  amount: 4
                ingredients:
                  - material: OAK_PLANKS
                    amount: 2
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
