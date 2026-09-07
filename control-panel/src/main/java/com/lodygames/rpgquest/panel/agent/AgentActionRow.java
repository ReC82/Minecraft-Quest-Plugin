package com.lodygames.rpgquest.panel.agent;

import java.time.Instant;
import java.util.Map;

/**
 * Une action agent telle que persistée par {@link AgentStore} (issue #51).
 *
 * @param id            identifiant unique (UUID) — corrélation + idempotence
 * @param agentId       agent/cible destinataire
 * @param type          type d'action (ex. {@code player.variable.get})
 * @param params        paramètres (ex. {@code player}, {@code key})
 * @param status        statut courant
 * @param createdAt     création
 * @param createdBy     acteur PlugAdmin à l'origine (ex. {@code owner})
 * @param deliveredAt   première remise à l'agent (null tant que PENDING)
 * @param deliverCount  nombre de remises (diagnostic idempotence)
 * @param completedAt   réception du résultat terminal (null sinon)
 * @param resultStatus  statut renvoyé par l'agent ({@code SUCCESS}/{@code FAILED}/{@code REJECTED})
 * @param resultValue   valeur principale renvoyée (ex. valeur d'une variable)
 * @param resultMessage message humain renvoyé
 * @param resultJson    corps brut du résultat (diagnostic ; sans secret)
 */
public record AgentActionRow(
        String id,
        String agentId,
        String type,
        Map<String, String> params,
        AgentActionStatus status,
        Instant createdAt,
        String createdBy,
        Instant deliveredAt,
        int deliverCount,
        Instant completedAt,
        String resultStatus,
        String resultValue,
        String resultMessage,
        String resultJson) {

    public AgentActionRow {
        params = params == null ? Map.of() : Map.copyOf(params);
    }
}
