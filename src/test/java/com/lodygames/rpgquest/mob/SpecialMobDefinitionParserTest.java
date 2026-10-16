package com.lodygames.rpgquest.mob;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.mob.model.ExplosiveOnAttackAbility;
import com.lodygames.rpgquest.mob.model.MobAbility;
import com.lodygames.rpgquest.mob.model.SpecialMobDefinition;
import com.lodygames.rpgquest.mob.model.SplitOnHitAbility;
import com.lodygames.rpgquest.mob.model.StrongerExplosionAbility;
import com.lodygames.rpgquest.resource.model.VanillaItemDrop;
import java.io.StringReader;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

// Particle/Sound sont des OldEnum adossés au registre serveur (contrairement à Material, simple
// enum) : leurs constantes statiques (Particle.TOTEM_OF_UNDYING, Sound.ENTITY_PLAYER_LEVELUP...)
// ne se résolvent qu'une fois un serveur (même simulé) bootstrapé, d'où MockBukkit ici — inutile
// dans ResourceNodeDefinitionParserTest qui ne touche jamais ces deux types.
class SpecialMobDefinitionParserTest {

    private final SpecialMobDefinitionParser parser = new SpecialMobDefinitionParser();

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void validFileParsesSuccessfully() {
        SpecialMobDefinitionParser.ParseResult result = parser.parse("valid.yml", load("""
                id: rpgquest:golden_creeper
                entity-type: CREEPER
                name: "<gold>Creeper Doré</gold>"
                spawn-chance: 0.005
                worlds:
                  - world
                biomes:
                  - plains
                zones:
                  - arena
                health: 40
                damage: 6
                speed: 1.0
                armor: 2
                particle: TOTEM_OF_UNDYING
                sound: ENTITY_PLAYER_LEVELUP
                abilities:
                  - type: STRONGER_EXPLOSION
                    radius-multiplier: 1.5
                drops:
                  - material: GOLDEN_APPLE
                    weight: 40
                    min-amount: 1
                    max-amount: 1
                xp-reward: 50
                max-population: 2
                """));

        assertTrue(result.isSuccess(), () -> "issues: " + result.issues());
        SpecialMobDefinition def = result.definition();
        assertEquals("rpgquest:golden_creeper", def.id().toString());
        assertEquals(EntityType.CREEPER, def.entityType());
        assertEquals(0.005, def.spawnChance());
        assertEquals(java.util.Set.of("world"), def.allowedWorlds());
        assertEquals(java.util.Set.of("plains"), def.allowedBiomes());
        assertEquals(java.util.Set.of("arena"), def.allowedZones());
        assertEquals(40.0, def.health());
        assertEquals(6.0, def.damage());
        assertEquals(1.0, def.speed());
        assertEquals(2.0, def.armor());
        assertEquals(Particle.TOTEM_OF_UNDYING, def.particle());
        assertEquals(Sound.ENTITY_PLAYER_LEVELUP, def.sound());
        assertEquals(1, def.abilities().size());
        assertEquals(new StrongerExplosionAbility(1.5), def.abilities().get(0));
        assertEquals(1, def.drops().size());
        assertEquals(new VanillaItemDrop(Material.GOLDEN_APPLE, 40, 1, 1), def.drops().get(0));
        assertEquals(50, def.xpReward());
        assertEquals(2, def.maxPopulation());
    }

    @Test
    void minimalFileOnlyRequiresIdEntityTypeNameAndSpawnChance() {
        SpecialMobDefinitionParser.ParseResult result = parser.parse("minimal.yml", load(minimalMob()));

        assertTrue(result.isSuccess(), () -> "issues: " + result.issues());
        SpecialMobDefinition def = result.definition();
        assertTrue(def.allowedWorlds().isEmpty());
        assertTrue(def.abilities().isEmpty());
        assertTrue(def.drops().isEmpty());
        assertEquals(null, def.xpReward());
        assertEquals(null, def.maxPopulation());
    }

    @Test
    void missingRequiredFieldsAreAllReportedTogether() {
        SpecialMobDefinitionParser.ParseResult result = parser.parse("incomplete.yml", load("""
                entity-type: CREEPER
                """));

        assertFalse(result.isSuccess());
        String combined = String.join(" | ", result.issues().stream().map(SpecialMobLoadIssue::message).toList());
        assertTrue(combined.contains("id"), combined);
        assertTrue(combined.contains("name"), combined);
        assertTrue(combined.contains("spawn-chance"), combined);
    }

