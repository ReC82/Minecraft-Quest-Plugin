package com.lodygames.rpgquest.stats;

import com.lodygames.rpgquest.database.StatisticsRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/**
 * Vues PlugAdmin des classements (issue #735) : l'état complet du catalogue (tous statuts, avec la
 * version de chaque entrée) et la prévisualisation d'un classement. <strong>Aucun pseudo ni UUID</strong>
 * ne sort d'ici : la prévisualisation ne donne que des comptes agrégés (participants, total) ; le
 * podium montré par PlugAdmin est une fixture fictive, étiquetée comme telle.
 */
public final class LeaderboardAdminView {

    /** En dessous, l'aperçu prévient que l'échantillon est trop faible pour un classement parlant. */
    static final long LOW_SAMPLE = 3;

    private final StatisticsService service;
    private final StatisticsRepository repository;
    private final LeaderboardCatalogEditor editor;
    private final BooleanSupplier webExportEnabled;

    public LeaderboardAdminView(StatisticsService service, StatisticsRepository repository,
                                LeaderboardCatalogEditor editor, BooleanSupplier webExportEnabled) {
        this.service = service;
        this.repository = repository;
        this.editor = editor;
        this.webExportEnabled = webExportEnabled;
    }

    public LeaderboardCatalogEditor editor() {
        return editor;
    }

    /** {@code leaderboard.catalog.status}. Les dates de première donnée se lisent hors du fil principal. */
    public CompletableFuture<Map<String, Object>> status() {
        StatisticsDefinitionLoader.Result result = service.lastResult();
        StatisticsDefinition definition = service.definition();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("available", true);
        details.put("source", result.boardsSource().wireName());
        details.put("valid", result.valid());
        details.put("errors", result.errors());
        details.put("timezone", definition.zone().getId());
        details.put("webExportEnabled", webExportEnabled.getAsBoolean());
        List<Map<String, Object>> boards = new ArrayList<>();
        String refusal = null;
        try {
            LeaderboardCatalogEditor.Catalog catalog = editor.catalog();
            details.put("fileExists", catalog.fileExists());
            for (LeaderboardCatalogEditor.Entry entry : catalog.entries()) {
                Map<String, Object> row = new LinkedHashMap<>(entry.fields());
                row.put("version", entry.version());
                definition.board(entry.id()).flatMap(board -> board.current(service.now(), definition))
                        .ifPresent(window -> row.put("window", window(window)));
                boards.add(row);
            }
        } catch (LeaderboardCatalogEditor.Refusal e) {
            refusal = e.outcome.message();
            details.put("fileExists", java.nio.file.Files.isRegularFile(service.boardsFile()));
            for (StatBoard board : definition.boards()) {
                boards.add(describe(board));
            }
        }
        details.put("writable", refusal == null);
        details.put("readOnlyReason", refusal);
        boards.sort(java.util.Comparator.comparingInt(row -> row.get("order") instanceof Integer order ? order : 0));
        details.put("boards", boards);
        List<Map<String, Object>> events = new ArrayList<>();
        for (StatPeriod event : definition.events()) {
            events.add(new LinkedHashMap<>(Map.of("id", event.id(), "name", event.name(),
                    "start", event.start().toString(), "end", event.end().toString(), "state", event.state().name())));
        }
        details.put("events", events);
        details.put("categories", StatBoard.CATEGORIES);
        details.put("families", families());
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
        return CompletableFuture.allOf(firsts.toArray(CompletableFuture[]::new)).thenApply(ignored -> {
            List<Map<String, Object>> metrics = new ArrayList<>();
            for (StatMetric metric : StatMetric.catalog()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", metric.id());
                row.put("dated", metric.windowed());
                row.put("filters", metric.filters().keySet().stream().sorted().toList());
                row.put("trackedSince", Optional.ofNullable(since.get(metric.id())).map(Instant::toString).orElse(null));
                metrics.add(row);
            }
            details.put("metrics", metrics);
            return details;
        });
    }

