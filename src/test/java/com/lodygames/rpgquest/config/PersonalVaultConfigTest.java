package com.lodygames.rpgquest.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** Issue #106 — section {@code personal-vaults:} du config.yml. */
class PersonalVaultConfigTest {

    @Test
    void missingSectionOpensVault01WithThreeRows() throws Exception {
        PersonalVaultConfig vaults = ConfigValidator.validate(load("")).personalVaults();

        assertEquals(List.of("vault_01"), vaults.starterVaults());
        assertEquals(27, vaults.defaultSlots());
    }

    @Test
    void readsStarterVaultsAndSlots() throws Exception {
        PersonalVaultConfig vaults = ConfigValidator.validate(load("""
                personal-vaults:
                  starter-vaults: [vault_01, vault_02]
                  default-slots: 9
                """)).personalVaults();

        assertEquals(List.of("vault_01", "vault_02"), vaults.starterVaults());
        assertEquals(9, vaults.defaultSlots());
    }

    @Test
    void anEmptyStarterListLocksEveryVault() throws Exception {
        PersonalVaultConfig vaults = ConfigValidator.validate(load("""
                personal-vaults:
                  starter-vaults: []
                """)).personalVaults();

        assertTrue(vaults.starterVaults().isEmpty());
    }

    @Test
    void slotsThatAreNotWholeRowsAreRejected() {
        ConfigValidationException error = assertThrows(ConfigValidationException.class,
                () -> ConfigValidator.validate(load("""
                        personal-vaults:
                          default-slots: 30
                        """)));

        assertTrue(error.getMessage().contains("personal-vaults.default-slots"));
    }

    @Test
    void invalidStarterIdIsRejected() {
        assertThrows(ConfigValidationException.class, () -> ConfigValidator.validate(load("""
                personal-vaults:
                  starter-vaults: ["Vault 01"]
                """)));
    }

    @Test
    void bundledConfigShipsTheDemoCapacity() throws Exception {
        YamlConfiguration bundled = new YamlConfiguration();
        try (var in = getClass().getResourceAsStream("/config.yml");
             var reader = new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8)) {
            bundled.load(reader);
        }

        assertEquals(PersonalVaultConfig.defaults(), ConfigValidator.validate(bundled).personalVaults());
    }

    private static YamlConfiguration load(String yaml) throws InvalidConfigurationException {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(yaml);
        return config;
    }
}
