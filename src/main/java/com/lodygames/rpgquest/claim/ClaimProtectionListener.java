package com.lodygames.rpgquest.claim;

import com.lodygames.rpgquest.claim.model.Claim;
import com.lodygames.rpgquest.npc.NpcIdentityService;
import com.lodygames.rpgquest.permission.AdminBypassMode;
import com.lodygames.rpgquest.permission.RpgPermissions;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.Animals;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityInteractEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

/**
 * Applique la protection d'un {@link Claim} à chaque événement Bukkit
 * concerné — même conception que {@code zone.ZoneProtectionListener}
 * (vérification bon marché via {@code ClaimService#claimsInWorld}, indexé
 * par monde), mais confiance par UUID (propriétaire + membres) plutôt que
 * par un flag global : {@link Claim#isTrusted} est la seule porte d'entrée,
 * jamais un test sur le pseudo affiché.
 *
 * <p>Seule {@code redstone} (boutons, leviers, portes/trappes/portails en
 * bois, dalles de pression, fils) est réellement configurable par le propriétaire
 * ({@link com.lodygames.rpgquest.claim.model.ClaimFlags#allowPublicRedstone}) ;
 * blocs, conteneurs, seaux, terre labourée, animaux et autres entités
 * (hors joueurs et PNJ RPGQuest/Citizens), cadres, armor stands, explosions
 * et pistons traversant la frontière sont protégés inconditionnellement dès
 * qu'un acteur non membre est en cause (issue #531 pour seaux, piétinement
 * et interactions d'entités).</p>
 *
 * <p>Bypass (mode bypass administrateur activé, issue #35, avec {@code rpgquest.bypass.claim} ou {@code rpgquest.admin.world}, même permission que le bypass
 * des zones protégées) : vérifié sur l'acteur direct, jamais sur la
 * victime.</p>
 */
public final class ClaimProtectionListener implements Listener {

    private static final String BYPASS_PERMISSION = "rpgquest.admin.world";

    private final ClaimService claimService;

    /** Issue #531 : les PNJ RPGQuest / Citizens d'un claim restent utilisables par tous. */
    private final NpcIdentityService npcIdentityService;

    /** Issue #35 : seul chemin d'un contournement — session explicite + nœud dédié, jamais l'OP seul. */
    private final AdminBypassMode bypass;

    public ClaimProtectionListener(ClaimService claimService, NpcIdentityService npcIdentityService,
                                   AdminBypassMode bypass) {
        this.claimService = claimService;
        this.npcIdentityService = npcIdentityService;
        this.bypass = bypass;
    }

