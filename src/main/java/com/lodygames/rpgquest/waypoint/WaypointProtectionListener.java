package com.lodygames.rpgquest.waypoint;

import io.papermc.paper.event.player.PlayerOpenSignEvent;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;

/**
 * Protection MVP des blocs constitutifs d'un waypoint (issue #124) : « casse joueur, explosion,
 * déplacement piston, feu / altération pertinente, remplacement direct » — et, depuis l'issue #654,
 * édition des panneaux de nom (éditeur vanilla, teinture, cire, écriture de texte).
 *
 * <p>Même conception que {@code claim.ClaimProtectionListener} : test bon marché
 * ({@link WaypointService#isProtectedBlock}) sur chaque événement concerné ; bypass explicite
 * {@code rpgquest.admin.world} pour la maintenance. La protection <em>fonctionnelle</em> de
 * proximité (anti-enfermement, dégagement vertical, auto-heal) est hors périmètre — voir #122 —
 * mais l'architecture (service propriétaire d'un ensemble de positions protégées, découplé du
 * rendu) est compatible avec elle.</p>
 */
final class WaypointProtectionListener implements Listener {

    /** Interrogé sur chaque événement : {@code true} si (x, y, z) est un bloc constitutif d'un waypoint. */
    @FunctionalInterface
    interface ProtectedBlockLookup {
        boolean isProtected(String world, int x, int y, int z);
    }

    private final ProtectedBlockLookup lookup;
    private final com.lodygames.rpgquest.travel.TravelMaintenanceMode maintenance;

    WaypointProtectionListener(ProtectedBlockLookup lookup,
                               com.lodygames.rpgquest.travel.TravelMaintenanceMode maintenance) {
        this.lookup = lookup;
        this.maintenance = maintenance;
    }

    private boolean isProtected(Block block) {
        return lookup.isProtected(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
    }

    /**
     * Issue #191 : plus aucun bypass implicite lié au seul statut OP. Il faut la permission dédiée
     * {@code rpgquest.admin.travel.maintenance} (défaut {@code false}) ET une activation volontaire
     * et temporaire via {@code /rpgadmin travel maintenance on}.
     */
    private boolean bypassing(Player player) {
        return maintenance.isActive(player);
    }

    // ---- Casse / remplacement joueur --------------------------------------------------------

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onBreak(BlockBreakEvent event) {
        if (!bypassing(event.getPlayer()) && isProtected(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onPlace(BlockPlaceEvent event) {
        if (!bypassing(event.getPlayer())
                && (isProtected(event.getBlock()) || isProtected(event.getBlockReplacedState().getBlock()))) {
            event.setCancelled(true);
        }
    }

    // ---- Panneaux de nom (issue #654) -------------------------------------------------------
    //
    // Depuis 1.20, un clic droit sur un panneau non ciré ouvre l'éditeur vanilla (recto ou verso
    // selon le côté visé) ; teinture, encre lumineuse et cire passent par la même interaction.
    // Trois barrières indépendantes, sans aucun bypass (ni OP, ni maintenance #191 : l'éditeur
    // standard ne doit jamais être exposé, le nom canonique ne change que par le renommage #134) :
    //  1. le clic droit sur le panneau ne déclenche plus l'usage du bloc → ni GUI, ni teinture/cire ;
    //  2. l'ouverture de l'éditeur (Paper) est refusée quelle qu'en soit la cause ;
    //  3. l'écriture du texte (paquet d'édition, client modifié) est refusée.
    // Seuls les blocs protégés de type panneau sont concernés : le bouton garde son interaction et
    // les panneaux des joueurs hors structure restent éditables.

    @EventHandler(priority = EventPriority.HIGH)
    public void onSignInteract(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && block != null && isProtectedSign(block)) {
            event.setUseInteractedBlock(Event.Result.DENY);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onSignOpen(PlayerOpenSignEvent event) {
        if (isProtected(event.getSign().getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onSignChange(SignChangeEvent event) {
        if (isProtected(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    private boolean isProtectedSign(Block block) {
        return Tag.ALL_SIGNS.isTagged(block.getType()) && isProtected(block);
    }

    // ---- Explosions ------------------------------------------------------------------------

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(this::isProtected);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(this::isProtected);
    }

    // ---- Pistons -------------------------------------------------------------------------

    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        for (Block moved : event.getBlocks()) {
            if (isProtected(moved) || isProtected(moved.getRelative(event.getDirection()))) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        for (Block moved : event.getBlocks()) {
            if (isProtected(moved) || isProtected(moved.getRelative(event.getDirection()))) {
                event.setCancelled(true);
                return;
            }
        }
    }

    // ---- Feu / fluides / gravité / entités ------------------------------------------------

    @EventHandler(ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        if (isProtected(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent event) {
        if (isProtected(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onFluidFlow(BlockFromToEvent event) {
        if (isProtected(event.getToBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        // Chute de sable/gravier, enderman, etc. : ne doit ni retirer ni recouvrir un bloc protégé.
        if (isProtected(event.getBlock())) {
            event.setCancelled(true);
        }
    }
}
