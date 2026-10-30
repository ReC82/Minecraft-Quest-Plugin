package com.lodygames.rpgquest.hub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.config.HubConfig;
import com.lodygames.rpgquest.permission.AdminBypassMode;
import com.lodygames.rpgquest.settings.GameSettings;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Container;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Vehicle;
import org.bukkit.entity.Villager;
import org.bukkit.entity.WanderingTrader;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.CrafterCraftEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.event.vehicle.VehicleDamageEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** Issue #675 : commerce vanilla et conteneurs de stockage verrouillés dans le seul monde Hub. */
class HubResourceLockListenerTest {

    private static final HubConfig HUB_CONFIG = new HubConfig("hub_675");

    private final AdminBypassMode bypass = new AdminBypassMode(() -> GameSettings.DEFAULTS);
    private final AtomicLong clock = new AtomicLong(10_000L);
    private ServerMock server;
    private RPGQuestPlugin plugin;
    private World hub;
    private World wild;
    private World claims;
    private Set<Entity> citizensNpcs;
    private HubResourceLockListener listener;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(RPGQuestPlugin.class);
        hub = server.addSimpleWorld("hub_675");
        wild = server.addSimpleWorld("world_wild");
        claims = server.addSimpleWorld("world_claims");
        citizensNpcs = new java.util.HashSet<>();
        listener = new HubResourceLockListener(() -> HUB_CONFIG, citizensNpcs::contains, bypass, clock::get);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    // ---- Commerce ------------------------------------------------------------------------------------

    @Test
    void tradingWithAHubVillagerOrWanderingTraderIsRefusedWithOneMessage() {
        PlayerMock player = playerIn(hub);
        Villager villager = hub.spawn(new Location(hub, 2, 64, 2), Villager.class);
        WanderingTrader trader = hub.spawn(new Location(hub, 4, 64, 4), WanderingTrader.class);

        PlayerInteractEntityEvent main = new PlayerInteractEntityEvent(player, villager, EquipmentSlot.HAND);
        PlayerInteractEntityEvent off = new PlayerInteractEntityEvent(player, villager, EquipmentSlot.OFF_HAND);
        listener.onInteractEntity(main);
        listener.onInteractEntity(off);
        PlayerInteractEntityEvent wandering = new PlayerInteractEntityEvent(player, trader, EquipmentSlot.HAND);
        listener.onInteractEntity(wandering);

        assertTrue(main.isCancelled());
        assertTrue(off.isCancelled(), "la main secondaire ne doit pas ouvrir le commerce");
        assertTrue(wandering.isCancelled());
        assertTrue(player.nextComponentMessage() != null, "un message discret");
        assertNull(player.nextComponentMessage(), "pas de spam : un seul message par fenêtre de 2 s");
        assertFalse(villager.isDead());
    }

    @Test
    void citizensNpcVillagersAndOtherWorldsAreUntouched() {
        PlayerMock inHub = playerIn(hub);
        Villager npc = hub.spawn(new Location(hub, 2, 64, 2), Villager.class);
        citizensNpcs.add(npc);
        PlayerInteractEntityEvent onNpc = new PlayerInteractEntityEvent(inHub, npc, EquipmentSlot.HAND);
        listener.onInteractEntity(onNpc);
        assertFalse(onNpc.isCancelled(), "PNJ Citizens : dialogues et marchands RPGQuest");

        PlayerMock inWild = playerIn(wild);
        Villager wildVillager = wild.spawn(new Location(wild, 2, 64, 2), Villager.class);
        PlayerInteractEntityEvent onWild = new PlayerInteractEntityEvent(inWild, wildVillager, EquipmentSlot.HAND);
        listener.onInteractEntity(onWild);
        assertFalse(onWild.isCancelled(), "Wild inchangé");
    }

