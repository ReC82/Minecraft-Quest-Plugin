package com.lodygames.rpgquest.panel.agent;

import com.lodygames.rpgquest.panel.audit.AuditLog;
import com.lodygames.rpgquest.panel.http.Http;
import com.lodygames.rpgquest.panel.json.Json;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * Contrat <strong>versionné</strong> {@code /agent/v1/*} par lequel les agents RPGQuest (issue #51)
 * poussent leur état et récupèrent des actions. Distinct des routes navigateur et du bridge local
 * {@code /admin/v1/*} : ici l'authentification se fait <strong>par agent</strong> (jeton dédié), pas
 * par session.
 *
 * <ul>
 *   <li>{@code POST /agent/v1/heartbeat} — état live (même format que {@code HealthSource}) ;</li>
 *   <li>{@code GET  /agent/v1/actions} — actions PENDING/DELIVERED pour cet agent ;</li>
 *   <li>{@code POST /agent/v1/actions/{id}/result} — résultat structuré d'une action.</li>
 * </ul>
 *
 * <p>Sécurité : jeton porteur obligatoire (comparaison en temps constant), agent id + environnement
 * validés, payload borné (413), aucune commande / chemin / SQL transporté, aucun secret dans les
 * réponses ni les logs, idempotence côté {@link AgentStore}.</p>
 */
public final class AgentEndpoints {

    private static final System.Logger LOG = System.getLogger("rpgquest.panel.agent");
    private static final String BEARER = "Bearer ";
    private static final long MAX_BODY_BYTES = 64 * 1024;

    private final AgentRegistry registry;
    private final AgentStore store;
    private final AuditLog audit;
    private final Duration actionExpiry;
    private final BooleanSupplier disabled;

    public AgentEndpoints(AgentRegistry registry, AgentStore store, AuditLog audit,
                          Duration actionExpiry, BooleanSupplier disabled) {
        this.registry = registry;
        this.store = store;
        this.audit = audit;
        this.actionExpiry = actionExpiry;
        this.disabled = disabled;
    }

    /** Branche les routes agent sur le serveur HTTP partagé du panel. */
    public void register(HttpServer server) {
        server.createContext("/agent/v1/heartbeat", this::route);
        server.createContext("/agent/v1/actions", this::route);
    }

    // ---- Pipeline commun -----------------------------------------------------------

    private void route(HttpExchange exchange) throws IOException {
        String rid = UUID.randomUUID().toString().substring(0, 8);
        int status = 0;
        try (exchange) {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            Body body = readBody(exchange); // draine toujours le corps (même sur erreur ultérieure)

            if (disabled.getAsBoolean()) {
                status = send(exchange, 503, error("panel_disabled", "PlugAdmin est temporairement hors service."));
                return;
            }

            boolean isResult = path.startsWith("/agent/v1/actions/") && path.endsWith("/result");
            boolean isActionsList = path.equals("/agent/v1/actions");
            boolean isHeartbeat = path.equals("/agent/v1/heartbeat");
            if (!isResult && !isActionsList && !isHeartbeat) {
                status = send(exchange, 404, error("not_found", "Route agent inconnue."));
                return;
            }

            String expectedMethod = isActionsList ? "GET" : "POST";
            if (!expectedMethod.equalsIgnoreCase(method)) {
                status = send(exchange, 405, error("method_not_allowed", expectedMethod + " requis."));
                return;
            }

            AgentIdentity agent = authenticate(exchange);
            if (agent == null) {
                status = send(exchange, 401, error("unauthorized", "Agent ou jeton invalide."));
                return;
            }
            if (body.tooLarge()) {
                status = send(exchange, 413, error("payload_too_large", "Corps de requête trop volumineux."));
                return;
            }

            if (isHeartbeat) {
                status = handleHeartbeat(exchange, rid, agent, body.text());
            } else if (isActionsList) {
                status = handleActionsList(exchange, rid, agent);
            } else {
                status = handleActionResult(exchange, rid, agent, path, body.text());
            }
        } catch (RuntimeException e) {
            status = 500;
            LOG.log(System.Logger.Level.WARNING, "event=agent_handler_error rid=" + rid + " " + e.getClass().getSimpleName());
            trySend(exchange, 500, error("internal_error", "Erreur interne."));
        } finally {
            LOG.log(System.Logger.Level.INFO, "event=agent_request rid=" + rid + " method="
                    + exchange.getRequestMethod() + " path=" + exchange.getRequestURI().getPath() + " status=" + status);
        }
    }

    // ---- POST /agent/v1/heartbeat --------------------------------------------

    private int handleHeartbeat(HttpExchange exchange, String rid, AgentIdentity agent, String rawBody) throws IOException {
        Map<String, Object> json;
        try {
            json = Json.parseObject(rawBody);
        } catch (RuntimeException e) {
            return send(exchange, 400, error("bad_request", "Corps JSON illisible."));
        }
        String bodyAgentId = str(json.get("agent_id"));
        if (bodyAgentId != null && !bodyAgentId.equals(agent.id())) {
            return send(exchange, 400, error("agent_mismatch", "agent_id du corps ≠ agent authentifié."));
        }
        String bodyEnv = str(json.get("environment"));
        if (bodyEnv != null && !bodyEnv.equalsIgnoreCase(agent.environment())) {
            return send(exchange, 400, error("env_mismatch", "environnement du corps ≠ environnement déclaré."));
        }

        HeartbeatRecord record = toHeartbeat(agent, json, rawBody);
        store.saveHeartbeat(record);
        LOG.log(System.Logger.Level.INFO, "event=agent_heartbeat rid=" + rid + " agent=" + record.agentId()
                + " env=" + record.environment() + " version=" + nz(record.pluginVersion())
                + " players=" + record.playersOnline() + "/" + record.maxPlayers());

        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("ok", true);
        ok.put("received_at", record.receivedAt().toString());
        return send(exchange, 200, ok);
    }

    // ---- GET /agent/v1/actions --------------------------------------------

    private int handleActionsList(HttpExchange exchange, String rid, AgentIdentity agent) throws IOException {
        List<AgentActionRow> rows = store.deliverableActions(agent.id(), Instant.now(), actionExpiry);
        List<Map<String, Object>> actions = new ArrayList<>();
        for (AgentActionRow row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.id());
            item.put("type", row.type());
            item.put("params", new LinkedHashMap<String, Object>(row.params()));
            actions.add(item);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("actions", actions);
        LOG.log(System.Logger.Level.INFO, "event=agent_actions_poll rid=" + rid + " agent="
                + agent.id() + " count=" + actions.size());
        return send(exchange, 200, root);
    }

    // ---- POST /agent/v1/actions/{id}/result ---------------------------

    private int handleActionResult(HttpExchange exchange, String rid, AgentIdentity agent, String path, String rawBody)
            throws IOException {
        String actionId = path.substring("/agent/v1/actions/".length(), path.length() - "/result".length());
        if (!isPlainId(actionId)) {
            return send(exchange, 400, error("bad_request", "Identifiant d'action invalide."));
        }
        Map<String, Object> json;
        try {
            json = Json.parseObject(rawBody);
        } catch (RuntimeException e) {
            return send(exchange, 400, error("bad_request", "Corps JSON illisible."));
        }
        String bodyActionId = str(json.get("action_id"));
        if (bodyActionId != null && !bodyActionId.equals(actionId)) {
            return send(exchange, 400, error("action_mismatch", "action_id du corps ≠ id de l'URL."));
        }
        AgentActionStatus status = AgentActionStatus.fromAgentResult(str(json.get("status")));
        String value = str(json.get("value"));
        String message = truncate(str(json.get("message")), 500);

        boolean accepted = store.recordResult(actionId, agent.id(), status, value, message,
                truncate(rawBody, 4000), Instant.now());
        if (!accepted) {
            return send(exchange, 404, error("unknown_action", "Action inconnue ou destinée à un autre agent."));
        }
        audit.record("agent:" + agent.id(), "agent.action.result", "action=" + actionId,
                status.name(), message, rid);
        LOG.log(System.Logger.Level.INFO, "event=agent_action_result rid=" + rid + " agent="
                + agent.id() + " action=" + actionId + " status=" + status);
        return send(exchange, 200, Map.of("ok", true));
    }

    // ---- Auth + IO -----------------------------------------------------

    private AgentIdentity authenticate(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        String token = header != null && header.startsWith(BEARER) ? header.substring(BEARER.length()).trim() : null;
        String agentId = exchange.getRequestHeaders().getFirst("X-Agent-Id");
        return registry.authenticate(agentId, token).orElse(null);
    }

    private record Body(String text, boolean tooLarge) {
    }

    private static Body readBody(HttpExchange exchange) throws IOException {
        long declared = -1;
        String cl = exchange.getRequestHeaders().getFirst("Content-Length");
        if (cl != null) {
            try {
                declared = Long.parseLong(cl.trim());
            } catch (NumberFormatException ignored) {
                declared = -1;
            }
        }
        try (InputStream in = exchange.getRequestBody()) {
            byte[] bytes = in.readNBytes((int) MAX_BODY_BYTES + 1);
            boolean tooLarge = bytes.length > MAX_BODY_BYTES || declared > MAX_BODY_BYTES;
            if (tooLarge) {
                in.readAllBytes(); // draine le reste pour une réponse propre
                return new Body("", true);
            }
            return new Body(new String(bytes, StandardCharsets.UTF_8), false);
        }
    }

    private HeartbeatRecord toHeartbeat(AgentIdentity identity, Map<String, Object> json, String rawBody) {
        Map<String, Object> plugin = obj(json.get("plugin"));
        Map<String, Object> server = obj(json.get("server"));
        Object worlds = json.get("worlds");
        return new HeartbeatRecord(
                identity.id(),
                identity.environment(),
                Instant.now(),
                str(json.get("generated_at")),
                str(json.get("protocol")),
                str(plugin.get("name")),
                str(plugin.get("version")),
                str(server.get("state")),
                lng(server.get("players_online")),
                lng(server.get("max_players")),
                lng(server.get("uptime_seconds")),
                worlds == null ? "{}" : Json.write(worlds),
                truncate(rawBody, 8000));
    }

    private int send(HttpExchange exchange, int status, Map<String, Object> body) throws IOException {
        Http.json(exchange, status, Json.write(body));
        return status;
    }

    private void trySend(HttpExchange exchange, int status, Map<String, Object> body) {
        try {
            if (exchange.getResponseCode() == -1) {
                send(exchange, status, body);
            }
        } catch (IOException ignored) {
            // réponse déjà (partiellement) envoyée
        }
    }

    private static Map<String, Object> error(String code, String message) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("code", code);
        err.put("message", message);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("error", err);
        return root;
    }

    // ---- Petits utilitaires --------------------------------------------

    private static boolean isPlainId(String raw) {
        if (raw == null || raw.isEmpty() || raw.length() > 80) {
            return false;
        }
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '-' && c != '_') {
                return false;
            }
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> obj(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : new LinkedHashMap<>();
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static long lng(Object value) {
        return value instanceof Number n ? n.longValue() : -1;
    }

    private static String nz(String value) {
        return value == null ? "—" : value;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
