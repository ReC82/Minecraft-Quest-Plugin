package com.lodygames.rpgquest.entitlement.reward;

/**
 * Origine d'un droit à récompense (issue #422). Une future source (saison, concours, code promo,
 * VIP) = une nouvelle valeur et un format de clé, sans nouvelle table.
 */
public enum RewardSource {
    /** Récompense d'un événement archivé (#57), lue dans l'archive figée. */
    EVENT,
    /** Déclaration manuelle depuis PlugAdmin. */
    ADMIN,
    /** Participation éligible à un boss communautaire vaincu (#176). */
    BOSS,
    /** Gain d'un tirage de table de récompenses à la fin d'une quête (#177). */
    QUEST
}
