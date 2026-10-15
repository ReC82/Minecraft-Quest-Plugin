package com.lodygames.rpgquest.web;

import com.lodygames.rpgquest.bootstrap.PluginService;
import com.lodygames.rpgquest.config.WebExportConfig;
import com.lodygames.rpgquest.database.ProgressionRepository;
import com.lodygames.rpgquest.database.StatisticsRepository;
import com.lodygames.rpgquest.item.YamlCustomItemRegistry;
import com.lodygames.rpgquest.item.model.CustomItemDefinition;
import com.lodygames.rpgquest.player.PublicProfilePolicy;
import com.lodygames.rpgquest.progression.model.SkillType;
import com.lodygames.rpgquest.stats.StatMetric;
import com.lodygames.rpgquest.stats.StatPeriod;
import com.lodygames.rpgquest.stats.StatScope;
import com.lodygames.rpgquest.stats.StatisticsDefinition;
import com.lodygames.rpgquest.stats.StatisticsService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.slf4j.Logger;

/**
 * Génère périodiquement {@code <dataFolder>/<output-dir>/snapshot.json},
 * seule donnée que le module {@code web-api} séparé est autorisé à lire
 * (mission étape 21, points 1-2 : jamais d'accès direct au fichier SQLite
 * depuis le site). Toutes les lectures nécessaires (classements, catalogue)
 * passent par des opérations async ou des caches déjà en mémoire (point 4).
 *
 * <p>La vérification de péremption ({@code enabled}, intervalle) se fait à
 * chaque tick de housekeeping (thread principal, coût négligeable) ; le
 * calcul des classements (SQLite async) et l'écriture disque elle-même
 * s'exécutent toujours en dehors du thread principal — voir {@link #tick()}
 * et {@link #ioExecutor}.</p>
 */
public final class WebSnapshotWriter implements PluginService {