    @Test
    void unknownEntityTypeIsRejected() {
        SpecialMobDefinitionParser.ParseResult result = parser.parse("bad.yml", load("""
                id: rpgquest:bad
                entity-type: NOT_AN_ENTITY
                name: "Bad"
                spawn-chance: 0.1
                """));

        assertFalse(result.isSuccess());
        assertTrue(result.issues().get(0).message().contains("entity-type"), result.issues().toString());
    }

    @Test
    void nonLivingEntityTypeIsRejected() {
        SpecialMobDefinitionParser.ParseResult result = parser.parse("bad.yml", load("""
                id: rpgquest:bad
                entity-type: ARROW
                name: "Bad"
                spawn-chance: 0.1
                """));

        assertFalse(result.isSuccess());
    }

    @Test
    void spawnChanceOutOfRangeIsRejected() {
        SpecialMobDefinitionParser.ParseResult result = parser.parse("bad.yml", load("""
                id: rpgquest:bad
                entity-type: ZOMBIE
                name: "Bad"
                spawn-chance: 1.5
                """));

        assertFalse(result.isSuccess());
    }

    @Test
    void explosiveOnAttackAbilityParsesAllFields() {
        SpecialMobDefinitionParser.ParseResult result = parser.parse("pig.yml", load("""
                id: rpgquest:creeper_pig
                entity-type: PIG
                name: "Creeper Pig"
                spawn-chance: 0.01
                abilities:
                  - type: EXPLOSIVE_ON_ATTACK
                    power: 3.0
                    set-fire: true
                    trigger-range-blocks: 3.0
                """));

        assertTrue(result.isSuccess(), () -> "issues: " + result.issues());
        MobAbility ability = result.definition().abilities().get(0);
        assertEquals(new ExplosiveOnAttackAbility(3.0f, true, 3.0), ability);
    }

    @Test
    void splitOnHitAbilityParsesAllFields() {
        SpecialMobDefinitionParser.ParseResult result = parser.parse("zombie.yml", load("""
                id: rpgquest:splitting_zombie
                entity-type: ZOMBIE
                name: "Splitting Zombie"
                spawn-chance: 0.02
                abilities:
                  - type: SPLIT_ON_HIT
                    max-depth: 2
                    max-children-per-hit: 2
                """));

        assertTrue(result.isSuccess(), () -> "issues: " + result.issues());
        MobAbility ability = result.definition().abilities().get(0);
        assertEquals(new SplitOnHitAbility(2, 2), ability);
        assertEquals(2, ((SplitOnHitAbility) ability).maxAlivePerParent(),
                "« max-alive-per-parent » absent du YAML doit défaut à 2 (issue #190)");
    }

    @Test
    void splitOnHitAbilityAcceptsAnExplicitMaxAlivePerParent() {
        SpecialMobDefinitionParser.ParseResult result = parser.parse("zombie.yml", load("""
                id: rpgquest:splitting_zombie
                entity-type: ZOMBIE
                name: "Splitting Zombie"
                spawn-chance: 0.02
                abilities:
                  - type: SPLIT_ON_HIT
                    max-depth: 2
                    max-children-per-hit: 2
                    max-alive-per-parent: 5
                """));

        assertTrue(result.isSuccess(), () -> "issues: " + result.issues());
        SplitOnHitAbility ability = (SplitOnHitAbility) result.definition().abilities().get(0);
        assertEquals(5, ability.maxAlivePerParent());
    }

    @Test
    void unknownAbilityTypeIsRejected() {
        SpecialMobDefinitionParser.ParseResult result = parser.parse("bad.yml", load("""
                id: rpgquest:bad
                entity-type: ZOMBIE
                name: "Bad"
                spawn-chance: 0.1
                abilities:
                  - type: NOT_AN_ABILITY
                """));

        assertFalse(result.isSuccess());
        assertTrue(result.issues().get(0).message().contains("abilities[0]"), result.issues().toString());
    }

    @Test
    void dropWithBothCustomItemAndMaterialIsRejected() {
        SpecialMobDefinitionParser.ParseResult result = parser.parse("bad.yml", load("""
                id: rpgquest:bad
                entity-type: ZOMBIE
                name: "Bad"
                spawn-chance: 0.1
                drops:
                  - custom-item: rpgquest:refined_crystal
                    material: QUARTZ
                    weight: 1
                    min-amount: 1
                    max-amount: 1
                """));

        assertFalse(result.isSuccess());
        assertTrue(result.issues().get(0).message().contains("exactement un"), result.issues().toString());
    }

