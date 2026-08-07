package com.lodygames.rpgquest.item.model;

import org.bukkit.potion.PotionEffectType;

/**
 * Effet appliqué à la cible sur un coup, avec une chance de déclenchement et
 * un cooldown indépendants. {@code abilityId} sert de clé de cooldown
 * (avec l'UUID du joueur) — voir {@code com.lodygames.rpgquest.item.behavior.CooldownManager}.
 */
public record ConditionalEffect(
        String abilityId,
        PotionEffectType effectType,
        int durationTicks,
        int amplifier,
        double chance,
        long cooldownMillis
) {

    public ConditionalEffect {
        if (abilityId == null || abilityId.isBlank()) {
            throw new IllegalArgumentException("effect.ability-id ne peut pas être vide.");
        }
        if (effectType == null) {
            throw new IllegalArgumentException("effect.type ne peut pas être nul.");
        }
        if (durationTicks < 1) {
            throw new IllegalArgumentException("effect.duration-ticks doit être strictement positif.");
        }
        if (amplifier < 0) {
            throw new IllegalArgumentException("effect.amplifier ne peut pas être négatif.");
        }
        if (Double.isNaN(chance) || Double.isInfinite(chance) || chance < 0 || chance > 1) {
            throw new IllegalArgumentException("effect.chance doit être compris entre 0 et 1.");
        }
        if (cooldownMillis < 0) {
            throw new IllegalArgumentException("effect.cooldown-seconds ne peut pas être négatif.");
        }
    }
}
