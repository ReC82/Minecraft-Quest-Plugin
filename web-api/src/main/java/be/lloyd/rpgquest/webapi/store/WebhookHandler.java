package be.lloyd.rpgquest.webapi.store;

import be.lloyd.rpgquest.webapi.http.ApiHandler;
import be.lloyd.rpgquest.webapi.http.HttpResponses;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;

/**
 * {@code POST /store/webhook} — reçoit les notifications du prestataire de
 * paiement. Authentifié par signature (mission point 9), pas par le jeton
 * serveur-à-serveur classique : voir {@link WebhookSigner}. Idempotent par
 * {@code eventId} (mission, test "webhook répété") — un rejeu renvoie
 * {@code 200} sans jamais relivrer une seconde fois.
 */
public final class WebhookHandler implements ApiHandler {

    private final StoreService store;

    public WebhookHandler(StoreService store) {
        this.store = store;
    }

    @Override
    public int handle(HttpExchange exchange) throws IOException {
        byte[] payload = exchange.getRequestBody().readAllBytes();
        String signature = exchange.getRequestHeaders().getFirst("X-Store-Signature");

        StoreService.WebhookOutcome outcome = store.handleWebhook(payload, signature).join();
        return switch (outcome) {
            case PROCESSED, DUPLICATE -> {
                HttpResponses.sendJson(exchange, 200, Map.of("status", outcome.name().toLowerCase(Locale.ROOT)));
                yield 200;
            }
            case INVALID_SIGNATURE -> {
                HttpResponses.sendJson(exchange, 401, Map.of("error", "invalid_signature"));
                yield 401;
            }
            case UNKNOWN_ORDER -> {
                HttpResponses.sendJson(exchange, 404, Map.of("error", "unknown_order"));
                yield 404;
            }
            case MALFORMED -> {
                HttpResponses.sendJson(exchange, 400, Map.of("error", "malformed_webhook"));
                yield 400;
            }
        };
    }
}
