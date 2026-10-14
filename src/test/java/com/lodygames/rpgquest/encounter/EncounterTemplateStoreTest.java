package com.lodygames.rpgquest.encounter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.encounter.model.EncounterTemplate;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Issue #13 étape 3 — rendu YAML et écriture sûre des modèles de PNJ récurrents. */
class EncounterTemplateStoreTest {

    @TempDir
    Path dir;

    private EncounterTemplateStore store() {
        return new EncounterTemplateStore(dir.resolve("encounters"));
    }

    @Test
    void renderedYamlParsesBackToTheSameTemplate() throws InvalidConfigurationException {
        EncounterTemplate original = new EncounterTemplate("desert_merchant", "Marchand \"égaré\" : # dunes",
                "Une caravane perdue \\ loin", "encounter_merchant", "marchand", "desert",
                Set.of("minecraft:desert", "minecraft:badlands"), List.of("wild", "wild_2"), 25, 40,
                "https://minesk.in/0123456789abcdef0123456789abcdef", false);
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(EncounterTemplateYaml.render(original));

        var parsed = new EncounterTemplateParser().parse("x.yml", yaml);

        assertTrue(parsed.isSuccess(), () -> parsed.issues().toString());
        assertEquals(original, parsed.template());
    }

    @Test
    void emptyWorldsStayEmpty() throws InvalidConfigurationException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(EncounterTemplateYaml.render(template("a", true)));
        assertEquals(List.of(), new EncounterTemplateParser().parse("a.yml", yaml).template().worlds());
    }

    @Test
    void createRefusesToOverwriteAndUpdateRequiresAnExistingTemplate() {
        assertEquals("NOT_FOUND", store().update(template("forest_bandit", true)).code());
        assertEquals("CREATED", store().create(template("forest_bandit", true)).code());
        EncounterTemplateStore.Result again = store().create(template("forest_bandit", false));
        assertFalse(again.ok());
        assertEquals("EXISTS", again.code());
        assertTrue(store().find("forest_bandit").orElseThrow().enabled(), "le second create n'a rien écrasé");

        EncounterTemplateStore.Result updated = store().update(template("forest_bandit", false));
        assertEquals("UPDATED", updated.code());
        assertFalse(store().find("forest_bandit").orElseThrow().enabled());
    }

    @Test
    void createDetectsAnIdDeclaredInAnotherFile() throws IOException {
        Files.createDirectories(dir.resolve("encounters"));
        Files.writeString(dir.resolve("encounters/legacy.yml"), EncounterTemplateYaml.render(template("smith", true)));
        assertEquals("EXISTS", store().create(template("smith", true)).code());
        assertEquals("UPDATED", store().update(template("smith", false)).code());
        assertTrue(Files.exists(dir.resolve("encounters/legacy.yml")), "la modification vise le fichier existant");
        assertFalse(Files.exists(dir.resolve("encounters/smith.yml")));
    }

    @Test
    void deleteBacksUpFirstAndIsIdempotent() throws IOException {
        store().create(template("forest_bandit", true));
        Path backups = dir.resolve("encounter-backups");

        EncounterTemplateStore.Result deleted = store().delete("forest_bandit", backups);

        assertEquals("DELETED", deleted.code(), deleted.message());
        assertFalse(Files.exists(dir.resolve("encounters/forest_bandit.yml")));
        try (var files = Files.list(backups)) {
            assertEquals(1, files.filter(f -> f.getFileName().toString().endsWith("-forest_bandit.yml")).count());
        }
        EncounterTemplateStore.Result again = store().delete("forest_bandit", backups);
        assertTrue(again.ok());
        assertEquals("ABSENT", again.code());
    }

    @Test
    void deleteWithoutBackupDirectoryIsRefused() {
        store().create(template("forest_bandit", true));
        assertEquals("NO_BACKUP_DIR", store().delete("forest_bandit", null).code());
        assertTrue(store().find("forest_bandit").isPresent());
    }

    private static EncounterTemplate template(String id, boolean enabled) {
        return new EncounterTemplate(id, "Nom " + id, null, id, null, null, Set.of("minecraft:forest"), List.of(),
                10, 0, null, enabled);
    }
}