    private static final long HOUSEKEEPING_INTERVAL_TICKS = 100L; // 5 s
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final Plugin plugin;
    private final Path dataFolder;
    private final ProgressionRepository progressionRepository;
    private final YamlCustomItemRegistry customItemRegistry;
    private final Supplier<WebExportConfig> config;
    private final Logger logger;
    private final LongSupplier clock;
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "RPGQuest-WebExport");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * Version du format de l'instantané. 2 (#54/#56) : ajoute {@code statistics} (portées
     * {@code lifetime}/{@code season}/{@code events}) et {@code profiles}. Les clés de la version 1
     * sont inchangées : un portail plus ancien continue de fonctionner. Les ajouts suivants de #54
     * ({@code statistics.metrics}, {@code rank}, contenu de {@code season}/{@code events}) sont
     * additifs et ne changent pas la version.
     */
    static final int SCHEMA_VERSION = 2;

    private com.lodygames.rpgquest.database.QuestStatisticsRepository questStatistics;

    /** Issue #54 — dépendance facultative : sans elle, la statistique de quêtes est simplement absente. */
    public void setQuestStatistics(com.lodygames.rpgquest.database.QuestStatisticsRepository questStatistics) {
        this.questStatistics = questStatistics;
    }

    private com.lodygames.rpgquest.database.PlayerVariableRepository variables;

    private StatisticsService statisticsService;
    private StatisticsRepository statisticsRepository;

    /**
     * Issue #54 — saisons, événements et exclusions de {@code statistics.yml}. Sans eux, seule la
     * portée {@code lifetime} est exportée, sans exclusion.
     */
    public void setStatistics(StatisticsService service, StatisticsRepository repository) {
        this.statisticsService = service;
        this.statisticsRepository = repository;
    }

    /**
     * Issue #55 — choix « /profile web » des joueurs connectés. Sans elle, la liste nominative des
     * connectés n'est jamais exportée (seul le nombre l'est).
     */
    public void setVariables(com.lodygames.rpgquest.database.PlayerVariableRepository variables) {
        this.variables = variables;
    }

    private BukkitTask task;
    private volatile long lastWriteMillis = Long.MIN_VALUE;
    private volatile boolean writeInProgress = false;

    public WebSnapshotWriter(Plugin plugin, Path dataFolder, ProgressionRepository progressionRepository,
                              YamlCustomItemRegistry customItemRegistry, Supplier<WebExportConfig> config, Logger logger) {
        this(plugin, dataFolder, progressionRepository, customItemRegistry, config, logger, System::currentTimeMillis);
    }

    WebSnapshotWriter(Plugin plugin, Path dataFolder, ProgressionRepository progressionRepository,
                       YamlCustomItemRegistry customItemRegistry, Supplier<WebExportConfig> config, Logger logger,
                       LongSupplier clock) {
        this.plugin = plugin;
        this.dataFolder = dataFolder;
        this.progressionRepository = progressionRepository;
        this.customItemRegistry = customItemRegistry;
        this.config = config;
        this.logger = logger;
        this.clock = clock;
    }

    @Override
    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(
                plugin, (Runnable) this::tick, HOUSEKEEPING_INTERVAL_TICKS, HOUSEKEEPING_INTERVAL_TICKS);
    }

    @Override
    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        ioExecutor.shutdown();
        try {
            if (!ioExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                ioExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ioExecutor.shutdownNow();
        }
    }

    /** Force une génération immédiate, quel que soit l'intervalle configuré (outil de test/admin). */
    public void triggerNow() {
        tick(true);
    }

    private void tick() {
        tick(false);
    }

    private void tick(boolean force) {
        WebExportConfig current = config.get();
        if (!current.enabled()) {
            return;
        }
        long now = clock.getAsLong();
        if (writeInProgress || (!force && now - lastWriteMillis < current.intervalSeconds() * 1000L)) {
            return;
        }
        writeInProgress = true;

        int playerCount = Bukkit.getOnlinePlayers().size();
        int maxPlayers = Bukkit.getMaxPlayers();
        CompletableFuture<List<String>> playerNames = current.includeConnectedPlayers()
                ? visibleNames(List.copyOf(Bukkit.getOnlinePlayers()), current.profileVisibilityDefault())
                : CompletableFuture.completedFuture(List.of());
        List<Map<String, Object>> catalog = buildCatalog();

        StatisticsDefinition stats = statisticsService == null ? StatisticsDefinition.EMPTY : statisticsService.definition();
        Set<UUID> lifetimeExcluded = stats.excludedFrom(StatScope.LIFETIME.wireName());
        CompletableFuture<List<com.lodygames.rpgquest.database.QuestStatisticsRepository.Row>> questRows =
                questStatistics == null ? CompletableFuture.completedFuture(List.of())
                        : questStatistics.topCompleted(current.leaderboardSize(), current.profileVisibilityDefault(),
                        lifetimeExcluded);
        CompletableFuture<PeriodBoards> periods = gatherPeriods(current, stats);
        gatherLeaderboards(current, lifetimeExcluded)
                .thenCombine(playerNames, (leaderboards, names) -> Map.entry(leaderboards, names))
                .thenCombine(questRows, (boardsAndNames, quests) -> Map.entry(boardsAndNames, quests))
                .thenCombine(periods, (gathered, periodBoards) -> {
                    var leaderboards = gathered.getKey().getKey();
                    var names = gathered.getKey().getValue();
                    var quests = gathered.getValue();
                    Map<String, Object> snapshot =
                            buildSnapshot(current, playerCount, maxPlayers, names, catalog, leaderboards);
                    snapshot.put("statistics", statistics(leaderboards, quests, periodBoards));
                    snapshot.put("profiles", profiles(leaderboards, quests));
                    return snapshot;
                })
                .thenAccept(snapshot -> {
                    CompletableFuture.runAsync(() -> writeAtomic(current, Json.write(snapshot)), ioExecutor)
                            .whenComplete((ignored, error) -> {
                                if (error != null) {
                                    logger.warn("Échec de l'écriture du snapshot web.", error);
                                }
                                lastWriteMillis = clock.getAsLong();
                                writeInProgress = false;
                            });
                })
                .exceptionally(error -> {
                    logger.warn("Échec de la génération du snapshot web (classements indisponibles).", error);
                    writeInProgress = false;
                    return null;
                });
    }

    private CompletableFuture<Map<SkillType, List<ProgressionRepository.LeaderboardRow>>> gatherLeaderboards(
            WebExportConfig current, Set<UUID> excluded) {
        Map<SkillType, List<ProgressionRepository.LeaderboardRow>> result =
                java.util.Collections.synchronizedMap(new EnumMap<>(SkillType.class));
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (SkillType skill : current.leaderboardSkills()) {
            futures.add(progressionRepository.topPlayers(skill, current.leaderboardSize(),
                            current.profileVisibilityDefault(), excluded)
                    .thenAccept(rows -> result.put(skill, rows)));
        }
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> new EnumMap<>(result));
    }

    /** Classements d'une saison ou d'un événement, tels qu'exportés. */
    record PeriodBoard(StatPeriod period, String status, Map<String, List<StatisticsRepository.RankedRow>> boards) {
    }

    /** La saison montrée (ou {@code null}) et tous les événements définis. */
    record PeriodBoards(PeriodBoard season, List<PeriodBoard> events) {
        static final PeriodBoards NONE = new PeriodBoards(null, List.of());
    }

    private CompletableFuture<PeriodBoards> gatherPeriods(WebExportConfig current, StatisticsDefinition stats) {
        if (statisticsService == null || statisticsRepository == null) {
            return CompletableFuture.completedFuture(PeriodBoards.NONE);
        }
        Instant now = statisticsService.now();
        CompletableFuture<PeriodBoard> season = stats.currentSeason(now)
                .map(period -> periodBoard(current, stats, period, now))
                .orElse(CompletableFuture.completedFuture(null));
        List<CompletableFuture<PeriodBoard>> events = stats.events().stream()
                .map(period -> periodBoard(current, stats, period, now)).toList();
        return season.thenCombine(CompletableFuture.allOf(events.toArray(CompletableFuture[]::new)),
                (seasonBoard, ignored) -> new PeriodBoards(seasonBoard,
                        events.stream().map(CompletableFuture::join).toList()));
    }

    /**
     * Une période programmée n'a pas encore de classement ; une période archivée se lit dans son
     * archive figée ; sinon le classement est calculé sur la fenêtre du journal.
     */
    private CompletableFuture<PeriodBoard> periodBoard(WebExportConfig current, StatisticsDefinition stats,
                                                       StatPeriod period, Instant now) {
        StatPeriod.Status status = period.status(now);
        boolean archived = statisticsService.isArchived(period.id());
        String wireStatus = archived ? "ARCHIVED" : status.name();
        Set<UUID> excluded = stats.excludedFrom(period.id());
        Map<String, List<StatisticsRepository.RankedRow>> boards =
                java.util.Collections.synchronizedMap(new LinkedHashMap<>());
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (String metricId : period.metrics()) {
            StatMetric metric = StatMetric.find(metricId).orElseThrow();
            boards.put(metricId, List.of());
            if (status == StatPeriod.Status.SCHEDULED) {
                continue;
            }
            CompletableFuture<List<StatisticsRepository.RankedRow>> rows = archived
                    ? statisticsRepository.archivedTop(period.id(), metric, current.leaderboardSize(),
                    current.profileVisibilityDefault(), excluded)
                    : statisticsRepository.liveTop(period, metric, current.leaderboardSize(),
                    current.profileVisibilityDefault(), excluded, stats.excludedXpReasons());
            futures.add(rows.thenAccept(list -> boards.put(metricId, list)));
        }
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> new PeriodBoard(period, wireStatus, new LinkedHashMap<>(boards)));
    }

    /**
     * Pseudos des connectés dont le profil web est visible (#55), dans l'ordre reçu. Noms et UUID
     * sont lus sur le thread principal par l'appelant ; seules les lectures de préférences sont
     * asynchrones. Sans dépôt de variables, aucun nom n'est exporté.
     */
    private CompletableFuture<List<String>> visibleNames(List<? extends Player> online,
                                                         PublicProfilePolicy.Visibility visibility) {
        if (variables == null) {
            return CompletableFuture.completedFuture(List.of());
        }
        List<String> names = online.stream().map(Player::getName).toList();
        List<CompletableFuture<Boolean>> visible = online.stream()
                .map(player -> variables.get(player.getUniqueId(), PublicProfilePolicy.HIDDEN_KEY)
                        .thenApply(choice -> PublicProfilePolicy.isVisible(choice, visibility)))
                .toList();
        return CompletableFuture.allOf(visible.toArray(CompletableFuture[]::new)).thenApply(ignored -> {
            List<String> result = new ArrayList<>();
            for (int i = 0; i < names.size(); i++) {
                if (visible.get(i).join()) {
                    result.add(names.get(i));
                }
            }
            return result;
        });
    }

    private List<Map<String, Object>> buildCatalog() {
        List<Map<String, Object>> catalog = new ArrayList<>();
        for (CustomItemDefinition item : customItemRegistry.items()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", item.id().toString());
            entry.put("name", PlainTextComponentSerializer.plainText().serialize(MM.deserialize(item.displayName())));
            entry.put("rarity", item.rarity().name());
            catalog.add(entry);
        }
        return catalog;
    }

    private Map<String, Object> buildSnapshot(WebExportConfig current, int playerCount, int maxPlayers,
                                               List<String> playerNames, List<Map<String, Object>> catalog,
                                               Map<SkillType, List<ProgressionRepository.LeaderboardRow>> leaderboardRows) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", SCHEMA_VERSION);
        root.put("generatedAt", Instant.now().toString());

        Map<String, Object> server = new LinkedHashMap<>();
        server.put("online", true);
        server.put("playerCount", playerCount);
        server.put("maxPlayers", maxPlayers);
        root.put("server", server);

        root.put("players", playerNames);

        Map<String, Object> leaderboards = new LinkedHashMap<>();
        for (Map.Entry<SkillType, List<ProgressionRepository.LeaderboardRow>> entry : leaderboardRows.entrySet()) {
            List<Map<String, Object>> entries = new ArrayList<>();
            for (ProgressionRepository.LeaderboardRow row : entry.getValue()) {
                Map<String, Object> rowMap = new LinkedHashMap<>();
                rowMap.put("name", row.name());
                rowMap.put("totalXp", row.totalXp());
                entries.add(rowMap);
            }
            leaderboards.put(entry.getKey().name(), entries);
        }
        root.put("leaderboards", leaderboards);

        root.put("catalog", catalog);

        List<Map<String, Object>> announcements = new ArrayList<>();
        for (WebExportConfig.Announcement announcement : current.announcements()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("title", announcement.title());
            entry.put("body", announcement.body());
            announcements.add(entry);
        }
        root.put("announcements", announcements);

        return root;
    }

    /** Statistiques sans saison ni événement (aucun {@code statistics.yml}). */
    static Map<String, Object> statistics(Map<SkillType, List<ProgressionRepository.LeaderboardRow>> leaderboards,
                                          List<com.lodygames.rpgquest.database.QuestStatisticsRepository.Row> quests) {
        return statistics(leaderboards, quests, PeriodBoards.NONE);
    }

    /**
     * Statistiques par portée (#54) : le contrat lu par le portail. {@code metrics} décrit chaque
     * métrique (agrégation, sens, portées calculables) ; {@code lifetime} vient des sources de
     * vérité (XP des pistes, quêtes terminées) ; {@code season} et {@code events} des fenêtres du
     * journal d'XP ou de leur archive figée. Chaque ligne porte un rang (ex-aequo au même rang).
     * Aucune valeur n'est inventée, aucun UUID n'est écrit.
     */
    static Map<String, Object> statistics(Map<SkillType, List<ProgressionRepository.LeaderboardRow>> leaderboards,
                                          List<com.lodygames.rpgquest.database.QuestStatisticsRepository.Row> quests,
                                          PeriodBoards periods) {
        Map<String, Object> lifetime = new LinkedHashMap<>();
        for (Map.Entry<SkillType, List<ProgressionRepository.LeaderboardRow>> entry : leaderboards.entrySet()) {
            lifetime.put(StatMetric.xpMetricId(entry.getKey()), rankedRows(StatisticsRepository.rank(
                    entry.getValue().stream().map(ProgressionRepository.LeaderboardRow::name).toList(),
                    entry.getValue().stream().map(ProgressionRepository.LeaderboardRow::totalXp).toList())));
        }
        lifetime.put(StatMetric.QUESTS_COMPLETED, rankedRows(StatisticsRepository.rank(
                quests.stream().map(com.lodygames.rpgquest.database.QuestStatisticsRepository.Row::name).toList(),
                quests.stream().map(com.lodygames.rpgquest.database.QuestStatisticsRepository.Row::completed).toList())));

        List<String> scopes = new ArrayList<>(List.of(StatScope.LIFETIME.wireName()));
        if (periods.season() != null) {
            scopes.add(StatScope.SEASON.wireName());
        }
        if (!periods.events().isEmpty()) {
            scopes.add(StatScope.EVENT.wireName());
        }
        Map<String, Object> statistics = new LinkedHashMap<>();
        statistics.put("scopes", scopes);
        statistics.put("metrics", StatMetric.catalog().stream().map(StatMetric::describe).toList());
        statistics.put("lifetime", lifetime);
        statistics.put("season", periods.season() == null ? null : period(periods.season()));
        statistics.put("events", periods.events().stream().map(WebSnapshotWriter::period).toList());
        return statistics;
    }

    private static Map<String, Object> period(PeriodBoard board) {
        Map<String, Object> period = new LinkedHashMap<>();
        period.put("id", board.period().id());
        period.put("name", board.period().name());
        period.put("start", board.period().start().toString());
        period.put("end", board.period().end().toString());
        period.put("status", board.status());
        Map<String, Object> boards = new LinkedHashMap<>();
        for (Map.Entry<String, List<StatisticsRepository.RankedRow>> entry : board.boards().entrySet()) {
            boards.put(entry.getKey(), rankedRows(entry.getValue()));
        }
        period.put("leaderboards", boards);
        return period;
    }

    private static List<Map<String, Object>> rankedRows(List<StatisticsRepository.RankedRow> rows) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (StatisticsRepository.RankedRow row : rows) {
            Map<String, Object> map = metricRow(row.name(), row.value());
            map.put("rank", row.rank());
            result.add(map);
        }
        return result;
    }

    private static Map<String, Object> metricRow(String name, long value) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", name);
        row.put("value", value);
        return row;
    }

    /**
     * Profils publics limités (#56) : uniquement les joueurs déjà présents dans un classement
     * (donc non masqués, #55), avec ce que ces classements disent d'eux — rien de plus. Ni UUID, ni
     * position, ni inventaire, ni date de connexion.
     */
    static List<Map<String, Object>> profiles(Map<SkillType, List<ProgressionRepository.LeaderboardRow>> leaderboards,
                                              List<com.lodygames.rpgquest.database.QuestStatisticsRepository.Row> quests) {
        Map<String, Map<String, Object>> byName = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Map.Entry<SkillType, List<ProgressionRepository.LeaderboardRow>> entry : leaderboards.entrySet()) {
            for (ProgressionRepository.LeaderboardRow row : entry.getValue()) {
                profile(byName, row.name()).computeIfAbsent("skills", k -> new LinkedHashMap<String, Object>());
                @SuppressWarnings("unchecked")
                Map<String, Object> skills = (Map<String, Object>) byName.get(row.name()).get("skills");
                skills.put(entry.getKey().name(), row.totalXp());
            }
        }
        for (var row : quests) {
            profile(byName, row.name()).put("questsCompleted", row.completed());
        }
        return new ArrayList<>(byName.values());
    }

    private static Map<String, Object> profile(Map<String, Map<String, Object>> byName, String name) {
        return byName.computeIfAbsent(name, n -> {
            Map<String, Object> profile = new LinkedHashMap<>();
            profile.put("name", n);
            return profile;
        });
    }

    private void writeAtomic(WebExportConfig current, String json) {
        Path outputDir = dataFolder.resolve(current.outputDirectory());
        Path target = outputDir.resolve("snapshot.json");
        Path tmp = outputDir.resolve("snapshot.json.tmp");
        try {
            Files.createDirectories(outputDir);
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            logger.warn("Impossible d'écrire le snapshot web dans {}.", target, e);
        }
    }
}
