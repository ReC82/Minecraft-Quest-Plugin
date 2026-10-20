package com.lodygames.rpgquest.mob.ability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.mob.model.EnragedAbility;
import com.lodygames.rpgquest.mob.model.MobAbilityType;
import com.lodygames.rpgquest.mob.model.SummonOnDamageAbility;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;

/** Issue #543 — catalogue publié à PlugAdmin et lecture de la liste structurée des cartes. */
class MobAbilityExchangeTest {

    private static Map<String, Object> entry(String type, String... keyValues) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", type);
        for (int i = 0; i < keyValues.length; i += 2) {
            out.put(keyValues[i], keyValues[i + 1]);
        }
        return out;
    }

    @Test
    void catalogListsEveryTypeWithBoundedParamsPresetsAndCommonFlags() {
        Map<String, Object> catalog = MobAbilityExchange.catalogDetails();
        assertEquals(MobAbilityRules.MAX_ABILITIES_PER_PROFILE, catalog.get("maxPerProfile"));
        List<?> types = (List<?>) catalog.get("types");
        assertEquals(MobAbilityType.values().length, types.size());
        Map<?, ?> enraged = types.stream().map(t -> (Map<?, ?>) t)
                .filter(t -> "ENRAGED".equals(t.get("type"))).findFirst().orElseThrow();
        assertEquals("Enragé", enraged.get("label"));
        assertEquals("NONE", enraged.get("worldMutation"));
        assertEquals(List.of("Doux", "Normal", "Brutal"), List.copyOf(((Map<?, ?>) enraged.get("presets")).keySet()));
        Map<?, ?> fraction = ((List<?>) enraged.get("params")).stream().map(p -> (Map<?, ?>) p)
                .filter(p -> "health-fraction".equals(p.get("key"))).findFirst().orElseThrow();
        assertEquals(true, fraction.get("required"));
        assertEquals(false, fraction.get("common"));
        assertEquals("doit être strictement compris entre 0 et 1", fraction.get("bounds"));
        Map<?, ?> chance = ((List<?>) enraged.get("params")).stream().map(p -> (Map<?, ?>) p)
                .filter(p -> "chance".equals(p.get("key"))).findFirst().orElseThrow();
        assertEquals(true, chance.get("common"), "champ commun du moteur → réglages avancés");
        Map<?, ?> creeper = types.stream().map(t -> (Map<?, ?>) t)
                .filter(t -> "STRONGER_EXPLOSION".equals(t.get("type"))).findFirst().orElseThrow();
        assertEquals(List.of("CREEPER"), creeper.get("effectiveOn"));
    }

    @Test
    void instanceViewCarriesEnabledFlagAndEveryValueByYamlKey() {
        Map<String, Object> view = MobAbilityExchange.instanceView(new SummonOnDamageAbility(EntityType.ZOMBIE, 2,
                4, false, new com.lodygames.rpgquest.mob.model.AbilityOptions(false, 0.25, 20)));
        assertEquals("SUMMON_ON_DAMAGE", view.get("type"));
        assertEquals(false, view.get("enabled"));
        Map<?, ?> values = (Map<?, ?>) view.get("values");
        assertEquals("ZOMBIE", values.get("summon-entity-type"));
        assertEquals(0.25, values.get("chance"));
        assertEquals(20, values.get("cooldown-seconds"));
    }

    @Test
    void parsesTextValuesInOrderKeepingADisabledAbilityAndItsValues() {
        MobAbilityExchange.Parsed parsed = MobAbilityExchange.parseEntries(List.of(
                entry("summon_on_damage", "summon-entity-type", "zombie", "amount", "2", "chance", "0,25",
                        "cooldown-seconds", "20", "max-alive", "4", "enabled", "false"),
                entry("ENRAGED", "health-fraction", "0.3", "speed-multiplier", "1.3", "damage-multiplier", "1.5",
                        "chance", "")));
        assertTrue(parsed.ok(), parsed.errors().toString());
        assertEquals(2, parsed.abilities().size());
        SummonOnDamageAbility summon = (SummonOnDamageAbility) parsed.abilities().get(0);
        assertFalse(summon.options().enabled(), "désactivée mais gardée avec ses valeurs");
        assertEquals(0.25, summon.chance());
        assertEquals(EntityType.ZOMBIE, summon.summonedEntityType());
        EnragedAbility enraged = (EnragedAbility) parsed.abilities().get(1);
        assertEquals(1.0, enraged.options().chance(), "vide = défaut du champ commun");
    }

    @Test
    void refusesUnknownKeysUnknownTypesBadBooleansAndOutOfBoundsWithReadableMessages() {
        MobAbilityExchange.Parsed parsed = MobAbilityExchange.parseEntries(List.of(
                entry("ENRAGED", "health-fraction", "1.5", "speed-multiplier", "1.3", "damage-multiplier", "1.5"),
                entry("TELEPORT"),
                entry("STRONGER_EXPLOSION", "radius-multiplier", "2", "rayon", "3"),
                entry("EXPLOSIVE_ON_ATTACK", "power", "2", "trigger-range-blocks", "2", "set-fire", "peut-être")));
        assertFalse(parsed.ok());
        assertTrue(parsed.abilities().isEmpty(), "rien n'est retenu quand une entrée est refusée");
        String all = String.join("\n", parsed.errors());
        assertTrue(all.contains("Capacité n°1 « Enragé » : « health-fraction » doit être strictement compris entre 0 et 1"), all);
        assertTrue(all.contains("Capacité n°2 : capacité inconnue « TELEPORT »"), all);
        assertTrue(all.contains("réglage inconnu « rayon »"), all);
        assertTrue(all.contains("« set-fire » doit valoir true ou false"), all);
    }

    @Test
    void missingRequiredValueAndNonNumericTextAreRefusedByTheRealParser() {
        MobAbilityExchange.Parsed parsed = MobAbilityExchange.parseEntries(List.of(
                entry("ENRAGED", "health-fraction", "", "speed-multiplier", "vite", "damage-multiplier", "1.5")));
        assertFalse(parsed.ok());
        String all = String.join("\n", parsed.errors());
        assertTrue(all.contains("« health-fraction » est obligatoire"), all);
        assertTrue(all.contains("« speed-multiplier » est obligatoire et doit être un nombre"), all);
    }

    @Test
    void tooManyEntriesAreRefusedBeforeParsing() {
        List<Map<String, Object>> many = new java.util.ArrayList<>();
        for (int i = 0; i <= MobAbilityExchange.MAX_ENTRIES; i++) {
            many.add(entry("ENRAGED"));
        }
        MobAbilityExchange.Parsed parsed = MobAbilityExchange.parseEntries(many);
        assertFalse(parsed.ok());
        assertTrue(parsed.errors().get(0).contains("au plus par profil"), parsed.errors().toString());
    }
}
