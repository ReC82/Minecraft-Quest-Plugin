package com.lodygames.rpgquest.zone;

import com.lodygames.rpgquest.npc.NpcIdentityService;
import com.lodygames.rpgquest.zone.model.ZoneDefinition;
import com.lodygames.rpgquest.zone.model.ZoneFlags;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.jetbrains.annotations.Nullable;

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
 * (joueur qui casse/pose/interagit, ou dégâts PvP/NPC), jamais sur la
 * victime — un administrateur peut agir librement dans la zone, mais
 * n'exempte personne d'autre de sa protection. Les dégâts environnementaux
 * (chute, noyade, faim...) n'ont pas d'« acteur » distinct de la victime :
 * ils s'appliquent donc à tout le monde, admin compris — rien ne justifierait
 * qu'un administrateur prenne des dégâts de chute dans le village alors que
 * personne d'autre n'en prend.</p>
 */
public final class ZoneProtectionListener implements Listener {

    private static final String BYPASS_PERMISSION = "rpgquest.admin.world";
    private static final MiniMessage MM = MiniMessage.miniMessage();

    /** Midi (temps client figé) pour {@code forceDay} — pleine lumière, aucune ombre de lever/coucher. */
    private static final long NOON_TICKS = 6000L;

    /**
     * Causes de dégâts considérées « accidentelles/environnementales » (voir {@code
     * ZoneFlags#allowEnvironmentalDamage}) — délibérément exclues : tout ce qui est déjà couvert par
     * {@code pvp}/{@code allowHostileDamage}/{@code allowExplosions} (ENTITY_ATTACK, PROJECTILE,
     * ENTITY_EXPLOSION...), et les causes trop exotiques pour une place de village (DRAGON_BREATH,
     * SONIC_BOOM, WORLD_BORDER).
     */
    private static final Set<EntityDamageEvent.DamageCause> ENVIRONMENTAL_CAUSES = Set.of(
            EntityDamageEvent.DamageCause.FALL,
            EntityDamageEvent.DamageCause.DROWNING,
            EntityDamageEvent.DamageCause.SUFFOCATION,
            EntityDamageEvent.DamageCause.LAVA,
            EntityDamageEvent.DamageCause.FIRE,
            EntityDamageEvent.DamageCause.FIRE_TICK,
            EntityDamageEvent.DamageCause.HOT_FLOOR,
            EntityDamageEvent.DamageCause.STARVATION,
            EntityDamageEvent.DamageCause.FREEZE,
            EntityDamageEvent.DamageCause.CONTACT,
            EntityDamageEvent.DamageCause.VOID,
            EntityDamageEvent.DamageCause.LIGHTNING,
            EntityDamageEvent.DamageCause.FALLING_BLOCK,
            EntityDamageEvent.DamageCause.CAMPFIRE,
            EntityDamageEvent.DamageCause.CRAMMING,
            EntityDamageEvent.DamageCause.FLY_INTO_WALL);

    private final ZoneRegistry registry;
    private final NpcIdentityService npcIdentityService;
    private final Map<UUID, String> currentZoneByPlayer = new ConcurrentHashMap<>();

    public ZoneProtectionListener(ZoneRegistry registry, NpcIdentityService npcIdentityService) {
        this.registry = registry;
        this.npcIdentityService = npcIdentityService;
    }

    // ---- Dégâts (PvP, mobs hostiles, environnement, explosions, PNJ) -------------------------

