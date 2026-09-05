package com.lodygames.rpgquest.zone;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

/**
 * Clic gauche/droit avec l'outil de sélection ({@link ZoneSelectionService#isWand})
 * pose la position 1/2 utilisée par {@code /rpgadmin zone create}. Annule
 * systématiquement l'événement (empêche de casser/poser un bloc avec
 * l'outil) et ignore la main secondaire, comme les capacités d'objets
 * personnalisés de l'étape 8.
 *
 * <p>{@code ignoreCancelled = false} et priorité {@link EventPriority#HIGHEST} sont volontaires :
 * l'outil doit rester utilisable même si un autre plugin (protection de zone type WorldGuard, etc.)
 * annule l'interaction avant nous. Ce n'est pas un risque de conflit puisque {@link
 * ZoneSelectionService#isWand} n'identifie l'objet que par PDC, jamais par son type — voir le
 * commentaire sur {@link ZoneSelectionService#createWandItem()} pour le cas WorldEdit qui a motivé
 * ce choix.</p>
 */
public final class ZoneWandListener implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final ZoneSelectionService selectionService;

    public ZoneWandListener(ZoneSelectionService selectionService) {
        this.selectionService = selectionService;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (!selectionService.isWand(event.getItem())) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }

        Player player = event.getPlayer();
        if (event.getAction() == Action.LEFT_CLICK_BLOCK) {
            event.setCancelled(true);
            selectionService.setPos1(player.getUniqueId(), block.getLocation());
            notify(player, "1", block);
        } else if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            event.setCancelled(true);
            selectionService.setPos2(player.getUniqueId(), block.getLocation());
            notify(player, "2", block);
        }
    }

    private void notify(Player player, String pointNumber, Block block) {
        player.sendMessage(MM.deserialize(
                "<green>Position <point> définie :</green> <white><x>, <y>, <z></white>",
                Placeholder.unparsed("point", pointNumber),
                Placeholder.unparsed("x", String.valueOf(block.getX())),
                Placeholder.unparsed("y", String.valueOf(block.getY())),
                Placeholder.unparsed("z", String.valueOf(block.getZ()))));
    }
}
