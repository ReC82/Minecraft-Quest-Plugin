package be.lloyd.rpgquest.webapi.api;

import be.lloyd.rpgquest.webapi.http.ApiHandler;
import be.lloyd.rpgquest.webapi.http.HttpResponses;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.Map;

/** Retombée pour toute route {@code /api/*} inconnue. */
public final class NotFoundHandler implements ApiHandler {

    @Override
    public int handle(HttpExchange exchange) throws IOException {
        HttpResponses.sendJson(exchange, 404, Map.of("error", "not_found", "message", "Route inconnue."));
        return 404;
    }
}
