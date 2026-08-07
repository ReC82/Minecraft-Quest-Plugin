package com.lodygames.rpgquest.zone;

import com.lodygames.rpgquest.zone.model.ZoneDefinition;
import com.lodygames.rpgquest.zone.model.ZoneFlags;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;

/**
 * Applique les permissions d'une {@link ZoneDefinition} à chaque événement
 * Bukkit protégé. Vérification bon marché à chaque événement : {@link
 * ZoneRegistry#zonesInWorld} est indexé par monde (une passe par {@code
 * reload()}, pas par événement), donc un événement dans un monde sans zone
 * ne coûte qu'un accès de map vide — jamais de balayage de toutes les
 * zones ni de tous les mondes.
 *
 * <p>Bypass ({@code rpgquest.admin.world}, la même permission que {@code
 * /rpgadmin flatten}) : vérifié sur l'acteur direct de chaque action
 * (joueur qui casse/pose/interagit, ou dégâts PvP), jamais sur la victime —
 * un administrateur peut agir librement dans la zone, mais n'exempte
 * personne d'autre de sa protection.</p>
 */
public final class ZoneProtectionListener implements Listener {

    private static final String BYPASS_PERMISSION = "rpgquest.admin.world";
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final ZoneRegistry registry;
    private final Map<UUID, String> currentZoneByPlayer = new ConcurrentHashMap<>();

    public ZoneProtectionListener(ZoneRegistry registry) {
        this.registry = registry;
    }

    // ---- PvP et dégâts d'explosion ----------------------------------------------------------

    @EventHandler(ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        Optional<ZoneDefinition> zoneOpt = zoneAt(victim.getLocation());
        if (zoneOpt.isEmpty()) {
            return;
        }
        ZoneFlags flags = zoneOpt.get().flags();

        if (event instanceof EntityDamageByEntityEvent byEntity) {
            Player attacker = pvpAttacker(byEntity);
            if (attacker != null && !flags.allowPvp() && !isBypassing(attacker)) {
                event.setCancelled(true);
                return;
            }
        }

        EntityDamageEvent.DamageCause cause = event.getCause();
        boolean explosionCause = cause == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION
                || cause == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION;
        if (explosionCause && !flags.allowExplosions()) {
            event.setCancelled(true);
        }
    }