    @Test
    void anAdminInBypassSessionWithTheDedicatedNodeMayTrade() {
        PlayerMock admin = playerIn(hub);
        admin.addAttachment(plugin, HubResourceLockListener.BYPASS_PERMISSION, true);
        Villager villager = hub.spawn(new Location(hub, 2, 64, 2), Villager.class);

        PlayerInteractEntityEvent withoutSession = new PlayerInteractEntityEvent(admin, villager, EquipmentSlot.HAND);
        listener.onInteractEntity(withoutSession);
        assertTrue(withoutSession.isCancelled(), "le nœud seul ne suffit pas (#35)");

        admin.addAttachment(plugin, AdminBypassMode.PERMISSION, true);
        bypass.enable(admin);
        PlayerInteractEntityEvent withSession = new PlayerInteractEntityEvent(admin, villager, EquipmentSlot.HAND);
        listener.onInteractEntity(withSession);
        assertFalse(withSession.isCancelled());
    }

    // ---- Conteneurs ----------------------------------------------------------------------------------

    @Test
    void openingAHubChestOrBarrelIsRefusedAndItsContentIsKept() {
        PlayerMock player = playerIn(hub);
        for (Material type : new Material[] {Material.CHEST, Material.BARREL}) {
            Inventory inventory = server.createInventory(holderAt(type), 27);
            inventory.setItem(0, new ItemStack(Material.EMERALD, 5));

            InventoryOpenEvent event = new InventoryOpenEvent(player.openInventory(inventory));
            listener.onInventoryOpen(event);
            player.closeInventory();

            assertTrue(event.isCancelled(), type + " doit rester fermé");
            assertEquals(5, inventory.getItem(0).getAmount(), "verrouiller, jamais vider");
        }
    }

    @Test
    void storageBlocksAreLockedButUtilityBlocksAreNot() {
        for (Material type : new Material[] {Material.CHEST, Material.TRAPPED_CHEST, Material.BARREL,
                Material.SHULKER_BOX, Material.HOPPER, Material.DISPENSER, Material.DROPPER}) {
            assertTrue(HubResourceLockListener.isLockedStorage(holderAt(type)), type.name());
        }
        for (Material type : new Material[] {Material.FURNACE, Material.SMOKER, Material.BLAST_FURNACE,
                Material.BREWING_STAND}) {
            assertFalse(HubResourceLockListener.isLockedStorage(holderAt(type)), type + " : salles de préparation (#107)");
        }
        assertFalse(HubResourceLockListener.isLockedStorage(null), "inventaire du plugin sans détenteur");
    }

    @Test
    void utilityBlocksAndPluginInventoriesStayUsableInTheHub() {
        PlayerMock player = playerIn(hub);
        InventoryOpenEvent furnace = new InventoryOpenEvent(player.openInventory(containerAt(hub, Material.FURNACE)));
        listener.onInventoryOpen(furnace);
        assertFalse(furnace.isCancelled(), "salles de préparation (#107)");

        InventoryOpenEvent pluginGui = new InventoryOpenEvent(player.openInventory(server.createInventory(null, 27)));
        listener.onInventoryOpen(pluginGui);
        assertFalse(pluginGui.isCancelled(), "coffres personnels, sacs, boutiques RPGQuest");
    }

    @Test
    void wildChestsAreUntouched() {
        PlayerMock player = playerIn(wild);
        InventoryOpenEvent event = new InventoryOpenEvent(player.openInventory(containerAt(wild, Material.CHEST)));
        listener.onInventoryOpen(event);
        assertFalse(event.isCancelled());
    }

    @Test
    void decoratedPotsAndChiseledBookshelvesRefuseItemsInTheHubOnly() {
        PlayerMock player = playerIn(hub);
        Block pot = hub.getBlockAt(6, 64, 6);
        pot.setType(Material.DECORATED_POT);
        PlayerInteractEvent inHub = new PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK,
                new ItemStack(Material.EMERALD), pot, BlockFace.UP, EquipmentSlot.HAND);
        listener.onBlockInsert(inHub);
        assertTrue(inHub.isCancelled());

