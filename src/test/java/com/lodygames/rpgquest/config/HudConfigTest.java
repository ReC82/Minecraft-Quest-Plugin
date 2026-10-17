package com.lodygames.rpgquest.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** Issue #184 — section {@code hud:} du config.yml. */
class HudConfigTest {

    @Test
    void missingSectionKeepsSidebarOffAndWalletFeedbackOn() throws Exception {
        HudConfig hud = ConfigValidator.validate(load("")).hud();

        assertEquals(HudMode.OFF, hud.defaultMode());
        assertTrue(hud.walletFeedback());
    }

    @Test
    void readsModeCaseInsensitivelyAndFeedbackFlag() throws Exception {
        HudConfig hud = ConfigValidator.validate(load("""
                hud:
                  default-mode: Sidebar
                  wallet-feedback: false
                """)).hud();

        assertEquals(HudMode.SIDEBAR, hud.defaultMode());
        assertFalse(hud.walletFeedback());
    }

    @Test
    void unquotedOffReadAsYamlBooleanStillMeansOff() throws Exception {
        HudConfig hud = ConfigValidator.validate(load("""
                hud:
                  default-mode: off
                """)).hud();

        assertEquals(HudMode.OFF, hud.defaultMode());
    }

    @Test
    void unknownModeIsRejectedWithTheValidValues() {
        ConfigValidationException error = assertThrows(ConfigValidationException.class,
                () -> ConfigValidator.validate(load("""
                        hud:
                          default-mode: scoreboard
                        """)));

        assertTrue(error.getMessage().contains("hud.default-mode"));
        assertTrue(error.getMessage().contains("sidebar"));
    }

    @Test
    void bundledConfigShipsThePrototypeOff() throws Exception {
        YamlConfiguration bundled = new YamlConfiguration();
        try (var in = getClass().getResourceAsStream("/config.yml");
             var reader = new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8)) {
            bundled.load(reader);
        }

        HudConfig hud = ConfigValidator.validate(bundled).hud();

        assertEquals(HudMode.OFF, hud.defaultMode());
        assertTrue(hud.walletFeedback());
    }

    private static YamlConfiguration load(String yaml) throws InvalidConfigurationException {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(yaml);
        return config;
    }
}
