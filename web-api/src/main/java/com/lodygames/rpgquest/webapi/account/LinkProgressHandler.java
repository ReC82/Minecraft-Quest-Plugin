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

/**
 * {@code POST /api/link/progress} (issue #53) — progression personnelle d'un joueur relié, envoyée
 * par le serveur Minecraft. Même jeton dédié que {@code /api/link/confirm}.
 *
 * <ul>
 *   <li>{@code {"uuid": "…"}} : <em>sonde</em> — répond {@code LINKED} ou {@code NOT_LINKED}, n'écrit rien ;</li>
 *   <li>{@code {"uuid": "…", "name": "…", "progress": {…}}} : enregistre la progression
 *       ({@link LinkedProgress}, liste blanche) si l'UUID est relié — {@code STORED},
 *       {@code NOT_LINKED} (rien n'est gardé) ou {@code THROTTLED} (envoi trop rapproché).</li>
 * </ul>
 *
 * <p>La réponse ne dit jamais rien du compte web ; le contenu n'est jamais journalisé.</p>
 */
public final class LinkProgressHandler implements ApiHandler {

    static final int MAX_BODY_BYTES = 64 * 1024;

    private final AccountService accounts;
    private final AuthFilter linkAuth;

    public LinkProgressHandler(AccountService accounts, String linkToken) {
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
        UUID uuid;
        try {
            uuid = UUID.fromString(body.get("uuid") instanceof String text ? text : "");
        } catch (IllegalArgumentException e) {
            throw new MalformedRequestException("« uuid » invalide.");
        }
        String result;
        try {
            if (!body.containsKey("progress")) {
                result = accounts.isLinked(uuid) ? "LINKED" : "NOT_LINKED";
            } else {
                if (!(body.get("name") instanceof String name) || !LinkConfirmHandler.PLAYER_NAME.matcher(name).matches()) {
                    throw new MalformedRequestException("« name » invalide.");
                }
                result = accounts.recordProgress(uuid, LinkedProgress.fromWire(body.get("progress"))).name();
            }
        } catch (SQLException e) {
            throw new IOException("Base des comptes indisponible", e);
        }
        HttpResponses.sendJson(exchange, 200, Map.of("result", result));
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
}
