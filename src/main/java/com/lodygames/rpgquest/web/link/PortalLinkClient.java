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
        HttpRequest request = HttpRequest.newBuilder(URI.create(config.baseUrl() + "/api/link/confirm"))
                .timeout(Duration.ofMillis(config.requestTimeoutMs()))
                .header("Authorization", "Bearer " + config.token())
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8))
                .build();
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(response -> parse(response.statusCode(), response.body()))
                .exceptionally(error -> LinkReply.UNAVAILABLE);
    }

    static LinkReply parse(int status, String body) {
        if (status != 200 || body == null) {
            return LinkReply.UNAVAILABLE;
        }
        try {
            if (Json.parse(body) instanceof Map<?, ?> map && map.get("result") instanceof String result) {
                return LinkReply.parse(result);
            }
        } catch (JsonParseException e) {
            return LinkReply.UNAVAILABLE;
        }
        return LinkReply.UNAVAILABLE;
    }
}
