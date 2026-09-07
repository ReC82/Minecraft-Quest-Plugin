package com.lodygames.rpgquest.panel.agent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Optional;

/**
 * Les agents RPGQuest connus de PlugAdmin (issue #51). Multi-cible dès le départ : DEV aujourd'hui,
 * staging/prod ou d'autres plugins LodyLands demain, sans changement de code.
 */
public final class AgentRegistry {

    private final List<AgentIdentity> agents;

    public AgentRegistry(List<AgentIdentity> agents) {
        this.agents = agents == null ? List.of() : List.copyOf(agents);
    }

    public List<AgentIdentity> all() {
        return agents;
    }

    public boolean isEmpty() {
        return agents.isEmpty();
    }

    public Optional<AgentIdentity> byId(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        String needle = id.trim();
        return agents.stream().filter(a -> a.id().equals(needle)).findFirst();
    }

    /**
     * Authentifie une requête agent : l'agent doit être déclaré, exploitable (jeton configuré) et
     * le jeton porteur présenté doit correspondre (comparaison en temps constant). Le coût de
     * comparaison est payé même quand l'agent est inconnu, pour ne pas distinguer les cas par le
     * temps de réponse.
     */
    public Optional<AgentIdentity> authenticate(String agentId, String bearerToken) {
        Optional<AgentIdentity> match = byId(agentId);
        if (match.isPresent() && match.get().tokenMatches(bearerToken)) {
            return match;
        }
        // Agent inconnu / jeton faux : payer un coût de comparaison comparable (anti-timing).
        byte[] presented = (bearerToken == null ? "" : bearerToken).getBytes(StandardCharsets.UTF_8);
        MessageDigest.isEqual(presented, DUMMY);
        return Optional.empty();
    }

    private static final byte[] DUMMY =
            "0000000000000000000000000000000000000000".getBytes(StandardCharsets.UTF_8);
}
