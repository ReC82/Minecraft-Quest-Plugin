package be.lloyd.rpgquest.item.model;

import org.bukkit.Particle;

/** Capacité spéciale d'un outil, déclenchée par un clic droit, gérée par cooldown (voir {@link ConditionalEffect}). */
public record ToolSpecialAbility(
        String abilityId,
        long cooldownMillis,
        String activationMessage,
        Particle particle,
        int particleCount
) {

    public ToolSpecialAbility {
        if (abilityId == null || abilityId.isBlank()) {
            throw new IllegalArgumentException("special-ability.ability-id ne peut pas être vide.");
        }
        if (cooldownMillis < 0) {
            throw new IllegalArgumentException("special-ability.cooldown-seconds ne peut pas être négatif.");
        }
        if (particleCount < 0) {
            throw new IllegalArgumentException("special-ability.particle-count ne peut pas être négatif.");
        }
    }
}
