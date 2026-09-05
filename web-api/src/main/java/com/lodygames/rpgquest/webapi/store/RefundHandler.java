package com.lodygames.rpgquest.webapi.store;

import com.lodygames.rpgquest.webapi.http.ApiHandler;
import com.lodygames.rpgquest.webapi.http.HttpResponses;
import com.lodygames.rpgquest.webapi.http.MalformedRequestException;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.Map;

/** {@code POST /api/store/orders/{id}/refund} — simulation admin d'un remboursement (mission point 10). */
public final class RefundHandler implements ApiHandler {

    private static final String PREFIX = "/api/store/orders/";
    private static final String SUFFIX = "/refund";

    private final StoreService store;

    public RefundHandler(StoreService store) {
        this.store = store;
    }

    @Override
    public int handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (!"POST".equals(exchange.getRequestMethod()) || !path.startsWith(PREFIX) || !path.endsWith(SUFFIX)) {
            HttpResponses.sendJson(exchange, 404, Map.of("error", "not_found"));
            return 404;
        }
        String orderId = path.substring(PREFIX.length(), path.length() - SUFFIX.length());
        if (orderId.isBlank()) {
            throw new MalformedRequestException("Identifiant de commande manquant.");
        }

        StoreService.RefundOutcome outcome = store.refund(orderId).join();
        return switch (outcome) {
            case REFUNDED -> {
                HttpResponses.sendJson(exchange, 200, Map.of("status", "refunded"));
                yield 200;
            }
            case UNKNOWN_ORDER -> {
                HttpResponses.sendJson(exchange, 404, Map.of("error", "unknown_order"));
                yield 404;
            }
            case INVALID_STATE -> {
                HttpResponses.sendJson(exchange, 409, Map.of("error", "invalid_state"));
                yield 409;
            }
        };
    }
}
