package com.lodygames.rpgquest.item;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Allay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Issue #625 — refuse côté serveur l'entrée d'un objet permanent lié au joueur
 * ({@link SoulboundItemService#isStorageLocked}) dans tout stockage, dans tous les mondes :
 * <ul>
 *   <li>tout inventaire « du haut » d'une vue — coffres (simples, piégés, de l'Ender), tonneaux,
 *       shulkers, wagonnets, animaux de bât, hoppers, fours, établis, inventaires du plugin (coffres
 *       personnels, sacs…) — par clic, shift-click, touche numérique, touche F et glisser-déposer ;
 *       la grille 2×2 de l'inventaire du joueur est aussi refusée (un craft pourrait consommer
 *       l'objet), mais le shift-click interne à l'inventaire du joueur reste libre ;</li>
 *   <li>les bundles (rangement dans/par un bundle, qui pourrait ensuite être stocké) ;</li>
 *   <li>les blocs qui prennent un objet par clic droit (bibliothèque sculptée, pot décoré,
 *       étagères), les cadres, les allays et les porte-armures ;</li>
 *   <li>l'automatisation : hopper/dropper qui déplace l'objet et hopper qui l'aspirerait au sol.</li>
 * </ul>
 * Annuler l'évènement laisse l'objet exactement où il était (jamais retiré, jamais recréé) : aucune
 * perte ni duplication possible. Retirer d'un coffre un exemplaire déjà stocké avant #625 reste
 * permis (récupération manuelle) ; rien n'est scanné, purgé ni déplacé automatiquement.
 */
final class StorageLockedItemListener implements Listener {

    static final String REFUSAL_MESSAGE = "<red>Cet objet doit rester dans ton inventaire.</red>";
    static final long MESSAGE_COOLDOWN_MILLIS = 2_000L;

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final SoulboundItemService service;
    private final LongSupplier clock;
    private final Map<UUID, Long> lastMessageAt = new ConcurrentHashMap<>();

    StorageLockedItemListener(SoulboundItemService service) {
        this(service, System::currentTimeMillis);
    }

    StorageLockedItemListener(SoulboundItemService service, LongSupplier clock) {
        this.service = service;
        this.clock = clock;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (isBundleInsertion(event) || service.isStorageLocked(incomingToStorage(event, player))) {
            refuse(event, player);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || !service.isStorageLocked(event.getOldCursor())) {
            return;
        }
        int topSize = event.getView().getTopInventory().getSize();
        if (event.getRawSlots().stream().anyMatch(slot -> slot < topSize)) {
            refuse(event, player);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInventoryMove(InventoryMoveItemEvent event) {
        if (service.isStorageLocked(event.getItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onHopperPickup(InventoryPickupItemEvent event) {
        if (service.isStorageLocked(event.getItem().getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockInsert(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || block == null || !acceptsItems(block.getType())) {
            return;
        }
        if (service.isStorageLocked(event.getItem())) {
            refuse(event, event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEntityInsert(PlayerInteractEntityEvent event) {
        Entity target = event.getRightClicked();
        if (!(target instanceof ItemFrame) && !(target instanceof Allay)) {
            return;
        }
        if (service.isStorageLocked(event.getPlayer().getInventory().getItem(event.getHand()))) {
            refuse(event, event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent event) {
        if (service.isStorageLocked(event.getPlayerItem())) {
            refuse(event, event.getPlayer());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastMessageAt.remove(event.getPlayer().getUniqueId());
    }

    /**
     * Objet que ce clic rangerait hors de l'inventaire du joueur. Dans la vue de l'inventaire du
     * joueur lui-même (haut = grille 2×2), le shift-click ne fait que passer de la barre rapide au
     * sac : il reste libre.
     */
    private static ItemStack incomingToStorage(InventoryClickEvent event, Player player) {
        Inventory top = event.getView().getTopInventory();
        if (event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY && top.getType() == InventoryType.CRAFTING) {
            return null;
        }
        return InventoryTransfers.incomingToTop(event, player);
    }

    /** Ranger un objet verrouillé dans un bundle (bundle au curseur ou objet au curseur sur un bundle). */
    private boolean isBundleInsertion(InventoryClickEvent event) {
        InventoryAction action = event.getAction();
        boolean intoBundle = action == InventoryAction.PICKUP_ALL_INTO_BUNDLE
                || action == InventoryAction.PICKUP_SOME_INTO_BUNDLE
                || action == InventoryAction.PLACE_ALL_INTO_BUNDLE
                || action == InventoryAction.PLACE_SOME_INTO_BUNDLE;
        return intoBundle && (service.isStorageLocked(event.getCursor()) || service.isStorageLocked(event.getCurrentItem()));
    }

    /** Blocs qui prennent l'objet tenu en main sur un simple clic droit. */
    static boolean acceptsItems(Material type) {
        return type == Material.CHISELED_BOOKSHELF
                || type == Material.DECORATED_POT
                || type.name().endsWith("_SHELF");
    }

    private void refuse(Cancellable event, Player player) {
        event.setCancelled(true);
        long now = clock.getAsLong();
        Long last = lastMessageAt.get(player.getUniqueId());
        if (last == null || now - last >= MESSAGE_COOLDOWN_MILLIS) {
            lastMessageAt.put(player.getUniqueId(), now);
            player.sendMessage(MM.deserialize(REFUSAL_MESSAGE));
        }
    }
}
