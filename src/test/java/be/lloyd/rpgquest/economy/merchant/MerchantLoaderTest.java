package be.lloyd.rpgquest.economy.merchant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MerchantLoaderTest {

    private final MerchantLoader loader = new MerchantLoader();

    @TempDir
    Path tempDir;

    @Test
    void duplicateIdAcrossFilesRejectsBoth() {
        Map<String, ConfigurationSection> files = new LinkedHashMap<>();
        files.put("a.yml", load(merchant("rpgquest:duplicate")));
        files.put("b.yml", load(merchant("rpgquest:duplicate")));

        MerchantLoadReport report = loader.load(files);

        assertEquals(0, report.loaded().size());
        assertEquals(2, report.issues().size());
        assertTrue(report.issues().stream().allMatch(i -> i.message().contains("dupliqué")));
    }

    @Test
    void multipleFilesWithOneInvalidStillLoadTheOthers() {
        Map<String, ConfigurationSection> files = new LinkedHashMap<>();
        files.put("good-a.yml", load(merchant("rpgquest:good_a")));
        files.put("broken.yml", load("""
                id: rpgquest:broken
                title: "Marchand"
                offers:
                  - direction: NOT_A_REAL_DIRECTION
                    material: BREAD
                    quantity: 1
                    price: 1
                """));
        files.put("good-b.yml", load(merchant("rpgquest:good_b")));

        MerchantLoadReport report = loader.load(files);

        assertEquals(2, report.loaded().size());
        assertTrue(report.loaded().stream().anyMatch(m -> m.id().toString().equals("rpgquest:good_a")));
        assertTrue(report.loaded().stream().anyMatch(m -> m.id().toString().equals("rpgquest:good_b")));
        assertTrue(report.issues().stream().allMatch(issue -> issue.file().equals("broken.yml")),
                () -> "toutes les erreurs doivent venir du fichier fautif : " + report.issues());
    }

    @Test
    void loadDirectoryReturnsEmptyReportWhenDirectoryIsMissing() {
        MerchantLoadReport report = loader.loadDirectory(tempDir.resolve("does-not-exist"));

        assertEquals(0, report.loaded().size());
        assertEquals(0, report.issues().size());
    }

    @Test
    void loadDirectoryReadsRealFilesFromDisk() throws Exception {
        Files.writeString(tempDir.resolve("first.yml"), merchant("rpgquest:from_disk"));

        MerchantLoadReport report = loader.loadDirectory(tempDir);

        assertEquals(1, report.loaded().size());
        assertEquals("rpgquest:from_disk", report.loaded().get(0).id().toString());
    }

    private String merchant(String id) {
        return """
                id: %s
                title: "Marchand"
                offers:
                  - direction: SELL_TO_PLAYER
                    material: BREAD
                    quantity: 1
                    price: 1
                """.formatted(id);
    }

    private ConfigurationSection load(String yaml) {
        return YamlConfiguration.loadConfiguration(new java.io.StringReader(yaml));
    }
}