    // ---- Blocs -------------------------------------------------------------------------------
    // Issue #91 : LOW, comme toutes les protections de casse/pose, pour annuler AVANT les effets de
    // jeu (XP en HIGHEST, quêtes et positions posées en MONITOR) — voir BlockEventPriorityContractTest.

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onBreak(BlockBreakEvent event) {
        protect(event.getBlock().getLocation(), event.getPlayer(), event::setCancelled);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onPlace(BlockPlaceEvent event) {
        protect(event.getBlock().getLocation(), event.getPlayer(), event::setCancelled);
    }

    // ---- Interactions (conteneurs, redstone configurable, piétinement) ----------------------------

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }
        // Issue #531 : PHYSICAL (dalles, fils, terre labourée) n'a pas de main — getHand() vaut null.
        // Il doit donc être traité AVANT le filtre de main, sans quoi la branche n'est jamais atteinte.
        boolean physical = event.getAction() == Action.PHYSICAL;
        if (!physical && (event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.RIGHT_CLICK_BLOCK)) {
            return;
        }
        Optional<Claim> claimOpt = claimAt(block.getLocation());
        if (claimOpt.isEmpty() || isBypassing(event.getPlayer())) {
            return;
        }
        Claim claim = claimOpt.get();
        if (claim.isTrusted(event.getPlayer().getUniqueId())) {
            return;
        }
        if (physical) {
            if (isProtectedFromStepping(claim, block)) {
                event.setCancelled(true); // silencieux : l'événement se répète à chaque tick passé dessus.
            }
            return;
        }
        String typeName = block.getType().name();
        boolean isContainer = block.getState() instanceof Container;
        boolean isRedstoneInteractable = typeName.endsWith("_DOOR") || typeName.endsWith("_TRAPDOOR")
                || typeName.endsWith("_FENCE_GATE") || typeName.endsWith("_BUTTON") || typeName.equals("LEVER");
        if (isContainer || (isRedstoneInteractable && !claim.flags().allowPublicRedstone())) {
            event.setCancelled(true);
        }
    }

    /**
     * Issue #531 : une entité (cheval, cochon...) chevauchée par un non-membre ne piétine pas la
     * terre labourée et ne déclenche pas la redstone d'un claim. Une entité sans cavalier joueur
     * n'est pas attribuable à un joueur : elle n'est pas concernée (comportement vanilla).
     */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onEntityStep(EntityInteractEvent event) {
        Block block = event.getBlock();
        Optional<Claim> claimOpt = claimAt(block.getLocation());
        if (claimOpt.isEmpty() || !isProtectedFromStepping(claimOpt.get(), block)) {
            return;
        }
        Claim claim = claimOpt.get();
        boolean untrustedRider = event.getEntity().getPassengers().stream()
                .anyMatch(passenger -> passenger instanceof Player rider
                        && !isBypassing(rider) && !claim.isTrusted(rider.getUniqueId()));
        if (untrustedRider) {
            event.setCancelled(true);
        }
    }

    /**
     * Blocs déclenchés en marchant dessus : la terre labourée et les œufs de tortue sont toujours
     * protégés (cultures), les dalles de pression et fils seulement si la redstone n'est pas publique.
     */
    private static boolean isProtectedFromStepping(Claim claim, Block block) {
        Material type = block.getType();
        if (type == Material.FARMLAND || type == Material.TURTLE_EGG) {
            return true;
        }
        String typeName = type.name();
        boolean redstone = typeName.endsWith("_PRESSURE_PLATE") || type == Material.TRIPWIRE;
        return redstone && !claim.flags().allowPublicRedstone();
    }

    // ---- Seaux (issue #531) : ni BlockPlaceEvent ni BlockBreakEvent ne sont émis -----------------

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        protect(event.getBlock().getLocation(), event.getPlayer(), event::setCancelled);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onBucketFill(PlayerBucketFillEvent event) {
        protect(event.getBlock().getLocation(), event.getPlayer(), event::setCancelled);
    }

    // ---- Interactions avec les entités (issue #531) ----------------------------------------------

    /**
     * Traire, tondre, nourrir, mettre en laisse, renommer, monter, échanger, tourner un cadre... :
     * refusé à un non-membre pour toute entité du claim, sauf les joueurs et les PNJ RPGQuest /
     * Citizens (identité PDC ou liaison Citizens, jamais le nom), qui restent utilisables pour les
     * dialogues et les quêtes. Les deux mains sont annulées : laisser passer la main secondaire
     * permettrait la même action avec l'objet tenu dans l'autre main.
     */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        protectEntity(event.getRightClicked(), event.getPlayer(), event::setCancelled);
    }

    /** Événement distinct (liste de handlers propre) : clic précis, notamment sur les supports d'armure. */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onInteractAtEntity(PlayerInteractAtEntityEvent event) {
        protectEntity(event.getRightClicked(), event.getPlayer(), event::setCancelled);
    }

    /** Casser un cadre ou un tableau d'un claim (ni BlockBreakEvent, ni dégât d'entité classique). */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onHangingBreak(HangingBreakByEntityEvent event) {
        Player remover = event.getRemover() instanceof Player player ? player
                : event.getRemover() instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter
                        ? shooter : null;
        if (remover != null) {
            protect(event.getEntity().getLocation(), remover, event::setCancelled);
        }
    }

    private void protectEntity(Entity target, Player actor, Consumer<Boolean> cancel) {
        if (target instanceof Player || isNpc(target)) {
            return;
        }
        protect(target.getLocation(), actor, cancel);
    }

    private boolean isNpc(Entity entity) {
        return npcIdentityService.isCitizensNpc(entity) || npcIdentityService.currentId(entity).isPresent();
    }

    // ---- Animaux et armor stands -----------------------------------------------------------

    @EventHandler(ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        // Hanging : retirer l'objet d'un cadre est un dégât d'entité (issue #531).
        if (!(event.getEntity() instanceof Animals) && !(event.getEntity() instanceof ArmorStand)
                && !(event.getEntity() instanceof Hanging)) {
            return;
        }
        Player attacker = attacker(event);
        if (attacker == null || isBypassing(attacker)) {
            return;
        }
        claimAt(event.getEntity().getLocation())
                .filter(claim -> !claim.isTrusted(attacker.getUniqueId()))
                .ifPresent(claim -> event.setCancelled(true));
    }

    @EventHandler(ignoreCancelled = true)
    public void onArmorStandManipulate(PlayerArmorStandManipulateEvent event) {
        Player player = event.getPlayer();
        if (isBypassing(player)) {
            return;
        }
        claimAt(event.getRightClicked().getLocation())
                .filter(claim -> !claim.isTrusted(player.getUniqueId()))
                .ifPresent(claim -> event.setCancelled(true));
    }

    private Player attacker(EntityDamageByEntityEvent event) {
        Entity damager = event.getDamager();
        if (damager instanceof Player player) {
            return player;
        }
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) {
            return shooter;
        }
        return null;
    }

    // ---- Explosions et pistons ----------------------------------------------------------------

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        claimAt(event.getLocation()).ifPresent(claim -> event.blockList().clear());
        // blockList().clear() plutôt que setCancelled(true) : laisse l'entité (creeper, TNT...) se
        // consumer normalement, seule la destruction de blocs est empêchée — même choix que les zones.
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        claimAt(event.getBlock().getLocation()).ifPresent(claim -> event.blockList().clear());
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (crossesClaimBorder(event.getBlock().getLocation(), event.getBlocks().stream()
                .map(b -> b.getRelative(event.getDirection()).getLocation()).toList())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (crossesClaimBorder(event.getBlock().getLocation(), event.getBlocks().stream().map(Block::getLocation).toList())) {
            event.setCancelled(true);
        }
    }

    /**
     * {@code true} si le piston et l'un des blocs déplacés ne sont pas
     * dans le même claim (ou tous les deux hors claim) — contrairement aux
     * zones (flag configurable), un claim n'a aucun réglage pour cette
     * protection : dès qu'un claim est concerné d'un côté ou de l'autre,
     * le passage est bloqué.
     */
    private boolean crossesClaimBorder(Location pistonLocation, List<Location> movedBlockDestinations) {
        Optional<Claim> pistonClaim = claimAt(pistonLocation);
        for (Location destination : movedBlockDestinations) {
            Optional<Claim> destinationClaim = claimAt(destination);
            boolean sameClaim = pistonClaim.map(Claim::id).equals(destinationClaim.map(Claim::id));
            if (!sameClaim && (pistonClaim.isPresent() || destinationClaim.isPresent())) {
                return true;
            }
        }
        return false;
    }

    // ---- Utilitaires ---------------------------------------------------------------------------

    private void protect(Location location, Player actor, Consumer<Boolean> cancel) {
        if (isBypassing(actor)) {
            return;
        }
        claimAt(location)
                .filter(claim -> !claim.isTrusted(actor.getUniqueId()))
                .ifPresent(claim -> cancel.accept(true));
    }

    private Optional<Claim> claimAt(Location location) {
        World world = location.getWorld();
        if (world == null) {
            return Optional.empty();
        }
        return claimService.claimAt(world.getName(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    private boolean isBypassing(Player player) {
        // Issue #200 : nœud DÉDIÉ au bypass des claims d'autrui. Un builder du Hub ne
        // l'obtient jamais par sa permission de construction.
        return bypass.allows(player, RpgPermissions.BYPASS_CLAIM);
    }
}
