package com.lodygames.rpgquest.panel.agent;

import java.time.Duration;
import java.util.List;

/**
 * Bloc de configuration « agent sortant » du Control Panel (issue #51), regroupé pour n'ajouter
 * qu'un seul champ à {@code PanelConfig}.
 *
 * @param agents         agents RPGQuest connus (peut être vide : le canal agent est alors inerte)
 * @param thresholds     seuils ONLINE/STALE/OFFLINE
 * @param actionExpiry   au-delà de ce délai sans résultat, une action passe EXPIRED
 * @param defaultAgentId agent associé à la cible par défaut du dashboard (peut être vide)
 */
public record AgentSettings(
        List<AgentIdentity> agents,
        AgentLiveness.Thresholds thresholds,
        Duration actionExpiry,
        String defaultAgentId) {

    public AgentSettings {
        agents = agents == null ? List.of() : List.copyOf(agents);
        thresholds = thresholds == null ? AgentLiveness.Thresholds.defaults() : thresholds;
        actionExpiry = actionExpiry == null || actionExpiry.isNegative() || actionExpiry.isZero()
                ? Duration.ofMinutes(5) : actionExpiry;
        defaultAgentId = defaultAgentId == null || defaultAgentId.isBlank() ? null : defaultAgentId.trim();
    }

    public static AgentSettings none() {
        return new AgentSettings(List.of(), AgentLiveness.Thresholds.defaults(), Duration.ofMinutes(5), null);
    }

    public boolean enabled() {
        return !agents.isEmpty();
    }
}
