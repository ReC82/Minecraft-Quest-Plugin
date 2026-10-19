package com.lodygames.rpgquest.mob.ability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.mob.model.AbilityOptions;
import com.lodygames.rpgquest.mob.model.EnragedAbility;
import com.lodygames.rpgquest.mob.model.ExplosiveOnAttackAbility;
import com.lodygames.rpgquest.mob.model.MobAbility;
import com.lodygames.rpgquest.mob.model.MobAbilityType;
import com.lodygames.rpgquest.mob.model.MobCategory;
import com.lodygames.rpgquest.mob.model.SpecialMobDefinition;
import com.lodygames.rpgquest.mob.model.SplitOnHitAbility;
import com.lodygames.rpgquest.mob.model.StrongerExplosionAbility;
import com.lodygames.rpgquest.mob.model.SummonOnDamageAbility;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;

/** Issue #542 — composition des capacités d'un profil (décisions Q2, Q3, Q8 de #541). */
class MobAbilityRulesTest {

    private static final EnragedAbility ENRAGED = new EnragedAbility(0.3, 1.3, 1.5);
    private static final SummonOnDamageAbility SUMMON = new SummonOnDamageAbility(EntityType.ZOMBIE, 2, 0.25, 20, 4);
    private static final SplitOnHitAbility SPLIT = new SplitOnHitAbility(2, 2);
    private static final StrongerExplosionAbility STRONGER = new StrongerExplosionAbility(1.5);
    private static final ExplosiveOnAttackAbility EXPLOSIVE = new ExplosiveOnAttackAbility(2f, false, 2.0);

    private static SpecialMobDefinition profile(MobCategory category, double spawnChance, List<MobAbility> abilities) {
        return new SpecialMobDefinition(new NamespacedKey("rpgquest", "p"), category, true, EntityType.CREEPER, "P",
                spawnChance, Set.of(), Set.of(), Set.of(), null, null, null, null, null, null, null,
                null, null, abilities, List.of(), null, null);
    }

    @Test
    void zeroOneAndThreeAbilitiesAreAccepted() {
        assertEquals(List.of(), MobAbilityRules.violations(profile(MobCategory.SPECIAL, 0.1, List.of())));
        assertEquals(List.of(), MobAbilityRules.violations(profile(MobCategory.SPECIAL, 0.1, List.of(ENRAGED))));
        assertEquals(List.of(), MobAbilityRules.violations(profile(MobCategory.BOSS, 0, List.of(SUMMON, ENRAGED, SPLIT))));
    }

    @Test
    void fiveIsTheMaximum() {
        List<MobAbility> five = List.of(ENRAGED, SUMMON, SPLIT, STRONGER, EXPLOSIVE);
        assertEquals(List.of(), MobAbilityRules.violations(profile(MobCategory.BOSS, 0, five)));
        // Une sixième entrée est forcément un doublon aujourd'hui : on vérifie le plafond seul via un faux catalogue.
        Function<MobAbilityType, AbilityDescriptor> repeatable = type -> withStatus(type, AbilityDescriptor.Status.AVAILABLE, true);
        List<String> six = MobAbilityRules.violations(
                profile(MobCategory.BOSS, 0, List.of(ENRAGED, SUMMON, SPLIT, STRONGER, EXPLOSIVE, ENRAGED)), repeatable);
        assertEquals(List.of("6 capacités : 5 au plus par profil."), six);
    }

    @Test
    void duplicatesAreRefusedOnSaveButOnlyWarnedOnLoad() {
        SpecialMobDefinition twice = profile(MobCategory.BOSS, 0, List.of(ENRAGED, SUMMON,
                new EnragedAbility(0.5, 2.0, 2.0), new EnragedAbility(0.6, 2.0, 2.0)));
        List<String> violations = MobAbilityRules.violations(twice);
        assertEquals(1, violations.size(), violations.toString());
        assertTrue(violations.get(0).contains("ENRAGED") && violations.get(0).contains("en double"), violations.toString());

        List<String> warnings = MobAbilityRules.loadWarnings(twice);
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("seule la première instance"), warnings.toString());
    }

    @Test
    void onlyTheFirstEnabledInstanceOfEachTypeIsActiveInProfileOrder() {
        StrongerExplosionAbility second = new StrongerExplosionAbility(3.0);
        EnragedAbility disabled = new EnragedAbility(0.3, 1.3, 1.5, AbilityOptions.DEFAULTS.withEnabled(false));
        List<MobAbility> active = MobAbilityRules.activeAbilities(List.of(SUMMON, STRONGER, disabled, second, SPLIT));
        // Q3 : la 2e Explosion renforcée ne s'applique plus (avant : multiplication) ; un Enragé désactivé n'agit pas.
        assertEquals(List.of(SUMMON, STRONGER, SPLIT), active);
        // Une première instance désactivée ne « libère » pas la place pour la suivante.
        assertEquals(List.of(), MobAbilityRules.activeAbilities(List.of(disabled, ENRAGED)));
    }

    @Test
    void inDevelopmentAbilitiesOnlyGoOnTestProfiles() {
        Function<MobAbilityType, AbilityDescriptor> inDev = type -> withStatus(type, AbilityDescriptor.Status.IN_DEVELOPMENT, false);
        assertEquals(1, MobAbilityRules.violations(profile(MobCategory.SPECIAL, 0.1, List.of(ENRAGED)), inDev).size());
        assertEquals(List.of(), MobAbilityRules.violations(profile(MobCategory.SPECIAL, 0, List.of(ENRAGED)), inDev));
        assertEquals(List.of(), MobAbilityRules.violations(profile(MobCategory.BOSS, 0, List.of(ENRAGED)), inDev));
    }

    private static AbilityDescriptor withStatus(MobAbilityType type, AbilityDescriptor.Status status, boolean repeatable) {
        AbilityDescriptor real = MobAbilityCatalog.descriptor(type);
        return new AbilityDescriptor(type, real.label(), real.summary(), real.category(), status, real.kind(),
                real.triggers(), repeatable, real.effectiveOn(), real.params(), Map.of(), 0, real.worldMutation());
    }
}
