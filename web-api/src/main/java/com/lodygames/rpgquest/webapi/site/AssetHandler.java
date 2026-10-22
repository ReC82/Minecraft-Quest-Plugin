package com.lodygames.rpgquest.webapi.site;

import com.lodygames.rpgquest.webapi.http.ApiHandler;
import com.lodygames.rpgquest.webapi.http.HttpResponses;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.Map;

/**
 * {@code GET /assets/lq-icons.svg} — sprite des icônes du portail (#428), servi depuis le JAR.
 *
 * <p>Seule ressource statique du portail : aucun CDN, rien de chargé ailleurs (CSP {@code 'self'}).
 * L'adresse versionnée ({@code ?v=<empreinte>}) est mise en cache un an ; sans version, une heure.
 * Toute autre adresse sous {@code /assets/} répond 404.</p>
 */
public final class AssetHandler implements ApiHandler {

    public static final String PREFIX = "/assets/";
    private static final String ETAG = "\"lq-" + PortalIcons.VERSION + "\"";

    private final byte[] sprite = PortalIcons.sprite();

    @Override
    public int handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
            exchange.getResponseHeaders().set("Allow", "GET, HEAD");
            HttpResponses.sendJson(exchange, 405, Map.of("error", "method_not_allowed"));
            return 405;
        }
        if (!PortalIcons.SPRITE_PATH.equals(exchange.getRequestURI().getPath())) {
            HttpResponses.sendJson(exchange, 404, Map.of("error", "not_found"));
            return 404;
        }
        String query = exchange.getRequestURI().getRawQuery();
        boolean versioned = ("v=" + PortalIcons.VERSION).equals(query);
        exchange.getResponseHeaders().set("Cache-Control",
                versioned ? "public, max-age=31536000, immutable" : "public, max-age=3600");
        exchange.getResponseHeaders().set("ETag", ETAG);
        if (ETAG.equals(exchange.getRequestHeaders().getFirst("If-None-Match"))) {
            exchange.sendResponseHeaders(304, -1);
            return 304;
        }
        HttpResponses.sendBytes(exchange, 200, "image/svg+xml; charset=utf-8", sprite);
        return 200;
    }
}
