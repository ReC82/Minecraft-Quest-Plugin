package com.lodygames.rpgquest.progression.listener;

import com.lodygames.rpgquest.config.ProgressionConfig;
import com.lodygames.rpgquest.progression.ProgressionService;
import com.lodygames.rpgquest.progression.model.SkillType;
import com.lodygames.rpgquest.zone.ZoneRegistry;
import java.util.function.Supplier;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

/**
 * Accorde de l'XP d'exploration la première fois qu'un joueur entre dans une
 * zone nommée ({@code zone.ZoneRegistry}) — une seule fois par (joueur,
 * zone) pour toujours, garanti par la déduplication de {@code
 * ProgressionService#awardXp} (id d'événement = id de la zone), pas par un
 * état suivi séparément ici. Ne vérifie la zone qu'au changement de bloc
 * (jamais à chaque micro-mouvement dans le même bloc), comme tout listener
 * de {@code PlayerMoveEvent} à coût amorti dans ce projet.
 */
public final class ExplorationXpListener implements Listener {

    private final ProgressionService progression;
    private final ZoneRegistry zoneRegistry;
    private final Supplier<ProgressionConfig> config;

    public ExplorationXpListener(ProgressionService progression, ZoneRegistry zoneRegistry, Supplier<ProgressionConfig> config) {
        this.progression = progression;
        this.zoneRegistry = zoneRegistry;
        this.config = config;
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        ProgressionConfig current = config.get();
        if (event.isCancelled() || current.explorationZoneXp() <= 0) {
            return;
        }
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || sameBlock(from, to)) {
            return;
        }

        zoneRegistry.zoneAt(to.getWorld().getName(), to.getBlockX(), to.getBlockY(), to.getBlockZ())
                .ifPresent(zone -> {
                    Player player = event.getPlayer();
                    String eventId = "zone:" + zone.id();
                    progression.awardXp(player.getUniqueId(), SkillType.EXPLORATION, current.explorationZoneXp(),
                            "zone_discovery", eventId);
                });
    }

    private boolean sameBlock(Location from, Location to) {
        return from.getBlockX() == to.getBlockX() && from.getBlockY() == to.getBlockY() && from.getBlockZ() == to.getBlockZ();
    }
}
