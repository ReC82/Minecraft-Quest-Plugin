package com.lodygames.rpgquest.spawn;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.spigotmc.event.player.PlayerSpawnLocationEvent;

/**
 * Traduit les événements Bukkit en appels à {@link SpawnService}. {@code
 * Player#hasPlayedBefore()} distingue un tout nouveau joueur (jamais vu par ce serveur) d'une
 * reconnexion normale — seul le premier cas redirige la position d'arrivée vers le spawn
 * configuré ; une reconnexion normale ne déplace jamais un joueur déjà positionné ailleurs.
 */
final class SpawnPlayerListener implements Listener {

    private final SpawnService service;

    SpawnPlayerListener(SpawnService service) {
        this.service = service;
    }

    @SuppressWarnings("removal")
    @EventHandler
    public void onSpawnLocation(PlayerSpawnLocationEvent event) {
        if (!event.getPlayer().hasPlayedBefore()) {
            service.handleFirstJoin(event);
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        service.handleRespawn(event);
    }
}
