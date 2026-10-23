package com.lodygames.rpgquest.web.link;

import com.lodygames.rpgquest.web.Json;
import com.lodygames.rpgquest.web.JsonParseException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Envoie au portail la preuve d'un {@code /lien <code>} (issue #53) :
 * {@code POST <base-url>/api/link/confirm} avec le jeton dédié. Asynchrone, jamais sur le thread
 * principal ; toute panne réseau ou réponse inattendue devient {@link LinkReply#UNAVAILABLE}.
 */
public class PortalLinkClient {

    private final PortalLinkConfig config;
    private final HttpClient httpClient;

    public PortalLinkClient(PortalLinkConfig config) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(config.connectTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public CompletableFuture<LinkReply> confirm(String code, UUID playerUuid, String playerName) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("uuid", playerUuid.toString());
        body.put("name", playerName);
        return post("/api/link/confirm", body)
                .thenApply(response -> LinkReply.parse(result(response.statusCode(), response.body())))
                .exceptionally(error -> LinkReply.UNAVAILABLE);
    }

    /**
     * {@code POST <base-url>/api/link/progress} : sans {@code progress}, simple sonde « cet UUID est-il
     * relié ? » qui n'envoie que l'UUID ; avec, la progression personnelle du joueur relié.
     */
    public CompletableFuture<ProgressReply> pushProgress(UUID playerUuid, String playerName, Map<String, Object> progress) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("uuid", playerUuid.toString());
        if (progress != null) {
            body.put("name", playerName);
            body.put("progress", progress);
        }
        return post("/api/link/progress", body)
                .thenApply(response -> ProgressReply.parse(result(response.statusCode(), response.body())))
                .exceptionally(error -> ProgressReply.UNAVAILABLE);
    }

    private CompletableFuture<HttpResponse<String>> post(String path, Map<String, Object> body) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(config.baseUrl() + path))
                .timeout(Duration.ofMillis(config.requestTimeoutMs()))
                .header("Authorization", "Bearer " + config.token())
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8))
                .build();
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    static LinkReply parse(int status, String body) {
        return LinkReply.parse(result(status, body));
    }

    /** Le champ {@code result} d'une réponse 200, ou chaîne vide (réponse inattendue). */
    private static String result(int status, String body) {
        if (status != 200 || body == null) {
            return "";
        }
        try {
            if (Json.parse(body) instanceof Map<?, ?> map && map.get("result") instanceof String result) {
                return result;
            }
        } catch (JsonParseException e) {
            return "";
        }
        return "";
    }
}
