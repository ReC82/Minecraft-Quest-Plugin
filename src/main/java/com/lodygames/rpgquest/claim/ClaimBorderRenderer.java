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
 *
 * <p><b>{@link #showBeacon}</b> (mission « retrouver visuellement son claim à distance ») : un
 * second rendu, volontairement indépendant de {@link #show} (états/tâches séparés — les deux ne
 * représentent jamais la même situation, voir {@code claim.DeedClaimListener}) — une colonne de
 * particules du sol du monde jusqu'à sa limite de construction, au centre du claim, bien plus
 * visible de loin/de nuit qu'un périmètre au sol. Même garanties de confidentialité (uniquement le
 * propriétaire) et d'absence de bloc modifié que {@link #show}.</p>
 */
public class ClaimBorderRenderer implements PluginService {

    private static final long DURATION_TICKS = 100L; // ~5 s.
    private static final long PERIOD_TICKS = 10L; // ~0,5 s — assez fréquent pour paraître continu sans spammer.
    private static final double PERIMETER_STEP = 0.5;
    private static final double CORNER_HEIGHT = 3.0;
    private static final double CORNER_STEP = 0.4;
    private static final Particle.DustOptions DUST = new Particle.DustOptions(Color.fromRGB(80, 220, 120), 1.2f);

    private static final long BEACON_DURATION_TICKS = 260L; // ~13 s — dans la fenêtre demandée de 10-15 s.
    private static final long BEACON_PERIOD_TICKS = 5L; // plus fréquent : la colonne paraît pleine et continue.
    private static final double BEACON_VERTICAL_STEP = 1.0; // colonne dense, sans trou visible de loin.
    private static final Particle.DustOptions BEACON_DUST = new Particle.DustOptions(Color.fromRGB(255, 200, 40), 2.0f);

    private final RPGQuestPlugin plugin;
    private final Map<UUID, BukkitTask> activeRenders = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> activeBeacons = new ConcurrentHashMap<>();

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
        for (BukkitTask task : activeBeacons.values()) {
            task.cancel();
        }
        activeBeacons.clear();
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

    /**
     * Démarre (ou redémarre depuis zéro) une colonne de particules au centre du claim {@code claim}
     * pour {@code player}, pendant ~12 s — mission « retrouver visuellement son claim à distance » :
     * repérable de loin/de nuit, contrairement au périmètre au sol de {@link #show}.
     */
    public void showBeacon(Player player, Claim claim) {
        World world = plugin.getServer().getWorld(claim.world());
        if (world == null) {
            return;
        }
        UUID playerId = player.getUniqueId();
        BukkitTask previous = activeBeacons.remove(playerId);
        if (previous != null) {
            previous.cancel();
        }

        double centerX = (claim.minX() + claim.maxX() + 1) / 2.0;
        double centerZ = (claim.minZ() + claim.maxZ() + 1) / 2.0;
        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight();

        long[] elapsedTicks = {0L};
        BukkitTask task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (!player.isOnline()) {
                cancelBeacon(playerId);
                return;
            }
            // Deux particules superposées : DUST colorée (couleur du claim) pour la teinte, END_ROD
            // très lumineuse par-dessus pour la portée visuelle (repérable de loin/de nuit) — le
            // plus proche possible d'un faisceau de balise sans poser le moindre bloc réel.
            for (double y = minY; y <= maxY; y += BEACON_VERTICAL_STEP) {
                player.spawnParticle(Particle.DUST, centerX, y, centerZ, 2, 0.08, 0, 0.08, 0, BEACON_DUST);
                player.spawnParticle(Particle.END_ROD, centerX, y, centerZ, 1, 0, 0, 0, 0);
            }
            elapsedTicks[0] += BEACON_PERIOD_TICKS;
            if (elapsedTicks[0] >= BEACON_DURATION_TICKS) {
                cancelBeacon(playerId);
            }
        }, 0L, BEACON_PERIOD_TICKS);
        activeBeacons.put(playerId, task);
    }

    private void cancelBeacon(UUID playerId) {
        BukkitTask task = activeBeacons.remove(playerId);
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
