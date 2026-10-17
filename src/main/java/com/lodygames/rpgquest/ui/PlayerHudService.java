package com.lodygames.rpgquest.ui;

import com.lodygames.rpgquest.bootstrap.PluginService;
import com.lodygames.rpgquest.config.HudConfig;
import com.lodygames.rpgquest.config.HudMode;
import com.lodygames.rpgquest.economy.EconomyService;
import com.lodygames.rpgquest.economy.WalletChangeListener;
import com.lodygames.rpgquest.feedback.ActionBarFeedback;
import com.lodygames.rpgquest.progression.ProgressionService;
import com.lodygames.rpgquest.progression.model.SkillType;
import io.papermc.paper.scoreboard.numbers.NumberFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.slf4j.Logger;

/**
 * Prototype de HUD pièces / niveau RPG (issue #184) — à comparer en jeu, pas une décision finale.
 *
 * <ul>
 *   <li><b>Panneau latéral</b> ({@link HudMode#SIDEBAR}) : scoreboard natif <em>propre au
 *       joueur</em>, deux lignes (bourse, niveau RPG). Ne touche ni la bossbar de quête suivie ni
 *       l'action bar. N'est posé que si le joueur est encore sur le scoreboard principal : on
 *       n'écrase jamais un affichage installé par un autre plugin.</li>
 *   <li><b>Retour temporaire</b> : chaque mouvement <em>réussi</em> du portefeuille s'ajoute à
 *       l'action bar partagée (« +25 LodyCoins · solde 150 »), sans effacer une progression de
 *       quête récente.</li>
 * </ul>
 *
 * <p>Source unique : le portefeuille ({@link EconomyService#balance}) relu après chaque mouvement,
 * et la piste {@link SkillType#GLOBAL} en mémoire de {@link ProgressionService}. Rien n'est
 * persisté par ce service hormis le choix du joueur, rangé dans son {@code
 * PersistentDataContainer} (aucune table, aucune migration).</p>
 */
public final class PlayerHudService implements PluginService, Listener, WalletChangeListener {

    private static final String OBJECTIVE = "rpgquest_hud";
    private static final String COINS_ENTRY = "coins";
    private static final String LEVEL_ENTRY = "level";

    private final Plugin plugin;
    private final EconomyService economy;
    private final ProgressionService progression;
    private final ActionBarFeedback actionBar;
    private final Supplier<HudConfig> config;
    private final Logger logger;
    private final NamespacedKey modeKey;

    private final Map<UUID, Long> balances = new ConcurrentHashMap<>();
    private final Map<UUID, Scoreboard> boards = new ConcurrentHashMap<>();

    public PlayerHudService(Plugin plugin, EconomyService economy, ProgressionService progression,
                            ActionBarFeedback actionBar, Supplier<HudConfig> config, Logger logger) {
        this.plugin = plugin;
        this.economy = economy;
        this.progression = progression;
        this.actionBar = actionBar;
        this.config = config;
        this.logger = logger;
        this.modeKey = new NamespacedKey(plugin, "hud_mode");
    }

    @Override
    public void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        economy.setChangeListener(this);
        progression.setXpListener(playerId -> runOnMain(() -> refreshLevel(playerId)));
        for (Player player : Bukkit.getOnlinePlayers()) {
            applyMode(player);
        }
    }

    @Override
    public void stop() {
        HandlerList.unregisterAll(this);
        economy.setChangeListener(null);
        progression.setXpListener(null);
        for (UUID playerId : boards.keySet()) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                removeSidebar(player);
            }
        }
        boards.clear();
        balances.clear();
    }

    // ---- Choix du joueur --------------------------------------------------------------------

    /** Mode effectif : choix enregistré du joueur, sinon {@code hud.default-mode}. */
    public HudMode mode(Player player) {
        String stored = player.getPersistentDataContainer().get(modeKey, PersistentDataType.STRING);
        return parse(stored).orElse(config.get().defaultMode());
    }

    /** Enregistre le choix du joueur et l'applique. {@code false} si le panneau n'a pas pu être posé. */
    public boolean setMode(Player player, HudMode mode) {
        player.getPersistentDataContainer().set(modeKey, PersistentDataType.STRING, mode.name());
        return applyMode(player);
    }

    static Optional<HudMode> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(HudMode.valueOf(raw.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private boolean applyMode(Player player) {
        if (mode(player) != HudMode.SIDEBAR) {
            removeSidebar(player);
            return true;
        }
        if (!boards.containsKey(player.getUniqueId()) && !installSidebar(player)) {
            return false;
        }
        refreshBalance(player.getUniqueId());
        renderSidebar(player);
        return true;
    }

    // ---- Événements -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        applyMode(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        boards.remove(playerId);
        balances.remove(playerId);
    }

    @Override
    public void walletChanged(UUID playerId, long delta) {
        economy.balance(playerId).whenComplete((balance, error) -> {
            if (error != null) {
                logger.warn("HUD : relecture du solde impossible pour {}.", playerId, error);
                return;
            }
            runOnMain(() -> {
                Player player = Bukkit.getPlayer(playerId);
                if (player == null) {
                    return;
                }
                balances.put(playerId, balance);
                renderSidebar(player);
                if (config.get().walletFeedback()) {
                    actionBar.info(player, HudText.walletChange(delta, balance));
                }
            });
        });
    }

    // ---- Panneau latéral --------------------------------------------------------------------

    private boolean installSidebar(Player player) {
        if (player.getScoreboard() != Bukkit.getScoreboardManager().getMainScoreboard()) {
            return false; // Un autre plugin occupe déjà le scoreboard de ce joueur.
        }
        Scoreboard board = Bukkit.getScoreboardManager().getNewScoreboard();
        Objective objective = board.registerNewObjective(OBJECTIVE, Criteria.DUMMY, HudText.title());
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        objective.numberFormat(NumberFormat.blank()); // Masque les scores rouges à droite.
        boards.put(player.getUniqueId(), board);
        player.setScoreboard(board);
        return true;
    }

    private void removeSidebar(Player player) {
        Scoreboard board = boards.remove(player.getUniqueId());
        if (board != null && player.getScoreboard() == board) {
            player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        }
    }

    private void refreshBalance(UUID playerId) {
        economy.balance(playerId).whenComplete((balance, error) -> {
            if (error != null) {
                logger.warn("HUD : lecture du solde impossible pour {}.", playerId, error);
                return;
            }
            runOnMain(() -> {
                balances.put(playerId, balance);
                Player player = Bukkit.getPlayer(playerId);
                if (player != null) {
                    renderSidebar(player);
                }
            });
        });
    }

    private void refreshLevel(UUID playerId) {
        Player player = Bukkit.getPlayer(playerId);
        if (player != null) {
            renderSidebar(player);
        }
    }

    private void renderSidebar(Player player) {
        Scoreboard board = boards.get(player.getUniqueId());
        Objective objective = board == null ? null : board.getObjective(OBJECTIVE);
        if (objective == null) {
            return;
        }
        var coins = objective.getScore(COINS_ENTRY);
        coins.setScore(2);
        coins.customName(HudText.coinsLine(balances.get(player.getUniqueId())));
        var level = objective.getScore(LEVEL_ENTRY);
        level.setScore(1);
        level.customName(HudText.levelLine(progression.level(player.getUniqueId(), SkillType.GLOBAL)));
    }

    private void runOnMain(Runnable task) {
        if (!plugin.isEnabled()) {
            return;
        }
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }
}
