package com.lodygames.rpgquest.webapi.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Enchaîne, pour chaque requête : journalisation, limite de fréquence,
 * authentification (si requise par la route), puis le handler applicatif —
 * une seule implémentation de ces garanties, jamais dupliquée par route
 * (mission étape 21, points 5-6).
 */
public final class RequestPipeline {

    /** Adresse IPv4 ou IPv6 littérale ; tout autre contenu de {@code X-Real-IP} est ignoré. */
    private static final Pattern FORWARDED_IP = Pattern.compile("[0-9A-Fa-f.:]{2,45}");

    private final RateLimiter rateLimiter;
    private final AuthFilter authFilter;
    private final AccessLogger accessLogger;
    private final boolean trustProxyHeaders;

    public RequestPipeline(RateLimiter rateLimiter, AuthFilter authFilter, AccessLogger accessLogger) {
        this(rateLimiter, authFilter, accessLogger, false);
    }

    /**
     * @param trustProxyHeaders lire l'IP du visiteur dans {@code X-Real-IP} — uniquement pour une
     *                          connexion venue de la boucle locale (le nginx de la même machine) ;
     *                          un client direct ne peut donc pas usurper une autre IP (#52).
     */
    public RequestPipeline(RateLimiter rateLimiter, AuthFilter authFilter, AccessLogger accessLogger,
            boolean trustProxyHeaders) {
        this.rateLimiter = rateLimiter;
        this.authFilter = authFilter;
        this.accessLogger = accessLogger;
        this.trustProxyHeaders = trustProxyHeaders;
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
        if (remote == null || remote.getAddress() == null) {
            return "unknown";
        }
        if (trustProxyHeaders && remote.getAddress().isLoopbackAddress()) {
            String forwarded = exchange.getRequestHeaders().getFirst("X-Real-IP");
            if (forwarded != null && FORWARDED_IP.matcher(forwarded.trim()).matches()) {
                return forwarded.trim();
            }
        }
        return remote.getAddress().getHostAddress();
    }
}
