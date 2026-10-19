package com.lodygames.rpgquest.mob.model;

/** Multiplie le rayon d'explosion (via {@code ExplosionPrimeEvent}, jamais un recalcul manuel des blocs). */
public record StrongerExplosionAbility(double radiusMultiplier, AbilityOptions options) implements MobAbility {

    public StrongerExplosionAbility(double radiusMultiplier) {
        this(radiusMultiplier, AbilityOptions.DEFAULTS);
    }

    public StrongerExplosionAbility {
        if (radiusMultiplier <= 0) {
            throw new IllegalArgumentException("radiusMultiplier doit être strictement positif : " + radiusMultiplier);
        }
        options = options == null ? AbilityOptions.DEFAULTS : options;
    }

    @Override
    public MobAbilityType type() {
        return MobAbilityType.STRONGER_EXPLOSION;
    }
}
