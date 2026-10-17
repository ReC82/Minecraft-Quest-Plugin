package com.lodygames.rpgquest.quest.progress;

import org.bukkit.entity.Player;

/**
 * Issue #183 — remet une récompense de quête {@code EXPERIENCE}. Le package {@code quest} ne
 * connaît pas la progression RPG : le démarrage branche une implémentation qui choisit XP RPG ou
 * XP vanilla selon {@code progression.vanilla-xp-progression}. Sans branchement (tests), XP vanilla.
 */
@FunctionalInterface
public interface ExperienceRewardSink {

    /** XP vanilla directe, l'ancien comportement. */
    ExperienceRewardSink VANILLA = (player, amount, grantId) -> {
        player.giveExp(amount);
        return false;
    };

    /**
     * Accorde {@code amount} XP pour la complétion {@code grantId} (jeton unique et persisté de
     * la complétion : sert de clé de déduplication). Retourne {@code true} si l'XP accordée est de
     * l'XP RPG (le message de remise le dit alors explicitement), {@code false} si vanilla.
     */
    boolean grant(Player player, int amount, String grantId);
}
