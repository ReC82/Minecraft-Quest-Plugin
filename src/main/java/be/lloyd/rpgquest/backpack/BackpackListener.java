package be.lloyd.rpgquest.backpack;

import java.util.Map;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Traduit les événements Bukkit en appels à {@link BackpackService}. À la
 * différence de {@code economy.market.MarketListener} (vitrine en lecture
 * seule, tout clic annulé sans condition), un backpack est un conteneur en
 * lecture-écriture : seuls les mouvements qui feraient entrer un objet
 * interdit ({@link BackpackService#isForbidden}) dans le backpack sont
 * annulés, jamais les clics légitimes.
 *
 * <p>{@link #incomingItem} ne couvre que les vecteurs classiques (pose
 * directe, échange avec le curseur, shift-clic, échange de barre de
 * raccourcis) — l'édition du contenu interne d'un objet type paquet
 * (bundle) légitimement présent dans le backpack n'est pas auditée
 * (simplification documentée dans {@code docs/BACKPACKS.md}).</p>
 */
final class BackpackListener implements Listener {

    private final BackpackService service;

    BackpackListener(BackpackService service) {
        this.service = service;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (!service.isOpenItem(event.getItem())) {
            return;
        }
        event.setCancelled(true);
        service.open(event.getPlayer());
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof BackpackInventoryHolder)) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        ItemStack incoming = incomingItem(event, top, player);
        if (incoming != null && service.isForbidden(incoming)) {
            event.setCancelled(true);
        }
    }

    private ItemStack incomingItem(InventoryClickEvent event, Inventory top, Player player) {
        InventoryAction action = event.getAction();
        Inventory clicked = event.getClickedInventory();
        return switch (action) {
            case PLACE_ALL, PLACE_SOME, PLACE_ONE, SWAP_WITH_CURSOR -> clicked == top ? event.getCursor() : null;
            case MOVE_TO_OTHER_INVENTORY -> clicked != null && clicked != top ? event.getCurrentItem() : null;
            case HOTBAR_SWAP, HOTBAR_MOVE_AND_READD -> {
                if (clicked != top || event.getHotbarButton() < 0) {
                    yield null;
                }
                yield player.getInventory().getItem(event.getHotbarButton());
            }
            default -> null;
        };
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof BackpackInventoryHolder)) {
            return;
        }
        int topSize = top.getSize();
        for (Map.Entry<Integer, ItemStack> entry : event.getNewItems().entrySet()) {
            if (entry.getKey() < topSize && service.isForbidden(entry.getValue())) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof BackpackInventoryHolder
                && event.getPlayer() instanceof Player player) {
            service.handleClose(player.getUniqueId());
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        service.handleClose(event.getPlayer().getUniqueId());
    }
}
