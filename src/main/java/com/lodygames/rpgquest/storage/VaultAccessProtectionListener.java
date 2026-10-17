package com.lodygames.rpgquest.storage;

import java.util.function.Predicate;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;

/**
 * Protection des points d'accès aux coffres personnels (issue #105), en plus de celle du Hub —
 * même conception que {@code travel.beacon.TravelBeaconProtectionListener}. Personne, builder ou
 * administrateur compris, ne casse un point lié : il faut d'abord le délier
 * ({@code /rpgadmin vault unbind}), ce qui ne touche jamais au contenu des joueurs. Pistons,
 * explosions, feu et entités (endermen, wither…) ne peuvent ni le détruire ni le déplacer, et on
 * ne peut pas accoler un coffre à un coffre lié pour en faire un coffre double non lié.
 */
final class VaultAccessProtectionListener implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final BlockFace[] HORIZONTAL = {BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST};

    private final Predicate<Block> isAccessPoint;

    VaultAccessProtectionListener(Predicate<Block> isAccessPoint) {
        this.isAccessPoint = isAccessPoint;
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onBreak(BlockBreakEvent event) {
        if (isAccessPoint.test(event.getBlock())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(MM.deserialize(
                    "<red>Ce coffre est un point d'accès aux coffres personnels.</red> <gray>Délie-le d'abord avec "
                            + "/rpgadmin vault unbind (le contenu des joueurs n'est pas touché).</gray>"));
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onPlace(BlockPlaceEvent event) {
        Block placed = event.getBlockPlaced();
        if (isAccessPoint.test(placed) || formsDoubleChestWithAccessPoint(placed)) {
            event.setCancelled(true);
        }
    }

    private boolean formsDoubleChestWithAccessPoint(Block placed) {
        Material type = placed.getType();
        if (type != Material.CHEST && type != Material.TRAPPED_CHEST) {
            return false;
        }
        for (BlockFace face : HORIZONTAL) {
            Block neighbour = placed.getRelative(face);
            if (neighbour.getType() == type && isAccessPoint.test(neighbour)) {
                return true;
            }
        }
        return false;
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(isAccessPoint);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(isAccessPoint);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (event.getBlocks().stream().anyMatch(isAccessPoint)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (event.getBlocks().stream().anyMatch(isAccessPoint)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        if (isAccessPoint.test(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        if (isAccessPoint.test(event.getBlock())) {
            event.setCancelled(true);
        }
    }
}
