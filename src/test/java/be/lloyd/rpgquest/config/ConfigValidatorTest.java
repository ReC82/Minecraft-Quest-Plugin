package be.lloyd.rpgquest.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class ConfigValidatorTest {

    private static final String VALID_SHA1 = "da39a3ee5e6b4b0d3255bfef95601890afd80709";

    @Test
    void acceptsMinimalValidConfig() throws Exception {
        PluginConfig config = ConfigValidator.validate(load("""
                debug: false
                locale: fr
                database:
                  file: data.db
                """));

        assertFalse(config.debug());
        assertEquals("fr", config.locale());
        assertEquals("data.db", config.databaseFile());
        assertFalse(config.resourcePack().enabled());
    }

    @Test
    void defaultsApplyWhenFieldsAreMissing() throws Exception {
        PluginConfig config = ConfigValidator.validate(load(""));

        assertFalse(config.debug());
        assertEquals("fr", config.locale());
        assertEquals("data.db", config.databaseFile());
        assertFalse(config.resourcePack().enabled());
    }

    @Test
    void rejectsNonBooleanDebug() {
        ConfigurationSection section = load("debug: \"yes-please\"\n");

        ConfigValidationException exception =
                assertThrows(ConfigValidationException.class, () -> ConfigValidator.validate(section));
        assertTrue(exception.getMessage().contains("debug"));
    }

    @Test
    void rejectsUnknownLocale() {
        ConfigurationSection section = load("locale: xx-not-a-code\n");

        ConfigValidationException exception =
                assertThrows(ConfigValidationException.class, () -> ConfigValidator.validate(section));
        assertTrue(exception.getMessage().contains("locale"));
    }

    @Test
    void rejectsBlankDatabaseFile() {
        ConfigurationSection section = load("""
                database:
                  file: ""
                """);

        ConfigValidationException exception =
                assertThrows(ConfigValidationException.class, () -> ConfigValidator.validate(section));
        assertTrue(exception.getMessage().contains("database.file"));
    }

    @Test
    void rejectsDatabaseFileWithPathTraversal() {
        ConfigurationSection section = load("""
                database:
                  file: "../secrets.db"
                """);

        assertThrows(ConfigValidationException.class, () -> ConfigValidator.validate(section));
    }

    @Test
    void acceptsDisabledResourcePackWithoutUrlOrSha1() throws Exception {
        PluginConfig config = ConfigValidator.validate(load("""
                resource-pack:
                  enabled: false
                """));

        assertFalse(config.resourcePack().enabled());
    }

    @Test
    void rejectsEnabledResourcePackMissingSha1() {
        ConfigurationSection section = load("""
                resource-pack:
                  enabled: true
                  url: https://example.com/pack.zip
                """);

        ConfigValidationException exception =
                assertThrows(ConfigValidationException.class, () -> ConfigValidator.validate(section));
        assertTrue(exception.getMessage().contains("sha1"));
    }

    @Test
    void rejectsEnabledResourcePackWithInvalidUrlScheme() {
        ConfigurationSection section = load("""
                resource-pack:
                  enabled: true
                  url: ftp://example.com/pack.zip
                  sha1: %s
                """.formatted(VALID_SHA1));

        assertThrows(ConfigValidationException.class, () -> ConfigValidator.validate(section));
    }

    @Test
    void acceptsFullyValidEnabledResourcePack() throws Exception {
        PluginConfig config = ConfigValidator.validate(load("""
                resource-pack:
                  enabled: true
                  url: https://example.com/pack.zip
                  sha1: %s
                """.formatted(VALID_SHA1)));

        assertTrue(config.resourcePack().enabled());
        assertEquals("https://example.com/pack.zip", config.resourcePack().url());
        assertEquals(VALID_SHA1, config.resourcePack().sha1());
        assertFalse(config.resourcePack().required(), "« required » doit valoir false par défaut");
    }

    @Test
    void requiredDefaultsToFalseWhenResourcePackSectionIsAbsent() throws Exception {
        PluginConfig config = ConfigValidator.validate(load(""));

        assertFalse(config.resourcePack().required());
    }

    @Test
    void requiredCanBeEnabledAlongsideAValidResourcePack() throws Exception {
        PluginConfig config = ConfigValidator.validate(load("""
                resource-pack:
                  enabled: true
                  url: https://example.com/pack.zip
                  sha1: %s
                  required: true
                """.formatted(VALID_SHA1)));

        assertTrue(config.resourcePack().required());
    }

    private ConfigurationSection load(String yaml) {
        return YamlConfiguration.loadConfiguration(new StringReader(yaml));
    }
}
