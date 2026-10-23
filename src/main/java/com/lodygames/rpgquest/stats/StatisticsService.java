package com.lodygames.rpgquest.stats;

import com.lodygames.rpgquest.bootstrap.PluginService;
import com.lodygames.rpgquest.database.StatisticsRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import org.bukkit.Bukkit;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.slf4j.Logger;

/**
 * Saisons et événements de classement (issue #54).
 *
 * <p>Toutes les minutes, hors du thread principal : relit {@code statistics.yml} s'il a changé,
 * puis fige chaque période terminée et pas encore archivée ({@link StatisticsRepository#archive},
 * idempotent). L'archivage ne dépend pas de l'export web : une saison se termine et se fige même
 * quand {@code web-export.enabled} est faux.</p>
 *
 * <p>Fichier absent = aucune période, rien d'autre ne change. Fichier invalide = aucune période
 * (jamais un fichier appliqué à moitié), erreurs dans le journal.</p>
 */
public final class StatisticsService implements PluginService {

    private static final long INTERVAL_TICKS = 20L * 60;
    /** Classements gérés depuis PlugAdmin (#735), séparés des saisons et événements. */
    public static final String BOARDS_FILE = "leaderboards.yml";

    private final Plugin plugin;
    private final Path file;
    private final StatisticsRepository repository;
    private final Logger logger;
    private final Clock clock;

    private volatile StatisticsDefinition definition = StatisticsDefinition.EMPTY;
    private volatile Set<String> archived = Set.of();
    /** Transitions déjà notées par ce processus (évite une écriture par minute et par période). */
    private final Set<String> audited = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Path boardsFile;
    private volatile StatisticsDefinitionLoader.Result lastResult =
            new StatisticsDefinitionLoader.Result(StatisticsDefinition.EMPTY, List.of());
    private String loadedModified;
    private BukkitTask task;
    private volatile boolean stopped;

    public StatisticsService(Plugin plugin, Path file, StatisticsRepository repository, Logger logger) {
        this(plugin, file, repository, logger, Clock.systemUTC());
    }

    StatisticsService(Plugin plugin, Path file, StatisticsRepository repository, Logger logger, Clock clock) {
        this.plugin = plugin;
        this.file = file;
        this.boardsFile = file.resolveSibling(BOARDS_FILE);
        this.repository = repository;
        this.logger = logger;
        this.clock = clock;
    }

