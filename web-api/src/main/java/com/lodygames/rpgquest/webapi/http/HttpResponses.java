package com.lodygames.rpgquest.webapi.http;

import com.lodygames.rpgquest.webapi.json.Json;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Écrit une réponse JSON ou HTML complète (en-têtes + corps) sur un {@link HttpExchange}. Une
 * requête {@code HEAD} (sondes de supervision) reçoit les en-têtes sans corps.
 */
public final class HttpResponses {

    private HttpResponses() {
    }

    public static void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = Json.write(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        send(exchange, status, bytes);
    }

    public static void sendHtml(HttpExchange exchange, int status, String html) throws IOException {
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        send(exchange, status, bytes);
    }

    private static void send(HttpExchange exchange, int status, byte[] bytes) throws IOException {
        if ("HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
