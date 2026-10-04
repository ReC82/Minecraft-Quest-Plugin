package com.lodygames.rpgquest.mob.model;

/**
 * Issue #173 : au-dessous de {@code healthFraction} de vie max, le mob reçoit un signal visuel
 * puis bascule en rage (vitesse/dégâts multipliés). L'état est binaire et appliqué <strong>une
 * seule fois</strong> au passage du seuil (jamais réappliqué/cumulé à chaque coup -- voir
 * {@code EnragedAbilityListener#enragedEntities}, un simple marqueur "déjà en rage").
 */
public record EnragedAbility(double healthFraction, double speedMultiplier, double damageMultiplier) implements MobAbility {

    public EnragedAbility {
        if (healthFraction <= 0 || healthFraction >= 1) {
            throw new IllegalArgumentException("healthFraction doit être strictement compris entre 0 et 1 : " + healthFraction);
        }
        if (speedMultiplier <= 0) {
            throw new IllegalArgumentException("speedMultiplier doit être strictement positif : " + speedMultiplier);
        }
        if (damageMultiplier <= 0) {
            throw new IllegalArgumentException("damageMultiplier doit être strictement positif : " + damageMultiplier);
        }
    }

    @Override
    public MobAbilityType type() {
        return MobAbilityType.ENRAGED;
    }
}
