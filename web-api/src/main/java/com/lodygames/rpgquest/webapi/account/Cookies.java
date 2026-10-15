package com.lodygames.rpgquest.webapi.account;

import com.sun.net.httpserver.HttpExchange;
import java.time.Duration;
import java.util.Optional;

/** Lecture et écriture des cookies du portail (issue #53) : toujours {@code HttpOnly}, {@code Path=/}. */
final class Cookies {

    static final String SESSION = "lq_session";
    /** Jeton anti-CSRF des formulaires anonymes (connexion, inscription), en double soumission. */
    static final String FORM = "lq_form";

    private Cookies() {
    }

    static Optional<String> read(HttpExchange exchange, String name) {
        for (String header : exchange.getRequestHeaders().getOrDefault("Cookie", java.util.List.of())) {
            for (String part : header.split(";")) {
                String trimmed = part.trim();
                int eq = trimmed.indexOf('=');
                if (eq > 0 && trimmed.substring(0, eq).equals(name)) {
                    String value = trimmed.substring(eq + 1);
                    return value.isEmpty() ? Optional.empty() : Optional.of(value);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * {@code SameSite=Lax} pour la session (un lien venu d'ailleurs arrive connecté, mais aucun
     * formulaire tiers ne la transporte), {@code Strict} pour le jeton de formulaire.
     */
    static void set(HttpExchange exchange, String name, String value, Duration maxAge, String sameSite, boolean secure) {
        String cookie = name + "=" + value + "; Path=/; Max-Age=" + maxAge.toSeconds() + "; HttpOnly; SameSite=" + sameSite
                + (secure ? "; Secure" : "");
        exchange.getResponseHeaders().add("Set-Cookie", cookie);
    }

    static void clear(HttpExchange exchange, String name, boolean secure) {
        set(exchange, name, "", Duration.ZERO, "Lax", secure);
    }
}