    @Override
    public void start() {
        stopped = false;
        task = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::tick, 1L, INTERVAL_TICKS);
    }

    /**
     * Un tick qui tombe pendant l'arrêt du plugin (base déjà fermée : {@link RejectedExecutionException})
     * est simplement abandonné : il n'y a plus rien à archiver, le prochain démarrage le fera.
     */
    void tick() {
        if (stopped) {
            return;
        }
        try {
            refresh().join();
        } catch (RejectedExecutionException e) {
            logger.debug("Statistiques : base en cours d'arrêt, tick ignoré.");
        } catch (java.util.concurrent.CompletionException e) {
            if (!(e.getCause() instanceof RejectedExecutionException)) {
                throw e;
            }
        }
    }

    @Override
    public void stop() {
        stopped = true;
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    public StatisticsDefinition definition() {
        return definition;
    }

    public boolean isArchived(String periodId) {
        return archived.contains(periodId);
    }

    public Instant now() {
        return clock.instant();
    }

    /**
     * Relit le fichier si besoin, note les transitions de cycle de vie (#57) et archive les périodes
     * terminées. Une période en brouillon ou annulée n'est jamais archivée. Jamais sur le thread principal.
     */
    CompletableFuture<Void> refresh() {
        reloadIfChanged();
        StatisticsDefinition current = definition;
        Instant now = clock.instant();
        return repository.archivedPeriodIds().thenCompose(done -> {
            List<CompletableFuture<Void>> pending = new ArrayList<>();
            Set<String> nowArchived = new HashSet<>(done);
            for (StatPeriod period : current.periods()) {
                EventLifecycle state = EventLifecycle.of(period, now, done.contains(period.id()));
                if (state == EventLifecycle.ENDED) {
                    pending.add(repository.archive(period, current.excludedXpReasons(), now).thenAccept(created -> {
                        synchronized (nowArchived) {
                            nowArchived.add(period.id());
                        }
                        if (created) {
                            logger.info("Statistiques : {} « {} » terminée, classement figé.",
                                    period.scope().wireName(), period.id());
                        }
                    }));
                } else if (state != EventLifecycle.DRAFT && state != EventLifecycle.ARCHIVED) {
                    pending.add(audit(period, state, now));
                }
            }
            for (StatPeriod window : endedBoardWindows(current, now)) {
                if (!done.contains(window.id())) {
                    pending.add(repository.archive(window, current.excludedXpReasons(), now).thenAccept(created -> {
                        synchronized (nowArchived) {
                            nowArchived.add(window.id());
                        }
                        if (created) {
                            logger.info("Statistiques : classement « {} » terminé, résultats figés.", window.id());
                        }
                    }));
                }
            }
            return CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new))
                    .thenRun(() -> archived = Set.copyOf(nowArchived));
        }).exceptionally(error -> {
            logger.warn("Statistiques : archivage des périodes impossible, nouvel essai à la prochaine minute.", error);
            return null;
        });
    }

    /**
     * Fenêtres de classement (#734) terminées à figer : la dernière d'un classement de calendrier
     * (hier, la semaine ou le mois précédent), et la fenêtre d'un classement de saison ou à dates
     * fixes une fois finie. Jamais d'historique plus ancien : seule une fenêtre réellement observée
     * par le serveur est archivée.
     */
    static List<StatPeriod> endedBoardWindows(StatisticsDefinition definition, Instant now) {
        List<StatPeriod> windows = new ArrayList<>();
        for (StatBoard board : definition.activeBoards()) {
            if (!board.statMetric().windowed()) {
                continue;
            }
            if (board.period().calendar()) {
                board.previous(now, definition.zone()).ifPresent(window -> windows.add(board.periodFor(window)));
            } else if (board.period() != BoardPeriod.LIFETIME) {
                board.current(now, definition)
                        .filter(window -> !now.isBefore(window.end()))
                        .ifPresent(window -> windows.add(board.periodFor(window)));
            }
        }
        return windows;
    }

    /** Une transition n'est notée (et annoncée) qu'une fois, au premier tick qui l'observe. */
    private CompletableFuture<Void> audit(StatPeriod period, EventLifecycle state, Instant now) {
        String key = period.id() + "/" + state.name();
        if (audited.contains(key)) {
            return CompletableFuture.completedFuture(null);
        }
        return repository.recordTransition(period.id(), state, now).thenAccept(created -> {
            audited.add(key);
            if (created) {
                logger.info("Statistiques : {} « {} » → {}.", period.scope().wireName(), period.id(), state.name());
            }
        });
    }

    /**
     * Relit {@code statistics.yml} et {@code leaderboards.yml} (#735) si l'un des deux a changé. La
     * date des deux fichiers forme la clé : le fichier géré par PlugAdmin est pris en compte à la
     * minute suivante même sans {@link #reloadNow()}.
     */
    private synchronized void reloadIfChanged() {
        String modified;
        try {
            modified = modified(file) + "/" + modified(boardsFile);
        } catch (IOException e) {
            logger.warn("Statistiques : lecture de {} impossible.", file, e);
            return;
        }
        if (modified.equals(loadedModified)) {
            return;
        }
        loadedModified = modified;
        apply(read());
    }

    /**
     * Relit les deux fichiers tout de suite (écriture depuis PlugAdmin, #735) et rend le résultat
     * appliqué. Appelé hors du thread principal.
     */
    public synchronized StatisticsDefinitionLoader.Result reloadNow() {
        try {
            loadedModified = modified(file) + "/" + modified(boardsFile);
        } catch (IOException e) {
            loadedModified = null;
        }
        StatisticsDefinitionLoader.Result result = read();
        apply(result);
        return result;
    }

    private static long modified(Path path) throws IOException {
        return Files.exists(path) ? Files.getLastModifiedTime(path).toMillis() : -1L;
    }

    /** Lit et valide les deux fichiers, sans rien appliquer. */
    private StatisticsDefinitionLoader.Result read() {
        YamlConfiguration yaml = new YamlConfiguration();
        YamlConfiguration boards = null;
        try {
            if (Files.exists(file)) {
                yaml.loadFromString(Files.readString(file));
            }
        } catch (IOException | InvalidConfigurationException e) {
            return new StatisticsDefinitionLoader.Result(StatisticsDefinition.EMPTY,
                    List.of(file.getFileName() + " illisible : " + e.getMessage()));
        }
        try {
            if (Files.exists(boardsFile)) {
                boards = new YamlConfiguration();
                boards.loadFromString(Files.readString(boardsFile));
            }
        } catch (IOException | InvalidConfigurationException e) {
            return new StatisticsDefinitionLoader.Result(StatisticsDefinition.EMPTY,
                    List.of(boardsFile.getFileName() + " illisible : " + e.getMessage()),
                    StatisticsDefinitionLoader.BoardsSource.MANAGED_FILE);
        }
        return StatisticsDefinitionLoader.load(yaml, boards);
    }

    private void apply(StatisticsDefinitionLoader.Result result) {
        for (String error : result.errors()) {
            logger.warn("Statistiques : {}", error);
        }
        definition = result.definition();
        lastResult = result;
        logger.info("Statistiques : {} période(s) et {} classement(s) actif(s) chargés (classements : {}){}.",
                result.definition().periods().size(), result.definition().activeBoards().size(),
                result.boardsSource().wireName(), result.valid() ? "" : " — fichiers invalides ignorés");
    }

    /** Dernier chargement : source des classements et erreurs (#735, PlugAdmin). */
    public StatisticsDefinitionLoader.Result lastResult() {
        return lastResult;
    }

    public Path file() {
        return file;
    }

    /** Fichier des classements géré par PlugAdmin (#735), à côté de {@code statistics.yml}. */
    public Path boardsFile() {
        return boardsFile;
    }
}
