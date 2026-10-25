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
import com.lodygames.rpgquest.stats.EventLifecycle;
import com.lodygames.rpgquest.stats.EventReward;
import com.lodygames.rpgquest.stats.EventRules;
import com.lodygames.rpgquest.stats.StatPeriod;
import com.lodygames.rpgquest.stats.StatScope;
import com.lodygames.rpgquest.stats.StatisticsDefinition;
import com.lodygames.rpgquest.stats.StatisticsService;
import com.lodygames.rpgquest.web.push.SnapshotPushClient;
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
import java.util.concurrent.RejectedExecutionException;
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
    /**
     * Plafond des fiches exportées (#54) : tous les profils visibles, pas seulement ceux d'un top 10.
     * Au-delà, les plus fortes XP globales sont gardées (une fiche ≈ 0,2 Ko).
     */
    static final int PROFILE_LIMIT = 1000;
    /**
     * Statistiques persistées publiées sur une fiche (#54), totalisées depuis le début de leur
     * journal ({@code statistics.catalog.metrics[].trackedSince}). Les quêtes terminées viennent de
     * {@code quest_progress} et l'XP de {@code player_skills} : ni l'une ni l'autre n'est recomptée ici.
     */
    static final List<String> PROFILE_METRICS = List.of(StatMetric.ENTITY_KILL, StatMetric.BLOCK_BREAK,
            StatMetric.FISH_CAUGHT, StatMetric.WAYPOINT_DISCOVERED, StatMetric.CITY_DISCOVERED,
            StatMetric.WAYSTONE_DISCOVERED, StatMetric.STORY_COMPLETED);

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
    private volatile java.util.function.Function<String, String> specialMobLabels = id -> id;

    /**
     * Issue #54 — saisons, événements et exclusions de {@code statistics.yml}. Sans eux, seule la
     * portée {@code lifetime} est exportée, sans exclusion.
     */
    public void setStatistics(StatisticsService service, StatisticsRepository repository) {
        this.statisticsService = service;
        this.statisticsRepository = repository;
    }

    /** Issue #734 : nom public d'un mob spécial (classements par type de créature) ; sinon son identifiant. */
    public void setSpecialMobLabels(java.util.function.Function<String, String> labels) {
        this.specialMobLabels = labels;
    }

    /**
     * Issue #55 — choix « /profile web » des joueurs connectés. Sans elle, la liste nominative des
     * connectés n'est jamais exportée (seul le nombre l'est).
     */
    public void setVariables(com.lodygames.rpgquest.database.PlayerVariableRepository variables) {
        this.variables = variables;
    }

    private SnapshotPushClient portalPush;

    /**
     * Issue #421 — envoi de chaque instantané au portail hébergé sur AWS, depuis le fil d'export.
     * Sans client, seul le fichier local est écrit (comportement historique).
     */
    public void setPortalPush(SnapshotPushClient portalPush) {
        this.portalPush = portalPush;
    }

    /** Tests : délais entre les essais d'envoi (ms). */
    void setRetryDelaysMillis(long... delays) {
        this.retryDelaysMillis = delays.clone();
    }

    /** Numérotation des instantanés envoyés au portail (#421) : renvoi = doublon, jamais deux écritures. */
    private final String bootId = com.lodygames.rpgquest.web.push.ServerStatusReporter.newBootId();
    private final java.util.concurrent.atomic.AtomicLong sequence = new java.util.concurrent.atomic.AtomicLong();
    /** Attente avant chaque nouvel essai d'envoi au portail après une panne passagère (ms). */
    private long[] retryDelaysMillis = {30_000L, 120_000L};

    private BukkitTask task;
    private volatile boolean stopping = false;
    /** Au moins un instantané publié (ou en cours) pendant cette exécution du plugin. */
    private volatile boolean published = false;
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
        stopping = true;
        announceShutdown();
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

    /** Un instantané est en cours de collecte, d'écriture ou d'envoi (tests). */
    boolean isWriting() {
        return writeInProgress;
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
        if (stopping || !current.enabled()) {
            return;
        }
        long now = clock.getAsLong();
        // Premier export de l'exécution : immédiat. (« now - Long.MIN_VALUE » déborde en négatif et
        // bloquait tout export planifié — seul triggerNow() en produisait un, #421.)
        boolean due = lastWriteMillis == Long.MIN_VALUE || now - lastWriteMillis >= current.intervalSeconds() * 1000L;
        if (writeInProgress || (!force && !due)) {
            return;
        }
        writeInProgress = true;
        long startNanos = System.nanoTime();

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
        // Issue #734 : catalogue de classements (jour, semaine, mois, saison, dates, depuis toujours).
        CompletableFuture<BoardCatalogExport.Catalog> boardCatalog = questRows.thenCompose(quests ->
                statisticsService == null || statisticsRepository == null
                        ? CompletableFuture.completedFuture(BoardCatalogExport.Catalog.EMPTY)
                        : BoardCatalogExport.gather(statisticsRepository, stats, statisticsService.now(),
                        statisticsService::isArchived, current.profileVisibilityDefault(), quests, specialMobLabels));
        CompletableFuture<Map<SkillType, List<ProgressionRepository.LeaderboardRow>>> leaderboardRows =
                gatherLeaderboards(current, lifetimeExcluded);
        CompletableFuture<ProfileSources> profileSources = gatherProfileSources(current, lifetimeExcluded);
        // Part synchrone de la collecte (compteurs, catalogue, lancement des lectures async) : la
        // seule qui pèse sur le tick du serveur, mesurée pour l'exploitation (#421).
        long mainThreadMillis = (System.nanoTime() - startNanos) / 1_000_000L;
        leaderboardRows
                .thenCombine(playerNames, (leaderboards, names) -> Map.entry(leaderboards, names))
                .thenCombine(questRows, (boardsAndNames, quests) -> Map.entry(boardsAndNames, quests))
                .thenCombine(periods, (gathered, periodBoards) -> Map.entry(gathered, periodBoards))
                .thenCombine(boardCatalog, (gatheredPeriods, boards) -> Map.entry(gatheredPeriods, boards))
                .thenCombine(profileSources, (gatheredBoards, sources) -> {
                    var gatheredPeriods = gatheredBoards.getKey();
                    var boards = gatheredBoards.getValue();
                    var gathered = gatheredPeriods.getKey();
                    var leaderboards = gathered.getKey().getKey();
                    var names = gathered.getKey().getValue();
                    var quests = gathered.getValue();
                    Map<String, Object> snapshot =
                            buildSnapshot(current, playerCount, maxPlayers, names, catalog, leaderboards);
                    Map<String, Object> statistics = statistics(leaderboards, quests, gatheredPeriods.getValue());
                    statistics.put("boards", BoardCatalogExport.boards(boards));
                    statistics.put("catalog", BoardCatalogExport.catalog(boards));
                    snapshot.put("statistics", statistics);
                    snapshot.put("profiles", profiles(sources));
                    snapshot.put("bootId", bootId);
                    snapshot.put("sequence", sequence.incrementAndGet());
                    snapshot.put("export", exportCost(current.intervalSeconds(),
                            (System.nanoTime() - startNanos) / 1_000_000L, mainThreadMillis,
                            ((List<?>) snapshot.get("profiles")).size()));
                    return snapshot;
                })
                .thenAccept(snapshot -> {
                    if (stopping) {
                        writeInProgress = false;
                        return;
                    }
                    try {
                        CompletableFuture.runAsync(() -> publish(current, Json.write(snapshot)), ioExecutor)
                                .whenComplete((ignored, error) -> {
                                    if (error != null) {
                                        logger.warn("Échec de l'écriture du snapshot web.", error);
                                    }
                                    lastWriteMillis = clock.getAsLong();
                                    writeInProgress = false;
                                });
                    } catch (RejectedExecutionException e) {
                        writeInProgress = false; // arrêt du plugin pendant la collecte
                    }
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
    record PeriodBoard(StatPeriod period, String status, Map<String, List<StatisticsRepository.RankedRow>> boards,
                       Long community) {
        PeriodBoard(StatPeriod period, String status, Map<String, List<StatisticsRepository.RankedRow>> boards) {
            this(period, status, boards, null);
        }
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
        // Un brouillon (#57) n'est jamais publié.
        List<CompletableFuture<PeriodBoard>> events = stats.events().stream()
                .filter(period -> period.state() != StatPeriod.State.DRAFT)
                .map(period -> periodBoard(current, stats, period, now)).toList();
        return season.thenCombine(CompletableFuture.allOf(events.toArray(CompletableFuture[]::new)),
                (seasonBoard, ignored) -> new PeriodBoards(seasonBoard,
                        events.stream().map(CompletableFuture::join).toList()));
    }

    /**
     * Une période programmée ou annulée n'a pas de classement ; une période archivée se lit dans son
     * archive figée ; sinon le classement est calculé sur la fenêtre du journal. Un objectif commun
     * (#57) publie aussi le total de tous les participants.
     */
    private CompletableFuture<PeriodBoard> periodBoard(WebExportConfig current, StatisticsDefinition stats,
                                                       StatPeriod period, Instant now) {
        boolean archived = statisticsService.isArchived(period.id());
        EventLifecycle state = EventLifecycle.of(period, now, archived);
        Set<UUID> excluded = stats.excludedFrom(period.id());
        Map<String, List<StatisticsRepository.RankedRow>> boards =
                java.util.Collections.synchronizedMap(new LinkedHashMap<>());
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        java.util.concurrent.atomic.AtomicReference<Long> community = new java.util.concurrent.atomic.AtomicReference<>();
        for (String metricId : period.metrics()) {
            StatMetric metric = StatMetric.find(metricId).orElseThrow();
            boards.put(metricId, List.of());
            if (!state.hasBoard()) {
                continue;
            }
            CompletableFuture<List<StatisticsRepository.RankedRow>> rows = archived
                    ? statisticsRepository.archivedTop(period, metric, current.leaderboardSize(),
                    current.profileVisibilityDefault(), excluded)
                    : statisticsRepository.liveTop(period, metric, current.leaderboardSize(),
                    current.profileVisibilityDefault(), excluded, stats.excludedXpReasons());
            futures.add(rows.thenAccept(list -> boards.put(metricId, list)));
            if (period.rules().communityGoal()) {
                CompletableFuture<Long> total = archived
                        ? statisticsRepository.archivedCommunityTotal(period.id(), metric, excluded)
                        : statisticsRepository.communityTotal(period, metric, excluded, stats.excludedXpReasons());
                futures.add(total.thenAccept(community::set));
            }
        }
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> new PeriodBoard(period, state.name(), new LinkedHashMap<>(boards),
                        period.rules().communityGoal() ? (community.get() == null ? 0L : community.get()) : null));
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
        rules(period, board);
        Map<String, Object> boards = new LinkedHashMap<>();
        for (Map.Entry<String, List<StatisticsRepository.RankedRow>> entry : board.boards().entrySet()) {
            boards.put(entry.getKey(), rankedRows(entry.getValue()));
        }
        period.put("leaderboards", boards);
        return period;
    }

    /**
     * Règles publiques d'un événement (#57) : classement, filtre, objectif commun et récompenses
     * déclaratives (libellés seulement, jamais la clé d'avantage interne).
     */
    private static void rules(Map<String, Object> period, PeriodBoard board) {
        EventRules rules = board.period().rules();
        period.put("ranking", rules.ranking().name());
        if (!rules.filter().isEmpty()) {
            Map<String, Object> filter = new java.util.TreeMap<>();
            rules.filter().forEach((key, values) -> filter.put(key, values.stream().sorted().toList()));
            period.put("filter", filter);
        }
        if (rules.communityGoal()) {
            Map<String, Object> community = new LinkedHashMap<>();
            long total = board.community() == null ? 0L : board.community();
            community.put("goal", rules.goal());
            community.put("total", total);
            community.put("reached", total >= rules.goal());
            period.put("community", community);
        }
        if (!rules.rewards().isEmpty()) {
            period.put("rewards", rules.rewards().stream().map(EventReward::describe).toList());
        }
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

    /** Données des fiches (#54) : XP par piste, quêtes terminées et totaux des {@link #PROFILE_METRICS}. */
    record ProfileSources(Map<SkillType, List<ProgressionRepository.LeaderboardRow>> skills,
                          List<com.lodygames.rpgquest.database.QuestStatisticsRepository.Row> quests,
                          Map<String, List<StatisticsRepository.RankedRow>> metrics) {
        static final ProfileSources NONE = new ProfileSources(Map.of(), List.of(), Map.of());
    }

    /**
     * Mêmes sources que les classements, mais pour tous les profils visibles (#54, {@link #PROFILE_LIMIT}) :
     * lectures asynchrones, aucune copie ni compteur parallèle.
     */
    private CompletableFuture<ProfileSources> gatherProfileSources(WebExportConfig current, Set<UUID> excluded) {
        PublicProfilePolicy.Visibility visibility = current.profileVisibilityDefault();
        Map<SkillType, List<ProgressionRepository.LeaderboardRow>> skills =
                java.util.Collections.synchronizedMap(new EnumMap<>(SkillType.class));
        Map<String, List<StatisticsRepository.RankedRow>> metrics =
                java.util.Collections.synchronizedMap(new LinkedHashMap<>());
        List<CompletableFuture<?>> futures = new ArrayList<>();
        for (SkillType skill : current.leaderboardSkills()) {
            futures.add(progressionRepository.topPlayers(skill, PROFILE_LIMIT, visibility, excluded)
                    .thenAccept(rows -> skills.put(skill, rows)));
        }
        CompletableFuture<List<com.lodygames.rpgquest.database.QuestStatisticsRepository.Row>> quests =
                questStatistics == null ? CompletableFuture.completedFuture(List.of())
                        : questStatistics.topCompleted(PROFILE_LIMIT, visibility, excluded);
        futures.add(quests);
        if (statisticsRepository != null) {
            for (String id : PROFILE_METRICS) {
                futures.add(statisticsRepository.lifetimeTotals(StatMetric.find(id).orElseThrow(), PROFILE_LIMIT,
                        visibility, excluded).thenAccept(rows -> metrics.put(id, rows)));
            }
        }
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> new ProfileSources(new EnumMap<>(skills), quests.join(), orderedMetrics(metrics)));
    }

    private static Map<String, List<StatisticsRepository.RankedRow>> orderedMetrics(
            Map<String, List<StatisticsRepository.RankedRow>> metrics) {
        Map<String, List<StatisticsRepository.RankedRow>> ordered = new LinkedHashMap<>();
        for (String id : PROFILE_METRICS) {
            if (metrics.containsKey(id)) {
                ordered.put(id, metrics.get(id));
            }
        }
        return ordered;
    }

    /**
     * Fiches publiques (#54/#56) : chaque joueur au profil visible ayant de l'XP, une quête terminée ou
     * une statistique, avec ses valeurs persistées — rien de plus. Ni UUID, ni position, ni inventaire,
     * ni date de connexion. Triées par pseudo ; au-delà de {@link #PROFILE_LIMIT}, les plus fortes XP
     * globales sont gardées.
     */
    static List<Map<String, Object>> profiles(ProfileSources sources) {
        Map<String, Map<String, Object>> byName = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Map.Entry<SkillType, List<ProgressionRepository.LeaderboardRow>> entry : sources.skills().entrySet()) {
            for (ProgressionRepository.LeaderboardRow row : entry.getValue()) {
                section(profile(byName, row.name()), "skills").put(entry.getKey().name(), row.totalXp());
            }
        }
        for (var row : sources.quests()) {
            profile(byName, row.name()).put("questsCompleted", row.completed());
        }
        for (Map.Entry<String, List<StatisticsRepository.RankedRow>> entry : sources.metrics().entrySet()) {
            for (StatisticsRepository.RankedRow row : entry.getValue()) {
                section(profile(byName, row.name()), "stats").put(entry.getKey(), row.value());
            }
        }
        List<Map<String, Object>> result = new ArrayList<>(byName.values());
        if (result.size() > PROFILE_LIMIT) {
            result.sort(java.util.Comparator.comparingLong(WebSnapshotWriter::globalXp).reversed());
            result = new ArrayList<>(result.subList(0, PROFILE_LIMIT));
            result.sort(java.util.Comparator.comparing(p -> String.valueOf(p.get("name")), String.CASE_INSENSITIVE_ORDER));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(Map<String, Object> profile, String key) {
        return (Map<String, Object>) profile.computeIfAbsent(key, k -> new LinkedHashMap<String, Object>());
    }

    private static long globalXp(Map<String, Object> profile) {
        return profile.get("skills") instanceof Map<?, ?> skills && skills.get(SkillType.GLOBAL.name()) instanceof Number xp
                ? xp.longValue() : 0L;
    }

    private static Map<String, Object> profile(Map<String, Map<String, Object>> byName, String name) {
        return byName.computeIfAbsent(name, n -> {
            Map<String, Object> profile = new LinkedHashMap<>();
            profile.put("name", n);
            return profile;
        });
    }

    /** Coût de la collecte, publié avec l'instantané (lu dans {@code /health} du portail). */
    static Map<String, Object> exportCost(int intervalSeconds, long collectMillis, long mainThreadMillis, int profiles) {
        Map<String, Object> export = new LinkedHashMap<>();
        export.put("intervalSeconds", intervalSeconds);
        export.put("collectMs", collectMillis);
        export.put("mainThreadMs", mainThreadMillis);
        export.put("profiles", profiles);
        return export;
    }

    /** Fil d'export uniquement : fichier local, puis portail (#421) — un envoi raté ne bloque rien. */
    private void publish(WebExportConfig current, String json) {
        published = true;
        writeAtomic(current, json);
        SnapshotPushClient push = portalPush;
        if (push != null) {
            pushWithRetries(push, json);
        }
    }

    /**
     * Envoi au portail avec quelques nouveaux essais si le portail est injoignable ou répond une
     * erreur passagère. Le corps renvoyé est identique (même {@code bootId}/{@code sequence}) : si
     * le premier essai était arrivé, le portail répond « doublon » sans rien réécrire. Un refus
     * définitif (jeton, format) n'est pas réessayé. Les essais occupent le fil d'export, ce qui
     * empêche un nouvel instantané de partir en parallèle.
     */
    SnapshotPushClient.Outcome pushWithRetries(SnapshotPushClient push, String json) {
        long started = System.nanoTime();
        SnapshotPushClient.Outcome outcome = push.push(json);
        int attempt = 1;
        while (outcome == SnapshotPushClient.Outcome.UNAVAILABLE && attempt <= retryDelaysMillis.length && !stopping) {
            try {
                Thread.sleep(retryDelaysMillis[attempt - 1]);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            attempt++;
            outcome = push.push(json);
        }
        logger.info("Instantané portail : {} octets, envoi {} en {} ms ({} essai(s)).",
                json.getBytes(StandardCharsets.UTF_8).length, outcome, (System.nanoTime() - started) / 1_000_000L,
                attempt);
        return outcome;
    }

    /**
     * Issue #421 — à l'arrêt du plugin, écrit un dernier instantané local {@code server.online = false}.
     * Il n'est pas envoyé au portail : l'arrêt y est annoncé par le relevé d'état
     * ({@code ServerStatusReporter}), et le dernier instantané complet reçu (classements, profils)
     * doit y rester jusqu'au suivant. Rien n'est écrit si aucun instantané ne l'a été pendant cette
     * exécution. {@link #stop()} attend l'écriture au plus quelques secondes.
     */
    private void announceShutdown() {
        WebExportConfig current = config.get();
        if (!current.enabled() || !(published || writeInProgress)) {
            return;
        }
        String json = Json.write(shutdownSnapshot(Bukkit.getMaxPlayers(), Instant.now()));
        try {
            ioExecutor.execute(() -> writeAtomic(current, json));
        } catch (RejectedExecutionException e) {
            // déjà arrêté
        }
    }

    /** Instantané minimal d'arrêt annoncé : aucune donnée de joueur, seulement l'état du serveur. */
    static Map<String, Object> shutdownSnapshot(int maxPlayers, Instant now) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", SCHEMA_VERSION);
        root.put("generatedAt", now.toString());
        Map<String, Object> server = new LinkedHashMap<>();
        server.put("online", false);
        server.put("playerCount", 0);
        server.put("maxPlayers", maxPlayers);
        root.put("server", server);
        root.put("players", List.of());
        root.put("leaderboards", Map.of());
        root.put("catalog", List.of());
        root.put("announcements", List.of());
        return root;
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
