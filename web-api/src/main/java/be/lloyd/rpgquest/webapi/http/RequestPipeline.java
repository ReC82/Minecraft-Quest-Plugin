package be.lloyd.rpgquest.webapi.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.util.Map;

/**
 * Enchaîne, pour chaque requête : journalisation, limite de fréquence,
 * authentification (si requise par la route), puis le handler applicatif —
 * une seule implémentation de ces garanties, jamais dupliquée par route
 * (mission étape 21, points 5-6).
 */
public final class RequestPipeline {

    private final RateLimiter rateLimiter;
    private final AuthFilter authFilter;
    private final AccessLogger accessLogger;

    public RequestPipeline(RateLimiter rateLimiter, AuthFilter authFilter, AccessLogger accessLogger) {
        this.rateLimiter = rateLimiter;
        this.authFilter = authFilter;
        this.accessLogger = accessLogger;
    }

    public HttpHandler wrap(boolean requiresAuth, ApiHandler handler) {
        return exchange -> {
            long start = System.currentTimeMillis();
            String clientIp = clientIp(exchange);
            int status;
            try {
                if (!rateLimiter.tryAcquire(clientIp)) {
                    status = 429;
                    HttpResponses.sendJson(exchange, 429,
                            Map.of("error", "rate_limited", "message", "Trop de requêtes, réessaie plus tard."));
                } else if (requiresAuth && !authFilter.isAuthorized(exchange.getRequestHeaders().getFirst("Authorization"))) {
                    status = 401;
                    HttpResponses.sendJson(exchange, 401,
                            Map.of("error", "unauthorized", "message", "Jeton invalide ou manquant."));
                } else {
                    status = handler.handle(exchange);
                }
            } catch (MalformedRequestException e) {
                status = 400;
                HttpResponses.sendJson(exchange, 400, Map.of("error", "bad_request", "message", e.getMessage()));
            } catch (IOException | RuntimeException e) {
                status = 500;
                HttpResponses.sendJson(exchange, 500, Map.of("error", "internal_error", "message", "Erreur interne."));
            } finally {
                exchange.close();
            }
            accessLogger.log(exchange.getRequestMethod(), exchange.getRequestURI().getPath(), clientIp, status,
                    System.currentTimeMillis() - start);
        };
    }

    private String clientIp(HttpExchange exchange) {
        var remote = exchange.getRemoteAddress();
        return remote == null || remote.getAddress() == null ? "unknown" : remote.getAddress().getHostAddress();
    }
}
