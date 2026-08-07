package be.lloyd.rpgquest.web;

import be.lloyd.rpgquest.bootstrap.PluginService;
import be.lloyd.rpgquest.config.WebExportConfig;
import be.lloyd.rpgquest.database.ProgressionRepository;
import be.lloyd.rpgquest.item.YamlCustomItemRegistry;
import be.lloyd.rpgquest.item.model.CustomItemDefinition;
import be.lloyd.rpgquest.progression.model.SkillType;
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
        List<String> playerNames = current.includeConnectedPlayers()
                ? Bukkit.getOnlinePlayers().stream().map(Player::getName).toList()
                : List.of();
        List<Map<String, Object>> catalog = buildCatalog();

        gatherLeaderboards(current)
                .thenAccept(leaderboards -> {
                    Map<String, Object> snapshot =
                            buildSnapshot(current, playerCount, maxPlayers, playerNames, catalog, leaderboards);
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
            futures.add(progressionRepository.topPlayers(skill, current.leaderboardSize())
                    .thenAccept(rows -> result.put(skill, rows)));
        }
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).thenApply(ignored -> result);
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
