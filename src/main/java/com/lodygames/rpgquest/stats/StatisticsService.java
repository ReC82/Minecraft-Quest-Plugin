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

    private final Plugin plugin;
    private final Path file;
    private final StatisticsRepository repository;
    private final Logger logger;
    private final Clock clock;

    private volatile StatisticsDefinition definition = StatisticsDefinition.EMPTY;
    private volatile Set<String> archived = Set.of();
    /** Transitions déjà notées par ce processus (évite une écriture par minute et par période). */
    private final Set<String> audited = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private long loadedModified = Long.MIN_VALUE;
    private BukkitTask task;
    private volatile boolean stopped;

    public StatisticsService(Plugin plugin, Path file, StatisticsRepository repository, Logger logger) {
        this(plugin, file, repository, logger, Clock.systemUTC());
    }

    StatisticsService(Plugin plugin, Path file, StatisticsRepository repository, Logger logger, Clock clock) {
        this.plugin = plugin;
        this.file = file;
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
        java.util.Optional<StatPeriod> season = definition.currentSeason(now);
        for (StatBoard board : definition.boards()) {
            if (!board.statMetric().windowed()) {
                continue;
            }
            if (board.period().calendar()) {
                board.previous(now, definition.zone()).ifPresent(window -> windows.add(board.periodFor(window)));
            } else if (board.period() != BoardPeriod.LIFETIME) {
                board.current(now, definition.zone(), season)
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

    private void reloadIfChanged() {
        long modified;
        try {
            modified = Files.exists(file) ? Files.getLastModifiedTime(file).toMillis() : -1L;
        } catch (IOException e) {
            logger.warn("Statistiques : lecture de {} impossible.", file, e);
            return;
        }
        if (modified == loadedModified) {
            return;
        }
        loadedModified = modified;
        if (modified < 0) {
            // Aucun fichier : aucune saison ni aucun événement, mais le catalogue de classements du JAR (#734).
            definition = StatisticsDefinitionLoader.load(new YamlConfiguration()).definition();
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(Files.readString(file));
        } catch (IOException | InvalidConfigurationException e) {
            logger.warn("Statistiques : {} illisible, aucune saison ni aucun événement publié.", file, e);
            definition = StatisticsDefinition.EMPTY;
            return;
        }
        StatisticsDefinitionLoader.Result result = StatisticsDefinitionLoader.load(yaml);
        for (String error : result.errors()) {
            logger.warn("Statistiques : {} — {}", file.getFileName(), error);
        }
        definition = result.definition();
        logger.info("Statistiques : {} période(s) chargée(s) depuis {}{}.", result.definition().periods().size(),
                file.getFileName(), result.valid() ? "" : " (fichier invalide ignoré)");
    }
}
