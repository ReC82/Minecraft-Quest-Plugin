package com.lodygames.rpgquest.webapi.store;

import com.lodygames.rpgquest.webapi.http.ApiHandler;
import com.lodygames.rpgquest.webapi.http.HttpResponses;
import com.lodygames.rpgquest.webapi.http.MalformedRequestException;
import com.lodygames.rpgquest.webapi.http.QueryParams;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@code GET /api/store/orders[?playerUuid=...][&limit=N]} — historique
 * consultable par l'administrateur (mission point 11), via la commande
 * {@code /store history} du plugin.
 */
public final class OrdersHistoryHandler implements ApiHandler {

    private final StoreService store;

    public OrdersHistoryHandler(StoreService store) {
        this.store = store;
    }

    @Override
    public int handle(HttpExchange exchange) throws IOException {
        Map<String, String> query = QueryParams.parse(exchange.getRequestURI().getRawQuery());
        String playerUuidRaw = query.get("playerUuid");

        List<Order> orders;
        if (playerUuidRaw != null) {
            UUID playerUuid;
            try {
                playerUuid = UUID.fromString(playerUuidRaw);
            } catch (IllegalArgumentException e) {
                throw new MalformedRequestException("« playerUuid » invalide : \"" + playerUuidRaw + "\".");
            }
            orders = store.ordersForPlayer(playerUuid).join();
        } else {
            int limit = QueryParams.parseIntParam(query, "limit", 50, 1, 500);
            orders = store.allOrders(limit).join();
        }

        List<Map<String, Object>> body = new ArrayList<>();
        for (Order order : orders) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", order.id());
            entry.put("productId", order.productId());
            entry.put("playerUuid", order.playerUuid().toString());
            entry.put("playerName", order.playerName());
            entry.put("status", order.status().name());
            entry.put("amountCents", order.amountCents());
            entry.put("currency", order.currency());
            entry.put("createdAt", order.createdAt().toString());
            entry.put("updatedAt", order.updatedAt().toString());
            body.add(entry);
        }

        HttpResponses.sendJson(exchange, 200, Map.of("orders", body));
        return 200;
    }
}
