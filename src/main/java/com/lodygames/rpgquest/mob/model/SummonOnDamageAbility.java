package com.lodygames.rpgquest.mob.model;

import org.bukkit.entity.EntityType;

/**
 * Issue #173 : quand le mob subit des dégâts <strong>effectifs</strong> (événement non annulé,
 * dégâts finaux {@code > 0}), {@code chance} d'invoquer {@code amount} renforts de
 * {@code summonedEntityType}, sous réserve de {@code cooldownSeconds} écoulé et de
 * {@code maxAlive} renforts déjà vivants pour ce mob précis (jamais une cascade : les renforts
 * eux-mêmes n'invoquent jamais, voir {@code SummonOnDamageAbilityListener}).
 */
public record SummonOnDamageAbility(EntityType summonedEntityType, int amount, double chance,
                                     int cooldownSeconds, int maxAlive) implements MobAbility {

    public SummonOnDamageAbility {
        if (summonedEntityType == null || !summonedEntityType.isAlive()) {
            throw new IllegalArgumentException("summonedEntityType doit être un type d'entité vivante valide.");
        }
        if (amount <= 0) {
            throw new IllegalArgumentException("amount doit être strictement positif : " + amount);
        }
        if (chance <= 0 || chance > 1) {
            throw new IllegalArgumentException("chance doit être comprise entre 0 (exclu) et 1 : " + chance);
        }
        if (cooldownSeconds < 0) {
            throw new IllegalArgumentException("cooldownSeconds ne peut pas être négatif : " + cooldownSeconds);
        }
        if (maxAlive <= 0) {
            throw new IllegalArgumentException("maxAlive doit être strictement positif : " + maxAlive);
        }
    }

    @Override
    public MobAbilityType type() {
        return MobAbilityType.SUMMON_ON_DAMAGE;
    }
}
