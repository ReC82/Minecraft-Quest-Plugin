package com.lodygames.rpgquest.quest.progress;

public enum CompleteOutcome {
    COMPLETED,
    ALREADY_COMPLETED,
    UNKNOWN_QUEST,
    /** Issue #318 : les récompenses ne tiennent pas dans l'inventaire — rien n'a été accordé ni terminé. */
    INVENTORY_FULL,
    /** Issue #15 : quête périodique sans progression active (non attribuée, expirée, abandonnée) — rien accordé. */
    NOT_ACTIVE
}
