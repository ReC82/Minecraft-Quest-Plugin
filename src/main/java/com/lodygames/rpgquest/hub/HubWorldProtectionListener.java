package com.lodygames.rpgquest.hub;

import com.lodygames.rpgquest.config.HubConfig;
import com.lodygames.rpgquest.permission.BuildPermissionService;
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
 * <p>Casse/pose de bloc : autorisée uniquement au joueur qui peut construire dans ce Hub selon
 * {@link BuildPermissionService} — {@code rpgquest.admin.world}, {@code rpgquest.build.*},
 * {@code rpgquest.build.hub.*} ou {@code rpgquest.build.hub.<id>} (id « 0 » par défaut). Un
 * {@code builder-hub-0} construit donc ici sans être OP, mais {@code rpgquest.build.wild} ou un
 * autre {@code rpgquest.build.hub.<autre>} n'y donne rien (issue #27). Jamais de bypass pour les
 * dégâts, qui n'ont pas de raison de viser un administrateur dans un monde de spawn paisible.</p>
 */
public final class HubWorldProtectionListener implements Listener {

    private final Supplier<HubConfig> config;
    private final BuildPermissionService buildPermissions;

    public HubWorldProtectionListener(Supplier<HubConfig> config, BuildPermissionService buildPermissions) {
        this.config = config;
        this.buildPermissions = buildPermissions;
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
        if (isHub(event.getBlock().getWorld()) && !canBuild(event.getPlayer(), event.getBlock().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (isHub(event.getBlockPlaced().getWorld())
                && !canBuild(event.getPlayer(), event.getBlockPlaced().getWorld())) {
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

    private boolean canBuild(Player player, World world) {
        return player != null && buildPermissions.mayBuild(player, world);
    }
}