    private Player pvpAttacker(EntityDamageByEntityEvent event) {
        Entity damager = event.getDamager();
        if (damager instanceof Player player) {
            return player;
        }
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) {
            return shooter;
        }
        return null;
    }

    // ---- Blocs -------------------------------------------------------------------------------

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        applyBlockFlag(event.getBlock().getLocation(), event.getPlayer(), ZoneFlags::allowBlockBreak, event::setCancelled);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        applyBlockFlag(event.getBlock().getLocation(), event.getPlayer(), ZoneFlags::allowBlockPlace, event::setCancelled);
    }

    @EventHandler(ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent event) {
        zoneAt(event.getBlock().getLocation())
                .filter(zone -> !zone.flags().allowFire())
                .ifPresent(zone -> event.setCancelled(true));
    }

    @EventHandler(ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        zoneAt(event.getBlock().getLocation())
                .filter(zone -> !zone.flags().allowFire())
                .ifPresent(zone -> event.setCancelled(true));
    }

    @EventHandler(ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (event.getBucket() != org.bukkit.Material.LAVA_BUCKET) {
            return;
        }
        applyBlockFlag(event.getBlock().getLocation(), event.getPlayer(), ZoneFlags::allowLava, event::setCancelled);
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        zoneAt(event.getLocation())
                .filter(zone -> !zone.flags().allowExplosions())
                .ifPresent(zone -> event.blockList().clear());
        // blockList().clear() plutôt que setCancelled(true) : laisse l'entité (creeper, TNT...) se
        // consumer normalement, seule la destruction de blocs est empêchée — cohérent avec
        // "explosions" comme protection du terrain, pas comme interdiction totale de l'événement.
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        zoneAt(event.getBlock().getLocation())
                .filter(zone -> !zone.flags().allowExplosions())
                .ifPresent(zone -> event.blockList().clear());
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (crossesZoneBorder(event.getBlock().getLocation(), event.getBlocks().stream()
                .map(b -> b.getRelative(event.getDirection()).getLocation()).toList())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (crossesZoneBorder(event.getBlock().getLocation(), event.getBlocks().stream()
                .map(b -> b.getLocation()).toList())) {
            event.setCancelled(true);
        }
    }

    /** {@code true} si le piston et l'un des blocs déplacés ne sont pas tous dans la même zone (ou tous hors zone). */
    private boolean crossesZoneBorder(Location pistonLocation, java.util.List<Location> movedBlockDestinations) {
        Optional<ZoneDefinition> pistonZone = zoneAt(pistonLocation);
        for (Location destination : movedBlockDestinations) {
            Optional<ZoneDefinition> destinationZone = zoneAt(destination);
            boolean sameZone = pistonZone.map(ZoneDefinition::id).equals(destinationZone.map(ZoneDefinition::id));
            if (!sameZone) {
                // Zones différentes (ou l'une en zone et l'autre non) : n'interdit que si l'une des
                // deux zones concernées refuse explicitement les pistons traversant sa frontière.
                boolean pistonForbids = pistonZone.map(z -> !z.flags().allowPistonsAcrossBorder()).orElse(false);
                boolean destinationForbids = destinationZone.map(z -> !z.flags().allowPistonsAcrossBorder()).orElse(false);
                if (pistonForbids || destinationForbids) {
                    return true;
                }
            }
        }
        return false;
    }

    // ---- Spawns naturels -----------------------------------------------------------------------

    @EventHandler(ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.NATURAL) {
            return;
        }
        if (!(event.getEntity() instanceof Monster)) {
            return;
        }
        zoneAt(event.getLocation())
                .filter(zone -> !zone.flags().allowHostileSpawn())
                .ifPresent(zone -> event.setCancelled(true));
    }

    // ---- Interactions (portes, boutons, leviers, conteneurs) ------------------------------------

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        var block = event.getClickedBlock();
        if (block == null) {
            return;
        }
        Optional<ZoneDefinition> zoneOpt = zoneAt(block.getLocation());
        if (zoneOpt.isEmpty() || isBypassing(event.getPlayer())) {
            return;
        }
        ZoneFlags flags = zoneOpt.get().flags();
        String typeName = block.getType().name();

        boolean isDoor = typeName.endsWith("_DOOR") || typeName.endsWith("_TRAPDOOR") || typeName.endsWith("_FENCE_GATE");
        boolean isButton = typeName.endsWith("_BUTTON");
        boolean isLever = typeName.equals("LEVER");
        boolean isContainer = block.getState() instanceof org.bukkit.block.Container;

        if (isDoor && !flags.allowDoors()
                || isButton && !flags.allowButtons()
                || isLever && !flags.allowLevers()
                || isContainer && !flags.allowPublicContainers()) {
            event.setCancelled(true);
        }
    }

    // ---- Affichage entrée/sortie ---------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || (from.getBlockX() == to.getBlockX() && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ())) {
            return; // ne traite que les changements de bloc, jamais chaque micro-mouvement.
        }

        Player player = event.getPlayer();
        String newZoneId = zoneAt(to).map(ZoneDefinition::id).orElse(null);
        String previousZoneId = currentZoneByPlayer.get(player.getUniqueId());
        if (java.util.Objects.equals(newZoneId, previousZoneId)) {
            return;
        }

        if (newZoneId != null) {
            currentZoneByPlayer.put(player.getUniqueId(), newZoneId);
            player.sendActionBar(MM.deserialize(
                    "<green>Vous entrez dans :</green> <white><zone></white>", Placeholder.unparsed("zone", newZoneId)));
        } else {
            currentZoneByPlayer.remove(player.getUniqueId());
            player.sendActionBar(MM.deserialize(
                    "<yellow>Vous quittez :</yellow> <white><zone></white>", Placeholder.unparsed("zone", previousZoneId)));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        currentZoneByPlayer.remove(event.getPlayer().getUniqueId());
    }

    // ---- Utilitaires ---------------------------------------------------------------------------

    private void applyBlockFlag(Location location, Player actor, java.util.function.Predicate<ZoneFlags> allowed,
                                 java.util.function.Consumer<Boolean> cancel) {
        Optional<ZoneDefinition> zoneOpt = zoneAt(location);
        if (zoneOpt.isEmpty() || isBypassing(actor)) {
            return;
        }
        if (!allowed.test(zoneOpt.get().flags())) {
            cancel.accept(true);
        }
    }

    private Optional<ZoneDefinition> zoneAt(Location location) {
        World world = location.getWorld();
        if (world == null) {
            return Optional.empty();
        }
        return registry.zoneAt(world.getName(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    private boolean isBypassing(Player player) {
        return player != null && player.hasPermission(BYPASS_PERMISSION);
    }
}
