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
import org.bukkit.configuration.file.YamlConfiguration;

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

    public static final Pattern PERIOD_ID = Pattern.compile("[a-z0-9_]{1,64}");
    /** Valeur de filtre {@code entity}/{@code item} : un nom d'énumération Paper ({@code ZOMBIE}). */
    private static final Pattern CONSTANT = Pattern.compile("[A-Z0-9_]{1,64}");
    /** Valeur de filtre {@code special-mob} : l'id namespacé d'un mob spécial ({@code rpgquest:forest_brute}). */
    private static final Pattern NAMESPACED = Pattern.compile("[a-z0-9_.-]{1,64}:[a-z0-9_./-]{1,128}");
    /** Valeur de filtre {@code entity-family}/{@code block-family} (#734) : l'id d'une famille ({@code zombies}). */
    private static final Pattern FAMILY = Pattern.compile("[a-z0-9_-]{1,64}");
    private static final Pattern ENTITLEMENT = Pattern.compile("[a-z0-9_.-]{1,64}");
    public static final int LABEL_MAX = 120;
    public static final Pattern ICON = Pattern.compile("[a-z0-9-]{1,32}");
    private static final String BUNDLED_BOARDS = "/stats/leaderboards.yml";
    private static final int DEFAULT_BOARD_SIZE = 10;
    public static final int MAX_BOARD_SIZE = 50;
    /** Longueur maximale d'une description publique (#735). */
    public static final int DESCRIPTION_MAX = 200;
    /** Plus grand ordre d'affichage accepté (#735). */
    public static final int MAX_ORDER = 100_000;
    /** Clés réservées aux événements (issue #57) : une saison classe toujours par plus grand total. */
    private static final List<String> EVENT_ONLY_KEYS = List.of("ranking", "filter", "goal", "rewards");

    private StatisticsDefinitionLoader() {
    }

    /**
     * D'où vient la liste des classements (#735) : {@code statistics.yml} ({@code leaderboards:},
     * historique, prioritaire), le fichier géré par PlugAdmin ({@code leaderboards.yml}) ou le
     * catalogue par défaut du JAR.
     */
    public enum BoardsSource {
        STATISTICS_FILE, MANAGED_FILE, BUNDLED;

        public String wireName() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    public record Result(StatisticsDefinition definition, List<String> errors, BoardsSource boardsSource) {
        public Result(StatisticsDefinition definition, List<String> errors) {
            this(definition, errors, BoardsSource.BUNDLED);
        }

        public boolean valid() {
            return errors.isEmpty();
        }
    }

    public static Result load(ConfigurationSection root) {
        return load(root, null);
    }

    /**
     * @param managed fichier des classements géré par PlugAdmin ({@code leaderboards.yml}, #735), ou
     *                {@code null} s'il n'existe pas. Ignoré si {@code statistics.yml} a sa propre liste.
     */
    public static Result load(ConfigurationSection root, ConfigurationSection managed) {
        List<String> errors = new ArrayList<>();
        List<StatPeriod> periods = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        readPeriods(root, "seasons", StatScope.SEASON, periods, ids, errors);
        readPeriods(root, "events", StatScope.EVENT, periods, ids, errors);
        checkSeasonsDoNotOverlap(periods, errors);

        BoardsSource source = root.contains("leaderboards") ? BoardsSource.STATISTICS_FILE
                : managed != null ? BoardsSource.MANAGED_FILE : BoardsSource.BUNDLED;
        java.time.ZoneId zone = source == BoardsSource.MANAGED_FILE && managed.contains("timezone")
                ? zone(managed, errors) : zone(root, errors);
        Set<String> events = new HashSet<>();
        periods.stream().filter(period -> period.scope() == StatScope.EVENT).forEach(period -> events.add(period.id()));
        List<StatBoard> boards = switch (source) {
            case STATISTICS_FILE -> readBoards(root.getMapList("leaderboards"), "leaderboards", ids, events, errors);
            case MANAGED_FILE -> readBoards(managed.getMapList("leaderboards"), "leaderboards.yml", ids, events, errors);
            case BUNDLED -> readBoards(bundledBoards(), "catalogue par défaut", ids, events, errors);
        };

        Set<String> reasons = root.contains("excluded-xp-reasons")
                ? new LinkedHashSet<>(root.getStringList("excluded-xp-reasons"))
                : StatisticsDefinition.DEFAULT_EXCLUDED_XP_REASONS;
        Map<UUID, Set<String>> exclusions = readExclusions(root, ids, errors);

        if (!errors.isEmpty()) {
            return new Result(StatisticsDefinition.EMPTY, List.copyOf(errors), source);
        }
        return new Result(new StatisticsDefinition(periods, exclusions, reasons, boards, zone), List.of(), source);
    }

    private static java.time.ZoneId zone(ConfigurationSection root, List<String> errors) {
        String text = text(root.get("timezone"));
        if (text == null) {
            return StatisticsDefinition.DEFAULT_ZONE;
        }
        try {
            return java.time.ZoneId.of(text);
        } catch (java.time.DateTimeException e) {
            errors.add("« timezone » inconnu « " + text + " » (ex. Europe/Brussels).");
            return StatisticsDefinition.DEFAULT_ZONE;
        }
    }

    /** Catalogue livré dans le JAR ({@code stats/leaderboards.yml}), utilisé quand le fichier n'a pas de « leaderboards ». */
    public static List<Map<?, ?>> bundledBoards() {
        try (var in = StatisticsDefinitionLoader.class.getResourceAsStream(BUNDLED_BOARDS)) {
            if (in == null) {
                return List.of();
            }
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.loadFromString(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            return yaml.getMapList("leaderboards");
        } catch (java.io.IOException | org.bukkit.configuration.InvalidConfigurationException e) {
            throw new IllegalStateException(BUNDLED_BOARDS + " illisible", e);
        }
    }

    /**
     * Classements du catalogue (#734). Une métrique sans journal horodaté (quêtes terminées
     * « depuis toujours ») n'accepte que {@code period: lifetime} et aucun filtre.
     */
    private static List<StatBoard> readBoards(List<Map<?, ?>> entries, String key, Set<String> ids, Set<String> events,
                                              List<String> errors) {
        List<StatBoard> boards = new ArrayList<>();
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
                errors.add(where + " : identifiant déjà utilisé par un autre classement, une saison ou un événement.");
                continue;
            }
            String name = text(entry.get("name"));
            if (name == null || name.length() > LABEL_MAX) {
                errors.add(where + " : « name » obligatoire, " + LABEL_MAX + " caractères au plus (titre public).");
                continue;
            }
            String metricId = text(entry.get("metric"));
            var metric = metricId == null ? java.util.Optional.<StatMetric>empty() : StatMetric.find(metricId);
            if (metric.isEmpty()) {
                errors.add(where + " : « metric » inconnue « " + metricId + " ».");
                continue;
            }
            String periodText = text(entry.get("period"));
            var period = periodText == null ? java.util.Optional.of(BoardPeriod.LIFETIME)
                    : BoardPeriod.parse(periodText.toLowerCase(java.util.Locale.ROOT));
            if (period.isEmpty()) {
                errors.add(where + " : « period » vaut lifetime, daily, weekly, monthly, season, event ou custom.");
                continue;
            }
            if (!metric.get().windowed() && (period.get() != BoardPeriod.LIFETIME || entry.get("filter") != null)) {
                errors.add(where + " : « " + metricId + " » n'a pas d'historique daté : seulement period: lifetime, sans filtre.");
                continue;
            }
            Instant start = null;
            Instant end = null;
            if (period.get() == BoardPeriod.CUSTOM) {
                start = instant(entry.get("start"), where + ".start", errors);
                end = instant(entry.get("end"), where + ".end", errors);
                if (start == null || end == null) {
                    continue;
                }
                if (!start.isBefore(end)) {
                    errors.add(where + " : « start » doit précéder « end ».");
                    continue;
                }
            } else if (entry.containsKey("start") || entry.containsKey("end")) {
                errors.add(where + " : « start »/« end » ne s'emploient qu'avec period: custom.");
                continue;
            }
            String event = text(entry.get("event"));
            if (period.get() == BoardPeriod.EVENT) {
                if (event == null || !events.contains(event)) {
                    errors.add(where + " : period: event demande « event », l'id d'un événement de statistics.yml"
                            + (event == null ? "." : " (« " + event + " » inconnu)."));
                    continue;
                }
            } else if (event != null) {
                errors.add(where + " : « event » ne s'emploie qu'avec period: event.");
                continue;
            }
            BoardStatusFields fields = statusFields(entry, i, where, errors);
            if (fields == null) {
                continue;
            }
            String category = text(entry.get("category"));
            if (category != null && !StatBoard.CATEGORIES.contains(category)) {
                errors.add(where + " : « category » vaut " + String.join(", ", StatBoard.CATEGORIES) + ".");
                continue;
            }
            String icon = text(entry.get("icon"));
            if (icon != null && !ICON.matcher(icon).matches()) {
                errors.add(where + " : « icon » en minuscules, chiffres et - (32 caractères au plus).");
                continue;
            }
            int size = entry.get("size") instanceof Number number ? number.intValue() : DEFAULT_BOARD_SIZE;
            if (size < 1 || size > MAX_BOARD_SIZE) {
                errors.add(where + " : « size » entre 1 et " + MAX_BOARD_SIZE + ".");
                continue;
            }
            String split = text(entry.get("split"));
            if (split != null && (!StatBoard.SPLIT_ENTITY.equals(split) || !StatMetric.ENTITY_KILL.equals(metricId))) {
                errors.add(where + " : « split » vaut « " + StatBoard.SPLIT_ENTITY + " », et seulement avec metric: "
                        + StatMetric.ENTITY_KILL + " (un classement par type de créature).");
                continue;
            }
            int before = errors.size();
            Map<String, Set<String>> filter = filter(entry.get("filter"), List.of(metricId), where, errors);
            if (errors.size() != before) {
                continue;
            }
            boards.add(new StatBoard(id, name, category == null ? "autres" : category, icon, metricId, filter,
                    declared(entry.get("filter")), period.get(), start, end, size,
                    Boolean.TRUE.equals(entry.get("featured")), fields.description(), fields.status(), fields.order(),
                    fields.visible(), event, split));
        }
        // Ordre d'affichage (#735), puis ordre du fichier (tri stable).
        boards.sort(java.util.Comparator.comparingInt(StatBoard::order));
        return boards;
    }

    private record BoardStatusFields(String description, StatBoard.Status status, int order, boolean visible) {
    }

    /**
     * Champs administrés depuis PlugAdmin (#735). {@code enabled: false} (format #734) vaut
     * {@code status: paused} ; l'ordre par défaut suit la position dans le fichier.
     */
    private static BoardStatusFields statusFields(Map<?, ?> entry, int index, String where, List<String> errors) {
        String statusText = text(entry.get("status"));
        StatBoard.Status status = Boolean.FALSE.equals(entry.get("enabled")) ? StatBoard.Status.PAUSED
                : StatBoard.Status.ENABLED;
        if (statusText != null) {
            var parsed = StatBoard.Status.parse(statusText.toLowerCase(java.util.Locale.ROOT));
            if (parsed.isEmpty()) {
                errors.add(where + " : « status » vaut draft, enabled, paused ou archived.");
                return null;
            }
            status = parsed.get();
        }
        String description = text(entry.get("description"));
        if (description != null && (description.length() > DESCRIPTION_MAX
                || description.chars().anyMatch(Character::isISOControl))) {
            errors.add(where + " : « description » sur une ligne, " + DESCRIPTION_MAX + " caractères au plus.");
            return null;
        }
        Object rawOrder = entry.get("order");
        if (rawOrder != null && !(rawOrder instanceof Integer)) {
            errors.add(where + " : « order » est un nombre entier.");
            return null;
        }
        int order = rawOrder == null ? (index + 1) * 10 : (Integer) rawOrder;
        if (order < 0 || order > MAX_ORDER) {
            errors.add(where + " : « order » entre 0 et " + MAX_ORDER + ".");
            return null;
        }
        Object visible = entry.get("visible");
        if (visible != null && !(visible instanceof Boolean)) {
            errors.add(where + " : « visible » vaut true ou false.");
            return null;
        }
        return new BoardStatusFields(description, status, order, !Boolean.FALSE.equals(visible));
    }

    /** Filtre tel qu'écrit, valeurs normalisées et triées : ce que le portail affiche. */
    private static Map<String, List<String>> declared(Object raw) {
        Map<String, List<String>> declared = new java.util.TreeMap<>();
        if (raw instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> item : map.entrySet()) {
                List<?> values = item.getValue() instanceof List<?> list ? list : List.of(String.valueOf(item.getValue()));
                declared.put(String.valueOf(item.getKey()), values.stream().map(value -> String.valueOf(value).trim())
                        .sorted().toList());
            }
        }
        return declared;
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

    /**
     * Filtre de cibles d'un événement ou d'un classement. Une famille (#734, {@code entity-family:
     * [zombies]}) est remplacée ici par ses membres : la requête ne voit que des noms Paper.
     */
    static Map<String, Set<String>> filter(Object raw, List<String> metrics, String where, List<String> errors) {
        Map<String, Set<String>> filter = new java.util.LinkedHashMap<>();
        if (raw == null) {
            return filter;
        }
        if (!(raw instanceof Map<?, ?> map) || map.isEmpty()) {
            errors.add(where + " : « filter » doit associer une clé (entity, entity-family, special-mob, item, block, "
                    + "block-family, quest) à des valeurs.");
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
            var familyKind = StatDimensions.Kind.ofFilterKey(key);
            Pattern format = familyKind.isPresent() ? FAMILY
                    : key.equals("special-mob") || key.equals("quest") ? NAMESPACED : CONSTANT;
            for (Object value : values) {
                String text = String.valueOf(value).trim();
                if (format == CONSTANT) {
                    text = text.toUpperCase(java.util.Locale.ROOT);
                }
                if (!format.matcher(text).matches()) {
                    errors.add(where + " : valeur de filtre « " + value + " » invalide pour « " + key + " »"
                            + (format == CONSTANT ? " (nom Paper, ex. ZOMBIE ou COD)."
                            : format == FAMILY ? " (id de famille, ex. zombies ou ores)."
                            : " (id namespacé, ex. rpgquest:forest_brute)."));
                    continue;
                }
                if (familyKind.isPresent()) {
                    var family = StatDimensions.bundled().family(familyKind.get(), text);
                    if (family.isEmpty() || family.get().members().isEmpty()) {
                        errors.add(where + " : famille « " + text + " » inconnue pour « " + key + " ».");
                        continue;
                    }
                    accepted.addAll(family.get().members());
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