        Block wildShelf = wild.getBlockAt(6, 64, 6);
        wildShelf.setType(Material.CHISELED_BOOKSHELF);
        PlayerInteractEvent inWild = new PlayerInteractEvent(playerIn(wild), Action.RIGHT_CLICK_BLOCK,
                new ItemStack(Material.BOOK), wildShelf, BlockFace.UP, EquipmentSlot.HAND);
        listener.onBlockInsert(inWild);
        assertFalse(inWild.isCancelled());
    }

    // ---- Automatisation ------------------------------------------------------------------------------

    @Test
    void hoppersDoNotMoveItemsOutOfOrIntoHubContainers() {
        Inventory hubChest = containerAt(hub, Material.CHEST);
        Inventory hubHopper = containerAt(hub, Material.HOPPER);
        InventoryMoveItemEvent inHub = new InventoryMoveItemEvent(hubChest, new ItemStack(Material.EMERALD), hubHopper, true);
        listener.onInventoryMove(inHub);
        assertTrue(inHub.isCancelled());

        // MockBukkit place tout inventaire de bloc au même point fictif : la portée par monde est
        // donc vérifiée en changeant le monde Hub configuré (hors Hub = Wild/Claims, inchangés).
        HubResourceLockListener otherHub = new HubResourceLockListener(
                () -> new HubConfig("world_hub_inexistant"), citizensNpcs::contains, bypass, clock::get);
        InventoryMoveItemEvent outsideHub = new InventoryMoveItemEvent(hubChest, new ItemStack(Material.EMERALD), hubHopper, true);
        otherHub.onInventoryMove(outsideHub);
        assertFalse(outsideHub.isCancelled());

    }

    @Test
    void hoppersDoNotPickUpDroppedItemsInTheHubOnly() {
        Inventory hubHopper = containerAt(hub, Material.HOPPER);
        InventoryPickupItemEvent inHub = new InventoryPickupItemEvent(hubHopper,
                hub.dropItem(new Location(hub, 0.5, 65, 0.5), new ItemStack(Material.EMERALD)));
        listener.onHopperPickup(inHub);
        assertTrue(inHub.isCancelled(), "aucun hopper du Hub n'aspire un objet au sol");

        InventoryPickupItemEvent inWild = new InventoryPickupItemEvent(containerAt(wild, Material.HOPPER),
                wild.dropItem(new Location(wild, 0.5, 65, 0.5), new ItemStack(Material.EMERALD)));
        listener.onHopperPickup(inWild);
        assertFalse(inWild.isCancelled(), "Wild inchangé");
    }

    // ---- Éjection redstone (#939) ---------------------------------------------------------------------

    @Test
    void hubDroppersAndCraftersEjectNothing() {
        BlockDispenseEvent dropper = dispense(hub, Material.DROPPER, Material.FIREWORK_ROCKET);
        listener.onDispense(dropper);
        assertTrue(dropper.isCancelled(), "un dropper ne fait qu'éjecter, même un objet décoratif");

        CrafterCraftEvent craft = new CrafterCraftEvent(blockAt(hub, Material.CRAFTER), null,
                new ItemStack(Material.STICK, 4));
        listener.onCrafterCraft(craft);
        assertTrue(craft.isCancelled(), "le résultat d'un crafter serait éjecté au sol");
    }

    @Test
    void hubDispensersKeepTheirDecorativeUsesOnly() {
        for (Material item : HubResourceLockListener.DECORATIVE_DISPENSES) {
            BlockDispenseEvent decorative = dispense(hub, Material.DISPENSER, item);
            listener.onDispense(decorative);
            assertFalse(decorative.isCancelled(), item + " : distributeur décoratif conservé");
        }
        for (Material item : new Material[] {Material.ARROW, Material.DIAMOND, Material.IRON_CHESTPLATE,
                Material.SHEARS, Material.LAVA_BUCKET, Material.MINECART, Material.BONE_MEAL}) {
            BlockDispenseEvent resource = dispense(hub, Material.DISPENSER, item);
            listener.onDispense(resource);
            assertTrue(resource.isCancelled(), item + " : objet ramassable ou ressource, reste dans le bloc");
        }
    }

    @Test
    void dispensersDroppersAndCraftersOutsideTheHubAreUntouched() {
        for (World world : new World[] {wild, claims}) {
            for (Material block : new Material[] {Material.DISPENSER, Material.DROPPER}) {
                BlockDispenseEvent event = dispense(world, block, Material.DIAMOND);
                listener.onDispense(event);
                assertFalse(event.isCancelled(), block + " libre dans " + world.getName());
            }
            CrafterCraftEvent craft = new CrafterCraftEvent(blockAt(world, Material.CRAFTER), null,
                    new ItemStack(Material.STICK, 4));
            listener.onCrafterCraft(craft);
            assertFalse(craft.isCancelled(), "crafter libre dans " + world.getName());
        }
    }

    // ---- Véhicules à conteneur (#939) ----------------------------------------------------------------

    private static final EntityType[] CONTAINER_VEHICLES = {
            EntityType.CHEST_MINECART, EntityType.HOPPER_MINECART, EntityType.OAK_CHEST_BOAT};

    @Test
    void hubContainerVehiclesCannotBeDamagedOrDestroyedAndKeepTheirContent() {
        PlayerMock player = playerIn(hub);
        for (EntityType type : CONTAINER_VEHICLES) {
            Vehicle vehicle = vehicleIn(hub, type);
            ((InventoryHolder) vehicle).getInventory().setItem(0, new ItemStack(Material.EMERALD, 5));

            VehicleDamageEvent hit = new VehicleDamageEvent(vehicle, player, 10);
            listener.onVehicleDamage(hit);
            VehicleDestroyEvent destroy = new VehicleDestroyEvent(vehicle, player);
            listener.onVehicleDestroy(destroy);
            VehicleDamageEvent explosion = new VehicleDamageEvent(vehicle, null, 10);
            listener.onVehicleDamage(explosion);
            VehicleDestroyEvent environment = new VehicleDestroyEvent(vehicle, null);
            listener.onVehicleDestroy(environment);

            assertTrue(hit.isCancelled(), type + " : coup de joueur");
            assertTrue(destroy.isCancelled(), type + " : destruction par un joueur");
            assertTrue(explosion.isCancelled(), type + " : dégât sans auteur");
            assertTrue(environment.isCancelled(), type + " : destruction sans auteur");
            assertEquals(5, ((InventoryHolder) vehicle).getInventory().getItem(0).getAmount(), "verrouiller, jamais vider");
        }
        assertTrue(player.nextComponentMessage() != null, "un message discret");
        assertNull(player.nextComponentMessage(), "pas de spam");
    }

    @Test
    void plainVehiclesAndContainerVehiclesOutsideTheHubAreUntouched() {
        PlayerMock inHub = playerIn(hub);
        for (EntityType type : new EntityType[] {EntityType.MINECART, EntityType.OAK_BOAT}) {
            VehicleDamageEvent plain = new VehicleDamageEvent(vehicleIn(hub, type), inHub, 10);
            listener.onVehicleDamage(plain);
            assertFalse(plain.isCancelled(), type + " : véhicule sans conteneur, hors périmètre");
        }
        for (World world : new World[] {wild, claims}) {
            PlayerMock player = playerIn(world);
            for (EntityType type : CONTAINER_VEHICLES) {
                Vehicle vehicle = vehicleIn(world, type);
                VehicleDamageEvent hit = new VehicleDamageEvent(vehicle, player, 10);
                listener.onVehicleDamage(hit);
                VehicleDestroyEvent destroy = new VehicleDestroyEvent(vehicle, player);
                listener.onVehicleDestroy(destroy);
                assertFalse(hit.isCancelled(), type + " libre dans " + world.getName());
                assertFalse(destroy.isCancelled(), type + " libre dans " + world.getName());
            }
            assertNull(player.nextComponentMessage(), "aucun message de refus hors Hub");
        }
    }

    @Test
    void anAdminInBypassSessionMayBreakAHubContainerVehicleButNotAnExplosion() {
        PlayerMock admin = playerIn(hub);
        admin.addAttachment(plugin, HubResourceLockListener.BYPASS_PERMISSION, true);
        Vehicle cart = vehicleIn(hub, EntityType.CHEST_MINECART);

        VehicleDestroyEvent withoutSession = new VehicleDestroyEvent(cart, admin);
        listener.onVehicleDestroy(withoutSession);
        assertTrue(withoutSession.isCancelled(), "le nœud seul ne suffit pas (#35)");

        admin.addAttachment(plugin, AdminBypassMode.PERMISSION, true);
        bypass.enable(admin);
        VehicleDamageEvent hit = new VehicleDamageEvent(cart, admin, 10);
        listener.onVehicleDamage(hit);
        VehicleDestroyEvent destroy = new VehicleDestroyEvent(cart, admin);
        listener.onVehicleDestroy(destroy);
        assertFalse(hit.isCancelled());
        assertFalse(destroy.isCancelled());

        VehicleDestroyEvent environment = new VehicleDestroyEvent(cart, null);
        listener.onVehicleDestroy(environment);
        assertTrue(environment.isCancelled(), "sans auteur, aucun contournement");
    }

    // ---- Non-régression Claims ------------------------------------------------------------------------

    @Test
    void claimsTradingAndContainersAreUntouched() {
        PlayerMock player = playerIn(claims);
        Villager villager = claims.spawn(new Location(claims, 2, 64, 2), Villager.class);
        PlayerInteractEntityEvent trade = new PlayerInteractEntityEvent(player, villager, EquipmentSlot.HAND);
        listener.onInteractEntity(trade);
        assertFalse(trade.isCancelled(), "commerce libre dans Claims");

        for (Material type : new Material[] {Material.CHEST, Material.BARREL, Material.HOPPER}) {
            InventoryOpenEvent open = new InventoryOpenEvent(player.openInventory(containerAt(claims, type)));
            listener.onInventoryOpen(open);
            player.closeInventory();
            assertFalse(open.isCancelled(), type + " libre dans Claims");
        }
        assertNull(player.nextComponentMessage(), "aucun message de refus hors Hub");
    }

    // ---- Utilitaires ---------------------------------------------------------------------------------

    private PlayerMock playerIn(World world) {
        PlayerMock player = server.addPlayer();
        player.teleport(new Location(world, 0, 64, 0));
        return player;
    }

    private static int nextX;

    private InventoryHolder holderAt(Material type) {
        Block block = hub.getBlockAt(nextX++ * 2, 64, 40);
        block.setType(type);
        return (InventoryHolder) block.getState();
    }

    private static Block blockAt(World world, Material type) {
        Block block = world.getBlockAt(nextX++ * 2, 64, 60);
        block.setType(type);
        return block;
    }

    private static BlockDispenseEvent dispense(World world, Material block, Material item) {
        return new BlockDispenseEvent(blockAt(world, block), new ItemStack(item), new Vector(0, 0, 1));
    }

    private static Vehicle vehicleIn(World world, EntityType type) {
        return (Vehicle) world.spawnEntity(new Location(world, nextX++ * 2, 64, 80), type);
    }

    private static Inventory containerAt(World world, Material type) {
        Block block = world.getBlockAt(nextX++ * 2, 64, 20);
        block.setType(type);
        return ((Container) block.getState()).getInventory();
    }
}
