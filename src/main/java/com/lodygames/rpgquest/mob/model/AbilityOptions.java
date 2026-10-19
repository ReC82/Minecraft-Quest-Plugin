package com.lodygames.rpgquest.mob.model;

/**
 * Issue #542 : champs communs à toute capacité, écrits au même niveau que ses paramètres (format
 * « plat étendu », décision Q1 de #541). Absents du fichier = {@link #DEFAULTS}, soit exactement le
 * comportement d'avant #542 ; ils ne sont écrits que s'ils diffèrent du défaut, si bien qu'un profil
 * existant se réécrit à l'identique.
 *
 * @param enabled         {@code false} = instance conservée (valeurs gardées) mais jamais évaluée
 * @param chance          probabilité de déclenchement à chaque occasion, dans ]0 ; 1]
 * @param cooldownSeconds délai minimum entre deux déclenchements d'une même entité, ≥ 0
 */
public record AbilityOptions(boolean enabled, double chance, int cooldownSeconds) {

    public static final AbilityOptions DEFAULTS = new AbilityOptions(true, 1.0, 0);

    public AbilityOptions {
        if (chance <= 0 || chance > 1) {
            throw new IllegalArgumentException("chance doit être comprise entre 0 (exclu) et 1 : " + chance);
        }
        if (cooldownSeconds < 0) {
            throw new IllegalArgumentException("cooldownSeconds ne peut pas être négatif : " + cooldownSeconds);
        }
    }

    public AbilityOptions withEnabled(boolean value) {
        return new AbilityOptions(value, chance, cooldownSeconds);
    }
}
