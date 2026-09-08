package com.lodygames.rpgquest.spawn;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.spigotmc.event.player.PlayerSpawnLocationEvent;

/**
 * Traduit les événements Bukkit en appels à {@link SpawnService}. La distinction « nouveau
 * joueur » ↔ « reconnexion » n'est plus faite ici via le seul {@code Player#hasPlayedBefore()}
 * (peu fiable — voir issue #87) : {@link SpawnService#applyJoinSpawnPolicy} délègue à
 * {@link JoinSpawnPolicy}, qui ne redirige vers le spawn du village que si Paper place un
 * nouveau joueur dans le monde principal ou le Hub, jamais quand il restaure déjà le joueur
 * ailleurs (Wild, claims…).
 */
final class SpawnPlayerListener implements Listener {

    private final SpawnService service;

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
    }
}
