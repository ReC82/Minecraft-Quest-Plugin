package com.lodygames.rpgquest.web.agent;

import com.lodygames.rpgquest.web.Json;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Client HTTPS <strong>sortant</strong> de l'agent RPGQuest vers PlugAdmin (issue #51).
 *
 * <ul>
 *   <li>{@code java.net.http.HttpClient} — validation de certificat <strong>normale</strong>, aucun
 *       {@code trustAll} ;</li>
 *   <li>timeouts courts (connexion + requête) issus de {@link AgentConfig} ;</li>
 *   <li>corps de réponse borné ({@link AgentConfig#maxResponseBytes()}) ;</li>
 *   <li>toute panne réseau devient une {@link PlugAdminUnavailableException} au message affichable,
 *       jamais une stacktrace propagée, jamais de secret.</li>
 * </ul>
 *
 * <p>Les appels sont bloquants : ils sont toujours exécutés depuis un thread <em>asynchrone</em> de
 * l'agent, jamais depuis le thread principal Paper.</p>
 */
public final class PlugAdminClient implements PlugAdminTransport {

    private final AgentConfig config;
    private final HttpClient http;

    public PlugAdminClient(AgentConfig config) {
        this(config, HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(config.connectTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .build());
    }

    /** Constructeur injectable (tests) : {@code HttpClient} fourni. */
    public PlugAdminClient(AgentConfig config, HttpClient http) {
        this.config = config;
        this.http = http;
    }

    // ---- Heartbeat ---------------------------------------------------------------------

    /** {@code POST /agent/v1/heartbeat}. Renvoie le code HTTP (200 attendu). */
    @Override
    public int sendHeartbeat(Map<String, Object> payload) {
        HttpResponse<byte[]> response = send("POST", "/heartbeat", Json.write(payload));
        return response.statusCode();
    }

    // ---- File d'actions ---------------------------------------------------------------

    /** {@code GET /agent/v1/actions} — actions destinées à cet agent, PENDING ou DELIVERED. */
    @Override
    public List<AgentAction> fetchActions() {
        HttpResponse<byte[]> response = send("GET", "/actions", null);
        if (response.statusCode() != 200) {
            throw new PlugAdminUnavailableException("PlugAdmin a répondu HTTP " + response.statusCode()
                    + " sur GET /agent/v1/actions.");
        }
        return parseActions(bodyText(response));
    }

    /** {@code POST /agent/v1/actions/{id}/result}. Renvoie le code HTTP (200/202 attendus). */
    @Override
    public int sendResult(String actionId, Map<String, Object> body) {
        String path = "/actions/" + urlSegment(actionId) + "/result";
        HttpResponse<byte[]> response = send("POST", path, Json.write(body));
        return response.statusCode();
    }

    // ---- Bas niveau -----------------------------------------------------------------

    private HttpResponse<byte[]> send(String method, String path, String jsonBody) {
        URI uri = URI.create(config.agentBaseUrl() + path);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(config.requestTimeoutMs()))
                .header("Authorization", "Bearer " + config.token())
                .header("X-Agent-Id", config.agentId())
                .header("X-Agent-Env", config.environment())
                .header("Accept", "application/json")
                .header("User-Agent", "RPGQuest-Agent/" + AgentConfig.PROTOCOL);

        if (jsonBody != null) {
            builder.header("Content-Type", "application/json; charset=utf-8");
            builder.method(method, HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }

        HttpResponse<byte[]> response;
        try {
            response = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (HttpTimeoutException e) {
            throw new PlugAdminUnavailableException("PlugAdmin injoignable : délai d'attente dépassé (" + method + " " + path + ").", e);
        } catch (ConnectException e) {
            throw new PlugAdminUnavailableException("PlugAdmin injoignable : connexion refusée (" + config.baseUrl() + ").", e);
        } catch (java.io.IOException e) {
            throw new PlugAdminUnavailableException("PlugAdmin injoignable : " + e.getClass().getSimpleName()
                    + " (" + safeMessage(e) + ").", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PlugAdminUnavailableException("Requête PlugAdmin interrompue (arrêt en cours).", e);
        }

        long declared = response.headers().firstValueAsLong("content-length").orElse(-1);
        if (declared > config.maxResponseBytes()) {
            throw new PlugAdminUnavailableException("Réponse PlugAdmin trop volumineuse (" + declared + " o).");
        }
        if (response.body() != null && response.body().length > config.maxResponseBytes()) {
            throw new PlugAdminUnavailableException("Réponse PlugAdmin trop volumineuse (" + response.body().length + " o).");
        }
        return response;
    }

    private static String bodyText(HttpResponse<byte[]> response) {
        byte[] body = response.body();
        return body == null ? "" : new String(body, StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private static List<AgentAction> parseActions(String body) {
        Object parsed;
        try {
            parsed = Json.parse(body.isBlank() ? "{}" : body);
        } catch (RuntimeException e) {
            throw new PlugAdminUnavailableException("Réponse d'actions PlugAdmin illisible (" + e.getMessage() + ").", e);
        }
        if (!(parsed instanceof Map<?, ?> root)) {
            throw new PlugAdminUnavailableException("Réponse d'actions PlugAdmin inattendue (objet requis).");
        }
        Object rawList = ((Map<String, Object>) root).get("actions");
        List<AgentAction> actions = new ArrayList<>();
        if (rawList instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> obj) {
                    actions.add(toAction((Map<String, Object>) obj));
                }
            }
        }
        return actions;
    }

    @SuppressWarnings("unchecked")
    private static AgentAction toAction(Map<String, Object> obj) {
        String id = str(obj.get("id"));
        String type = str(obj.get("type"));
        Map<String, String> params = new LinkedHashMap<>();
        Object rawParams = obj.get("params");
        if (rawParams instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : ((Map<String, Object>) map).entrySet()) {
                if (e.getValue() != null) {
                    params.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
                }
            }
        }
        return new AgentAction(id, type, params);
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String urlSegment(String raw) {
        StringBuilder sb = new StringBuilder();
        for (char c : raw.toCharArray()) {
            if (Character.isLetterOrDigit(c) || c == '-' || c == '_') {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String safeMessage(Throwable e) {
        String message = e.getMessage();
        return message == null ? "sans détail" : message.replace('\n', ' ');
    }
}
