package com.lodygames.rpgquest.mob;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.mob.model.EnragedAbility;
import com.lodygames.rpgquest.mob.model.MobCategory;
import com.lodygames.rpgquest.mob.model.SpecialMobDefinition;
import com.lodygames.rpgquest.mob.model.SummonOnDamageAbility;
import com.lodygames.rpgquest.resource.model.VanillaItemDrop;
import java.io.StringReader;
import java.util.List;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;

/** Le texte YAML produit par {@link SpecialMobDefinitionYaml} se re-parse à l'identique (issue #169). */
class SpecialMobDefinitionYamlTest {

    private SpecialMobDefinition roundTrip(SpecialMobDefinition in) {
        String yaml = SpecialMobDefinitionYaml.render(in);
        YamlConfiguration section = new YamlConfiguration();
        try {
            section.load(new StringReader(yaml));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        SpecialMobDefinitionParser.ParseResult r = new SpecialMobDefinitionParser().parse("x.yml", section);
        assertTrue(r.isSuccess(), r.issues().toString());
        return r.definition();
    }

    @Test
    void fullBossDefinitionRoundTrips() {
        SpecialMobDefinition in = new SpecialMobDefinition(
                new NamespacedKey("rpgquest", "swamp_king"),
                MobCategory.BOSS, true, EntityType.ZOMBIE, "<dark_green>Roi des Marais</dark_green>", 1.0,
                Set.of("wild"), Set.of("swamp"), Set.of(),
                120.0, 12.0, 1.2, 8.0, 0.4, 1.5, null,
                null, null,
                List.of(new EnragedAbility(0.3, 1.5, 2.0),
                        new SummonOnDamageAbility(EntityType.ZOMBIE, 2, 0.5, 30, 4)),
                List.of(new VanillaItemDrop(Material.EMERALD, 100, 1, 3)),
                200, 1);

        assertEquals(in, roundTrip(in));
    }

    @Test
    void creeperExplosionRadiusRoundTrips() {
        SpecialMobDefinition in = new SpecialMobDefinition(
                new NamespacedKey("rpgquest", "big_boom"),
                MobCategory.SPECIAL, true, EntityType.CREEPER, "Big Boom", 0.01,
                Set.of(), Set.of(), Set.of(),
                null, null, null, null, null, null, 6.0,
                null, null, List.of(), List.of(), null, null);

        assertEquals(in, roundTrip(in));
    }

    @Test
    void minimalDefinitionRoundTrips() {
        SpecialMobDefinition in = new SpecialMobDefinition(
                new NamespacedKey("rpgquest", "plain_zombie"),
                MobCategory.SPECIAL, true, EntityType.ZOMBIE, "Plain", 0.1,
                Set.of(), Set.of(), Set.of(),
                null, null, null, null, null, null, null,
                null, null, List.of(), List.of(), null, null);

        assertEquals(in, roundTrip(in));
    }

    @Test
    void disabledDefinitionRoundTrips() {
        SpecialMobDefinition in = new SpecialMobDefinition(
                new NamespacedKey("rpgquest", "paused"),
                MobCategory.SPECIAL, false, EntityType.ZOMBIE, "Paused", 0.1,
                Set.of(), Set.of(), Set.of(),
                null, null, null, null, null, null, null,
                null, null, List.of(), List.of(), null, null);

        SpecialMobDefinition out = roundTrip(in);
        assertEquals(in, out);
        assertEquals(false, out.enabled());
    }
}
