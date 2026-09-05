package com.lodygames.rpgquest.travel;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.bootstrap.PluginService;
import com.lodygames.rpgquest.travel.model.WorldPortalDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;
import org.bukkit.scheduler.BukkitTask;
import org.slf4j.Logger;

/**
 * TODO(debug bug TP hub) : outil de diagnostic admin ({@code /rpgadmin worldportal debug
 * show|hide|showall|hideall}) — visualise le contour d'une {@link WorldPortalDefinition} par
 * particules (jamais de modification de bloc, voir la consigne de la mission : « éviter de modifier
 * définitivement les blocs du monde ») plus une étiquette flottante ({@link TextDisplay}, {@code
 * setPersistent(false)} — ne survit jamais à un déchargement de chunk/redémarrage, purement
 * cosmétique, jamais sauvegardée sur disque).
 *
 * <p>État global (pas par joueur) : une fois {@code show} appelé, la zone est visible pour
 * <strong>tous</strong> les joueurs à proximité (comportement natif de {@code
 * World#spawnParticle}, aucun ciblage par joueur nécessaire). Reste utile après la résolution du
 * bug pour tout futur diagnostic de zone — contrairement à {@link TpTraceLogger}, ce n'est pas une
 * instrumentation à retirer.</p>
 */
public final class WorldPortalDebugService implements PluginService {

    private static final long RENDER_PERIOD_TICKS = 10L; // 0,5 s.
    private static final double PARTICLE_STEP = 1.0;
    private static final int MAX_PARTICLES_PER_PORTAL = 400;

    private final RPGQuestPlugin plugin;
    private final WorldPortalRegistry registry;
    private final Logger logger;

    private final Set<String> visiblePortalIds = ConcurrentHashMap.newKeySet();
    private final Map<String, UUID> labelEntities = new ConcurrentHashMap<>();
    private BukkitTask renderTask;

    public WorldPortalDebugService(RPGQuestPlugin plugin, WorldPortalRegistry registry, Logger logger) {
        this.plugin = plugin;
        this.registry = registry;
        this.logger = logger;
    }

    @Override
    public void start() {
        renderTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::renderVisiblePortals, 1L, RENDER_PERIOD_TICKS);
    }

    @Override
    public void stop() {
        if (renderTask != null) {
            renderTask.cancel();
            renderTask = null;
        }
        // Annulation à 100% : toute étiquette encore affichée est retirée, jamais laissée orpheline
        // après un rechargement/arrêt du plugin.
        hideAll();
    }

    public enum ShowOutcome {
        SHOWN, ALREADY_VISIBLE, UNKNOWN_PORTAL
    }

    public ShowOutcome show(String portalId) {
        if (registry.find(portalId).isEmpty()) {
            return ShowOutcome.UNKNOWN_PORTAL;
        }
        return visiblePortalIds.add(portalId) ? ShowOutcome.SHOWN : ShowOutcome.ALREADY_VISIBLE;
    }

    public enum HideOutcome {
        HIDDEN, NOT_VISIBLE
    }

    public HideOutcome hide(String portalId) {
        boolean removed = visiblePortalIds.remove(portalId);
        removeLabel(portalId);
        return removed ? HideOutcome.HIDDEN : HideOutcome.NOT_VISIBLE;
    }

    /** @return le nombre de portails nouvellement rendus visibles (ceux déjà visibles ne comptent pas). */
    public int showAll() {
        int shown = 0;
        for (WorldPortalDefinition portal : registry.portals()) {
            if (visiblePortalIds.add(portal.id())) {
                shown++;
            }
        }
        return shown;
    }

    /** @return le nombre de portails masqués. */
    public int hideAll() {
        int hidden = visiblePortalIds.size();
        for (String portalId : List.copyOf(visiblePortalIds)) {
            visiblePortalIds.remove(portalId);
            removeLabel(portalId);
        }
        return hidden;
    }

    public Set<String> visiblePortalIds() {
        return Set.copyOf(visiblePortalIds);
    }

    private void renderVisiblePortals() {
        for (String portalId : visiblePortalIds) {
            Optional<WorldPortalDefinition> portalOpt = registry.find(portalId);
            if (portalOpt.isEmpty()) {
                // Le portail a été supprimé (/rpgadmin worldportal delete) pendant qu'il était
                // affiché : nettoie plutôt que de continuer à dessiner une zone qui n'existe plus.
                visiblePortalIds.remove(portalId);
                removeLabel(portalId);
                continue;
            }
            WorldPortalDefinition portal = portalOpt.get();
            World world = plugin.getServer().getWorld(portal.world());
            if (world == null) {
                continue; // monde pas (encore) chargé : rien à dessiner ce cycle, réessaiera au suivant.
            }
            // Isolé par portail : une erreur de rendu sur l'un (API d'affichage indisponible,
            // position aberrante...) ne doit jamais empêcher les autres portails visibles de se
            // dessiner au même cycle, ni interrompre la tâche répétée elle-même.
            try {
                renderParticles(world, portal);
                ensureLabel(world, portal);
            } catch (RuntimeException e) {
                logger.warn("Échec du rendu de debug pour le portail simple {} : {}", portalId, e.toString());
            }
        }
    }

    private void renderParticles(World world, WorldPortalDefinition portal) {
        Particle.DustOptions dust = new Particle.DustOptions(colorFor(portal.id()), 1.0f);
        for (WorldPortalDebugGeometry.Point point : WorldPortalDebugGeometry.edgePoints(portal, PARTICLE_STEP, MAX_PARTICLES_PER_PORTAL)) {
            world.spawnParticle(Particle.DUST, point.x(), point.y(), point.z(), 1, 0, 0, 0, 0, dust);
        }
    }

    private void ensureLabel(World world, WorldPortalDefinition portal) {
        UUID existingId = labelEntities.get(portal.id());
        if (existingId != null && plugin.getServer().getEntity(existingId) != null) {
            return; // étiquette déjà présente et vivante, rien à refaire ce cycle.
        }
        WorldPortalDebugGeometry.Point center = WorldPortalDebugGeometry.center(portal);
        TextDisplay label = world.spawn(
                new Location(world, center.x(), center.y() + 0.5, center.z()),
                TextDisplay.class,
                entity -> {
                    entity.text(Component.text(portal.id() + " (" + portal.world() + " -> " + portal.destinationWorld() + ")"
                            + (portal.enabled() ? "" : " [désactivé]")));
                    entity.setBillboard(Display.Billboard.CENTER);
                    entity.setPersistent(false); // purement visuel, jamais sauvegardé sur disque/redémarrage.
                    entity.setGravity(false);
                    entity.setSeeThrough(true);
                });
        labelEntities.put(portal.id(), label.getUniqueId());
    }

    private void removeLabel(String portalId) {
        UUID entityId = labelEntities.remove(portalId);
        if (entityId == null) {
            return;
        }
        var entity = plugin.getServer().getEntity(entityId);
        if (entity != null) {
            entity.remove();
        }
    }

    /** Couleur déterministe dérivée de l'id, pour distinguer visuellement deux portails superposés. */
    private Color colorFor(String portalId) {
        int hash = portalId.hashCode();
        int r = 96 + (Math.abs(hash) % 160);
        int g = 96 + (Math.abs(hash / 7) % 160);
        int b = 96 + (Math.abs(hash / 13) % 160);
        return Color.fromRGB(r, g, b);
    }
}
