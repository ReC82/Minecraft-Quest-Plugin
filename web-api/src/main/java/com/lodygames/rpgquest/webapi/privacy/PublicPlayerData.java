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

    /**
     * Statistiques par portée (#54) : {@code scopes}, {@code metrics} (contrat des métriques),
     * {@code lifetime} (métrique → lignes), {@code season} (une période ou {@code null}) et
     * {@code events} (périodes). Une ligne ne garde que {@code name}, {@code value} et {@code rank}.
     */
    public static Map<String, Object> statistics(Object raw) {
        Map<String, Object> safe = new LinkedHashMap<>();
        if (!(raw instanceof Map<?, ?> statistics)) {
            return safe;
        }
        safe.put("scopes", strings(statistics.get("scopes")));
        List<Map<String, Object>> metrics = new ArrayList<>();
        for (Object item : list(statistics.get("metrics"))) {
            if (item instanceof Map<?, ?> metric && metric.get("id") instanceof String id) {
                Map<String, Object> descriptor = new LinkedHashMap<>();
                descriptor.put("id", id);
                copyString(metric, descriptor, "aggregation");
                copyString(metric, descriptor, "order");
                descriptor.put("scopes", strings(metric.get("scopes")));
                metrics.add(descriptor);
            }
        }
        safe.put("metrics", metrics);
        safe.put("lifetime", boards(statistics.get("lifetime")));
        safe.put("season", period(statistics.get("season")));
        List<Map<String, Object>> events = new ArrayList<>();
        for (Object item : list(statistics.get("events"))) {
            Map<String, Object> event = period(item);
            if (event != null) {
                events.add(event);
            }
        }
        safe.put("events", events);
        List<Map<String, Object>> boards = new ArrayList<>();
        for (Object item : list(statistics.get("boards"))) {
            Map<String, Object> board = board(item);
            if (board != null) {
                boards.add(board);
            }
        }
        safe.put("boards", boards);
        safe.put("catalog", catalog(statistics.get("catalog")));
        return safe;
    }

    /**
     * Un classement du catalogue (#734) : identité, description (#735), thème, période, filtre écrit, fenêtres et lignes
     * ({@code name}, {@code value}, {@code rank}), rien d'autre.
     */
    private static Map<String, Object> board(Object raw) {
        if (!(raw instanceof Map<?, ?> board) || !(board.get("id") instanceof String id)
                || !(board.get("name") instanceof String)) {
            return null;
        }
        Map<String, Object> safe = new LinkedHashMap<>();
        safe.put("id", id);
        for (String key : List.of("name", "description", "category", "icon", "metric", "period", "status",
                "trackedSince")) {
            copyString(board, safe, key);
        }
        if (board.get("filter") instanceof Map<?, ?> filter) {
            Map<String, Object> safeFilter = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : filter.entrySet()) {
                safeFilter.put(String.valueOf(entry.getKey()), strings(entry.getValue()));
            }
            safe.put("filter", safeFilter);
        }
        safe.put("featured", Boolean.TRUE.equals(board.get("featured")));
        if (board.get("size") instanceof Number size) {
            safe.put("size", size.intValue());
        }
        Map<String, Object> window = window(board.get("window"));
        if (window != null) {
            safe.put("window", window);
        }
        safe.put("leaderboard", statRows(board.get("leaderboard")));
        Map<String, Object> previous = window(board.get("previous"));
        if (previous != null && board.get("previous") instanceof Map<?, ?> rawPrevious) {
            previous.put("leaderboard", statRows(rawPrevious.get("leaderboard")));
            safe.put("previous", previous);
        }
        return safe;
    }

    private static Map<String, Object> window(Object raw) {
        if (!(raw instanceof Map<?, ?> window) || !(window.get("key") instanceof String)) {
            return null;
        }
        Map<String, Object> safe = new LinkedHashMap<>();
        for (String key : List.of("key", "start", "end")) {
            copyString(window, safe, key);
        }
        return safe;
    }

    /** Catalogue des mesures (#734) : métriques suivies et depuis quand, familles, mesures non suivies. */
    private static Map<String, Object> catalog(Object raw) {
        Map<String, Object> safe = new LinkedHashMap<>();
        if (!(raw instanceof Map<?, ?> catalog)) {
            return safe;
        }
        copyString(catalog, safe, "timezone");
        List<Map<String, Object>> metrics = new ArrayList<>();
        for (Object item : list(catalog.get("metrics"))) {
            if (item instanceof Map<?, ?> metric && metric.get("id") instanceof String id) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("id", id);
                entry.put("dated", Boolean.TRUE.equals(metric.get("dated")));
                copyString(metric, entry, "trackedSince");
                metrics.add(entry);
            }
        }
        safe.put("metrics", metrics);
        Map<String, Object> families = new LinkedHashMap<>();
        if (catalog.get("families") instanceof Map<?, ?> byKind) {
            for (Map.Entry<?, ?> kind : byKind.entrySet()) {
                List<Map<String, Object>> list = new ArrayList<>();
                for (Object item : list(kind.getValue())) {
                    if (item instanceof Map<?, ?> family && family.get("id") instanceof String id
                            && family.get("label") instanceof String label) {
                        list.add(Map.of("id", id, "label", label));
                    }
                }
                families.put(String.valueOf(kind.getKey()), list);
            }
        }
        safe.put("families", families);
        List<Map<String, Object>> planned = new ArrayList<>();
        for (Object item : list(catalog.get("planned"))) {
            if (item instanceof Map<?, ?> entry && entry.get("id") instanceof String id
                    && entry.get("reason") instanceof String reason) {
                planned.add(Map.of("id", id, "reason", reason));
            }
        }
        safe.put("planned", planned);
        return safe;
    }

    /**
     * Une saison ou un événement : identité, fenêtre, statut, règles publiques (#57 : classement,
     * filtre, objectif commun, libellés de récompense) et classements, rien d'autre.
     */
    private static Map<String, Object> period(Object raw) {
        if (!(raw instanceof Map<?, ?> period) || !(period.get("id") instanceof String id)) {
            return null;
        }
        Map<String, Object> safe = new LinkedHashMap<>();
        safe.put("id", id);
        for (String key : List.of("name", "start", "end", "status", "ranking")) {
            copyString(period, safe, key);
        }
        if (period.get("filter") instanceof Map<?, ?> filter) {
            Map<String, Object> safeFilter = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : filter.entrySet()) {
                safeFilter.put(String.valueOf(entry.getKey()), strings(entry.getValue()));
            }
            safe.put("filter", safeFilter);
        }
        if (period.get("community") instanceof Map<?, ?> community && community.get("goal") instanceof Number goal
                && community.get("total") instanceof Number total) {
            safe.put("community", Map.of("goal", goal.longValue(), "total", total.longValue(),
                    "reached", total.longValue() >= goal.longValue()));
        }
        List<Map<String, Object>> rewards = new ArrayList<>();
        for (Object item : list(period.get("rewards"))) {
            if (item instanceof Map<?, ?> reward && reward.get("kind") instanceof String kind
                    && reward.get("label") instanceof String label) {
                Map<String, Object> safeReward = new LinkedHashMap<>();
                safeReward.put("kind", kind);
                if (reward.get("value") instanceof Number value) {
                    safeReward.put("value", value.longValue());
                }
                safeReward.put("label", label);
                rewards.add(safeReward);
            }
        }
        if (!rewards.isEmpty()) {
            safe.put("rewards", rewards);
        }
        safe.put("leaderboards", boards(period.get("leaderboards")));
        return safe;
    }

    private static Map<String, Object> boards(Object raw) {
        Map<String, Object> boards = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> metrics) {
            for (Map.Entry<?, ?> metric : metrics.entrySet()) {
                boards.put(String.valueOf(metric.getKey()), statRows(metric.getValue()));
            }
        }
        return boards;
    }

    private static List<Map<String, Object>> statRows(Object raw) {
        List<Map<String, Object>> rows = rankingRows(raw, "value");
        List<?> source = list(raw).stream()
                .filter(item -> item instanceof Map<?, ?> row && row.get("name") instanceof String
                        && row.get("value") instanceof Number)
                .toList();
        for (int i = 0; i < rows.size(); i++) {
            if (source.get(i) instanceof Map<?, ?> row && row.get("rank") instanceof Number rank) {
                rows.get(i).put("rank", rank);
            }
        }
        return rows;
    }

    private static void copyString(Map<?, ?> from, Map<String, Object> to, String key) {
        if (from.get(key) instanceof String value) {
            to.put(key, value);
        }
    }

    private static List<String> strings(Object raw) {
        List<String> result = new ArrayList<>();
        for (Object item : list(raw)) {
            if (item instanceof String text) {
                result.add(text);
            }
        }
        return result;
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
