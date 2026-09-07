package com.lodygames.rpgquest.panel.agent;

/**
 * Cycle de vie d'une action agent côté PlugAdmin (issue #51, phase 9).
 *
 * <pre>
 *   PENDING ──(relevée par l'agent)──▶ DELIVERED ──(résultat reçu)──▶ SUCCESS | FAILED | REJECTED
 *      │                                   │
 *      └──────────(sans résultat, trop vieille)──────────▶ EXPIRED
 * </pre>
 *
 * <p>Une action DELIVERED reste renvoyée à l'agent tant qu'aucun résultat terminal n'est arrivé :
 * c'est le pendant côté panel de l'idempotence (une réponse de résultat perdue n'immobilise pas
 * l'action).</p>
 */
public enum AgentActionStatus {

    PENDING,
    DELIVERED,
    SUCCESS,
    FAILED,
    REJECTED,
    EXPIRED;

    public boolean terminal() {
        return this == SUCCESS || this == FAILED || this == REJECTED || this == EXPIRED;
    }

    /** Traduit le {@code status} renvoyé par l'agent en statut d'action. */
    public static AgentActionStatus fromAgentResult(String raw) {
        if (raw == null) {
            return FAILED;
        }
        return switch (raw.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "SUCCESS" -> SUCCESS;
            case "REJECTED" -> REJECTED;
            default -> FAILED;
        };
    }
}
