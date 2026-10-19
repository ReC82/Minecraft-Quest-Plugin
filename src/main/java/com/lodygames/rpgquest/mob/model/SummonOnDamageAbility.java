package com.lodygames.rpgquest.mob.model;

import org.bukkit.entity.EntityType;

/**
 * Issue #173 : quand le mob subit des dégâts <strong>effectifs</strong> (événement non annulé,
 * dégâts finaux {@code > 0}), {@code chance} d'invoquer {@code amount} renforts de
 * {@code summonedEntityType}, sous réserve de {@code cooldownSeconds} écoulé et de
 * {@code maxAlive} renforts déjà vivants pour ce mob précis (jamais une cascade : les renforts
 * eux-mêmes n'invoquent jamais, voir {@code mob.ability.SummonOnDamageExecutor}).
 *
 * <p>Issue #542 : {@code chance} et {@code cooldown-seconds} ont toujours eu le nom et le sens des
 * champs communs de {@link AbilityOptions} ; ils y sont désormais rangés (et restent obligatoires et
 * toujours écrits pour cette capacité). {@code despawnWithOwner} (décision Q7 de #541) : les renforts
 * disparaissent à la mort de l'invocateur ; défaut {@code false} = comportement d'avant (ils restent).</p>
 */
public record SummonOnDamageAbility(EntityType summonedEntityType, int amount, int maxAlive,
                                    boolean despawnWithOwner, AbilityOptions options) implements MobAbility {

    /** Forme d'avant #542 (renforts conservés, capacité active). */
    public SummonOnDamageAbility(EntityType summonedEntityType, int amount, double chance, int cooldownSeconds,
                                 int maxAlive) {
        this(summonedEntityType, amount, maxAlive, false, new AbilityOptions(true, chance, cooldownSeconds));
    }

    public SummonOnDamageAbility {
        if (summonedEntityType == null || !summonedEntityType.isAlive()) {
            throw new IllegalArgumentException("summonedEntityType doit être un type d'entité vivante valide.");
        }
        if (amount <= 0) {
            throw new IllegalArgumentException("amount doit être strictement positif : " + amount);
        }
        if (maxAlive <= 0) {
            throw new IllegalArgumentException("maxAlive doit être strictement positif : " + maxAlive);
        }
        options = options == null ? AbilityOptions.DEFAULTS : options;
    }

    public double chance() {
        return options.chance();
    }

    public int cooldownSeconds() {
        return options.cooldownSeconds();
    }

    @Override
    public MobAbilityType type() {
        return MobAbilityType.SUMMON_ON_DAMAGE;
    }
}
