package com.lodygames.rpgquest.webapi.store;

import com.lodygames.rpgquest.webapi.http.ApiHandler;
import com.lodygames.rpgquest.webapi.http.HttpResponses;
import com.lodygames.rpgquest.webapi.http.QueryParams;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code GET /api/store/deliveries/pending[?limit=N]} — sondé par le
 * serveur de jeu (mission point 8, "récupère les livraisons en attente
 * après redémarrage" — un simple sondage périodique couvre aussi bien un
 * redémarrage normal qu'une reprise après crash, aucune logique spéciale
 * de reprise n'est nécessaire côté web-api).
 */
public final class PendingDeliveriesHandler implements ApiHandler {

    private final StoreService store;

    public PendingDeliveriesHandler(StoreService store) {
        this.store = store;
    }

    @Override
    public int handle(HttpExchange exchange) throws IOException {
        Map<String, String> query = QueryParams.parse(exchange.getRequestURI().getRawQuery());
        int limit = QueryParams.parseIntParam(query, "limit", 50, 1, 500);

        List<Delivery> deliveries = store.pendingDeliveries(limit).join();
        List<Map<String, Object>> body = new ArrayList<>();
        for (Delivery delivery : deliveries) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", delivery.id());
            entry.put("orderId", delivery.orderId());
            entry.put("kind", delivery.kind().name());
            entry.put("productId", delivery.productId());
            entry.put("playerUuid", delivery.playerUuid().toString());
            entry.put("playerName", delivery.playerName());
            entry.put("attempts", delivery.attempts());
            body.add(entry);
        }

        HttpResponses.sendJson(exchange, 200, Map.of("deliveries", body));
        return 200;
    }
}
