package com.lodygames.rpgquest.hub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.dialogue.model.ConditionType;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** Issue #675 : lecture validée de {@code hub.stations.exceptions}. */
class HubStationExceptionsYamlTest {

    private static final String HUB = "world_hub";

    @Test
    void theBundledConfigShipsAnEmptyExceptionList() throws Exception {
        try (var in = Objects.requireNonNull(getClass().getClassLoader().getResourceAsStream("config.yml"))) {
            YamlConfiguration config = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
            assertTrue(config.isList("hub.stations.exceptions"), "clé présente : le completer l'ajoute sur DEV");
            assertEquals(List.of(), HubStationExceptionsYaml.read(config.getConfigurationSection("hub"), HUB));
        }
    }

    @Test
    void aMissingSectionMeansNoException() {
        assertEquals(List.of(), HubStationExceptionsYaml.read(hub(""), HUB));
        assertEquals(List.of(), HubStationExceptionsYaml.read(null, HUB));
    }

    @Test
    void aFullExceptionIsRead() {
        List<HubStationException> read = HubStationExceptionsYaml.read(hub("""
                stations:
                  exceptions:
                    - id: salle_de_craft
                      stations: [crafting_table, FURNACE]
                      blocks:
                        - { x: 12, y: 64, z: -30 }
                      required-level: 5
                      conditions:
                        - type: QUEST_STATE
                          quest: rpgquest:crystal_hunt
                          state: COMPLETED
                        - type: VARIABLE_EQUALS
                          key: HUB_STATION_SALLE_DE_CRAFT
                          value: "true"
                      message: "<gray>Pas encore.</gray>"
                    - id: enclumes
                      stations: [ANVIL]
                """), HUB);

        assertEquals(2, read.size());
        HubStationException craft = read.get(0);
        assertEquals("salle_de_craft", craft.id());
        assertEquals(Set.of(HubStation.CRAFTING_TABLE, HubStation.FURNACE), craft.stations());
        assertEquals(List.of(new HubStationException.BlockRef(HUB, 12, 64, -30)), craft.blocks());
        assertEquals(5, craft.requiredLevel());
        assertEquals(List.of(ConditionType.QUEST_STATE, ConditionType.VARIABLE_EQUALS),
                craft.conditions().stream().map(c -> c.type()).toList());
        assertEquals("<gray>Pas encore.</gray>", craft.message());
        assertTrue(read.get(1).unconditional());
    }

    @Test
    void invalidExceptionsAreRefusedWithAReadableMessage() {
        assertRefused("doit être une liste", "stations:\n  exceptions: oui\n");
        assertRefused("id", "stations:\n  exceptions:\n    - stations: [ANVIL]\n");
        assertRefused("déjà utilisé", "stations:\n  exceptions:\n    - id: a\n    - id: a\n");
        assertRefused("poste inconnu", "stations:\n  exceptions:\n    - id: a\n      stations: [CHEST]\n");
        assertRefused("n'est pas le monde Hub",
                "stations:\n  exceptions:\n    - id: a\n      blocks:\n        - { world: wild, x: 1, y: 2, z: 3 }\n");
        assertRefused(".y", "stations:\n  exceptions:\n    - id: a\n      blocks:\n        - { x: 1, z: 3 }\n");
        assertRefused("required-level", "stations:\n  exceptions:\n    - id: a\n      required-level: 0\n");
        assertRefused("non proposée", """
                stations:
                  exceptions:
                    - id: a
                      conditions:
                        - type: HAS_INVENTORY_SPACE
                          slots: 2
                """);
    }

    private static void assertRefused(String expected, String yaml) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> HubStationExceptionsYaml.read(hub(yaml), HUB));
        assertTrue(error.getMessage().contains(expected), error.getMessage());
    }

    private static ConfigurationSection hub(String yaml) {
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.loadFromString("hub:\n  world: " + HUB + "\n" + yaml.indent(2));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return config.getConfigurationSection("hub");
    }
}
