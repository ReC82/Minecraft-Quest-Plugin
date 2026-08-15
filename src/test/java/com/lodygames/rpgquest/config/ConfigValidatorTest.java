package com.lodygames.rpgquest.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.util.List;
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
    void dialogueRendererDefaultsToPaperDialogWhenSectionIsMissing() throws Exception {
        PluginConfig config = ConfigValidator.validate(load(""));

        assertEquals(RendererKind.PAPER_DIALOG, config.dialogue().renderer());
    }

    @Test
    void dialogueRendererDefaultsToPaperDialogWhenKeyIsMissing() throws Exception {
        PluginConfig config = ConfigValidator.validate(load("""
                dialogue:
                  allowed-commands: []
                """));

        assertEquals(RendererKind.PAPER_DIALOG, config.dialogue().renderer());
    }

    @Test
    void dialogueRendererAcceptsExplicitChat() throws Exception {
        PluginConfig config = ConfigValidator.validate(load("""
                dialogue:
                  renderer: chat
                """));

        assertEquals(RendererKind.CHAT, config.dialogue().renderer());
    }

    @Test
    void rejectsUnknownDialogueRenderer() {
        ConfigurationSection section = load("""
                dialogue:
                  renderer: not-a-renderer
                """);

        ConfigValidationException exception =
                assertThrows(ConfigValidationException.class, () -> ConfigValidator.validate(section));
        assertTrue(exception.getMessage().contains("dialogue.renderer"));
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

    @Test
    void adminFlattenDefaultsApplyWhenSectionIsAbsent() throws Exception {
        PluginConfig config = ConfigValidator.validate(load(""));

        AdminFlattenConfig flatten = config.adminFlatten();
        assertEquals(48, flatten.maxRadius());
        assertEquals(FlattenShape.SQUARE, flatten.defaultShape());
        assertEquals(org.bukkit.Material.GRASS_BLOCK, flatten.topLayerMaterial());
        assertEquals(org.bukkit.Material.DIRT, flatten.subLayerMaterial());
        assertEquals(3, flatten.subLayerDepth());
        assertEquals(10, flatten.clearAboveHeight());
        assertEquals(30, flatten.confirmationTimeoutSeconds());
        assertEquals(4000, flatten.blocksPerTick());
        assertTrue(flatten.forbiddenWorlds().isEmpty());
    }

    @Test
    void acceptsFullyCustomAdminFlattenConfig() throws Exception {
        PluginConfig config = ConfigValidator.validate(load("""
                admin:
                  flatten:
                    max-radius: 16
                    default-shape: circle
                    top-layer-material: stone
                    sub-layer-material: cobblestone
                    sub-layer-depth: 2
                    clear-above-height: 5
                    confirmation-timeout-seconds: 15
                    blocks-per-tick: 500
                    forbidden-worlds: ["world_the_end"]
                """));

        AdminFlattenConfig flatten = config.adminFlatten();
        assertEquals(16, flatten.maxRadius());
        assertEquals(FlattenShape.CIRCLE, flatten.defaultShape());
        assertEquals(org.bukkit.Material.STONE, flatten.topLayerMaterial());
        assertEquals(org.bukkit.Material.COBBLESTONE, flatten.subLayerMaterial());
        assertEquals(2, flatten.subLayerDepth());
        assertEquals(5, flatten.clearAboveHeight());
        assertEquals(15, flatten.confirmationTimeoutSeconds());
        assertEquals(500, flatten.blocksPerTick());
        assertEquals(List.of("world_the_end"), flatten.forbiddenWorlds());
    }

    @Test
    void rejectsNonPositiveAdminFlattenMaxRadius() {
        ConfigurationSection section = load("""
                admin:
                  flatten:
                    max-radius: 0
                """);

        ConfigValidationException exception =
                assertThrows(ConfigValidationException.class, () -> ConfigValidator.validate(section));
        assertTrue(exception.getMessage().contains("max-radius"));
    }

    @Test
    void rejectsUnknownAdminFlattenShape() {
        ConfigurationSection section = load("""
                admin:
                  flatten:
                    default-shape: triangle
                """);

        ConfigValidationException exception =
                assertThrows(ConfigValidationException.class, () -> ConfigValidator.validate(section));
        assertTrue(exception.getMessage().contains("default-shape"));
    }

    @Test
    void rejectsNonBlockAdminFlattenMaterial() {
        ConfigurationSection section = load("""
                admin:
                  flatten:
                    top-layer-material: diamond
                """);

        ConfigValidationException exception =
                assertThrows(ConfigValidationException.class, () -> ConfigValidator.validate(section));
        assertTrue(exception.getMessage().contains("top-layer-material"));
    }

    @Test
    void rejectsNegativeAdminFlattenSubLayerDepth() {
        ConfigurationSection section = load("""
                admin:
                  flatten:
                    sub-layer-depth: -1
                """);

        assertThrows(ConfigValidationException.class, () -> ConfigValidator.validate(section));
    }

    @Test
    void rejectsNonPositiveAdminFlattenBlocksPerTick() {
        ConfigurationSection section = load("""
                admin:
                  flatten:
                    blocks-per-tick: 0
                """);

        assertThrows(ConfigValidationException.class, () -> ConfigValidator.validate(section));
    }

    @Test
    void claimsDefaultsApplyWhenSectionIsAbsent() throws Exception {
        PluginConfig config = ConfigValidator.validate(load(""));

        ClaimConfig claims = config.claims();
        assertEquals(64, claims.maxWidth());
        assertEquals(384, claims.maxHeight());
        assertEquals(3, claims.maxClaimsPerPlayer());
        assertEquals(16, claims.portalBufferBlocks());
    }

    @Test
    void acceptsFullyCustomClaimsConfig() throws Exception {
        PluginConfig config = ConfigValidator.validate(load("""
                claims:
                  max-width: 32
                  max-height: 128
                  max-claims-per-player: 5
                  portal-buffer-blocks: 8
                """));

        ClaimConfig claims = config.claims();
        assertEquals(32, claims.maxWidth());
        assertEquals(128, claims.maxHeight());
        assertEquals(5, claims.maxClaimsPerPlayer());
        assertEquals(8, claims.portalBufferBlocks());
    }

    @Test
    void rejectsNonPositiveClaimsMaxWidth() {
        ConfigurationSection section = load("""
                claims:
                  max-width: 0
                """);

        ConfigValidationException exception =
                assertThrows(ConfigValidationException.class, () -> ConfigValidator.validate(section));
        assertTrue(exception.getMessage().contains("max-width"));
    }

    @Test
    void rejectsNegativeClaimsMaxClaimsPerPlayer() {
        ConfigurationSection section = load("""
                claims:
                  max-claims-per-player: -1
                """);

        assertThrows(ConfigValidationException.class, () -> ConfigValidator.validate(section));
    }

    @Test
    void webExportDefaultsToDisabledWithAllSkills() throws Exception {
        PluginConfig config = ConfigValidator.validate(load(""));

        WebExportConfig webExport = config.webExport();
        assertFalse(webExport.enabled());
        assertEquals("web-export", webExport.outputDirectory());
        assertEquals(30, webExport.intervalSeconds());
        assertFalse(webExport.includeConnectedPlayers());
        assertEquals(10, webExport.leaderboardSize());
        assertEquals(com.lodygames.rpgquest.progression.model.SkillType.values().length, webExport.leaderboardSkills().size());
        assertTrue(webExport.announcements().isEmpty());
    }

    @Test
    void acceptsFullyCustomWebExportConfig() throws Exception {
        PluginConfig config = ConfigValidator.validate(load("""
                web-export:
                  enabled: true
                  output-dir: portal-export
                  interval-seconds: 60
                  include-connected-players: true
                  leaderboard-size: 5
                  leaderboard-skills:
                    - combat
                    - mining
                  announcements:
                    - title: "Maintenance"
                      body: "Serveur en pause dimanche."
                """));

        WebExportConfig webExport = config.webExport();
        assertTrue(webExport.enabled());
        assertEquals("portal-export", webExport.outputDirectory());
        assertEquals(60, webExport.intervalSeconds());
        assertTrue(webExport.includeConnectedPlayers());
        assertEquals(5, webExport.leaderboardSize());
        assertEquals(List.of(
                com.lodygames.rpgquest.progression.model.SkillType.COMBAT,
                com.lodygames.rpgquest.progression.model.SkillType.MINING), webExport.leaderboardSkills());
        assertEquals(1, webExport.announcements().size());
        assertEquals("Maintenance", webExport.announcements().get(0).title());
    }

    @Test
    void rejectsUnknownWebExportLeaderboardSkill() {
        ConfigurationSection section = load("""
                web-export:
                  leaderboard-skills:
                    - not-a-skill
                """);

        ConfigValidationException exception =
                assertThrows(ConfigValidationException.class, () -> ConfigValidator.validate(section));
        assertTrue(exception.getMessage().contains("leaderboard-skills"));
    }

    @Test
    void rejectsWebExportAnnouncementWithoutTitle() {
        ConfigurationSection section = load("""
                web-export:
                  announcements:
                    - body: "Sans titre."
                """);

        assertThrows(ConfigValidationException.class, () -> ConfigValidator.validate(section));
    }

    @Test
    void rejectsTooShortWebExportInterval() {
        ConfigurationSection section = load("""
                web-export:
                  interval-seconds: 1
                """);

        ConfigValidationException exception =
                assertThrows(ConfigValidationException.class, () -> ConfigValidator.validate(section));
        assertTrue(exception.getMessage().contains("interval-seconds"));
    }

    @Test
    void randomSafeArrivalDefaultsWhenSectionIsMissing() throws Exception {
        PluginConfig config = ConfigValidator.validate(load(""));

        assertEquals(500, config.randomSafeArrival().minRadius());
        assertEquals(5000, config.randomSafeArrival().maxRadius());
        assertEquals(20, config.randomSafeArrival().maxAttempts());
    }

    @Test
    void randomSafeArrivalAcceptsCustomValues() throws Exception {
        PluginConfig config = ConfigValidator.validate(load("""
                travel:
                  random-safe-arrival:
                    min-radius: 100
                    max-radius: 2000
                    max-attempts: 5
                """));

        assertEquals(100, config.randomSafeArrival().minRadius());
        assertEquals(2000, config.randomSafeArrival().maxRadius());
        assertEquals(5, config.randomSafeArrival().maxAttempts());
    }

    @Test
    void rejectsRandomSafeArrivalMaxRadiusBelowMinRadius() {
        ConfigurationSection section = load("""
                travel:
                  random-safe-arrival:
                    min-radius: 1000
                    max-radius: 500
                """);

        ConfigValidationException exception =
                assertThrows(ConfigValidationException.class, () -> ConfigValidator.validate(section));
        assertTrue(exception.getMessage().contains("max-radius"));
    }

    @Test
    void rejectsRandomSafeArrivalMaxAttemptsBelowOne() {
        ConfigurationSection section = load("""
                travel:
                  random-safe-arrival:
                    max-attempts: 0
                """);

        ConfigValidationException exception =
                assertThrows(ConfigValidationException.class, () -> ConfigValidator.validate(section));
        assertTrue(exception.getMessage().contains("max-attempts"));
    }

    @Test
    void hubDefaultsToWorldHubWhenSectionIsMissing() throws Exception {
        PluginConfig config = ConfigValidator.validate(load(""));

        assertEquals("world_hub", config.hub().world());
    }

    @Test
    void hubAcceptsACustomWorldName() throws Exception {
        PluginConfig config = ConfigValidator.validate(load("""
                hub:
                  world: my_custom_hub
                """));

        assertEquals("my_custom_hub", config.hub().world());
    }

    @Test
    void rejectsBlankHubWorld() {
        ConfigurationSection section = load("""
                hub:
                  world: ""
                """);

        ConfigValidationException exception =
                assertThrows(ConfigValidationException.class, () -> ConfigValidator.validate(section));
        assertTrue(exception.getMessage().contains("hub.world"));
    }

    private ConfigurationSection load(String yaml) {
        return YamlConfiguration.loadConfiguration(new StringReader(yaml));
    }
}
