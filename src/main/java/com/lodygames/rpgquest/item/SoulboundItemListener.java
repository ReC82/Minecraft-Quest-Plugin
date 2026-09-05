package com.lodygames.rpgquest.item;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Applique les deux règles anti-perte de {@link SoulboundItemService} à tous les objets soulbound
 * enregistrés :
 * <ul>
 *   <li>{@link PlayerDropItemEvent} : un drop volontaire (touche Q) d'un objet soulbound est annulé,
 *       message bref au joueur.</li>
 *   <li>{@link PlayerDeathEvent} : chaque exemplaire soulbound est retiré des drops et mémorisé,
 *       puis rendu tel quel (même méta/PDC) à {@link PlayerRespawnEvent}. Jamais de duplication :
 *       seuls les exemplaires réellement retirés sont rendus, et uniquement à la réapparition qui
 *       suit cette mort précise.</li>
 * </ul>
 */
final class SoulboundItemListener implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final SoulboundItemService service;
    private final Map<UUID, List<ItemStack>> pendingRestoration = new ConcurrentHashMap<>();

    SoulboundItemListener(SoulboundItemService service) {
        this.service = service;
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (!service.isSoulbound(event.getItemDrop().getItemStack())) {
            return;
        }
        event.setCancelled(true);
        event.getPlayer().sendMessage(MM.deserialize("<red>Cet objet est lié à toi : il ne peut pas être jeté.</red>"));
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        List<ItemStack> removed = new ArrayList<>();
        for (Iterator<ItemStack> it = event.getDrops().iterator(); it.hasNext(); ) {
            ItemStack drop = it.next();
            if (service.isSoulbound(drop)) {
                removed.add(drop.clone());
                it.remove();
            }
        }
        if (!removed.isEmpty()) {
            pendingRestoration.put(event.getEntity().getUniqueId(), removed);
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        List<ItemStack> toRestore = pendingRestoration.remove(player.getUniqueId());
        if (toRestore == null) {
            return;
        }
        for (ItemStack stack : toRestore) {
            player.getInventory().addItem(stack);
        }
    }
}
