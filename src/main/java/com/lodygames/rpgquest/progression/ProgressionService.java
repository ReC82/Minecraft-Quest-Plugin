package com.lodygames.rpgquest.progression;

import com.lodygames.rpgquest.bootstrap.PluginService;
import com.lodygames.rpgquest.config.DisplayMode;
import com.lodygames.rpgquest.config.ProgressionConfig;
import com.lodygames.rpgquest.database.ProgressionRepository;
import com.lodygames.rpgquest.progression.model.AwardOutcome;
import com.lodygames.rpgquest.progression.model.ProgressionCurve;
import com.lodygames.rpgquest.progression.model.SkillType;
import com.lodygames.rpgquest.progression.model.XpGrantResult;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.slf4j.Logger;

/**
 * Possède l'état runtime de la progression RPG : cache en mémoire par joueur
 * (jamais interrogé en base pour un calcul de niveau — voir {@link
 * ProgressionCurve}), octroi d'XP dédupliqué et limité en fréquence, et
 * affichage transitoire (action bar/bossbar, mission point 9).
 *
 * <p>Chaque octroi sur une compétence spécifique (≠ {@code GLOBAL}) mirroir
 * automatiquement une partie de son montant sur {@code GLOBAL} ({@code
 * ProgressionConfig#globalMirrorRatio}) : un appelant n'accorde jamais les
 * deux explicitement (mission point 1).</p>
 */
