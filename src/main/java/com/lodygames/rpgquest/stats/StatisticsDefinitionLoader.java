package com.lodygames.rpgquest.stats;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Lit et valide {@code statistics.yml} (issue #54). Un fichier invalide n'est jamais appliqué à
 * moitié : la moindre erreur rend {@link StatisticsDefinition#EMPTY} et la liste des erreurs, le
 * plugin démarre normalement et aucun classement de période n'est publié.
 *
 * <p>Les dates doivent porter un fuseau explicite ({@code Z} ou {@code +02:00}) et tomber sur une
 * seconde entière : une saison ne commence jamais « à l'heure du serveur », toujours à un instant
 * précis.</p>
 */
public final class StatisticsDefinitionLoader {

    private static final Pattern PERIOD_ID = Pattern.compile("[a-z0-9_]{1,64}");
    /** Valeur de filtre {@code entity}/{@code item} : un nom d'énumération Paper ({@code ZOMBIE}). */
    private static final Pattern CONSTANT = Pattern.compile("[A-Z0-9_]{1,64}");
    /** Valeur de filtre {@code special-mob} : l'id namespacé d'un mob spécial ({@code rpgquest:forest_brute}). */
    private static final Pattern NAMESPACED = Pattern.compile("[a-z0-9_.-]{1,64}:[a-z0-9_./-]{1,128}");
    private static final Pattern ENTITLEMENT = Pattern.compile("[a-z0-9_.-]{1,64}");
    private static final int LABEL_MAX = 120;
    /** Clés réservées aux événements (issue #57) : une saison classe toujours par plus grand total. */
    private static final List<String> EVENT_ONLY_KEYS = List.of("ranking", "filter", "goal", "rewards");

    private StatisticsDefinitionLoader() {
    }

    public record Result(StatisticsDefinition definition, List<String> errors) {
        public boolean valid() {
            return errors.isEmpty();
        }
    }

    public static Result load(ConfigurationSection root) {
        List<String> errors = new ArrayList<>();
        List<StatPeriod> periods = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        readPeriods(root, "seasons", StatScope.SEASON, periods, ids, errors);
        readPeriods(root, "events", StatScope.EVENT, periods, ids, errors);
        checkSeasonsDoNotOverlap(periods, errors);

        Set<String> reasons = root.contains("excluded-xp-reasons")
                ? new LinkedHashSet<>(root.getStringList("excluded-xp-reasons"))
                : StatisticsDefinition.DEFAULT_EXCLUDED_XP_REASONS;
        Map<UUID, Set<String>> exclusions = readExclusions(root, ids, errors);

        if (!errors.isEmpty()) {
            return new Result(StatisticsDefinition.EMPTY, List.copyOf(errors));
        }
        return new Result(new StatisticsDefinition(periods, exclusions, reasons), List.of());
    }

    private static void readPeriods(ConfigurationSection root, String key, StatScope scope,
                                    List<StatPeriod> periods, Set<String> ids, List<String> errors) {
        List<Map<?, ?>> entries = root.getMapList(key);
        for (int i = 0; i < entries.size(); i++) {
            Map<?, ?> entry = entries.get(i);
            String where = key + "[" + i + "]";
            String id = text(entry.get("id"));
            if (id == null || !PERIOD_ID.matcher(id).matches()) {
                errors.add(where + " : « id » obligatoire, en minuscules, chiffres et _ (64 caractères au plus).");
                continue;
            }
            where = key + " « " + id + " »";
            if (!ids.add(id)) {
                errors.add(where + " : identifiant déjà utilisé par une autre saison ou un autre événement.");
                continue;
            }
            Instant start = instant(entry.get("start"), where + ".start", errors);
            Instant end = instant(entry.get("end"), where + ".end", errors);
            List<String> metrics = metrics(entry.get("metrics"), scope, where, errors);
            if (start == null || end == null || metrics == null) {
                continue;
            }
            if (!start.isBefore(end)) {
                errors.add(where + " : « start » doit précéder « end ».");
                continue;
            }
            StatPeriod.State state = state(entry.get("status"), where, errors);
            EventRules rules = scope == StatScope.EVENT ? rules(entry, metrics, where, errors)
                    : seasonRules(entry, where, errors);
            if (state == null || rules == null) {
                continue;
            }
            String name = text(entry.get("name"));
            periods.add(new StatPeriod(id, scope, name == null ? id : name, start, end, metrics, state, rules));
        }
    }

    private static StatPeriod.State state(Object raw, String where, List<String> errors) {
        String text = text(raw);
        if (text == null) {
            return StatPeriod.State.PLANNED;
        }
        try {
            return StatPeriod.State.valueOf(text.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            errors.add(where + " : « status » vaut planned, draft ou cancelled (pas « " + text + " »).");
            return null;
        }
    }

    private static EventRules seasonRules(Map<?, ?> entry, String where, List<String> errors) {
        for (String key : EVENT_ONLY_KEYS) {
            if (entry.containsKey(key)) {
                errors.add(where + " : « " + key + " » est réservé aux événements (une saison classe par plus grand total).");
                return null;
            }
        }
        return EventRules.DEFAULT;
    }

    /** Règles d'un événement (issue #57) ; {@code null} et une erreur au moindre défaut. */
    private static EventRules rules(Map<?, ?> entry, List<String> metrics, String where, List<String> errors) {
        int before = errors.size();
        EventRanking ranking = EventRanking.HIGHEST_TOTAL;
        String rawRanking = text(entry.get("ranking"));
        if (rawRanking != null) {
            try {
                ranking = EventRanking.valueOf(rawRanking.toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                errors.add(where + " : « ranking » inconnu « " + rawRanking
                        + " » (HIGHEST_TOTAL, LOWEST_TOTAL, BEST_SINGLE ou COMMUNITY_GOAL).");
                return null;
            }
        }
        if (ranking == EventRanking.LOWEST_TOTAL) {
            for (String metricId : metrics) {
                if (StatMetric.find(metricId).orElseThrow().order() != StatMetric.SortOrder.ASC) {
                    errors.add(where + " : LOWEST_TOTAL demande une métrique où la plus petite valeur gagne (un chrono) ; "
                            + "« " + metricId + " » n'en est pas une — un joueur absent gagnerait avec 0.");
                }
            }
        }
        long goal = 0L;
        if (entry.get("goal") != null) {
            goal = entry.get("goal") instanceof Number number ? number.longValue() : -1L;
            if (ranking != EventRanking.COMMUNITY_GOAL) {
                errors.add(where + " : « goal » n'a de sens qu'avec ranking: COMMUNITY_GOAL.");
            } else if (goal <= 0) {
                errors.add(where + " : « goal » doit être un nombre entier positif.");
            }
        } else if (ranking == EventRanking.COMMUNITY_GOAL) {
            errors.add(where + " : un objectif commun demande « goal » (le total à atteindre ensemble).");
        }
        if (ranking == EventRanking.COMMUNITY_GOAL && metrics.size() != 1) {
            errors.add(where + " : un objectif commun porte sur une seule métrique.");
        }
        Map<String, Set<String>> filter = filter(entry.get("filter"), metrics, where, errors);
        List<EventReward> rewards = rewards(entry.get("rewards"), ranking, where, errors);
        if (errors.size() != before) {
            return null;
        }
        return new EventRules(ranking, filter, goal, rewards);
    }

    private static Map<String, Set<String>> filter(Object raw, List<String> metrics, String where, List<String> errors) {
        Map<String, Set<String>> filter = new java.util.LinkedHashMap<>();
        if (raw == null) {
            return filter;
        }
        if (!(raw instanceof Map<?, ?> map) || map.isEmpty()) {
            errors.add(where + " : « filter » doit associer une clé (entity, special-mob, item) à des valeurs.");
            return filter;
        }
        for (Map.Entry<?, ?> item : map.entrySet()) {
            String key = String.valueOf(item.getKey());
            for (String metricId : metrics) {
                if (!StatMetric.find(metricId).orElseThrow().filters().containsKey(key)) {
                    errors.add(where + " : la métrique « " + metricId + " » ne se filtre pas par « " + key
                            + " » (un filtre ignoré fausserait le classement).");
                }
            }
            List<?> values = item.getValue() instanceof List<?> list ? list : List.of(String.valueOf(item.getValue()));
            Set<String> accepted = new LinkedHashSet<>();
            Pattern format = key.equals("special-mob") ? NAMESPACED : CONSTANT;
            for (Object value : values) {
                String text = String.valueOf(value).trim();
                if (format == CONSTANT) {
                    text = text.toUpperCase(java.util.Locale.ROOT);
                }
                if (!format.matcher(text).matches()) {
                    errors.add(where + " : valeur de filtre « " + value + " » invalide pour « " + key + " »"
                            + (format == CONSTANT ? " (nom Paper, ex. ZOMBIE ou COD)." : " (id namespacé, ex. rpgquest:forest_brute)."));
                    continue;
                }
                accepted.add(text);
            }
            if (values.isEmpty()) {
                errors.add(where + " : le filtre « " + key + " » ne liste aucune valeur.");
            }
            filter.put(key, Set.copyOf(accepted));
        }
        return filter;
    }

    private static List<EventReward> rewards(Object raw, EventRanking ranking, String where, List<String> errors) {
        List<EventReward> rewards = new ArrayList<>();
        if (raw == null) {
            return rewards;
        }
        if (!(raw instanceof List<?> list)) {
            errors.add(where + " : « rewards » doit être une liste.");
            return rewards;
        }
        for (int i = 0; i < list.size(); i++) {
            String at = where + ".rewards[" + i + "]";
            if (!(list.get(i) instanceof Map<?, ?> reward)) {
                errors.add(at + " : chaque récompense est un bloc (rank, threshold ou community).");
                continue;
            }
            List<EventReward.Kind> kinds = new ArrayList<>();
            if (reward.containsKey("rank")) {
                kinds.add(EventReward.Kind.RANK);
            }
            if (reward.containsKey("threshold")) {
                kinds.add(EventReward.Kind.THRESHOLD);
            }
            if (Boolean.TRUE.equals(reward.get("community"))) {
                kinds.add(EventReward.Kind.COMMUNITY);
            }
            if (kinds.size() != 1) {
                errors.add(at + " : préciser exactement un critère — rank (top N), threshold (valeur minimale) ou community: true.");
                continue;
            }
            EventReward.Kind kind = kinds.get(0);
            long value = 0L;
            if (kind != EventReward.Kind.COMMUNITY) {
                Object number = reward.get(kind == EventReward.Kind.RANK ? "rank" : "threshold");
                value = number instanceof Number n ? n.longValue() : 0L;
                if (value <= 0) {
                    errors.add(at + " : « " + kind.name().toLowerCase(java.util.Locale.ROOT) + " » doit être un entier positif.");
                    continue;
                }
            } else if (ranking != EventRanking.COMMUNITY_GOAL) {
                errors.add(at + " : community: true demande ranking: COMMUNITY_GOAL.");
                continue;
            }
            String entitlement = text(reward.get("entitlement"));
            if (entitlement == null || !ENTITLEMENT.matcher(entitlement).matches()) {
                errors.add(at + " : « entitlement » obligatoire (minuscules, chiffres, _ . -) : l'avantage à accorder plus tard.");
                continue;
            }
            String label = text(reward.get("label"));
            if (label == null || label.length() > LABEL_MAX) {
                errors.add(at + " : « label » obligatoire, " + LABEL_MAX + " caractères au plus (texte montré aux joueurs).");
                continue;
            }
            rewards.add(new EventReward(kind, value, entitlement, label));
        }
        return rewards;
    }

    private static List<String> metrics(Object raw, StatScope scope, String where, List<String> errors) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            errors.add(where + " : « metrics » doit lister au moins une métrique.");
            return null;
        }
        List<String> metrics = new ArrayList<>();
        for (Object item : list) {
            String id = String.valueOf(item);
            var metric = StatMetric.find(id);
            if (metric.isEmpty()) {
                errors.add(where + " : métrique inconnue « " + id + " ».");
                return null;
            }
            if (!metric.get().supports(scope)) {
                errors.add(where + " : la métrique « " + id + " » n'est pas calculable sur une "
                        + scope.wireName() + " (source sans historique horodaté).");
                return null;
            }
            if (!metrics.contains(id)) {
                metrics.add(id);
            }
        }
        return metrics;
    }

    private static Instant instant(Object raw, String where, List<String> errors) {
        Instant instant;
        if (raw instanceof Date date) {
            // SnakeYAML convertit lui-même une date ISO non entre guillemets.
            instant = date.toInstant();
        } else if (raw instanceof String text) {
            try {
                instant = OffsetDateTime.parse(text.trim()).toInstant();
            } catch (DateTimeParseException e) {
                errors.add(where + " : date invalide « " + text + " » — attendu 2026-11-01T00:00:00Z (fuseau obligatoire).");
                return null;
            }
        } else {
            errors.add(where + " : date obligatoire, au format 2026-11-01T00:00:00Z.");
            return null;
        }
        if (instant.getNano() != 0) {
            errors.add(where + " : la date doit tomber sur une seconde entière.");
            return null;
        }
        return instant;
    }

    private static Map<UUID, Set<String>> readExclusions(ConfigurationSection root, Set<String> periodIds,
                                                         List<String> errors) {
        Map<UUID, Set<String>> exclusions = new HashMap<>();
        List<Map<?, ?>> entries = root.getMapList("exclusions");
        for (int i = 0; i < entries.size(); i++) {
            Map<?, ?> entry = entries.get(i);
            String where = "exclusions[" + i + "]";
            UUID player;
            try {
                player = UUID.fromString(String.valueOf(entry.get("player")));
            } catch (IllegalArgumentException e) {
                errors.add(where + " : « player » doit être l'UUID du joueur (le pseudo peut changer).");
                continue;
            }
            Set<String> scopes = new HashSet<>();
            Object raw = entry.get("scopes");
            List<?> list = raw instanceof List<?> l ? l : List.of(StatisticsDefinition.ALL_SCOPES);
            for (Object item : list) {
                String scope = String.valueOf(item);
                if (!scope.equals(StatisticsDefinition.ALL_SCOPES) && !scope.equals(StatScope.LIFETIME.wireName())
                        && !periodIds.contains(scope)) {
                    errors.add(where + " : portée inconnue « " + scope + " » (attendu *, lifetime ou un id de période).");
                    continue;
                }
                scopes.add(scope);
            }
            exclusions.computeIfAbsent(player, k -> new HashSet<>()).addAll(scopes);
        }
        return exclusions;
    }

    private static void checkSeasonsDoNotOverlap(List<StatPeriod> periods, List<String> errors) {
        List<StatPeriod> seasons = periods.stream().filter(period -> period.scope() == StatScope.SEASON).toList();
        for (int i = 0; i < seasons.size(); i++) {
            for (int j = i + 1; j < seasons.size(); j++) {
                if (seasons.get(i).overlaps(seasons.get(j))) {
                    errors.add("Les saisons « " + seasons.get(i).id() + " » et « " + seasons.get(j).id()
                            + " » se chevauchent : une seule saison à la fois (les événements, eux, peuvent se chevaucher).");
                }
            }
        }
    }

    private static String text(Object raw) {
        return raw == null || String.valueOf(raw).isBlank() ? null : String.valueOf(raw).trim();
    }
}
