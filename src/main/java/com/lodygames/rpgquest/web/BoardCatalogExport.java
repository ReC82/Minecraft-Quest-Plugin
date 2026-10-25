package com.lodygames.rpgquest.web;

import com.lodygames.rpgquest.database.QuestStatisticsRepository;
import com.lodygames.rpgquest.database.StatisticsRepository;
import com.lodygames.rpgquest.database.StatisticsRepository.Target;
import com.lodygames.rpgquest.player.PublicProfilePolicy;
import com.lodygames.rpgquest.stats.BoardPeriod;
import com.lodygames.rpgquest.stats.StatBoard;
import com.lodygames.rpgquest.stats.StatDimensions;
import com.lodygames.rpgquest.stats.StatMetric;
import com.lodygames.rpgquest.stats.StatPeriod;
import com.lodygames.rpgquest.stats.StatisticsDefinition;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Catalogue de classements de l'instantané web (issue #734) : {@code statistics.boards} (chaque
 * classement, sa fenêtre en cours, sa fenêtre précédente figée) et {@code statistics.catalog}
 * (métriques suivies et depuis quand, familles de cibles, mesures pas encore suivies).
 *
 * <p>Aucune valeur n'est inventée : un classement sans saison en cours est publié « sans saison »,
 * une métrique sans donnée a {@code trackedSince: null}. Comme tous les classements de l'instantané,
 * les lignes ne portent que pseudo, valeur et rang, et seulement pour les profils visibles (#55).</p>
 *
 * <p>Un classement {@code split: entity} (#734, priorité du 25/10) publie {@code targets} : un
 * classement par type de créature réellement tué depuis le début du suivi (mob spécial RPGQuest à
 * part, jamais confondu avec son type de base), chacun avec sa catégorie, son « suivi depuis » et ses
 * lignes. Un type jamais tué n'est pas listé : aucune liste de centaines de classements vides.</p>
 */
final class BoardCatalogExport {

    /** Un classement calculé : fenêtre en cours et précédente, avec leurs lignes. */
    record BoardResult(StatBoard board, String status, BoardPeriod.Window window,
                       List<StatisticsRepository.RankedRow> rows, BoardPeriod.Window previous,
                       List<StatisticsRepository.RankedRow> previousRows,
                       Map<Target, List<StatisticsRepository.RankedRow>> targets,
                       Map<Target, List<StatisticsRepository.RankedRow>> previousTargets) {

        BoardResult(StatBoard board, String status, BoardPeriod.Window window,
                    List<StatisticsRepository.RankedRow> rows, BoardPeriod.Window previous,
                    List<StatisticsRepository.RankedRow> previousRows) {
            this(board, status, window, rows, previous, previousRows, Map.of(), Map.of());
        }
    }

    /**
     * Tous les classements, la date de première donnée de chaque métrique horodatée et, pour les
     * classements par type (#734), celle de chaque créature rencontrée et le nom des mobs spéciaux.
     */
    record Catalog(List<BoardResult> boards, Map<String, Instant> trackedSince, ZoneId zone,
                   Map<Target, Instant> targetSince, Function<String, String> specialLabel) {
        static final Catalog EMPTY = new Catalog(List.of(), Map.of(), StatisticsDefinition.DEFAULT_ZONE);

        Catalog(List<BoardResult> boards, Map<String, Instant> trackedSince, ZoneId zone) {
            this(boards, trackedSince, zone, Map.of(), id -> id);
        }
    }

    /** Ordre d'affichage des catégories de créatures : boss, mobs spéciaux, hostiles, non hostiles. */
    private static final List<String> TARGET_CATEGORIES = List.of("boss", "special", "hostile", "passive");

    private BoardCatalogExport() {
    }

    /**
     * Calcule le catalogue hors du fil principal. {@code archived} dit si une fenêtre est déjà figée
     * (lecture de l'archive) ; sinon elle se lit en direct dans la source de sa métrique.
     */
    static CompletableFuture<Catalog> gather(StatisticsRepository repository, StatisticsDefinition stats, Instant now,
                                             Predicate<String> archived, PublicProfilePolicy.Visibility visibility,
                                             List<QuestStatisticsRepository.Row> lifetimeQuests) {
        return gather(repository, stats, now, archived, visibility, lifetimeQuests, id -> id);
    }

    /**
     * @param specialLabel identifiant d'un mob spécial ({@code rpgquest:roi_squelette}) → nom public
     *                     sans mise en forme ; l'identifiant lui-même si le mob n'existe plus
     */
    static CompletableFuture<Catalog> gather(StatisticsRepository repository, StatisticsDefinition stats, Instant now,
                                             Predicate<String> archived, PublicProfilePolicy.Visibility visibility,
                                             List<QuestStatisticsRepository.Row> lifetimeQuests,
                                             Function<String, String> specialLabel) {
        List<CompletableFuture<BoardResult>> boards = new ArrayList<>();
        boolean split = false;
        for (StatBoard board : stats.publishedBoards()) {
            if (board.splitByEntity()) {
                split = true;
                boards.add(splitBoard(repository, stats, board, now, visibility));
            } else {
                boards.add(board(repository, stats, board, now, archived, visibility, lifetimeQuests));
            }
        }
        CompletableFuture<Map<Target, Instant>> targetSince = split
                ? repository.firstSeenByTarget(StatMetric.find(StatMetric.ENTITY_KILL).orElseThrow())
                : CompletableFuture.completedFuture(Map.of());
        Map<String, Instant> since = new ConcurrentHashMap<>();
        List<CompletableFuture<Void>> firsts = new ArrayList<>();
        for (StatMetric metric : StatMetric.catalog()) {
            if (metric.windowed()) {
                firsts.add(repository.trackedSince(metric).thenAccept(first -> {
                    if (first != null) {
                        since.put(metric.id(), first);
                    }
                }));
            }
        }
        List<CompletableFuture<?>> all = new ArrayList<>(boards);
        all.addAll(firsts);
        all.add(targetSince);
        return CompletableFuture.allOf(all.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> new Catalog(boards.stream().map(CompletableFuture::join).toList(),
                        Map.copyOf(since), stats.zone(), targetSince.join(), specialLabel));
    }

    /**
     * Classement par type de créature (#734) : fenêtre en cours et, pour un classement de calendrier,
     * la précédente, relues en direct dans le journal (jamais figées : le journal est la source).
     */
    private static CompletableFuture<BoardResult> splitBoard(StatisticsRepository repository,
                                                             StatisticsDefinition stats, StatBoard board, Instant now,
                                                             PublicProfilePolicy.Visibility visibility) {
        Optional<BoardPeriod.Window> current = board.current(now, stats);
        if (current.isEmpty()) {
            return CompletableFuture.completedFuture(new BoardResult(board,
                    board.period() == BoardPeriod.EVENT ? "NO_EVENT" : "NO_SEASON", null, List.of(), null, List.of()));
        }
        BoardPeriod.Window window = current.get();
        Set<UUID> excluded = stats.excludedFrom(board.id());
        String status = now.isBefore(window.start()) ? "SCHEDULED" : now.isBefore(window.end()) ? "ACTIVE" : "ENDED";
        StatMetric metric = board.statMetric();
        CompletableFuture<Map<Target, List<StatisticsRepository.RankedRow>>> rows = "SCHEDULED".equals(status)
                ? CompletableFuture.completedFuture(Map.of())
                : repository.liveTopByTarget(board.periodFor(window), metric, board.size(), visibility, excluded);
        Optional<BoardPeriod.Window> previous = board.previous(now, stats.zone());
        CompletableFuture<Map<Target, List<StatisticsRepository.RankedRow>>> previousRows = previous
                .map(p -> repository.liveTopByTarget(board.periodFor(p), metric, board.size(), visibility, excluded))
                .orElse(CompletableFuture.completedFuture(Map.of()));
        return rows.thenCombine(previousRows, (live, before) -> new BoardResult(board, status, window, List.of(),
                previous.orElse(null), List.of(), live, before));
    }

    private static CompletableFuture<BoardResult> board(StatisticsRepository repository, StatisticsDefinition stats,
                                                        StatBoard board, Instant now,
                                                        Predicate<String> archived,
                                                        PublicProfilePolicy.Visibility visibility,
                                                        List<QuestStatisticsRepository.Row> lifetimeQuests) {
        Optional<BoardPeriod.Window> current = board.current(now, stats);
        if (!board.statMetric().windowed()) {
            // Quêtes terminées « depuis toujours » : la source de vérité est quest_progress (#54).
            List<QuestStatisticsRepository.Row> top = lifetimeQuests.stream().limit(board.size()).toList();
            return CompletableFuture.completedFuture(new BoardResult(board, "ACTIVE", current.orElse(null),
                    StatisticsRepository.rank(top.stream().map(QuestStatisticsRepository.Row::name).toList(),
                            top.stream().map(QuestStatisticsRepository.Row::completed).toList()), null, List.of()));
        }
        if (current.isEmpty()) {
            return CompletableFuture.completedFuture(new BoardResult(board,
                    board.period() == BoardPeriod.EVENT ? "NO_EVENT" : "NO_SEASON", null, List.of(), null, List.of()));
        }
        BoardPeriod.Window window = current.get();
        Set<UUID> excluded = stats.excludedFrom(board.id());
        String status = now.isBefore(window.start()) ? "SCHEDULED" : now.isBefore(window.end()) ? "ACTIVE" : "ENDED";
        CompletableFuture<List<StatisticsRepository.RankedRow>> rows = "SCHEDULED".equals(status)
                ? CompletableFuture.completedFuture(List.of())
                : top(repository, stats, board, window, archived, visibility, excluded);
        Optional<BoardPeriod.Window> previous = board.previous(now, stats.zone());
        CompletableFuture<List<StatisticsRepository.RankedRow>> previousRows = previous
                .map(p -> top(repository, stats, board, p, archived, visibility, excluded))
                .orElse(CompletableFuture.completedFuture(List.of()));
        return rows.thenCombine(previousRows, (live, before) ->
                new BoardResult(board, status, window, live, previous.orElse(null), before));
    }

    private static CompletableFuture<List<StatisticsRepository.RankedRow>> top(
            StatisticsRepository repository, StatisticsDefinition stats, StatBoard board, BoardPeriod.Window window,
            Predicate<String> archived, PublicProfilePolicy.Visibility visibility, Set<UUID> excluded) {
        StatPeriod period = board.periodFor(window);
        return archived.test(period.id())
                ? repository.archivedTop(period, board.statMetric(), board.size(), visibility, excluded)
                : repository.liveTop(period, board.statMetric(), board.size(), visibility, excluded,
                stats.excludedXpReasons());
    }

    /** {@code statistics.boards} : le contrat lu par le portail. */
    static List<Map<String, Object>> boards(Catalog catalog) {
        List<Map<String, Object>> boards = new ArrayList<>();
        for (BoardResult result : catalog.boards()) {
            StatBoard board = result.board();
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", board.id());
            entry.put("name", board.name());
            if (board.description() != null) {
                entry.put("description", board.description());
            }
            entry.put("category", board.category());
            if (board.icon() != null) {
                entry.put("icon", board.icon());
            }
            entry.put("metric", board.metric());
            entry.put("period", board.period().wireName());
            if (!board.declared().isEmpty()) {
                entry.put("filter", board.declared());
            }
            entry.put("featured", board.featured());
            entry.put("size", board.size());
            entry.put("status", result.status());
            entry.put("trackedSince", Optional.ofNullable(catalog.trackedSince().get(board.metric()))
                    .map(Instant::toString).orElse(null));
            entry.put("window", window(result.window(), board.period()));
            if (board.splitByEntity()) {
                entry.put("split", board.split());
                entry.put("targets", targets(board, catalog, result.targets()));
                if (result.previous() != null) {
                    Map<String, Object> previous = window(result.previous(), board.period());
                    previous.put("targets", targets(board, catalog, result.previousTargets()));
                    entry.put("previous", previous);
                }
                boards.add(entry);
                continue;
            }
            entry.put("leaderboard", rows(result.rows()));
            if (result.previous() != null) {
                Map<String, Object> previous = window(result.previous(), board.period());
                previous.put("leaderboard", rows(result.previousRows()));
                entry.put("previous", previous);
            }
            boards.add(entry);
        }
        return boards;
    }

    /** {@code statistics.catalog} : métriques et depuis quand, familles, mesures non suivies. */
    static Map<String, Object> catalog(Catalog catalog) {
        List<Map<String, Object>> metrics = new ArrayList<>();
        for (StatMetric metric : StatMetric.catalog()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", metric.id());
            entry.put("dated", metric.windowed());
            entry.put("trackedSince", Optional.ofNullable(catalog.trackedSince().get(metric.id()))
                    .map(Instant::toString).orElse(null));
            entry.put("filters", metric.filters().keySet().stream().sorted().toList());
            metrics.add(entry);
        }
        Map<String, Object> families = new LinkedHashMap<>();
        for (StatDimensions.Kind kind : StatDimensions.Kind.values()) {
            List<Map<String, Object>> list = new ArrayList<>();
            for (StatDimensions.Family family : StatDimensions.bundled().families(kind)) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("id", family.id());
                entry.put("label", family.label());
                entry.put("size", family.members().size());
                list.add(entry);
            }
            families.put(kind.filterKey(), list);
        }
        List<Map<String, Object>> planned = new ArrayList<>();
        StatMetric.PLANNED.forEach((id, reason) -> planned.add(Map.of("id", id, "reason", reason)));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("timezone", catalog.zone().getId());
        result.put("metrics", metrics);
        result.put("families", families);
        result.put("planned", planned);
        return result;
    }

    /**
     * Les types de créatures d'un classement découpé (#734) : toutes les cibles déjà rencontrées dans le
     * journal qui passent son filtre, même sans joueur visible sur la fenêtre (lignes vides : « personne
     * pour cette période »), triées par catégorie puis par nom.
     */
    private static List<Map<String, Object>> targets(StatBoard board, Catalog catalog,
                                                     Map<Target, List<StatisticsRepository.RankedRow>> rows) {
        StatDimensions dimensions = StatDimensions.bundled();
        List<Map<String, Object>> targets = new ArrayList<>();
        java.util.Set<Target> known = new java.util.LinkedHashSet<>(catalog.targetSince().keySet());
        known.addAll(rows.keySet());
        for (Target target : known) {
            if (!matches(board, target)) {
                continue;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("key", target.key());
            entry.put("label", target.variant() == null ? dimensions.entityLabel(target.target())
                    : catalog.specialLabel().apply(target.variant()));
            entry.put("category", category(dimensions, target));
            if (target.variant() != null) {
                entry.put("base", dimensions.entityLabel(target.target()));
            }
            entry.put("trackedSince", Optional.ofNullable(catalog.targetSince().get(target))
                    .map(Instant::toString).orElse(null));
            entry.put("leaderboard", rows(rows.getOrDefault(target, List.of())));
            targets.add(entry);
        }
        targets.sort(java.util.Comparator
                .comparingInt((Map<String, Object> t) -> TARGET_CATEGORIES.indexOf(t.get("category")))
                .thenComparing(t -> String.valueOf(t.get("label")), java.text.Collator.getInstance(java.util.Locale.FRENCH))
                .thenComparing(t -> String.valueOf(t.get("key"))));
        return targets;
    }

    /** Catégorie publique : {@code special} pour un mob spécial, sinon celle de son type Paper. */
    private static String category(StatDimensions dimensions, Target target) {
        if (target.variant() != null) {
            return "special";
        }
        StatDimensions.EntityCategory category = dimensions.categoryOf(target.target());
        return category == StatDimensions.EntityCategory.EXCLUDED ? "passive"
                : category.name().toLowerCase(java.util.Locale.ROOT);
    }

    /** Vrai si la cible passe le filtre du classement (même règle que la requête : OU par colonne, ET entre colonnes). */
    private static boolean matches(StatBoard board, Target target) {
        Map<String, Set<String>> byColumn = new LinkedHashMap<>();
        board.filter().forEach((key, values) -> {
            String column = board.statMetric().filters().get(key);
            if (column != null && !values.isEmpty()) {
                byColumn.computeIfAbsent(column, k -> new java.util.HashSet<>()).addAll(values);
            }
        });
        for (Map.Entry<String, Set<String>> filter : byColumn.entrySet()) {
            String value = "variant".equals(filter.getKey()) ? target.variant() : target.target();
            if (value == null || !filter.getValue().contains(value)) {
                return false;
            }
        }
        return true;
    }

    private static Map<String, Object> window(BoardPeriod.Window window, BoardPeriod period) {
        if (window == null) {
            return null;
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("key", window.key());
        if (period != BoardPeriod.LIFETIME) {
            map.put("start", window.start().toString());
            map.put("end", window.end().toString());
        }
        return map;
    }

    private static List<Map<String, Object>> rows(List<StatisticsRepository.RankedRow> rows) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (StatisticsRepository.RankedRow row : rows) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("name", row.name());
            map.put("value", row.value());
            map.put("rank", row.rank());
            result.add(map);
        }
        return result;
    }
}
