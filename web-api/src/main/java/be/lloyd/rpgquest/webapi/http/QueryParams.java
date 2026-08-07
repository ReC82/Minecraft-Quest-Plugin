package be.lloyd.rpgquest.webapi.http;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Analyse et valide les paramètres de requête ({@code ?clé=valeur&...}) ; jamais d'exception non traduite en 400. */
public final class QueryParams {

    private QueryParams() {
    }

    public static Map<String, String> parse(String rawQuery) {
        Map<String, String> result = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return result;
        }
        for (String pair : rawQuery.split("&")) {
            if (pair.isBlank()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String rawKey = eq >= 0 ? pair.substring(0, eq) : pair;
            String rawValue = eq >= 0 ? pair.substring(eq + 1) : "";
            try {
                result.put(URLDecoder.decode(rawKey, StandardCharsets.UTF_8), URLDecoder.decode(rawValue, StandardCharsets.UTF_8));
            } catch (IllegalArgumentException e) {
                throw new MalformedRequestException("Paramètre de requête invalide : \"" + pair + "\".");
            }
        }
        return result;
    }

    public static int parseIntParam(Map<String, String> params, String key, int defaultValue, int min, int max) {
        String raw = params.get(key);
        if (raw == null) {
            return defaultValue;
        }
        int value;
        try {
            value = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new MalformedRequestException("« " + key + " » doit être un entier, valeur reçue : \"" + raw + "\".");
        }
        if (value < min || value > max) {
            throw new MalformedRequestException(
                    "« " + key + " » doit être compris entre " + min + " et " + max + ", valeur reçue : " + value + ".");
        }
        return value;
    }
}
