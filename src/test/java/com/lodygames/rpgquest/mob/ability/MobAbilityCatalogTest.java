package com.lodygames.rpgquest.mob.ability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.mob.SpecialMobSchema;
import com.lodygames.rpgquest.mob.model.AbilityOptions;
import com.lodygames.rpgquest.mob.model.EnragedAbility;
import com.lodygames.rpgquest.mob.model.MobAbility;
import com.lodygames.rpgquest.mob.model.MobAbilityType;
import com.lodygames.rpgquest.mob.model.SummonOnDamageAbility;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;

/** Issue #542 — registre des capacités : descripteurs, lecture/écriture génériques, champs communs. */
class MobAbilityCatalogTest {

    private static ConfigurationSection entry(String yaml) throws Exception {
        YamlConfiguration section = new YamlConfiguration();
        section.load(new StringReader(yaml));
        return section;
    }

    private static MobAbility parse(String yaml, List<String> errors) throws Exception {
        return MobAbilityCatalog.parse(entry(yaml), "abilities[0]", errors);
    }

    private static String render(MobAbility ability) {
        StringBuilder sb = new StringBuilder();
        MobAbilityCatalog.render(ability, sb);
        return sb.toString();
    }

    /** Re-lit un rendu (une entrée de liste indentée) comme une section. */
    private static MobAbility reparse(String rendered) throws Exception {
        String body = rendered.replaceFirst("^  - ", "").replace("\n    ", "\n");
        List<String> errors = new ArrayList<>();
        MobAbility out = parse(body, errors);
        assertTrue(errors.isEmpty(), errors.toString());
        return out;
    }

    @Test
    void everyTypeHasADescriptorWithUniqueKeysAndNoTerrainChange() {
        assertEquals(MobAbilityType.values().length, MobAbilityCatalog.descriptors().size());
        for (AbilityDescriptor d : MobAbilityCatalog.descriptors()) {
            Set<String> keys = new HashSet<>();
            d.params().forEach(p -> assertTrue(keys.add(p.key()), d.type() + " : clé en double " + p.key()));
            assertTrue(keys.containsAll(Set.of("enabled", "chance", "cooldown-seconds")), d.type().name());
            assertEquals(AbilityDescriptor.WorldMutation.NONE, d.worldMutation(), d.type().name());
            assertEquals(AbilityDescriptor.Status.AVAILABLE, d.status(), d.type().name());
            assertFalse(d.repeatable(), d.type().name());
            assertFalse(d.triggers().isEmpty(), d.type().name());
            assertFalse(d.label().isBlank() || d.summary().isBlank(), d.type().name());
        }
    }

    @Test
    void schemaIsAViewOfTheCatalog() {
        for (MobAbilityType type : MobAbilityType.values()) {
            assertEquals(MobAbilityCatalog.acceptedKeys(type), SpecialMobSchema.abilityKeys().get(type));
            assertEquals(MobAbilityCatalog.descriptor(type).summary(), SpecialMobSchema.abilityDoc(type));
            assertEquals(MobAbilityCatalog.ownParams(type).stream().map(AbilityParam::key).toList(),
                    SpecialMobSchema.abilityFields(type).stream().map(SpecialMobSchema.Field::key).toList());
        }
    }

    /** Chaque exemple du modèle et chaque préréglage Doux/Normal/Brutal se lisent sans erreur. */
    @Test
    void examplesAndPresetsAreValidValues() throws Exception {
        for (AbilityDescriptor d : MobAbilityCatalog.descriptors()) {
            StringBuilder base = new StringBuilder("type: " + d.type() + "\n");
            for (AbilityParam p : MobAbilityCatalog.ownParams(d.type())) {
                base.append(p.key()).append(": ").append(p.example()).append('\n');
            }
            List<String> errors = new ArrayList<>();
            assertNotNull(parse(base.toString(), errors), d.type() + " " + errors);
            assertEquals(3, d.presets().size(), d.type().name());
            for (var preset : d.presets().entrySet()) {
                ConfigurationSection section = entry(base.toString());
                for (var value : preset.getValue().entrySet()) {
                    assertTrue(d.param(value.getKey()).isPresent(), d.type() + " préréglage : " + value.getKey());
                    section.set(value.getKey(), value.getValue());
                }
                errors.clear();
                MobAbility ability = MobAbilityCatalog.parse(section, "p", errors);
                assertNotNull(ability, d.type() + " " + preset.getKey() + " " + errors);
                Map<String, Object> values = MobAbilityCatalog.values(ability);
                // Comparaison textuelle : « power » est un float (rendu « 1.5 » comme le préréglage double).
                preset.getValue().forEach((k, v) -> assertEquals(String.valueOf(v), String.valueOf(values.get(k)),
                        d.type() + " " + preset.getKey() + " " + k));
            }
        }
    }

