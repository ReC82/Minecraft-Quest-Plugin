package com.lodygames.rpgquest.store;

import com.lodygames.rpgquest.config.StoreConfig;
import com.lodygames.rpgquest.web.Json;
import com.lodygames.rpgquest.web.JsonParseException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Client HTTP vers l'API boutique de web-api (mission étape 22, point 9 :
 * authentification serveur-à-serveur). Même jeton que l'export en lecture
 * seule de l'étape 21 — lu exclusivement depuis la variable d'environnement
 * {@value #TOKEN_ENV}, jamais depuis {@code config.yml}.
 */
public final class StoreClient {

    public static final String TOKEN_ENV = "RPGQUEST_WEB_API_TOKEN";

    private final Supplier<StoreConfig> config;
    private final String authToken;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    public StoreClient(Supplier<StoreConfig> config) {
        this(config, System.getenv(TOKEN_ENV));
    }

    StoreClient(Supplier<StoreConfig> config, String authToken) {
        this.config = config;
        this.authToken = authToken;
    }

    public CompletableFuture<List<PendingDelivery>> fetchPendingDeliveries(int limit) {
        String url = config.get().webApiBaseUrl() + "/api/store/deliveries/pending?limit=" + limit;
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + authToken)
                .GET()
                .build();
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(this::parsePendingDeliveries);
    }

    private List<PendingDelivery> parsePendingDeliveries(HttpResponse<String> response) {
        if (response.statusCode() != 200) {
            throw new StoreClientException(
                    "Réponse inattendue (" + response.statusCode() + ") de web-api pour les livraisons en attente.");
        }
        Object parsed;
        try {
            parsed = Json.parse(response.body());
        } catch (JsonParseException e) {
            throw new StoreClientException("Réponse JSON illisible de web-api.", e);
        }
        if (!(parsed instanceof Map<?, ?> root) || !(root.get("deliveries") instanceof List<?> rawList)) {
            throw new StoreClientException("Format de réponse inattendu de web-api (« deliveries » manquant).");
        }

        List<PendingDelivery> deliveries = new ArrayList<>();
        for (Object rawItem : rawList) {
            if (!(rawItem instanceof Map<?, ?> raw)) {
                continue;
            }
            try {
                deliveries.add(new PendingDelivery(
                        (String) raw.get("id"),
                        (String) raw.get("orderId"),
                        (String) raw.get("kind"),
                        (String) raw.get("productId"),
                        UUID.fromString((String) raw.get("playerUuid")),
                        raw.get("playerName") instanceof String name ? name : null,
                        ((Number) raw.get("attempts")).intValue()));
            } catch (RuntimeException e) {
                throw new StoreClientException("Entrée de livraison malformée dans la réponse de web-api.", e);
            }
        }
        return deliveries;
    }

    /** Historique admin (mission point 11) : {@code playerUuid} vide = les {@code limit} commandes les plus récentes, toutes confondues. */
    public CompletableFuture<List<StoreOrderSummary>> fetchOrderHistory(UUID playerUuid, int limit) {
        String query = playerUuid != null ? "?playerUuid=" + playerUuid : "?limit=" + limit;
        String url = config.get().webApiBaseUrl() + "/api/store/orders" + query;
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + authToken)
                .GET()
                .build();
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(this::parseOrders);
    }

    private List<StoreOrderSummary> parseOrders(HttpResponse<String> response) {
        if (response.statusCode() != 200) {
            throw new StoreClientException("Réponse inattendue (" + response.statusCode() + ") de web-api pour l'historique des commandes.");
        }
        Object parsed;
        try {
            parsed = Json.parse(response.body());
        } catch (JsonParseException e) {
            throw new StoreClientException("Réponse JSON illisible de web-api.", e);
        }
        if (!(parsed instanceof Map<?, ?> root) || !(root.get("orders") instanceof List<?> rawList)) {
            throw new StoreClientException("Format de réponse inattendu de web-api (« orders » manquant).");
        }

        List<StoreOrderSummary> orders = new ArrayList<>();
        for (Object rawItem : rawList) {
            if (!(rawItem instanceof Map<?, ?> raw)) {
                continue;
            }
            orders.add(new StoreOrderSummary(
                    String.valueOf(raw.get("id")),
                    String.valueOf(raw.get("productId")),
                    String.valueOf(raw.get("playerUuid")),
                    raw.get("playerName") instanceof String name ? name : null,
                    String.valueOf(raw.get("status")),
                    raw.get("amountCents") instanceof Number amount ? amount.longValue() : 0L,
                    String.valueOf(raw.get("currency")),
                    String.valueOf(raw.get("createdAt"))));
        }
        return orders;
    }

    public CompletableFuture<Boolean> acknowledgeDelivery(String deliveryId, boolean delivered, String detail) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("delivered", delivered);
        if (detail != null) {
            body.put("detail", detail);
        }
        String url = config.get().webApiBaseUrl() + "/api/store/deliveries/" + deliveryId + "/ack";
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + authToken)
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8))
                .build();
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .thenApply(response -> response.statusCode() == 200);
    }
}