    @EventHandler(ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player victim) {
            handlePlayerDamage(event, victim);
            return;
        }
        if (npcIdentityService.isCitizensNpc(event.getEntity())) {
            handleNpcDamage(event);
        }
    }

    private void handlePlayerDamage(EntityDamageEvent event, Player victim) {
        Optional<ZoneDefinition> zoneOpt = zoneAt(victim.getLocation());
        if (zoneOpt.isEmpty()) {
            return;
        }
        ZoneFlags flags = zoneOpt.get().flags();

        if (event instanceof EntityDamageByEntityEvent byEntity) {
            Player attacker = pvpAttacker(byEntity);
            if (attacker != null) {
                if (!flags.allowPvp() && !isBypassing(attacker)) {
                    event.setCancelled(true);
                }
                return; // dégâts PvP déjà tranchés : jamais aussi comptés comme hostiles/environnementaux.
            }
            if (isHostileAttacker(byEntity) && !flags.allowHostileDamage()) {
                event.setCancelled(true);
                return;
            }
        }

        EntityDamageEvent.DamageCause cause = event.getCause();
        boolean explosionCause = cause == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION
                || cause == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION;
        if (explosionCause) {
            if (!flags.allowExplosions()) {
                event.setCancelled(true);
            }
            return;
        }

        if (!flags.allowEnvironmentalDamage() && ENVIRONMENTAL_CAUSES.contains(cause)) {
            event.setCancelled(true);
        }
    }

    /** PNJ Citizens : protégé de tout dégât dans une zone qui l'interdit, sauf acteur direct exempté. */
    private void handleNpcDamage(EntityDamageEvent event) {
        Optional<ZoneDefinition> zoneOpt = zoneAt(event.getEntity().getLocation());
        if (zoneOpt.isEmpty() || zoneOpt.get().flags().allowNpcDamage()) {
            return;
        }
        if (event instanceof EntityDamageByEntityEvent byEntity) {
            Player attacker = pvpAttacker(byEntity);
            if (attacker != null && isBypassing(attacker)) {
                return;
            }
        }
        event.setCancelled(true);
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

    private boolean isHostileAttacker(EntityDamageByEntityEvent event) {
        Entity damager = event.getDamager();
        if (damager instanceof Monster) {
            return true;
        }
        return damager instanceof Projectile projectile && projectile.getShooter() instanceof Monster;
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

    // ---- Affichage entrée/sortie + jour figé (forceDay) ------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Optional<ZoneDefinition> zoneOpt = zoneAt(player.getLocation());
        zoneOpt.ifPresent(zone -> currentZoneByPlayer.put(player.getUniqueId(), zone.id()));
        applyDayOverride(player, zoneOpt.orElse(null));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || (from.getBlockX() == to.getBlockX() && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ())) {
            return; // ne traite que les changements de bloc, jamais chaque micro-mouvement.
        }

        Player player = event.getPlayer();
        Optional<ZoneDefinition> newZoneOpt = zoneAt(to);
        String newZoneId = newZoneOpt.map(ZoneDefinition::id).orElse(null);
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
        applyDayOverride(player, newZoneOpt.orElse(null));
    }

    /**
     * Jour figé côté client uniquement ({@code Player#setPlayerTime}), jamais {@code
     * World#setTime} : Minecraft/Paper n'offre aucun mécanisme d'heure différente pour une seule
     * région d'un même monde — l'horloge du monde ({@code World#setTime}) est globale et affecte
     * tous les joueurs de ce monde, où qu'ils soient. Figer l'heure du monde entier casserait le
     * cycle jour/nuit partout uniquement pour un confort visuel local au village — inacceptable
     * (fermes de récolte au jour, spawn de monstres la nuit ailleurs, etc.). Le temps par joueur
     * est en revanche une fonctionnalité Bukkit stable (pas expérimentale) et purement cosmétique
     * (aucun effet sur la simulation du monde, le spawn de mobs restant governé par {@code
     * allowHostileSpawn}) — solution propre, compatible avec l'architecture existante (même
     * mécanisme de suivi de zone que l'affichage d'entrée/sortie ci-dessus).
     */
    private void applyDayOverride(Player player, @Nullable ZoneDefinition zone) {
        if (zone != null && zone.flags().forceDay()) {
            player.setPlayerTime(NOON_TICKS, false);
        } else {
            player.resetPlayerTime();
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
