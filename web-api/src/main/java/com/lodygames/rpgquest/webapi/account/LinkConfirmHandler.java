package com.lodygames.rpgquest.webapi.account;

import com.lodygames.rpgquest.webapi.http.ApiHandler;
import com.lodygames.rpgquest.webapi.http.AuthFilter;
import com.lodygames.rpgquest.webapi.http.HttpResponses;
import com.lodygames.rpgquest.webapi.http.MalformedRequestException;
import com.lodygames.rpgquest.webapi.json.Json;
import com.lodygames.rpgquest.webapi.json.JsonParseException;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * {@code POST /api/link/confirm} (issue #53) — preuve de liaison envoyée par le serveur Minecraft
 * après {@code /lien <code>} : {@code {"code": "...", "uuid": "...", "name": "..."}}.
 *
 * <p>Authentifiée par le jeton <em>dédié</em> {@code RPGQUEST_PORTAL_LINK_TOKEN} (en-tête
 * {@code Authorization: Bearer}), distinct du jeton général de l'API : ce jeton n'ouvre que cette
 * route. Sans jeton configuré, tout est refusé (401). La réponse ne contient que le résultat — jamais
 * l'identifiant du compte web — et le code n'est jamais journalisé.</p>
 */
public final class LinkConfirmHandler implements ApiHandler {

    private static final int MAX_BODY_BYTES = 2048;
    /** Pseudos Java (3–16) et préfixes Bedrock courants ({@code .}, {@code *}). */
    static final Pattern PLAYER_NAME = Pattern.compile("[.*]?[A-Za-z0-9_]{1,32}");

    private final AccountService accounts;
    private final AuthFilter linkAuth;

    public LinkConfirmHandler(AccountService accounts, String linkToken) {
        this.accounts = accounts;
        this.linkAuth = new AuthFilter(linkToken);
    }

    @Override
    public int handle(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", "POST");
            HttpResponses.sendJson(exchange, 405, Map.of("error", "method_not_allowed"));
            return 405;
        }
        if (!linkAuth.isAuthorized(exchange.getRequestHeaders().getFirst("Authorization"))) {
            HttpResponses.sendJson(exchange, 401, Map.of("error", "unauthorized"));
            return 401;
        }
        if (!accounts.open()) {
            HttpResponses.sendJson(exchange, 503, Map.of("error", "accounts_closed"));
            return 503;
        }
        Map<?, ?> body = readBody(exchange);
        String code = text(body, "code");
        String name = text(body, "name");
        UUID uuid;
        try {
            uuid = UUID.fromString(text(body, "uuid"));
        } catch (IllegalArgumentException e) {
            throw new MalformedRequestException("« uuid » invalide.");
        }
        if (!PLAYER_NAME.matcher(name).matches()) {
            throw new MalformedRequestException("« name » invalide.");
        }
        LinkOutcome outcome;
        try {
            outcome = accounts.confirmLink(code, uuid, name);
        } catch (SQLException e) {
            throw new IOException("Base des comptes indisponible", e);
        }
        HttpResponses.sendJson(exchange, 200, Map.of("result", outcome.name()));
        return 200;
    }

    private static Map<?, ?> readBody(HttpExchange exchange) throws IOException {
        byte[] bytes;
        try (InputStream in = exchange.getRequestBody()) {
            bytes = in.readNBytes(MAX_BODY_BYTES + 1);
        }
        if (bytes.length > MAX_BODY_BYTES) {
            throw new MalformedRequestException("Corps de requête trop long.");
        }
        try {
            if (Json.parse(new String(bytes, StandardCharsets.UTF_8)) instanceof Map<?, ?> map) {
                return map;
            }
        } catch (JsonParseException e) {
            // traité ci-dessous
        }
        throw new MalformedRequestException("Corps JSON attendu.");
    }

    private static String text(Map<?, ?> body, String key) {
        if (body.get(key) instanceof String value && !value.isBlank() && value.length() <= 64) {
            return value.trim();
        }
        throw new MalformedRequestException("« " + key + " » manquant ou invalide.");
    }
}