    /**
     * {@code leaderboard.preview} : fenêtre en cours et précédente (figée ou non), participation
     * agrégée de la fenêtre en cours, avertissements. Brouillons compris.
     */
    public CompletableFuture<Map<String, Object>> preview(String boardId) {
        StatisticsDefinition definition = service.definition();
        Optional<StatBoard> found = definition.board(boardId);
        if (found.isEmpty()) {
            return CompletableFuture.completedFuture(Map.of("available", false, "message",
                    "Classement « " + boardId + " » absent des classements chargés (fichier refusé ou recharger la page)."));
        }
        StatBoard board = found.get();
        Instant now = service.now();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("available", true);
        details.put("board", describe(board));
        details.put("timezone", definition.zone().getId());
        details.put("now", now.toString());
        List<String> warnings = new ArrayList<>();
        if (!board.active()) {
            warnings.add("Statut « " + LeaderboardCatalogEditor.statusLabel(board.status())
                    + " » : ce classement n'est ni calculé ni publié tant qu'il n'est pas activé.");
        } else if (!board.visible()) {
            warnings.add("Caché du site : il est calculé et figé, mais personne ne le voit.");
        }
        if (!webExportEnabled.getAsBoolean()) {
            warnings.add("L'export web est désactivé sur ce serveur : le site public n'affiche encore aucun classement "
                    + "réel (décision #55). L'aperçu ci-dessous est fictif.");
        }
        Optional<BoardPeriod.Window> current = board.current(now, definition);
        current.ifPresent(window -> details.put("window", window(window)));
        if (current.isEmpty()) {
            warnings.add(board.period() == BoardPeriod.EVENT ? "L'événement suivi est en brouillon ou annulé : rien à classer."
                    : "Aucune saison définie dans statistics.yml : ce classement attend la prochaine saison.");
        } else if (now.isBefore(current.get().start())) {
            warnings.add("Pas encore commencé : ouverture le " + current.get().start() + ".");
        } else if (!now.isBefore(current.get().end())) {
            warnings.add("Terminé : ses résultats sont figés à la fin de la période.");
        }
        board.previous(now, definition.zone()).ifPresent(previous -> {
            Map<String, Object> map = window(previous);
            map.put("archived", service.isArchived(board.periodFor(previous).id()));
            details.put("previous", map);
        });
        StatMetric metric = board.statMetric();
        if (!metric.windowed()) {
            details.put("participation", null);
            details.put("warnings", warnings);
            return CompletableFuture.completedFuture(details);
        }
        CompletableFuture<Instant> since = repository.trackedSince(metric);
        CompletableFuture<StatisticsRepository.Participation> participation = current
                .filter(window -> !now.isBefore(window.start()))
                .map(window -> repository.participation(board.periodFor(window), metric,
                        definition.excludedFrom(board.id()), definition.excludedXpReasons()))
                .orElse(CompletableFuture.completedFuture(null));
        return since.thenCombine(participation, (first, counted) -> {
            details.put("trackedSince", first == null ? null : first.toString());
            if (first == null) {
                warnings.add("Mesure jamais encore collectée sur ce serveur : le classement restera vide tant que "
                        + "personne n'a joué l'activité.");
            }
            if (counted != null) {
                details.put("participation", Map.of("players", counted.players(), "total", counted.total()));
                if (counted.players() < LOW_SAMPLE) {
                    warnings.add("Échantillon faible : " + counted.players() + " joueur(s) sur la période en cours.");
                }
            }
            details.put("warnings", warnings);
            return details;
        });
    }

    /** Un classement chargé, au format des entrées du fichier (lecture seule, sans version). */
    static Map<String, Object> describe(StatBoard board) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", board.id());
        row.put("name", board.name());
        if (board.description() != null) {
            row.put("description", board.description());
        }
        row.put("category", board.category());
        if (board.icon() != null) {
            row.put("icon", board.icon());
        }
        row.put("metric", board.metric());
        if (!board.declared().isEmpty()) {
            row.put("filter", board.declared());
        }
        row.put("period", board.period().wireName());
        if (board.event() != null) {
            row.put("event", board.event());
        }
        if (board.start() != null) {
            row.put("start", board.start().toString());
            row.put("end", board.end().toString());
        }
        row.put("size", board.size());
        row.put("status", board.status().wireName());
        row.put("order", board.order());
        row.put("visible", board.visible());
        row.put("featured", board.featured());
        return row;
    }

    private static Map<String, Object> families() {
        Map<String, Object> families = new LinkedHashMap<>();
        for (StatDimensions.Kind kind : StatDimensions.Kind.values()) {
            List<Map<String, Object>> list = new ArrayList<>();
            for (StatDimensions.Family family : StatDimensions.bundled().families(kind)) {
                list.add(new LinkedHashMap<>(Map.of("id", family.id(), "label", family.label(),
                        "size", family.members().size())));
            }
            families.put(kind.filterKey(), list);
        }
        return families;
    }

    private static Map<String, Object> window(BoardPeriod.Window window) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("key", window.key());
        if (!window.end().equals(BoardPeriod.OPEN_END)) {
            map.put("start", window.start().toString());
            map.put("end", window.end().toString());
        }
        return map;
    }
}
