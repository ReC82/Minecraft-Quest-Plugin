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
            String name = text(entry.get("name"));
            periods.add(new StatPeriod(id, scope, name == null ? id : name, start, end, metrics));
        }
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
