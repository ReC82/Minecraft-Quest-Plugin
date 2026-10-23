package com.lodygames.rpgquest.web;

import com.lodygames.rpgquest.database.QuestStatisticsRepository;
import com.lodygames.rpgquest.database.StatisticsRepository;
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
import java.util.function.Predicate;

/**
 * Catalogue de classements de l'instantané web (issue #734) : {@code statistics.boards} (chaque
 * classement, sa fenêtre en cours, sa fenêtre précédente figée) et {@code statistics.catalog}
 * (métriques suivies et depuis quand, familles de cibles, mesures pas encore suivies).
 *
 * <p>Aucune valeur n'est inventée : un classement sans saison en cours est publié « sans saison »,
 * une métrique sans donnée a {@code trackedSince: null}. Comme tous les classements de l'instantané,
 * les lignes ne portent que pseudo, valeur et rang, et seulement pour les profils visibles (#55).</p>
 */
final class BoardCatalogExport {

    /** Un classement calculé : fenêtre en cours et précédente, avec leurs lignes. */
    record BoardResult(StatBoard board, String status, BoardPeriod.Window window,
                       List<StatisticsRepository.RankedRow> rows, BoardPeriod.Window previous,
                       List<StatisticsRepository.RankedRow> previousRows) {
    }

    /** Tous les classements et la date de première donnée de chaque métrique horodatée. */
    record Catalog(List<BoardResult> boards, Map<String, Instant> trackedSince, ZoneId zone) {
        static final Catalog EMPTY = new Catalog(List.of(), Map.of(), StatisticsDefinition.DEFAULT_ZONE);
    }

    private BoardCatalogExport() {
    }

    /**
     * Calcule le catalogue hors du fil principal. {@code archived} dit si une fenêtre est déjà figée
     * (lecture de l'archive) ; sinon elle se lit en direct dans la source de sa métrique.
     */
    static CompletableFuture<Catalog> gather(StatisticsRepository repository, StatisticsDefinition stats, Instant now,
                                             Predicate<String> archived, PublicProfilePolicy.Visibility visibility,
                                             List<QuestStatisticsRepository.Row> lifetimeQuests) {
        Optional<StatPeriod> season = stats.currentSeason(now);
        List<CompletableFuture<BoardResult>> boards = new ArrayList<>();
        for (StatBoard board : stats.boards()) {
            boards.add(board(repository, stats, board, now, season, archived, visibility, lifetimeQuests));
        }
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
        return CompletableFuture.allOf(all.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> new Catalog(boards.stream().map(CompletableFuture::join).toList(),
                        Map.copyOf(since), stats.zone()));
    }

    private static CompletableFuture<BoardResult> board(StatisticsRepository repository, StatisticsDefinition stats,
                                                        StatBoard board, Instant now, Optional<StatPeriod> season,
                                                        Predicate<String> archived,
                                                        PublicProfilePolicy.Visibility visibility,
                                                        List<QuestStatisticsRepository.Row> lifetimeQuests) {
        Optional<BoardPeriod.Window> current = board.current(now, stats.zone(), season);
        if (!board.statMetric().windowed()) {
            // Quêtes terminées « depuis toujours » : la source de vérité est quest_progress (#54).
            List<QuestStatisticsRepository.Row> top = lifetimeQuests.stream().limit(board.size()).toList();
            return CompletableFuture.completedFuture(new BoardResult(board, "ACTIVE", current.orElse(null),
                    StatisticsRepository.rank(top.stream().map(QuestStatisticsRepository.Row::name).toList(),
                            top.stream().map(QuestStatisticsRepository.Row::completed).toList()), null, List.of()));
        }
        if (current.isEmpty()) {
            return CompletableFuture.completedFuture(new BoardResult(board, "NO_SEASON", null, List.of(), null, List.of()));
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
