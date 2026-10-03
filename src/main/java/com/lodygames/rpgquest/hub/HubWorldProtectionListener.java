package com.lodygames.rpgquest.hub;

import com.lodygames.rpgquest.config.HubConfig;
import com.lodygames.rpgquest.npc.NpcIdentityService;
import java.util.function.Supplier;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.world.EntitiesLoadEvent;

/**
 * Applique les protections d'<strong>événement</strong> du Hub (dégâts, casse/pose de bloc, spawn
 * de mob hostile, explosions), scoped par le nom de monde de {@link HubConfig#world()} — jamais un
 * cuboïde comme {@code zone.ZoneProtectionListener} : le Hub est un monde entier dédié, pas une
 * région à l'intérieur d'un monde partagé.
 *
 * <p><strong>Dégâts joueur — règle unique, sans exception</strong> : {@code onEntityDamage}
 * annule tout dégât subi par un joueur dans le Hub, quelle que soit la cause (PvP, mob, chute,
 * explosion, environnement...).</p>
 *
 * <p><strong>Dégâts aux entités protégées (issues #30/#31)</strong> : tout dégât <strong>causé par
 * un joueur</strong> (mêlée ou projectile) à une entité vivante non-joueur du Hub est annulé — sauf
 * un PNJ Citizens, jamais concerné (Citizens gère déjà sa propre invulnérabilité). Ceci couvre
 * animaux passifs, mobs décoratifs, villageois et toute autre entité vivante, pas seulement les
 * drops. Bypass {@code rpgquest.admin.hub.combat}, <strong>explicite et distinct</strong> du droit
 * de construire ({@code rpgquest.admin.world}) — jamais accordé implicitement par ce dernier.</p>
 *
 * <p><strong>Spawns de mobs indésirables (issues #121/#155)</strong> : tout spawn (quelle que soit
 * la raison — naturel, génération de chunk, spawner...) d'un mob hostile (tout {@link Monster},
 * {@code ENDERMAN}) ou d'un marchand ambulant/lama de commerce ({@code WANDERING_TRADER}/
 * {@code TRADER_LLAMA}) dans le Hub est annulé à la source. Un nettoyage ciblé des entités déjà
 * présentes (avant l'activation de cette protection) s'applique aux chunks déjà chargés au
 * démarrage ({@link #sweepAlreadyLoaded}) et à chaque nouveau chargement de chunk
 * ({@link #onEntitiesLoad}) — jamais un chargement forcé de tout le monde.</p>
 *
 * <p>Bypass ({@code rpgquest.admin.world}) uniquement pour la casse/pose de bloc — jamais pour les
 * dégâts aux joueurs, qui n'ont pas de raison de viser un administrateur dans un monde de spawn
 * paisible.</p>
 */
public final class HubWorldProtectionListener implements Listener {

    private static final String BUILD_BYPASS_PERMISSION = "rpgquest.admin.world";
    private static final String COMBAT_BYPASS_PERMISSION = "rpgquest.admin.hub.combat";

    private final Supplier<HubConfig> config;
    private final NpcIdentityService npcIdentityService;

    public HubWorldProtectionListener(Supplier<HubConfig> config, NpcIdentityService npcIdentityService) {
        this.config = config;
        this.npcIdentityService = npcIdentityService;
    }

    // ---- Dégâts aux joueurs (PvP inclus) --------------------------------------------------------

    @EventHandler(ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && isHub(player.getWorld())) {
            event.setCancelled(true);
            return;
        }
        // ---- Dégâts aux entités protégées (issues #30/#31) --------------------------------------
        if (!(event.getEntity() instanceof LivingEntity victim) || !isHub(victim.getWorld())) {
            return;
        }
        if (npcIdentityService.isCitizensNpc(victim)) {
            return; // Citizens gère sa propre invulnérabilité, jamais concerné ici.
        }
        // Dégât non causé par un joueur identifiable (environnement, autre mob...) : la protection
        // ne vise que les dégâts provoqués par un joueur — laisser passer.
        Player attacker = resolveAttacker(event);
        if (attacker != null && !attacker.hasPermission(COMBAT_BYPASS_PERMISSION)) {
            event.setCancelled(true);
        }
    }

    /** Joueur à l'origine du dégât (mêlée directe ou projectile tiré par un joueur), sinon {@code null}. */
    private static Player resolveAttacker(EntityDamageEvent event) {
        if (!(event instanceof EntityDamageByEntityEvent byEntity)) {
            return null;
        }
        Entity damager = byEntity.getDamager();
        if (damager instanceof Player player) {
            return player;
        }
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) {
            return shooter;
        }
        return null;
    }

    // ---- Blocs -----------------------------------------------------------------------------------

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (isHub(event.getBlock().getWorld()) && !isBypassingBuild(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (isHub(event.getBlockPlaced().getWorld()) && !isBypassingBuild(event.getPlayer())) {
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

    // ---- Spawns de mobs indésirables (issues #121/#155) -------------------------------------------

    @EventHandler(ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (isHub(event.getEntity().getWorld()) && isUnwanted(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    /**
     * Nettoyage ciblé des entités déjà présentes dans un chunk qui vient de se charger (jamais un
     * chargement forcé de tout le monde) — couvre les entités persistées avant l'activation de
     * cette protection (ex. import Multiverse, génération antérieure).
     */
    @EventHandler(ignoreCancelled = true)
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        if (!isHub(event.getWorld())) {
            return;
        }
        for (Entity entity : event.getEntities()) {
            removeIfUnwanted(entity);
        }
    }

    /** À appeler une fois au démarrage (chunks déjà chargés au moment où le plugin s'active, ex. spawn). */
    public void sweepAlreadyLoaded(World world) {
        if (!isHub(world)) {
            return;
        }
        for (Entity entity : world.getEntities()) {
            removeIfUnwanted(entity);
        }
    }

    private void removeIfUnwanted(Entity entity) {
        if (isUnwanted(entity)) {
            entity.remove();
        }
    }

    private boolean isUnwanted(Entity entity) {
        if (npcIdentityService.isCitizensNpc(entity)) {
            return false; // jamais un PNJ Citizens ni une entité administrée explicitement.
        }
        if (entity instanceof Monster) {
            return true;
        }
        EntityType type = entity.getType();
        return type == EntityType.ENDERMAN || type == EntityType.WANDERING_TRADER || type == EntityType.TRADER_LLAMA;
    }

    // ---- Utilitaires -------------------------------------------------------------------------------

    private boolean isHub(World world) {
        return world != null && world.getName().equals(config.get().world());
    }

    private boolean isBypassingBuild(Player player) {
        return player != null && player.hasPermission(BUILD_BYPASS_PERMISSION);
    }
}
