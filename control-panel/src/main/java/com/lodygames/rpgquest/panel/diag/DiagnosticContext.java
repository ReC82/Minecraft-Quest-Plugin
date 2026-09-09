package com.lodygames.rpgquest.panel.diag;

import com.lodygames.rpgquest.panel.agent.AgentActionRow;
import com.lodygames.rpgquest.panel.agent.AgentStore;
import com.lodygames.rpgquest.panel.agent.HeartbeatRecord;
import com.lodygames.rpgquest.panel.json.Json;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Accès en lecture, <strong>sans déclencher aucune requête</strong>, aux données déjà collectées
 * par l'agent (dernières actions {@code *.list} réussies + dernier heartbeat). Partagé par tous les
 * {@link DiagnosticProvider} : ils consomment le même snapshot, jamais une logique divergente.
 */
public final class DiagnosticContext {

    private final AgentStore store;
    private final String agentId;

    public DiagnosticContext(AgentStore store, String agentId) {
        this.store = store;
        this.agentId = agentId;
    }

    public String agentId() {
        return agentId;
    }

    public boolean hasAgent() {
        return agentId != null && !agentId.isBlank();
    }

    /** Dernier heartbeat connu de l'agent. */
    public Optional<HeartbeatRecord> heartbeat() {
        return hasAgent() ? store.latestHeartbeat(agentId) : Optional.empty();
    }

    /** {@code details} de la dernière action {@code type} <strong>réussie</strong>, ou vide. */
    @SuppressWarnings("unchecked")
    public Optional<Map<String, Object>> details(String type) {
        return successRow(type).flatMap(row -> {
            if (row.resultJson() == null || row.resultJson().isBlank()) {
                return Optional.empty();
            }
            try {
                Object d = Json.parseObject(row.resultJson()).get("details");
                return d instanceof Map<?, ?> m ? Optional.of((Map<String, Object>) m) : Optional.empty();
            } catch (RuntimeException e) {
                return Optional.empty();
            }
        });
    }

    /** Instant de complétion du relevé {@code type} (fraîcheur), ou vide si jamais réussi. */
    public Optional<Instant> freshnessOf(String type) {
        return successRow(type).map(AgentActionRow::completedAt);
    }

    private Optional<AgentActionRow> successRow(String type) {
        return hasAgent() ? store.latestSuccessfulActionOfType(agentId, type) : Optional.empty();
    }

    // ---- helpers de lecture tolérants (les payloads agent sont du JSON libre) -----------------

    public static List<Object> list(Object v) {
        return v instanceof List<?> l ? List.copyOf(l) : List.of();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Object v) {
        return v instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    public static String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    public static boolean isTrue(Object v) {
        return Boolean.TRUE.equals(v) || "true".equalsIgnoreCase(str(v));
    }
}
