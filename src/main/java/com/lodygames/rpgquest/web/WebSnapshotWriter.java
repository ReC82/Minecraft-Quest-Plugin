package com.lodygames.rpgquest.web;

import com.lodygames.rpgquest.bootstrap.PluginService;
import com.lodygames.rpgquest.config.WebExportConfig;
import com.lodygames.rpgquest.database.ProgressionRepository;
import com.lodygames.rpgquest.item.YamlCustomItemRegistry;
import com.lodygames.rpgquest.item.model.CustomItemDefinition;
import com.lodygames.rpgquest.player.PublicProfilePolicy;
import com.lodygames.rpgquest.progression.model.SkillType;
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
     * sont inchangées : un portail plus ancien continue de fonctionner.
     */
    static final int SCHEMA_VERSION = 2;

    private com.lodygames.rpgquest.database.QuestStatisticsRepository questStatistics;

    /** Issue #54 — dépendance facultative : sans elle, la statistique de quêtes est simplement absente. */
    public void setQuestStatistics(com.lodygames.rpgquest.database.QuestStatisticsRepository questStatistics) {
        this.questStatistics = questStatistics;
    }

    private com.lodygames.rpgquest.database.PlayerVariableRepository variables;

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

        CompletableFuture<List<com.lodygames.rpgquest.database.QuestStatisticsRepository.Row>> questRows =
                questStatistics == null ? CompletableFuture.completedFuture(List.of())
                        : questStatistics.topCompleted(current.leaderboardSize(), current.profileVisibilityDefault());
        gatherLeaderboards(current)
                .thenCombine(playerNames, (leaderboards, names) -> Map.entry(leaderboards, names))
                .thenCombine(questRows, (boardsAndNames, quests) -> {
                    var leaderboards = boardsAndNames.getKey();
                    var names = boardsAndNames.getValue();
                    Map<String, Object> snapshot =
                            buildSnapshot(current, playerCount, maxPlayers, names, catalog, leaderboards);
                    snapshot.put("statistics", statistics(leaderboards, quests));
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
            WebExportConfig current) {
        Map<SkillType, List<ProgressionRepository.LeaderboardRow>> result = new EnumMap<>(SkillType.class);
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (SkillType skill : current.leaderboardSkills()) {
            futures.add(progressionRepository.topPlayers(skill, current.leaderboardSize(), current.profileVisibilityDefault())
                    .thenAccept(rows -> result.put(skill, rows)));
        }
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).thenApply(ignored -> result);
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

    /**
     * Statistiques par portée (#54). Aujourd'hui seule {@code lifetime} est alimentée, depuis les
     * sources de vérité existantes (XP des pistes, quêtes terminées) ; {@code season} et
     * {@code events} sont présents et vides, pour que le format ne change pas le jour où ils le
     * seront. Aucune valeur n'est inventée.
     */
    static Map<String, Object> statistics(Map<SkillType, List<ProgressionRepository.LeaderboardRow>> leaderboards,
                                          List<com.lodygames.rpgquest.database.QuestStatisticsRepository.Row> quests) {
        Map<String, Object> lifetime = new LinkedHashMap<>();
        for (Map.Entry<SkillType, List<ProgressionRepository.LeaderboardRow>> entry : leaderboards.entrySet()) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (ProgressionRepository.LeaderboardRow row : entry.getValue()) {
                rows.add(metricRow(row.name(), row.totalXp()));
            }
            lifetime.put("RPG_XP_" + entry.getKey().name(), rows);
        }
        List<Map<String, Object>> questBoard = new ArrayList<>();
        for (var row : quests) {
            questBoard.add(metricRow(row.name(), row.completed()));
        }
        lifetime.put("QUESTS_COMPLETED", questBoard);

        Map<String, Object> statistics = new LinkedHashMap<>();
        statistics.put("scopes", List.of("lifetime"));
        statistics.put("lifetime", lifetime);
        statistics.put("season", null);
        statistics.put("events", List.of());
        return statistics;
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
