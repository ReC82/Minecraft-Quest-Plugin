package be.lloyd.rpgquest.webapi.store;

import be.lloyd.rpgquest.webapi.http.ApiHandler;
import be.lloyd.rpgquest.webapi.http.HttpResponses;
import be.lloyd.rpgquest.webapi.http.MalformedRequestException;
import be.lloyd.rpgquest.webapi.json.Json;
import be.lloyd.rpgquest.webapi.json.JsonParseException;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

/**
 * {@code POST /api/store/deliveries/{id}/ack} — accusé de réception envoyé
 * par le serveur de jeu après traitement (mission point 7) : idempotent,
 * ré-acquitter une livraison déjà traitée n'est jamais une erreur (test
 * "livraison répétée").
 */
public final class AckDeliveryHandler implements ApiHandler {

    private static final String PREFIX = "/api/store/deliveries/";
    private static final String SUFFIX = "/ack";

    private final StoreService store;

    public AckDeliveryHandler(StoreService store) {
        this.store = store;
    }

    @Override
    public int handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (!"POST".equals(exchange.getRequestMethod()) || !path.startsWith(PREFIX) || !path.endsWith(SUFFIX)) {
            HttpResponses.sendJson(exchange, 404, Map.of("error", "not_found"));
            return 404;
        }
        String deliveryId = path.substring(PREFIX.length(), path.length() - SUFFIX.length());
        if (deliveryId.isBlank()) {
            throw new MalformedRequestException("Identifiant de livraison manquant.");
        }

        String rawBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Object parsed;
        try {
            parsed = rawBody.isBlank() ? Map.of() : Json.parse(rawBody);
        } catch (JsonParseException e) {
            throw new MalformedRequestException("Corps JSON invalide : " + e.getMessage());
        }
        if (!(parsed instanceof Map<?, ?> raw)) {
            throw new MalformedRequestException("Corps JSON invalide.");
        }
        if (!(raw.get("delivered") instanceof Boolean delivered)) {
            throw new MalformedRequestException("« delivered » (booléen) est requis.");
        }
        String detail = raw.get("detail") instanceof String detailText ? detailText : null;

        StoreRepository.AckOutcome outcome = store.acknowledgeDelivery(deliveryId, delivered, detail).join();
        return switch (outcome) {
            case RECORDED, ALREADY_ACKNOWLEDGED -> {
                HttpResponses.sendJson(exchange, 200, Map.of("status", outcome.name().toLowerCase(Locale.ROOT)));
                yield 200;
            }
            case UNKNOWN_DELIVERY -> {
                HttpResponses.sendJson(exchange, 404, Map.of("error", "unknown_delivery"));
                yield 404;
            }
        };
    }
}
