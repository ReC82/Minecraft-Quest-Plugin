package com.lodygames.rpgquest.claim;

import com.lodygames.rpgquest.claim.model.Claim;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.Animals;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
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
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
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
 * bois, dalles de pression) est réellement configurable par le propriétaire
 * ({@link com.lodygames.rpgquest.claim.model.ClaimFlags#allowPublicRedstone}) ;
 * blocs, conteneurs, animaux, armor stands, explosions et pistons
 * traversant la frontière sont protégés inconditionnellement dès qu'un
 * acteur non membre est en cause.</p>
 *
 * <p>Bypass ({@code rpgquest.admin.world}, même permission que le bypass
 * des zones protégées) : vérifié sur l'acteur direct, jamais sur la
 * victime.</p>
 */
public final class ClaimProtectionListener implements Listener {

    private static final String BYPASS_PERMISSION = "rpgquest.admin.world";

    private final ClaimService claimService;

    public ClaimProtectionListener(ClaimService claimService) {
        this.claimService = claimService;
    }

    // ---- Blocs -------------------------------------------------------------------------------

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        protect(event.getBlock().getLocation(), event.getPlayer(), event::setCancelled);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        protect(event.getBlock().getLocation(), event.getPlayer(), event::setCancelled);
    }

    // ---- Interactions (conteneurs, redstone configurable) -------------------------------------

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null) {
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
        String typeName = block.getType().name();

        if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            boolean isContainer = block.getState() instanceof Container;
            boolean isRedstoneInteractable = typeName.endsWith("_DOOR") || typeName.endsWith("_TRAPDOOR")
                    || typeName.endsWith("_FENCE_GATE") || typeName.endsWith("_BUTTON") || typeName.equals("LEVER");
            if (isContainer || (isRedstoneInteractable && !claim.flags().allowPublicRedstone())) {
                event.setCancelled(true);
            }
        } else if (event.getAction() == Action.PHYSICAL) {
            if (typeName.endsWith("_PRESSURE_PLATE") && !claim.flags().allowPublicRedstone()) {
                event.setCancelled(true);
            }
        }
    }

    // ---- Animaux et armor stands -----------------------------------------------------------

    @EventHandler(ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Animals) && !(event.getEntity() instanceof ArmorStand)) {
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
        return player != null && player.hasPermission(BYPASS_PERMISSION);
    }
}
