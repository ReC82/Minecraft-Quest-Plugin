package com.lodygames.rpgquest.webapi.privacy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Liste blanche de ce que le portail renvoie sur un joueur (issue #55).
 *
 * <p>L'instantané est produit par le plugin, qui n'exporte déjà que des données publiques. Ce filtre
 * est la seconde barrière : si un jour l'export contenait une clé de trop (UUID, adresse IP,
 * position, âge…), elle s'arrêterait ici. Seules les clés nommées ci-dessous, avec un type attendu,
 * sortent ; tout le reste est ignoré sans erreur.</p>
 */
public final class PublicPlayerData {

    private PublicPlayerData() {
    }

    /** Profils : {@code name}, {@code skills} (piste → nombre), {@code questsCompleted}. */
    public static List<Map<String, Object>> profiles(Object raw) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list(raw)) {
            if (item instanceof Map<?, ?> profile && profile.get("name") instanceof String name) {
                Map<String, Object> safe = new LinkedHashMap<>();
                safe.put("name", name);
                if (profile.get("skills") instanceof Map<?, ?> skills) {
                    safe.put("skills", numbersByKey(skills));
                }
                if (profile.get("questsCompleted") instanceof Number count) {
                    safe.put("questsCompleted", count);
                }
                result.add(safe);
            }
        }
        return result;
    }

    /** Lignes de classement : {@code name} et la valeur {@code valueKey} uniquement. */
    public static List<Map<String, Object>> rankingRows(Object raw, String valueKey) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list(raw)) {
            if (item instanceof Map<?, ?> row && row.get("name") instanceof String name
                    && row.get(valueKey) instanceof Number value) {
                Map<String, Object> safe = new LinkedHashMap<>();
                safe.put("name", name);
                safe.put(valueKey, value);
                result.add(safe);
            }
        }
        return result;
    }

    /** Statistiques par portée : {@code scopes}, {@code lifetime} (métrique → lignes), {@code season}, {@code events}. */
    public static Map<String, Object> statistics(Object raw) {
        Map<String, Object> safe = new LinkedHashMap<>();
        if (!(raw instanceof Map<?, ?> statistics)) {
            return safe;
        }
        List<String> scopes = new ArrayList<>();
        for (Object scope : list(statistics.get("scopes"))) {
            if (scope instanceof String text) {
                scopes.add(text);
            }
        }
        safe.put("scopes", scopes);
        Map<String, Object> lifetime = new LinkedHashMap<>();
        if (statistics.get("lifetime") instanceof Map<?, ?> metrics) {
            for (Map.Entry<?, ?> metric : metrics.entrySet()) {
                lifetime.put(String.valueOf(metric.getKey()), rankingRows(metric.getValue(), "value"));
            }
        }
        safe.put("lifetime", lifetime);
        // Saisons et événements ne sont pas encore produits (#54/#57) : rien n'est relayé tant
        // que leur format n'est pas fixé et filtré ici.
        safe.put("season", null);
        safe.put("events", List.of());
        return safe;
    }

    /** Pseudos des connectés : seulement des chaînes. */
    public static List<String> names(Object raw) {
        List<String> result = new ArrayList<>();
        for (Object item : list(raw)) {
            if (item instanceof String name) {
                result.add(name);
            }
        }
        return result;
    }

    private static Map<String, Object> numbersByKey(Map<?, ?> raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (entry.getValue() instanceof Number number) {
                result.put(String.valueOf(entry.getKey()), number);
            }
        }
        return result;
    }

    private static List<?> list(Object raw) {
        return raw instanceof List<?> list ? list : List.of();
    }
}