    private String minimalMob() {
        return """
                id: rpgquest:minimal
                entity-type: ZOMBIE
                name: "Minimal"
                spawn-chance: 0.1
                """;
    }

    // ---- Issue #175 : mode de boss ------------------------------------------------------------

    @Test
    void bossModeDefaultsToQuestAndWeightToOne() {
        SpecialMobDefinitionParser.ParseResult result = parser.parse("boss.yml", load("""
                id: rpgquest:old_boss
                category: BOSS
                entity-type: ZOMBIE
                name: "Ancien boss"
                spawn-chance: 0.0
                """));
        assertTrue(result.isSuccess(), result.issues().toString());
        assertEquals(com.lodygames.rpgquest.mob.model.BossMode.QUEST, result.definition().bossMode());
        assertEquals(1, result.definition().communityWeight());
        assertFalse(result.definition().isCommunityBoss());
    }

    @Test
    void communityBossParsesWithItsWeight() {
        SpecialMobDefinitionParser.ParseResult result = parser.parse("boss.yml", load("""
                id: rpgquest:ogre
                category: BOSS
                boss-mode: community
                community-weight: 4
                entity-type: ZOMBIE
                name: "Ogre"
                spawn-chance: 0.0
                """));
        assertTrue(result.isSuccess(), result.issues().toString());
        assertTrue(result.definition().isCommunityBoss());
        assertEquals(4, result.definition().communityWeight());
    }

    @Test
    void communityModeOnASpecialProfileAndABadWeightAreRejected() {
        SpecialMobDefinitionParser.ParseResult special = parser.parse("s.yml", load("""
                id: rpgquest:s
                entity-type: ZOMBIE
                name: "S"
                spawn-chance: 0.1
                boss-mode: COMMUNITY
                """));
        assertFalse(special.isSuccess());
        SpecialMobDefinitionParser.ParseResult weight = parser.parse("w.yml", load("""
                id: rpgquest:w
                category: BOSS
                boss-mode: COMMUNITY
                community-weight: 0
                entity-type: ZOMBIE
                name: "W"
                spawn-chance: 0.0
                """));
        assertFalse(weight.isSuccess());
    }

    // ---- Issue #176 : récompenses de participation -------------------------------------------

    @Test
    void participationRewardsAreOptionalDeduplicatedAndBossOnly() {
        SpecialMobDefinitionParser.ParseResult boss = parser.parse("boss.yml", load("""
                id: rpgquest:ogre
                category: BOSS
                boss-mode: COMMUNITY
                participation-rewards: [boss_ogre, boss_ogre, titre.ogre, "table:ogre_loot"]
                entity-type: ZOMBIE
                name: "Ogre"
                spawn-chance: 0.0
                """));
        assertTrue(boss.isSuccess(), boss.issues().toString());
        assertEquals(List.of("boss_ogre", "titre.ogre", "table:ogre_loot"), boss.definition().participationRewards());

        SpecialMobDefinitionParser.ParseResult none = parser.parse("old.yml", load("""
                id: rpgquest:old
                category: BOSS
                entity-type: ZOMBIE
                name: "Old"
                spawn-chance: 0.0
                """));
        assertTrue(none.definition().participationRewards().isEmpty());
    }

    @Test
    void participationRewardsAreRejectedOnSpecialOrWithABadKey() {
        SpecialMobDefinitionParser.ParseResult special = parser.parse("s.yml", load(minimalMob()
                + "participation-rewards: [boss_ogre]\n"));
        assertFalse(special.isSuccess());
        SpecialMobDefinitionParser.ParseResult badKey = parser.parse("b.yml", load("""
                id: rpgquest:b
                category: BOSS
                participation-rewards: ["Pas Valide"]
                entity-type: ZOMBIE
                name: "B"
                spawn-chance: 0.0
                """));
        assertFalse(badKey.isSuccess());
        SpecialMobDefinitionParser.ParseResult notAList = parser.parse("n.yml", load("""
                id: rpgquest:n
                category: BOSS
                participation-rewards: boss_ogre
                entity-type: ZOMBIE
                name: "N"
                spawn-chance: 0.0
                """));
        assertFalse(notAList.isSuccess());
    }

    private ConfigurationSection load(String yaml) {
        YamlConfiguration configuration = new YamlConfiguration();
        try {
            configuration.load(new StringReader(yaml));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return configuration;
    }
}
