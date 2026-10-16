package com.lodygames.rpgquest.entitlement.reward;

/**
 * Statut d'un droit à récompense (issue #422, docs/REWARD_ENTITLEMENTS.md §4). Deux transitions
 * seulement, toutes deux depuis {@link #PENDING} ; {@link #DELIVERED} et {@link #REVOKED} sont
 * terminaux.
 */
public enum RewardEntitlementStatus {
    /** Déclaré, pas encore remis. */
    PENDING,
    /** Remis au joueur (ou pris par la remise en cours, voir le protocole §5). */
    DELIVERED,
    /** Retiré par un administrateur avant toute remise. */
    REVOKED
}
