package com.lodygames.rpgquest.panel.web;

import com.lodygames.rpgquest.panel.agent.AgentActionRow;
import com.lodygames.rpgquest.panel.agent.AgentStore;
import com.lodygames.rpgquest.panel.json.Json;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Pseudos connus pour l'affichage des actions (retour du 22/10 sur #93) : lus dans le dernier relevé
 * {@code player.catalog} réussi de l'agent par défaut, déjà en base. Le relevé n'est réanalysé que
 * lorsqu'un nouveau le remplace (cache sur l'identifiant de l'action) ; aucune requête vers le
 * serveur Minecraft, aucune écriture.
 */
final class PlayerNameDirectory {

    private final AgentStore store;
    private final Supplier<String> agentId;
    private String cachedActionId;
    private PlayerNames cached = PlayerNames.EMPTY;

    PlayerNameDirectory(AgentStore store, Supplier<String> agentId) {
        this.store = store;
        this.agentId = agentId;
    }

    /** Les pseudos du dernier annuaire ; {@link PlayerNames#EMPTY} si aucun relevé ou en cas d'erreur. */
    synchronized PlayerNames current() {
        String agent = agentId.get();
        if (store == null || agent == null || agent.isBlank()) {
            return PlayerNames.EMPTY;
        }
        try {
            Optional<AgentActionRow> latest = store.latestSuccessfulActionOfType(agent, "player.catalog");
            if (latest.isEmpty()) {
                return PlayerNames.EMPTY;
            }
            AgentActionRow row = latest.get();
            if (!row.id().equals(cachedActionId)) {
                cached = PlayerNames.fromCatalogDetails(details(row));
                cachedActionId = row.id();
            }
            return cached;
        } catch (RuntimeException e) {
            // L'affichage ne doit jamais tomber pour un annuaire illisible : repli sur l'UUID brut.
            return PlayerNames.EMPTY;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> details(AgentActionRow row) {
        if (row.resultJson() == null || row.resultJson().isBlank()) {
            return null;
        }
        Object details = Json.parseObject(row.resultJson()).get("details");
        return details instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }
}