    @Test
    void absentCommonFieldsAreDefaultsAndAreNeverWritten() throws Exception {
        List<String> errors = new ArrayList<>();
        MobAbility enraged = parse("type: ENRAGED\nhealth-fraction: 0.3\nspeed-multiplier: 1.3\ndamage-multiplier: 1.5\n", errors);
        assertTrue(errors.isEmpty(), errors.toString());
        assertEquals(AbilityOptions.DEFAULTS, enraged.options());
        assertEquals("  - type: ENRAGED\n    health-fraction: 0.3\n    speed-multiplier: 1.3\n    damage-multiplier: 1.5\n",
                render(enraged));
    }

    @Test
    void nonDefaultCommonFieldsRoundTrip() throws Exception {
        EnragedAbility in = new EnragedAbility(0.3, 1.3, 1.5, new AbilityOptions(false, 0.5, 12));
        String rendered = render(in);
        assertTrue(rendered.endsWith("    enabled: false\n    chance: 0.5\n    cooldown-seconds: 12\n"), rendered);
        assertEquals(in, reparse(rendered));

        SummonOnDamageAbility summon = new SummonOnDamageAbility(EntityType.ZOMBIE, 2, 4, true,
                new AbilityOptions(false, 0.25, 20));
        String summonText = render(summon);
        assertEquals("""
                  - type: SUMMON_ON_DAMAGE
                    summon-entity-type: ZOMBIE
                    amount: 2
                    chance: 0.25
                    cooldown-seconds: 20
                    max-alive: 4
                    despawn-with-owner: true
                    enabled: false
                """, summonText);
        assertEquals(summon, reparse(summonText));
    }

    @Test
    void summonKeepsChanceAndCooldownMandatory() throws Exception {
        List<String> errors = new ArrayList<>();
        assertNull(parse("type: SUMMON_ON_DAMAGE\nsummon-entity-type: ZOMBIE\namount: 2\nmax-alive: 4\n", errors));
        assertTrue(errors.stream().anyMatch(e -> e.contains("« chance » est obligatoire")), errors.toString());
        assertTrue(errors.stream().anyMatch(e -> e.contains("« cooldown-seconds » est obligatoire")), errors.toString());
    }

    @Test
    void boundsAreCheckedWithTheFormerMessagesAndEveryErrorIsReported() throws Exception {
        List<String> errors = new ArrayList<>();
        assertNull(parse("type: ENRAGED\nhealth-fraction: 1\nspeed-multiplier: 0\ndamage-multiplier: x\n"
                + "chance: 0\ncooldown-seconds: -1\n", errors));
        String all = String.join("\n", errors);
        assertTrue(all.contains("« health-fraction » doit être strictement compris entre 0 et 1, valeur trouvée : 1.0"), all);
        assertTrue(all.contains("« speed-multiplier » doit être strictement positif"), all);
        assertTrue(all.contains("« damage-multiplier » est obligatoire et doit être un nombre."), all);
        assertTrue(all.contains("« chance » doit être compris entre 0 (exclu) et 1, valeur trouvée : 0.0"), all);
        assertTrue(all.contains("« cooldown-seconds » ne peut pas être négatif, valeur trouvée : -1"), all);
        assertEquals(5, errors.size(), all);
    }

    @Test
    void unknownOrMissingTypeIsRefused() throws Exception {
        List<String> errors = new ArrayList<>();
        assertNull(parse("type: FLY\n", errors));
        assertNull(parse("power: 2\n", errors));
        assertTrue(errors.get(0).contains("capacité inconnue « FLY »"), errors.toString());
        assertTrue(errors.get(1).contains("« type » est obligatoire"), errors.toString());
    }

    @Test
    void nonLivingSummonIsRefused() throws Exception {
        List<String> errors = new ArrayList<>();
        assertNull(parse("type: SUMMON_ON_DAMAGE\nsummon-entity-type: ARROW\namount: 1\nchance: 1\n"
                + "cooldown-seconds: 0\nmax-alive: 1\n", errors));
        assertTrue(errors.get(0).contains("invalide ou non vivant"), errors.toString());
    }

    @Test
    void descriptorRefusesATerrainChangingAbility() {
        IllegalArgumentException e = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new AbilityDescriptor(MobAbilityType.ENRAGED, "x", "x", AbilityDescriptor.Category.REACTION,
                        AbilityDescriptor.Status.AVAILABLE, AbilityDescriptor.Kind.ACTION, Set.of(AbilityTrigger.TICK),
                        false, Set.of(), List.of(), Map.of(), 0, AbilityDescriptor.WorldMutation.TEMPORARY));
        assertTrue(e.getMessage().contains("terrain"), e.getMessage());
    }

    @Test
    void memorySectionEntriesFromAProfileListParseLikeYamlOnes() {
        MemoryConfiguration section = new MemoryConfiguration();
        section.set("type", "split_on_hit");
        section.set("max-depth", 1);
        section.set("max-children-per-hit", 2);
        List<String> errors = new ArrayList<>();
        MobAbility ability = MobAbilityCatalog.parse(section, "abilities[0]", errors);
        assertTrue(errors.isEmpty(), errors.toString());
        assertEquals(2, MobAbilityCatalog.values(ability).get("max-alive-per-parent"), "défaut #190 conservé");
    }
}
