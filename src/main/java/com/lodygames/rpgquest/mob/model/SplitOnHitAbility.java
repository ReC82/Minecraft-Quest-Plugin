package com.lodygames.rpgquest.mob.model;

/**
 * Fait apparaître des zombies enfants à chaque coup non mortel reçu.
 * {@code maxDepth} borne le nombre de générations (un enfant né à la
 * profondeur maximale ne peut plus se diviser), {@code maxChildrenPerHit}
 * borne le nombre d'enfants créés par coup — les deux ensemble garantissent
 * qu'aucune chaîne de divisions n'est infinie (mission étape 18, point 7).
 */
public record SplitOnHitAbility(int maxDepth, int maxChildrenPerHit) implements MobAbility {

    public SplitOnHitAbility {
        if (maxDepth < 1) {
            throw new IllegalArgumentException("maxDepth doit être au moins 1 : " + maxDepth);
        }
        if (maxChildrenPerHit < 1) {
            throw new IllegalArgumentException("maxChildrenPerHit doit être au moins 1 : " + maxChildrenPerHit);
        }
    }

    @Override
    public MobAbilityType type() {
        return MobAbilityType.SPLIT_ON_HIT;
    }
}
