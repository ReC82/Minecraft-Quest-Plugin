package com.lodygames.rpgquest.webapi.account;

/**
 * Résultat d'une preuve de liaison envoyée par le serveur Minecraft (issue #53).
 *
 * <p>{@link #INVALID_CODE} couvre volontairement « inconnu », « expiré » et « déjà utilisé » : le
 * joueur, comme un attaquant, n'apprend jamais lequel des trois.</p>
 */
public enum LinkOutcome {
    /** Liaison créée (ou déjà en place pour ce même UUID). */
    LINKED,
    /** Code inconnu, expiré ou déjà utilisé. */
    INVALID_CODE,
    /** Ce compte Minecraft est déjà relié à un autre compte du portail. */
    UUID_ALREADY_LINKED,
    /** Le compte du portail est déjà relié à un autre compte Minecraft. */
    ACCOUNT_ALREADY_LINKED,
    /** Le compte du portail est limité (consentement parental manquant, #55). */
    ACCOUNT_RESTRICTED,
    /** Trop d'échecs récents pour ce joueur : réessayer plus tard. */
    TOO_MANY_ATTEMPTS
}
