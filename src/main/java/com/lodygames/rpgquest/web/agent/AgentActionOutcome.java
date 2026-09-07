package com.lodygames.rpgquest.web.agent;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Résultat structuré d'une action exécutée (ou refusée) par l'agent RPGQuest (issue #51). Renvoyé
 * tel quel à {@code POST /agent/v1/actions/{id}/result}.
 *
 * @param actionId   identifiant de l'action corrélée
 * @param status     {@code SUCCESS} / {@code FAILED} / {@code REJECTED}
 * @param value      valeur principale lisible (ex. la valeur d'une variable), ou {@code null}
 * @param message    message humain non secret
 * @param details    détails structurés non secrets (peut être vide)
 * @param finishedAt horodatage de fin côté serveur
 */
public record AgentActionOutcome(
        String actionId,
        String status,
        String value,
        String message,
        Map<String, Object> details,
        Instant finishedAt) {

    public static final String SUCCESS = "SUCCESS";
    public static final String FAILED = "FAILED";
    public static final String REJECTED = "REJECTED";

    public AgentActionOutcome {
        details = details == null ? Map.of() : Map.copyOf(details);
        finishedAt = finishedAt == null ? Instant.now() : finishedAt;
    }

    public static AgentActionOutcome success(String actionId, String value, String message, Map<String, Object> details) {
        return new AgentActionOutcome(actionId, SUCCESS, value, message, details, Instant.now());
    }

    public static AgentActionOutcome failed(String actionId, String message) {
        return new AgentActionOutcome(actionId, FAILED, null, message, Map.of(), Instant.now());
    }

    public static AgentActionOutcome rejected(String actionId, String message) {
        return new AgentActionOutcome(actionId, REJECTED, null, message, Map.of(), Instant.now());
    }

    /** Corps JSON envoyé à PlugAdmin. */
    public Map<String, Object> toJson() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("action_id", actionId);
        root.put("status", status);
        root.put("value", value);
        root.put("message", message);
        root.put("details", details);
        root.put("finished_at", finishedAt.toString());
        return root;
    }
}
