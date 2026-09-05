package com.lodygames.rpgquest.database;

/** Résultat d'une action réservée au propriétaire d'un claim (suppression, confiance, permission). */
public enum ClaimActionOutcome {
    SUCCESS,
    CLAIM_NOT_FOUND,
    NOT_OWNER
}
