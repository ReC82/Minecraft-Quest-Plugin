package com.lodygames.rpgquest.permission;

import com.lodygames.rpgquest.bootstrap.PluginService;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Issue #35 — côté serveur du {@link AdminBypassMode} : visibilité et cycle de vie.
 *
 * <ul>
 *   <li><strong>Visible en permanence</strong> : tant qu'une session est ouverte, la barre d'action du
 *       joueur affiche {@link #INDICATOR}, rafraîchie toutes les {@value #PERIOD_TICKS} ticks (une barre
 *       d'action s'efface seule au bout de ~3 s). Aucun message de chat répété.</li>
 *   <li><strong>Fin automatique, toujours annoncée</strong> : durée maximale atteinte, mode retiré
 *       dans PlugAdmin, ou permission perdue → la session est close et le joueur reçoit la raison.</li>
 *   <li><strong>Déconnexion</strong> : session close, jamais restaurée à la reconnexion.</li>
 *   <li><strong>Arrêt / rechargement</strong> : toutes les sessions sont effacées.</li>
 * </ul>
 *
 * <p>La tâche ne parcourt que les sessions ouvertes (quelques administrateurs au plus), jamais tous
 * les joueurs.</p>
 */
public final class AdminBypassService implements PluginService, Listener {

    static final String INDICATOR = "<red><bold>⚠ MODE ADMIN / BYPASS ACTIF</bold></red> <gray>— /rpgadmin bypass off</gray>";
    static final long PERIOD_TICKS = 40L;
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final JavaPlugin plugin;
    private final AdminBypassMode mode;
    private BukkitTask task;

    public AdminBypassService(JavaPlugin plugin, AdminBypassMode mode) {
        this.plugin = plugin;
        this.mode = mode;
    }

    @Override
    public void start() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, PERIOD_TICKS, PERIOD_TICKS);
    }

    @Override
    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        HandlerList.unregisterAll(this);
        mode.clear();
    }

    /** Rafraîchit l'indicateur des sessions valables et clôt (en le disant) celles qui ne le sont plus. */
    void tick() {
        for (UUID id : mode.activePlayers()) {
            Player player = plugin.getServer().getPlayer(id);
            if (player == null) {
                mode.disable(id); // filet : la déconnexion l'a normalement déjà fait.
                continue;
            }
            AdminBypassMode.EndReason reason = mode.endReason(player);
            if (reason == null) {
                player.sendActionBar(MM.deserialize(INDICATOR));
                continue;
            }
            plugin.getSLF4JLogger().info("[admin-bypass] {} : mode bypass DÉSACTIVÉ automatiquement ({})",
                    player.getName(), reason);
            player.sendActionBar(Component.empty());
            player.sendMessage(MM.deserialize(endMessage(reason)));
        }
    }

    static String endMessage(AdminBypassMode.EndReason reason) {
        String why = switch (reason) {
            case EXPIRED -> "durée maximale atteinte";
            case UNAVAILABLE -> "mode retiré dans PlugAdmin > Paramètres généraux";
            case PERMISSION_LOST -> "permission " + AdminBypassMode.PERMISSION + " retirée";
        };
        return "<yellow>Mode bypass administrateur désactivé (" + why + ").</yellow> "
                + "<gray>Les règles de jeu s'appliquent de nouveau à toi.</gray>";
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        if (mode.disable(event.getPlayer().getUniqueId())) {
            plugin.getSLF4JLogger().info("[admin-bypass] {} : mode bypass désactivé (déconnexion)",
                    event.getPlayer().getName());
        }
    }
}
