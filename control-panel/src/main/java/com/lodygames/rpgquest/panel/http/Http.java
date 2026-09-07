package com.lodygames.rpgquest.panel.http;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Petites aides autour de {@code com.sun.net.httpserver} : cookies, formulaires, réponses. */
public final class Http {

    private Http() {
    }

    // ---- Lecture requête ----------------------------------------------------------------

    public static Map<String, String> cookies(HttpExchange exchange) {
        Map<String, String> map = new HashMap<>();
        List<String> headers = exchange.getRequestHeaders().get("Cookie");
        if (headers == null) {
            return map;
        }
        for (String header : headers) {
            for (String pair : header.split(";")) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    map.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
                }
            }
        }
        return map;
    }

    /** Lit et parse un corps {@code application/x-www-form-urlencoded} (limite 64 Kio). */
    public static Map<String, String> formBody(HttpExchange exchange) throws IOException {
        byte[] bytes = exchange.getRequestBody().readNBytes(64 * 1024);
        return parseUrlEncoded(new String(bytes, StandardCharsets.UTF_8));
    }

    public static Map<String, String> query(HttpExchange exchange) {
        String raw = exchange.getRequestURI().getRawQuery();
        return raw == null ? new HashMap<>() : parseUrlEncoded(raw);
    }

    private static Map<String, String> parseUrlEncoded(String body) {
        Map<String, String> map = new HashMap<>();
        if (body == null || body.isBlank()) {
            return map;
        }
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            String key = eq >= 0 ? pair.substring(0, eq) : pair;
            String value = eq >= 0 ? pair.substring(eq + 1) : "";
            map.put(URLDecoder.decode(key, StandardCharsets.UTF_8), URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return map;
    }

    // ---- Écriture réponse --------------------------------------------------------------

    public static void securityHeaders(HttpExchange exchange) {
        var h = exchange.getResponseHeaders();
        h.set("X-Content-Type-Options", "nosniff");
        h.set("X-Frame-Options", "DENY");
        h.set("Referrer-Policy", "same-origin");
        h.set("Content-Security-Policy",
                "default-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; form-action 'self'; frame-ancestors 'none'");
        h.set("Cache-Control", "no-store");
    }

    public static void html(HttpExchange exchange, int status, String body) throws IOException {
        securityHeaders(exchange);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    public static void json(HttpExchange exchange, int status, String body) throws IOException {
        securityHeaders(exchange);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    public static void text(HttpExchange exchange, int status, String body) throws IOException {
        securityHeaders(exchange);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    public static void redirect(HttpExchange exchange, String location) throws IOException {
        securityHeaders(exchange);
        exchange.getResponseHeaders().set("Location", location);
        exchange.sendResponseHeaders(303, -1);
        exchange.close();
    }

    public static void setCookie(HttpExchange exchange, String name, String value, boolean secure,
                                 String sameSite, int maxAgeSeconds) {
        StringBuilder sb = new StringBuilder();
        sb.append(name).append('=').append(value).append("; Path=/; HttpOnly; SameSite=").append(sameSite);
        if (secure) {
            sb.append("; Secure");
        }
        if (maxAgeSeconds >= 0) {
            sb.append("; Max-Age=").append(maxAgeSeconds);
        }
        exchange.getResponseHeaders().add("Set-Cookie", sb.toString());
    }

    public static void clearCookie(HttpExchange exchange, String name, boolean secure) {
        setCookie(exchange, name, "", secure, "Lax", 0);
    }

    /** Échappement HTML pour tout texte issu de données (bridge, formulaire, config). */
    public static String esc(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