public final class ProgressionService implements PluginService, Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final long THROTTLE_WINDOW_MILLIS = 60_000L;
    private static final long BOSS_BAR_HOLD_TICKS = 60L; // 3 s

    private final Plugin plugin;
    private final ProgressionRepository repository;
    private final Supplier<ProgressionConfig> config;
    private final Logger logger;
    private final LongSupplier clock;

    private final Map<UUID, Map<SkillType, Long>> cache = new ConcurrentHashMap<>();
    private final Map<UUID, Map<SkillType, ThrottleWindow>> throttles = new ConcurrentHashMap<>();
    private final Map<UUID, BossBar> activeBossBars = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> bossBarHideTasks = new ConcurrentHashMap<>();

    public ProgressionService(Plugin plugin, ProgressionRepository repository, Supplier<ProgressionConfig> config, Logger logger) {
        this(plugin, repository, config, logger, System::currentTimeMillis);
    }

    ProgressionService(Plugin plugin, ProgressionRepository repository, Supplier<ProgressionConfig> config, Logger logger,
                        LongSupplier clock) {
        this.plugin = plugin;
        this.repository = repository;
        this.config = config;
        this.logger = logger;
        this.clock = clock;
    }

    @Override
    public void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public void stop() {
        HandlerList.unregisterAll(this);
        for (var entry : bossBarHideTasks.entrySet()) {
            entry.getValue().cancel();
        }
        for (var entry : activeBossBars.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null) {
                player.hideBossBar(entry.getValue());
            }
        }
        activeBossBars.clear();
        bossBarHideTasks.clear();
        cache.clear();
        throttles.clear();
    }

    /** Recalculée à chaque appel depuis la configuration courante — jamais mise en cache, pour qu'un {@code /rpgquest reload} change la courbe immédiatement. */
    public ProgressionCurve curve() {
        ProgressionConfig current = config.get();
        return new ProgressionCurve(current.baseXp(), current.growthFactor(), current.maxLevel());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        loadForPlayer(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        unloadForPlayer(event.getPlayer().getUniqueId());
    }

    /** Charge l'XP totale par compétence en mémoire (asynchrone, jamais d'accès disque au moment d'une requête de niveau). */
    public CompletableFuture<Void> loadForPlayer(UUID playerId) {
        return repository.findAll(playerId).thenAccept(loaded -> {
            Map<SkillType, Long> copy = new EnumMap<>(SkillType.class);
            copy.putAll(loaded);
            cache.put(playerId, copy);
        });
    }

    public void unloadForPlayer(UUID playerId) {
        cache.remove(playerId);
        throttles.remove(playerId);
    }

    // ---- Lecture ---------------------------------------------------------------------------

    public long totalXp(UUID playerId, SkillType skill) {
        return cache.getOrDefault(playerId, Map.of()).getOrDefault(skill, 0L);
    }

    public int level(UUID playerId, SkillType skill) {
        return curve().levelForTotalXp(totalXp(playerId, skill));
    }

    /** Hook de déblocage générique (mission point 11) : recette/portail/quête/claim peuvent l'appeler directement. */
    public boolean hasLevel(UUID playerId, SkillType skill, int requiredLevel) {
        return level(playerId, skill) >= requiredLevel;
    }

    public Map<SkillType, Long> snapshot(UUID playerId) {
        return Map.copyOf(cache.getOrDefault(playerId, Map.of()));
    }

    // ---- Octroi ------------------------------------------------------------------------------

    /**
     * Accorde {@code amount} XP à {@code skill} pour l'événement {@code eventId} (déduplication
     * par (joueur, compétence, événement) — mission point 5). Refuse silencieusement (sans jamais
     * lever d'exception) un montant négatif ou nul, ou un dépassement de la limite de fréquence
     * (mission point 7, anti-farm par répétition).
     */
    public CompletableFuture<XpGrantResult> awardXp(UUID playerId, SkillType skill, long amount, String reason, String eventId) {
        ProgressionCurve curve = curve();
        long previousTotal = totalXp(playerId, skill);
        int previousLevel = curve.levelForTotalXp(previousTotal);

        if (amount <= 0) {
            return CompletableFuture.completedFuture(new XpGrantResult(
                    skill, AwardOutcome.REJECTED_INVALID_AMOUNT, 0L, previousTotal, previousLevel, previousLevel));
        }
        if (isThrottled(playerId, skill)) {
            return CompletableFuture.completedFuture(new XpGrantResult(
                    skill, AwardOutcome.THROTTLED, 0L, previousTotal, previousLevel, previousLevel));
        }

        return repository.grantXp(playerId, skill, amount, reason, eventId, curve.maxTotalXp())
                .thenCompose(outcome -> {
                    if (!outcome.granted()) {
                        return CompletableFuture.completedFuture(new XpGrantResult(
                                skill, AwardOutcome.DUPLICATE, 0L, previousTotal, previousLevel, previousLevel));
                    }

                    cache.computeIfAbsent(playerId, k -> new EnumMap<>(SkillType.class)).put(skill, outcome.newTotalXp());
                    long actualGranted = outcome.newTotalXp() - previousTotal;
                    int newLevel = curve.levelForTotalXp(outcome.newTotalXp());
                    XpGrantResult result = new XpGrantResult(
                            skill, AwardOutcome.GRANTED, actualGranted, outcome.newTotalXp(), previousLevel, newLevel);

                    display(playerId, result);

                    if (skill != SkillType.GLOBAL && actualGranted > 0) {
                        long mirrored = Math.round(actualGranted * config.get().globalMirrorRatio());
                        if (mirrored > 0) {
                            return awardXp(playerId, SkillType.GLOBAL, mirrored, reason, eventId + "#global")
                                    .thenApply(ignored -> result);
                        }
                    }
                    return CompletableFuture.completedFuture(result);
                })
                .exceptionally(error -> {
                    logger.error("Échec de l'octroi d'XP ({}, {}, eventId={}).", playerId, skill, eventId, error);
                    return new XpGrantResult(skill, AwardOutcome.REJECTED_INVALID_AMOUNT, 0L, previousTotal, previousLevel, previousLevel);
                });
    }

    /** Fixe l'XP totale d'une compétence à une valeur exacte (outil admin de test). Contourne dédup/throttle par conception. */
    public CompletableFuture<Void> setTotalXp(UUID playerId, SkillType skill, long totalXp) {
        return repository.setTotalXp(playerId, skill, totalXp)
                .thenRun(() -> cache.computeIfAbsent(playerId, k -> new EnumMap<>(SkillType.class)).put(skill, totalXp));
    }

    private boolean isThrottled(UUID playerId, SkillType skill) {
        long now = clock.getAsLong();
        ThrottleWindow window = throttles
                .computeIfAbsent(playerId, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(skill, k -> new ThrottleWindow());
        synchronized (window) {
            if (now - window.windowStartMillis >= THROTTLE_WINDOW_MILLIS) {
                window.windowStartMillis = now;
                window.count = 0;
            }
            if (window.count >= config.get().maxGrantsPerMinute()) {
                return true;
            }
            window.count++;
            return false;
        }
    }

    // ---- Affichage -----------------------------------------------------------------------------

    private void display(UUID playerId, XpGrantResult result) {
        DisplayMode displayMode = config.get().displayMode();
        if (displayMode == DisplayMode.OFF) {
            return;
        }
        Player player = Bukkit.getPlayer(playerId);
        if (player == null) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            Component message = result.leveledUp()
                    ? MM.deserialize("<gold><bold>Niveau supérieur !</bold></gold> <white><skill></white> <yellow>niveau <level></yellow>",
                            Placeholder.unparsed("skill", label(result.skill())),
                            Placeholder.unparsed("level", String.valueOf(result.newLevel())))
                    : MM.deserialize("<green>+<amount> XP</green> <gray>(<skill>)</gray>",
                            Placeholder.unparsed("amount", String.valueOf(result.amountGranted())),
                            Placeholder.unparsed("skill", label(result.skill())));

            switch (displayMode) {
                case ACTION_BAR -> player.sendActionBar(message);
                case BOSS_BAR -> showTransientBossBar(player, message, result);
                case OFF -> {
                }
            }
        });
    }

    private void showTransientBossBar(Player player, Component message, XpGrantResult result) {
        ProgressionCurve curve = curve();
        long xpIntoLevel = curve.xpIntoCurrentLevel(result.newTotalXp());
        long xpForNext = curve.xpToNextLevel(result.newLevel());
        float progress = xpForNext <= 0 ? 1.0f : clampProgress((float) xpIntoLevel / (float) xpForNext);

        BossBar bar = activeBossBars.get(player.getUniqueId());
        if (bar == null) {
            bar = BossBar.bossBar(message, progress, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
            activeBossBars.put(player.getUniqueId(), bar);
            player.showBossBar(bar);
        } else {
            bar.name(message);
            bar.progress(progress);
        }

        BukkitTask previousHide = bossBarHideTasks.remove(player.getUniqueId());
        if (previousHide != null) {
            previousHide.cancel();
        }
        BossBar finalBar = bar;
        BukkitTask hideTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            player.hideBossBar(finalBar);
            activeBossBars.remove(player.getUniqueId());
            bossBarHideTasks.remove(player.getUniqueId());
        }, BOSS_BAR_HOLD_TICKS);
        bossBarHideTasks.put(player.getUniqueId(), hideTask);
    }

    private float clampProgress(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }

    private String label(SkillType skill) {
        return switch (skill) {
            case GLOBAL -> "Global";
            case COMBAT -> "Combat";
            case MINING -> "Minage";
            case FARMING -> "Agriculture";
            case FISHING -> "Pêche";
            case EXPLORATION -> "Exploration";
        };
    }

    private static final class ThrottleWindow {
        long windowStartMillis;
        int count;
    }
}
