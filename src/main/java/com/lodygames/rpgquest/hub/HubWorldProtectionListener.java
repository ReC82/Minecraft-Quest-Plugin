package com.lodygames.rpgquest.hub;

import com.lodygames.rpgquest.config.HubConfig;
import java.util.function.Supplier;
import org.bukkit.World;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;

/**
 * Applique les protections d'<strong>événement</strong> du Hub (dégâts, casse/pose de bloc, spawn
 * de mob hostile, explosions), scoped par le nom de monde de {@link HubConfig#world()} — jamais un
 * cuboïde comme {@code zone.ZoneProtectionListener} : le Hub est un monde entier dédié, pas une
 * région à l'intérieur d'un monde partagé.
 *
 * <p><strong>Dégâts joueur — règle unique, sans exception</strong> : {@code onEntityDamage}
 * annule tout dégât subi par un joueur dans le Hub, quelle que soit la cause (PvP, mob, chute,
 * explosion, environnement...). « aucun dégât aux joueurs » couvre déjà « pas de PvP » sans
 * distinction de cause à faire — plus simple que le système à flags multiples de {@code ZoneFlags},
 * volontairement, car le Hub n'a besoin que d'une seule politique (contrairement à une zone
 * protégée configurable au cas par cas).</p>
 *
 * <p>Bypass ({@code rpgquest.admin.world}, même permission que {@code zone.ZoneProtectionListener})
 * uniquement pour la casse/pose de bloc (« admins/OP : construction autorisée ») — jamais pour les
 * dégâts, qui n'ont pas de raison de viser un administrateur dans un monde de spawn paisible.</p>
 */
public final class HubWorldProtectionListener implements Listener {

    private static final String BYPASS_PERMISSION = "rpgquest.admin.world";

    private final Supplier<HubConfig> config;

    public HubWorldProtectionListener(Supplier<HubConfig> config) {
        this.config = config;
    }

    // ---- Dégâts aux joueurs (PvP inclus) --------------------------------------------------------

    @EventHandler(ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && isHub(player.getWorld())) {
            event.setCancelled(true);
        }
    }

    // ---- Blocs -----------------------------------------------------------------------------------

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (isHub(event.getBlock().getWorld()) && !isBypassing(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (isHub(event.getBlockPlaced().getWorld()) && !isBypassing(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    // ---- Explosions (protection du terrain — les dégâts aux joueurs sont déjà couverts ci-dessus) ---

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (isHub(event.getLocation().getWorld())) {
            event.blockList().clear();
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (isHub(event.getBlock().getWorld())) {
            event.blockList().clear();
        }
    }

    // ---- Spawns naturels de mobs hostiles ---------------------------------------------------------

    @EventHandler(ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.NATURAL) {
            return;
        }
        if (event.getEntity() instanceof Monster && isHub(event.getEntity().getWorld())) {
            event.setCancelled(true);
        }
    }

    // ---- Utilitaires -------------------------------------------------------------------------------

    private boolean isHub(World world) {
        return world != null && world.getName().equals(config.get().world());
    }

    private boolean isBypassing(Player player) {
        return player != null && player.hasPermission(BYPASS_PERMISSION);
    }
}
