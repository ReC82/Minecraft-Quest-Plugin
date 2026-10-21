package com.lodygames.rpgquest.hub;

import com.lodygames.rpgquest.config.HubConfig;
import com.lodygames.rpgquest.item.InventoryTransfers;
import com.lodygames.rpgquest.permission.AdminBypassMode;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Barrel;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.Crafter;
import org.bukkit.block.Dispenser;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.Dropper;
import org.bukkit.block.Hopper;
import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.AbstractVillager;
import org.bukkit.entity.ChestBoat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.minecart.HopperMinecart;
import org.bukkit.entity.minecart.StorageMinecart;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.MerchantInventory;

/**
 * Issue #675 — le Hub n'est pas une source de ressources : dans le monde {@link HubConfig#world()}
 * (règle de monde, jamais une liste d'entités ou de coordonnées), un joueur ne peut ni commercer avec
 * un villageois vanilla, ni prendre ou déposer quoi que ce soit dans un conteneur de stockage vanilla.
 *
 * <ul>
 *   <li><strong>Commerce</strong> : clic droit sur un {@link AbstractVillager} (villageois, marchand
 *       ambulant) annulé, et toute interface marchande adossée à l'un d'eux refusée à l'ouverture
 *       (filet de sécurité). Un PNJ Citizens n'est jamais concerné — son clic ouvre les dialogues et
 *       marchands RPGQuest — ni une interface marchande créée par le plugin (aucun villageois derrière).</li>
 *   <li><strong>Stockage</strong> : ouverture refusée pour coffres (simples, doubles, piégés),
 *       tonneaux, shulkers, hoppers, distributeurs, droppers, crafters, wagonnets et bateaux à coffre ;
 *       un clic droit sur une bibliothèque sculptée, un pot décoré ou une étagère est annulé.</li>
 *   <li><strong>Automatisation</strong> : aucun hopper/dropper ne déplace d'objet entre inventaires
 *       du Hub et aucun hopper n'aspire d'objet au sol.</li>
 * </ul>
 *
 * <p><strong>Ce qui reste libre</strong> : établis, fours, fumoirs, hauts fourneaux, alambics et
 * autres blocs utilitaires des salles de préparation (#107), coffre de l'Ender (stockage personnel),
 * coffres personnels liés (#105 : {@code storage.VaultAccessListener} annule l'ouverture du bloc et
 * ouvre un inventaire du plugin, non concerné ici) et toutes les interfaces RPGQuest.</p>
 *
 * <p>Verrouiller, jamais vider : annuler l'évènement laisse chaque objet où il est, aucun villageois
 * ni bloc n'est modifié, et la règle étant évaluée à chaque évènement elle persiste d'elle-même après
 * un redémarrage. Wild et Claims ne sont pas concernés. Contournement : nœud
 * {@value #BYPASS_PERMISSION}, pendant une session {@code /rpgadmin bypass on} seulement (#35).</p>
 */
public final class HubResourceLockListener implements Listener {

    static final String BYPASS_PERMISSION = "rpgquest.admin.hub.resources";
    static final String TRADE_REFUSED = "<gray>Le commerce n'est pas disponible dans le Hub.</gray>";
    static final String CONTAINER_REFUSED = "<gray>Les coffres du Hub sont fermés.</gray>";
    static final long MESSAGE_COOLDOWN_MILLIS = 2_000L;

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final Supplier<HubConfig> config;
    private final Predicate<Entity> isCitizensNpc;
    private final AdminBypassMode bypass;
    private final LongSupplier clock;
    private final Map<UUID, Long> lastMessageAt = new ConcurrentHashMap<>();

    public HubResourceLockListener(Supplier<HubConfig> config, Predicate<Entity> isCitizensNpc,
                                   AdminBypassMode bypass) {
        this(config, isCitizensNpc, bypass, System::currentTimeMillis);
    }

    HubResourceLockListener(Supplier<HubConfig> config, Predicate<Entity> isCitizensNpc,
                            AdminBypassMode bypass, LongSupplier clock) {
        this.config = config;
        this.isCitizensNpc = isCitizensNpc;
        this.bypass = bypass;
        this.clock = clock;
    }

    // ---- Commerce vanilla --------------------------------------------------------------------------

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        Entity target = event.getRightClicked();
        if (target instanceof AbstractVillager && isHub(target.getWorld()) && !isCitizensNpc.test(target)
                && !isBypassing(event.getPlayer())) {
            // Les deux mains déclenchent l'évènement : les deux sont annulées, un seul message.
            refuse(event, event.getPlayer(), event.getHand() == EquipmentSlot.HAND ? TRADE_REFUSED : null);
        }
    }

    // ---- Conteneurs et interfaces marchandes --------------------------------------------------------

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player) || !isHub(player.getWorld()) || isBypassing(player)) {
            return;
        }
        Inventory top = event.getInventory();
        if (top instanceof MerchantInventory merchant && merchant.getMerchant() instanceof AbstractVillager villager
                && !isCitizensNpc.test(villager)) {
            refuse(event, player, TRADE_REFUSED);
        } else if (isLockedStorage(top.getHolder())) {
            refuse(event, player, CONTAINER_REFUSED);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onBlockInsert(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || block == null
                || !InventoryTransfers.takesItemsOnClick(block.getType())
                || !isHub(block.getWorld()) || isBypassing(event.getPlayer())) {
            return;
        }
        refuse(event, event.getPlayer(), event.getHand() == EquipmentSlot.HAND ? CONTAINER_REFUSED : null);
    }

    // ---- Automatisation (hoppers, droppers) --------------------------------------------------------

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onInventoryMove(InventoryMoveItemEvent event) {
        if (isInHub(event.getSource()) || isInHub(event.getDestination())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onHopperPickup(InventoryPickupItemEvent event) {
        if (isHub(event.getItem().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastMessageAt.remove(event.getPlayer().getUniqueId());
    }

    // ---- Utilitaires -------------------------------------------------------------------------------

    /** Conteneurs de stockage verrouillés. Les blocs utilitaires (fours, alambics…) restent libres (#107). */
    static boolean isLockedStorage(InventoryHolder holder) {
        return holder instanceof Chest || holder instanceof DoubleChest || holder instanceof Barrel
                || holder instanceof ShulkerBox || holder instanceof Hopper || holder instanceof Dispenser
                || holder instanceof Dropper || holder instanceof Crafter
                || holder instanceof StorageMinecart || holder instanceof HopperMinecart
                || holder instanceof ChestBoat;
    }

    private boolean isInHub(Inventory inventory) {
        Location location = inventory.getLocation();
        return location != null && isHub(location.getWorld());
    }

    private boolean isHub(World world) {
        return world != null && world.getName().equals(config.get().world());
    }

    private boolean isBypassing(Player player) {
        return bypass.allowsExact(player, BYPASS_PERMISSION);
    }

    /** Annule l'évènement ; message éventuel limité à un toutes les {@value #MESSAGE_COOLDOWN_MILLIS} ms. */
    private void refuse(Cancellable event, Player player, String message) {
        event.setCancelled(true);
        if (message == null) {
            return;
        }
        long now = clock.getAsLong();
        Long last = lastMessageAt.get(player.getUniqueId());
        if (last == null || now - last >= MESSAGE_COOLDOWN_MILLIS) {
            lastMessageAt.put(player.getUniqueId(), now);
            player.sendMessage(MM.deserialize(message));
        }
    }
}
