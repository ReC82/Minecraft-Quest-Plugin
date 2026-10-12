package com.lodygames.rpgquest.spawn;

import com.destroystokyo.paper.event.player.PlayerSetSpawnEvent;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.spigotmc.event.player.PlayerSpawnLocationEvent;

/**
 * Traduit les événements Bukkit en appels à {@link SpawnService}. La distinction « nouveau
 * joueur » ↔ « reconnexion » n'est plus faite ici via le seul {@code Player#hasPlayedBefore()}
 * (peu fiable — voir issue #87) : {@link SpawnService#applyJoinSpawnPolicy} délègue à
 * {@link JoinSpawnPolicy}, qui ne redirige vers le spawn du village que si Paper place un
 * nouveau joueur dans le monde principal ou le Hub, jamais quand il restaure déjà le joueur
 * ailleurs (Wild, claims…).
 *
 * <h2>Aucun lit ne fixe la réapparition (issue #220, consigne du 12/10/2026)</h2>
 *
 * <p>Dans le monde des claims comme ailleurs, un lit (ou une ancre de réapparition) ne devient
 * jamais le point de réapparition : la mort suit la politique LodyQuests (retour au village, puis
 * bornes et waypoints). On peut toujours dormir — seul l'enregistrement du point est refusé, et le
 * message vanilla « Point de réapparition défini », qui mentirait, est remplacé par la règle
 * réelle. Un point déjà enregistré avant cette règle est effacé à la connexion et à la
 * réapparition : lit détruit, obstrué, chunk déchargé ou redémarrage n'y changent rien, puisqu'il
 * n'en reste aucun. Les lits restent des meubles.</p>
 */
final class SpawnPlayerListener implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final long NOTICE_INTERVAL_MS = 60_000L;

    private final SpawnService service;
    private final Map<UUID, Long> lastNotice = new ConcurrentHashMap<>();

    SpawnPlayerListener(SpawnService service) {
        this.service = service;
    }

    @SuppressWarnings("removal")
    @EventHandler
    public void onSpawnLocation(PlayerSpawnLocationEvent event) {
        service.applyJoinSpawnPolicy(event);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        service.handleRespawn(event);
        // Un point de lit ou d'ancre hérité ne doit pas servir à la prochaine mort.
        if (event.isBedSpawn() || event.isAnchorSpawn()) {
            forgetRespawnPoint(event.getPlayer());
        }
    }

    /** Issue #220 : le lit (ou l'ancre) n'enregistre jamais de point de réapparition. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSetSpawn(PlayerSetSpawnEvent event) {
        if (event.getCause() != PlayerSetSpawnEvent.Cause.BED
                && event.getCause() != PlayerSetSpawnEvent.Cause.RESPAWN_ANCHOR) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        long now = System.currentTimeMillis();
        Long previous = lastNotice.get(player.getUniqueId());
        if (previous == null || now - previous >= NOTICE_INTERVAL_MS) {
            lastNotice.put(player.getUniqueId(), now);
            player.sendMessage(MM.deserialize(event.getCause() == PlayerSetSpawnEvent.Cause.BED
                    ? "<gray>Tu peux dormir ici, mais un lit ne fixe pas ton point de réapparition : après une "
                            + "mort, tu reviens au village, puis tu repars par les bornes et les waypoints.</gray>"
                    : "<gray>Une ancre ne fixe pas ton point de réapparition : après une mort, tu reviens au "
                            + "village.</gray>"));
        }
    }

    /** Un point enregistré avant la règle (ou par le vanilla d'une ancienne version) est effacé. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        forgetRespawnPoint(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        lastNotice.remove(event.getPlayer().getUniqueId());
    }

    /** Efface le point de réapparition personnel (lit, ancre) — sans rien lire, donc sans charger de chunk. */
    static void forgetRespawnPoint(Player player) {
        player.setRespawnLocation(null, true);
    }
}
