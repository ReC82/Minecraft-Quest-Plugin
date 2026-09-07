package com.lodygames.rpgquest.web.agent;

import java.util.Map;

/**
 * Une action reçue de PlugAdmin, telle que désérialisée depuis {@code GET /agent/v1/actions}
 * (issue #51). Structure <strong>opaque</strong> : {@link #type} est la chaîne brute du fil, à
 * valider contre {@link AgentActionType} ; {@link #params} sont des paires clé/valeur non
 * interprétées ici.
 *
 * @param id     identifiant unique de l'action (corrélation + idempotence)
 * @param type   type brut du fil (ex. {@code player.variable.get})
 * @param params paramètres bruts (ex. {@code player}, {@code player_uuid}, {@code key})
 */
public record AgentAction(String id, String type, Map<String, String> params) {

    public AgentAction {
        params = params == null ? Map.of() : Map.copyOf(params);
    }

    public String param(String key) {
        return params.get(key);
    }
}
