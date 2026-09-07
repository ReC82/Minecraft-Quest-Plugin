package com.lodygames.rpgquest.web.agent;

import java.util.List;
import java.util.Map;

/**
 * Transport de l'agent vers PlugAdmin (issue #51). Isolé en interface pour que {@link AgentLoop}
 * soit testable sans réseau ; l'implémentation réelle est {@link PlugAdminClient} (HTTPS sortant,
 * {@code java.net.http}). Toute panne réseau est signalée par une
 * {@link PlugAdminUnavailableException}.
 */
public interface PlugAdminTransport {

    /** {@code POST /agent/v1/heartbeat} — renvoie le code HTTP. */
    int sendHeartbeat(Map<String, Object> payload);

    /** {@code GET /agent/v1/actions} — actions PENDING/DELIVERED pour cet agent. */
    List<AgentAction> fetchActions();

    /** {@code POST /agent/v1/actions/{id}/result} — renvoie le code HTTP. */
    int sendResult(String actionId, Map<String, Object> body);
}
