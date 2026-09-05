package com.lodygames.rpgquest.mob.model;

/**
 * Détonne (comme un creeper) dès qu'un joueur entre dans {@code triggerRangeBlocks}
 * — voir {@code mob.SpecialMobService} pour le pourquoi d'un balayage de
 * proximité plutôt qu'un vrai coup de mêlée (la cible vanilla comme le
 * cochon n'a aucun comportement d'attaque, l'API publique Paper ne permet
 * pas de lui en greffer un sans IA de pathfinding personnalisée).
 */
public record ExplosiveOnAttackAbility(float power, boolean setFire, double triggerRangeBlocks) implements MobAbility {

    public ExplosiveOnAttackAbility {
        if (power <= 0) {
            throw new IllegalArgumentException("power doit être strictement positif : " + power);
        }
        if (triggerRangeBlocks <= 0) {
            throw new IllegalArgumentException("triggerRangeBlocks doit être strictement positif : " + triggerRangeBlocks);
        }
    }

    @Override
    public MobAbilityType type() {
        return MobAbilityType.EXPLOSIVE_ON_ATTACK;
    }
}
