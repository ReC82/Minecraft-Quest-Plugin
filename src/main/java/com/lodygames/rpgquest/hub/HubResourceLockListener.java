package com.lodygames.rpgquest.hub;

import com.lodygames.rpgquest.config.HubConfig;
import com.lodygames.rpgquest.item.InventoryTransfers;
import com.lodygames.rpgquest.permission.AdminBypassMode;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.bukkit.Location;
import org.bukkit.Material;
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
import org.bukkit.entity.Vehicle;
import org.bukkit.entity.minecart.HopperMinecart;
import org.bukkit.entity.minecart.StorageMinecart;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.CrafterCraftEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.vehicle.VehicleDamageEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
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
 *   <li><strong>Éjection redstone (#939)</strong> : un dropper n'éjecte rien, un crafter ne fabrique
 *       rien ; un distributeur n'est actionné que pour un effet décoratif sans objet ramassable
 *       ({@link #DECORATIVE_DISPENSES} : feux d'artifice, fontaines à eau, boules de neige, boules de
 *       feu). Tout autre objet (flèches, armures, outils, objets ordinaires…) reste dans le bloc.
 *       La redstone n'a pas d'auteur : aucun contournement ne s'applique.</li>
 *   <li><strong>Véhicules à conteneur (#939)</strong> : un wagonnet à coffre, un wagonnet-entonnoir ou
 *       un bateau à coffre du Hub ne subit ni dégât ni destruction (joueur, projectile, explosion,
 *       environnement) : son contenu et le véhicule lui-même ne tombent jamais au sol. Les autres
 *       véhicules (wagonnets et bateaux simples) ne sont pas concernés.</li>
 * </ul>
 *
 * <p><strong>Ce qui reste libre</strong> : coffre de l'Ender (stockage personnel),
 * coffres personnels liés (#105 : {@code storage.VaultAccessListener} annule l'ouverture du bloc et
 * ouvre un inventaire du plugin, non concerné ici) et toutes les interfaces RPGQuest. Les postes de
 * travail (établis, fours, alambics…) sont fermés par défaut par {@link HubStationLockListener}.</p>
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

    /**
     * Seuls objets qu'un distributeur du Hub peut utiliser : effet décoratif, aucun objet ramassable
     * créé. Le seau vide reste autorisé pour les fontaines à bascule (seau d'eau ↔ seau vide).
     */
    static final Set<Material> DECORATIVE_DISPENSES = Set.of(
            Material.FIREWORK_ROCKET, Material.WATER_BUCKET, Material.BUCKET,
            Material.SNOWBALL, Material.FIRE_CHARGE);

    private final Supplier<HubConfig> config;
    private final Predicate<Entity> isCitizensNpc;
    private final AdminBypassMode bypass;
    private final HubRefusalMessages messages;

    public HubResourceLockListener(Supplier<HubConfig> config, Predicate<Entity> isCitizensNpc,
                                   AdminBypassMode bypass) {
        this(config, isCitizensNpc, bypass, new HubRefusalMessages(System::currentTimeMillis));
    }

    HubResourceLockListener(Supplier<HubConfig> config, Predicate<Entity> isCitizensNpc,
                            AdminBypassMode bypass, LongSupplier clock) {
        this(config, isCitizensNpc, bypass, new HubRefusalMessages(clock));
    }

    /** {@code messages} partagé avec {@link HubStationLockListener} : un seul message discret par fenêtre. */
    public HubResourceLockListener(Supplier<HubConfig> config, Predicate<Entity> isCitizensNpc,
                                   AdminBypassMode bypass, HubRefusalMessages messages) {
        this.config = config;
        this.isCitizensNpc = isCitizensNpc;
        this.bypass = bypass;
        this.messages = messages;
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

    // ---- Éjection redstone (#939) ------------------------------------------------------------------

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onDispense(BlockDispenseEvent event) {
        Block block = event.getBlock();
        if (isHub(block.getWorld()) && !isDecorativeDispense(block.getType(), event.getItem().getType())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onCrafterCraft(CrafterCraftEvent event) {
        if (isHub(event.getBlock().getWorld())) {
            event.setCancelled(true);
        }
    }

    /** Un dropper n'a pas d'usage décoratif : il ne fait qu'éjecter. */
    static boolean isDecorativeDispense(Material block, Material item) {
        return block == Material.DISPENSER && DECORATIVE_DISPENSES.contains(item);
    }

    // ---- Véhicules à conteneur (#939) --------------------------------------------------------------

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onVehicleDamage(VehicleDamageEvent event) {
        Player attacker = HubWorldProtectionListener.playerBehind(event.getAttacker());
        if (isProtectedVehicle(event.getVehicle(), attacker)) {
            refuse(event, attacker, CONTAINER_REFUSED);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onVehicleDestroy(VehicleDestroyEvent event) {
        Player attacker = HubWorldProtectionListener.playerBehind(event.getAttacker());
        if (isProtectedVehicle(event.getVehicle(), attacker)) {
            refuse(event, attacker, null);
        }
    }

    /** Véhicule à conteneur du Hub ; sans auteur (explosion, environnement), jamais de contournement. */
    private boolean isProtectedVehicle(Vehicle vehicle, Player attacker) {
        return vehicle instanceof InventoryHolder holder && isLockedStorage(holder) && isHub(vehicle.getWorld())
                && (attacker == null || !isBypassing(attacker));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        messages.forget(event.getPlayer().getUniqueId());
    }

    // ---- Utilitaires -------------------------------------------------------------------------------

    /** Conteneurs de stockage verrouillés, sans exception (les postes de travail : {@link HubStationLockListener}). */
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

    /** Annule l'évènement ; message éventuel (joueur connu seulement), limité par {@link HubRefusalMessages}. */
    private void refuse(Cancellable event, Player player, String message) {
        event.setCancelled(true);
        if (message != null && player != null) {
            messages.send(player, message);
        }
    }
}
