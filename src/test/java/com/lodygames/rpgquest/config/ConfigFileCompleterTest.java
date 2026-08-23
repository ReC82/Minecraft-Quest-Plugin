package com.lodygames.rpgquest.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.helpers.NOPLogger;

/**
 * Mission « cohérence du config.yml » — pur JUnit (aucune dépendance à un serveur Bukkit vivant,
 * {@link YamlConfiguration} fonctionne en JUnit pur, même discipline que {@code ConfigValidatorTest}).
 */
class ConfigFileCompleterTest {

    private static final String TEMPLATE = """
            debug: false
            locale: fr
            database:
              file: data.db
            dialogue:
              renderer: paper-dialog
              allowed-commands:
                - "give"
                - "xp"
                - "customitem"
                - "claim"
            claims:
              max-width: 64
              world: claims
            """;

    /** Config historique VeryGames réel (voir {@code src/main/resources/backup-ftp/config.yml}) : seulement 4 clés. */
    private static final String HISTORICAL_MINIMAL_CONFIG = """
            debug: false
            locale: fr
            database:
              file: data.db
            resource-pack:
              enabled: false
              url: ""
              sha1: ""
            """;

    @TempDir
    Path tempDir;

    private Path writeConfig(String content) throws IOException {
        Path file = tempDir.resolve("config.yml");
        Files.writeString(file, content);
        return file;
    }

    private InputStream template() {
        return new ByteArrayInputStream(TEMPLATE.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void oldMinimalConfigReceivesTheMissingSections() throws Exception {
        Path file = writeConfig(HISTORICAL_MINIMAL_CONFIG);

        boolean changed = ConfigFileCompleter.complete(file, template(), NOPLogger.NOP_LOGGER);

        assertTrue(changed);
        YamlConfiguration result = YamlConfiguration.loadConfiguration(file.toFile());
        assertTrue(result.isConfigurationSection("dialogue"), "la section dialogue (apparue depuis) doit être ajoutée");
        assertTrue(result.isConfigurationSection("claims"), "la section claims (apparue depuis) doit être ajoutée");
        assertEquals("claims", result.getString("claims.world"));
        // resource-pack (déjà présent dans le fichier historique) doit rester tel quel.
        assertTrue(result.isConfigurationSection("resource-pack"));
    }

    @Test
    void existingCustomizedValuesAreNeverOverwritten() throws Exception {
        Path file = writeConfig("""
                debug: true
                locale: fr
                database:
                  file: custom-data.db
                """);

        ConfigFileCompleter.complete(file, template(), NOPLogger.NOP_LOGGER);

        YamlConfiguration result = YamlConfiguration.loadConfiguration(file.toFile());
        assertTrue(result.getBoolean("debug"), "une valeur personnalisée (debug=true) ne doit jamais être écrasée");
        assertEquals("custom-data.db", result.getString("database.file"), "database.file personnalisé ne doit jamais être écrasé");
    }

    @Test
    void listsAreMergedAdditivelyWithoutDuplicatesOrLoss() throws Exception {
        Path file = writeConfig("""
                debug: false
                locale: fr
                database:
                  file: data.db
                dialogue:
                  renderer: chat
                  allowed-commands:
                    - "give"
                    - "xp"
                    - "customitem"
                """);

        ConfigFileCompleter.complete(file, template(), NOPLogger.NOP_LOGGER);

        YamlConfiguration result = YamlConfiguration.loadConfiguration(file.toFile());
        List<String> commands = result.getStringList("dialogue.allowed-commands");
        assertEquals(List.of("give", "xp", "customitem", "claim"), commands,
                "la liste existante doit être conservée en tête et complétée par les entrées manquantes du gabarit");
        assertEquals("chat", result.getString("dialogue.renderer"), "renderer personnalisé jamais écrasé par la liste additive");
    }

    @Test
    void secondRunIsIdempotentAndWritesNothing() throws Exception {
        Path file = writeConfig(HISTORICAL_MINIMAL_CONFIG);
        ConfigFileCompleter.complete(file, template(), NOPLogger.NOP_LOGGER);
        String afterFirstRun = Files.readString(file);

        boolean changedOnSecondRun = ConfigFileCompleter.complete(file, template(), NOPLogger.NOP_LOGGER);

        assertFalse(changedOnSecondRun, "un fichier déjà à jour ne doit plus jamais être réécrit");
        assertEquals(afterFirstRun, Files.readString(file));
    }

    @Test
    void backupIsCreatedOnlyWhenARealChangeHappens() throws Exception {
        Path file = writeConfig(HISTORICAL_MINIMAL_CONFIG);
        Path backup = tempDir.resolve("config.yml.bak");
        assertFalse(Files.exists(backup));

        ConfigFileCompleter.complete(file, template(), NOPLogger.NOP_LOGGER);

        assertTrue(Files.exists(backup), "une vraie modification doit produire une sauvegarde .bak");
        assertTrue(Files.readString(backup).contains("resource-pack"), "la sauvegarde doit contenir l'ancien contenu, avant complétion");
    }

    @Test
    void noBackupWhenTheFileIsAlreadyUpToDate() throws Exception {
        Path file = writeConfig(HISTORICAL_MINIMAL_CONFIG);
        ConfigFileCompleter.complete(file, template(), NOPLogger.NOP_LOGGER);
        Path backup = tempDir.resolve("config.yml.bak");
        Files.deleteIfExists(backup);

        ConfigFileCompleter.complete(file, template(), NOPLogger.NOP_LOGGER);

        assertFalse(Files.exists(backup), "aucune écriture (idempotent) ne doit jamais recréer de sauvegarde");
    }

    @Test
    void configVersionIsStampedAfterCompletion() throws Exception {
        Path file = writeConfig(HISTORICAL_MINIMAL_CONFIG);

        ConfigFileCompleter.complete(file, template(), NOPLogger.NOP_LOGGER);

        YamlConfiguration result = YamlConfiguration.loadConfiguration(file.toFile());
        assertEquals(ConfigFileCompleter.CURRENT_VERSION, result.getInt("config-version"));
    }
}
