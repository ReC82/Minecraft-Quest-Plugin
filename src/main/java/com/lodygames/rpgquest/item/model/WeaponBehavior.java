package com.lodygames.rpgquest.item.model;

import org.bukkit.Particle;

/**
 * Comportement de combat d'une arme personnalisée. {@code baseDamage} et
 * {@code attackSpeedBonus} sont volontairement des <b>bonus additifs</b>,
 * jamais un remplacement du dégât déjà calculé par le jeu (attributs
 * vanilla, enchantements comme Tranchant) : c'est ce qui garantit la
 * « compatibilité enchantements » demandée par la mission — un Tranchant
 * posé sur cette arme continue de compter normalement, ce bonus s'ajoute
 * par-dessus plutôt que de l'écraser.
 */
public record WeaponBehavior(
        Double baseDamage,
        Double attackSpeedBonus,
        double criticalChance,
        double criticalMultiplier,
        ConditionalEffect conditionalEffect,
        String hitMessage,
        Particle particle,
        int particleCount
) {

    public WeaponBehavior {
        if (baseDamage != null && (Double.isNaN(baseDamage) || Double.isInfinite(baseDamage))) {
            throw new IllegalArgumentException("combat.base-damage ne peut pas être NaN ou infini.");
        }
        if (attackSpeedBonus != null && (Double.isNaN(attackSpeedBonus) || Double.isInfinite(attackSpeedBonus))) {
            throw new IllegalArgumentException("combat.attack-speed-bonus ne peut pas être NaN ou infini.");
        }
        if (Double.isNaN(criticalChance) || Double.isInfinite(criticalChance) || criticalChance < 0 || criticalChance > 1) {
            throw new IllegalArgumentException("combat.critical-chance doit être compris entre 0 et 1.");
        }
        if (Double.isNaN(criticalMultiplier) || Double.isInfinite(criticalMultiplier) || criticalMultiplier < 1.0) {
            throw new IllegalArgumentException("combat.critical-multiplier doit être supérieur ou égal à 1.");
        }
        if (particleCount < 0) {
            throw new IllegalArgumentException("combat.particle-count ne peut pas être négatif.");
        }
    }
}
