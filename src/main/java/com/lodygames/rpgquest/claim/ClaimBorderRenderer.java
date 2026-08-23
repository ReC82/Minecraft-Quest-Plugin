package com.lodygames.rpgquest.claim;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.bootstrap.PluginService;
import com.lodygames.rpgquest.claim.model.Claim;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Color;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

/**
 * Affiche le contour du cuboïde <strong>actif</strong> d'un claim (jamais la réservation, voir
 * {@link ClaimBorderGeometry}) pendant {@value #DURATION_TICKS} ticks (~5 s), par particules, au
 * propriétaire uniquement (mission « visualisation des limites du claim »).
 *
 * <p><b>Visibilité strictement privée</b> : {@link Player#spawnParticle} envoie le paquet
 * uniquement au client de ce joueur précis (contrairement à {@code World#spawnParticle}, utilisé
 * par {@code travel.WorldPortalDebugService} pour un diagnostic volontairement visible de tous) —
 * aucun filtrage par distance nécessaire, aucun visiteur ne reçoit jamais ces particules.</p>
 *
 * <p>Jamais de bloc modifié, jamais d'état persisté : un second appel pour le même joueur annule le
 * rendu en cours et en redémarre un nouveau de {@value #DURATION_TICKS} ticks (pas d'accumulation de
 * tâches). Séparée à dessein de toute détection d'entrée ({@link ClaimBorderEntryListener}) ou de
 * déclenchement volontaire ({@link DeedClaimListener}) — ce renderer ne sait que « dessiner ce claim
 * pour ce joueur pendant un moment », jamais pourquoi.</p>
 */
public class ClaimBorderRenderer implements PluginService {

    private static final long DURATION_TICKS = 100L; // ~5 s.
    private static final long PERIOD_TICKS = 10L; // ~0,5 s — assez fréquent pour paraître continu sans spammer.
    private static final double PERIMETER_STEP = 0.5;
    private static final double CORNER_HEIGHT = 3.0;
    private static final double CORNER_STEP = 0.4;
    private static final Particle.DustOptions DUST = new Particle.DustOptions(Color.fromRGB(80, 220, 120), 1.2f);

    private final RPGQuestPlugin plugin;
    private final Map<UUID, BukkitTask> activeRenders = new ConcurrentHashMap<>();

    public ClaimBorderRenderer(RPGQuestPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void start() {
        // Rien à démarrer : aucune tâche répétée tant qu'aucun rendu n'a été demandé.
    }

    @Override
    public void stop() {
        for (BukkitTask task : activeRenders.values()) {
            task.cancel();
        }
        activeRenders.clear();
    }

    /** Démarre (ou redémarre depuis zéro) l'affichage du contour de {@code claim} pour {@code player}, pendant ~5 s. */
    public void show(Player player, Claim claim) {
        World world = plugin.getServer().getWorld(claim.world());
        if (world == null) {
            return;
        }
        UUID playerId = player.getUniqueId();
        BukkitTask previous = activeRenders.remove(playerId);
        if (previous != null) {
            previous.cancel();
        }

        long[] elapsedTicks = {0L};
        BukkitTask task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (!player.isOnline()) {
                cancel(playerId);
                return;
            }
            renderOnce(player, claim);
            elapsedTicks[0] += PERIOD_TICKS;
            if (elapsedTicks[0] >= DURATION_TICKS) {
                cancel(playerId);
            }
        }, 0L, PERIOD_TICKS);
        activeRenders.put(playerId, task);
    }

    private void cancel(UUID playerId) {
        BukkitTask task = activeRenders.remove(playerId);
        if (task != null) {
            task.cancel();
        }
    }

    private void renderOnce(Player player, Claim claim) {
        // Hauteur dynamique (position actuelle du joueur) plutôt que minY/maxY du claim : un claim
        // peut s'étendre sur toute la hauteur du monde, la frontière doit rester lisible au sol où
        // le joueur se trouve réellement, pas dessinée loin au-dessus/en dessous de lui.
        double y = player.getLocation().getY();
        spawnAt(player, ClaimBorderGeometry.perimeter(claim, y, PERIMETER_STEP));
        spawnAt(player, ClaimBorderGeometry.cornerColumns(claim, y, CORNER_HEIGHT, CORNER_STEP));
    }

    private void spawnAt(Player player, List<ClaimBorderGeometry.Point> points) {
        for (ClaimBorderGeometry.Point point : points) {
            player.spawnParticle(Particle.DUST, point.x(), point.y(), point.z(), 1, 0, 0, 0, 0, DUST);
        }
    }
}
