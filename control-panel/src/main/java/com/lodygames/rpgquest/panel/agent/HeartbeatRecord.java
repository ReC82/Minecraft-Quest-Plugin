package com.lodygames.rpgquest.panel.agent;

import java.time.Instant;

/**
 * Dernier heartbeat connu d'un agent (issue #51, phases 4-6). Persisté par {@link AgentStore} dans
 * {@code control-panel.db} — jamais dans {@code data.db}.
 *
 * @param agentId       identifiant de l'agent
 * @param environment   étiquette d'environnement annoncée
 * @param receivedAt    horodatage de réception côté PlugAdmin (fait autorité pour ONLINE/STALE/OFFLINE)
 * @param generatedAt   horodatage produit par le serveur RPGQuest (peut être null / non parsable)
 * @param protocol      version du protocole agent
 * @param pluginName    nom du plugin
 * @param pluginVersion version du plugin
 * @param serverState   état serveur annoncé (ex. {@code ONLINE})
 * @param playersOnline joueurs connectés (-1 si absent)
 * @param maxPlayers    capacité (-1 si absent)
 * @param uptimeSeconds uptime plugin en secondes (-1 si absent)
 * @param worldsJson    JSON brut des mondes essentiels (hub/claims/wild)
 * @param rawJson       corps brut du heartbeat (diagnostic ; sans secret)
 */
public record HeartbeatRecord(
        String agentId,
        String environment,
        Instant receivedAt,
        String generatedAt,
        String protocol,
        String pluginName,
        String pluginVersion,
        String serverState,
        long playersOnline,
        long maxPlayers,
        long uptimeSeconds,
        String worldsJson,
        String rawJson) {

    public String uptimeHuman() {
        if (uptimeSeconds < 0) {
            return "—";
        }
        long s = uptimeSeconds;
        long d = s / 86400;
        long h = (s % 86400) / 3600;
        long m = (s % 3600) / 60;
        if (d > 0) {
            return d + "j " + h + "h " + m + "min";
        }
        if (h > 0) {
            return h + "h " + m + "min";
        }
        return m + "min " + (s % 60) + "s";
    }
}
