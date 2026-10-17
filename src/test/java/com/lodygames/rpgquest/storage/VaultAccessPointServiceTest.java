package com.lodygames.rpgquest.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.database.DatabaseManager;
import com.lodygames.rpgquest.database.PersonalVaultRepository;
import com.lodygames.rpgquest.database.VaultAccessPointRepository;
import com.lodygames.rpgquest.storage.VaultAccessPointService.BindOutcome;
import com.lodygames.rpgquest.storage.VaultAccessPointService.UnbindOutcome;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Container;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

/** Issue #105 — coffres physiques des Hubs liés aux coffres personnels. */
class VaultAccessPointServiceTest {

    private static final long TIMEOUT_SECONDS = 5;
    private static final UUID ADMIN = UUID.randomUUID();

    @TempDir
    Path tempDir;

    private ServerMock server;
    private RPGQuestPlugin plugin;
    private DatabaseManager database;
    private VaultAccessPointRepository accessRepository;
    private PersonalVaultService vaults;
    private final List<VaultAccessPointService> started = new ArrayList<>();
    private WorldMock hubA;
    private WorldMock hubB;
    private WorldMock wild;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(RPGQuestPlugin.class);
        database = new DatabaseManager(tempDir.resolve("test.db"));
        database.initialize().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        accessRepository = new VaultAccessPointRepository(database);
        vaults = new PersonalVaultService(plugin, new PersonalVaultRepository(database), plugin.getSLF4JLogger());
        vaults.start();
        hubA = server.addSimpleWorld("hub_a");
        hubB = server.addSimpleWorld("hub_b");
        wild = server.addSimpleWorld("wild");
    }

    @AfterEach
    void tearDown() {
        started.forEach(VaultAccessPointService::stop);
        vaults.stop();
        database.shutdown();
        MockBukkit.unmock();
    }

    private VaultAccessPointService newService() throws Exception {
        VaultAccessPointService service = new VaultAccessPointService(plugin, accessRepository, vaults,
                () -> Set.of("wild"), plugin.getSLF4JLogger());
        service.start();
        started.add(service);
        waitUntil(service::isLoaded);
        return service;
    }

    private <T> T await(CompletableFuture<T> future) throws Exception {
        long deadline = System.currentTimeMillis() + TIMEOUT_SECONDS * 1000;
        while (!future.isDone() && System.currentTimeMillis() < deadline) {
            server.getScheduler().performTicks(1);
            Thread.sleep(10);
        }
        return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private void waitUntil(BooleanSupplier condition) throws Exception {
        long deadline = System.currentTimeMillis() + TIMEOUT_SECONDS * 1000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            server.getScheduler().performTicks(1);
            Thread.sleep(10);
        }
        assertTrue(condition.getAsBoolean(), "condition non atteinte à temps");
    }

    private Block chest(WorldMock world, int x) {
        Block block = world.getBlockAt(x, 64, 0);
        block.setType(Material.CHEST);
        return block;
    }

    private PlayerInteractEvent rightClick(PlayerMock player, Block block, EquipmentSlot hand) {
        PlayerInteractEvent event = new PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK, null, block, BlockFace.UP, hand);
        server.getPluginManager().callEvent(event);
        return event;
    }

    /** Clic droit main principale puis attente de l'ouverture du coffre personnel. */
    private Inventory openThrough(PlayerMock player, Block block) throws Exception {
        PlayerInteractEvent event = rightClick(player, block, EquipmentSlot.HAND);
        assertEquals(Event.Result.DENY, event.useInteractedBlock(), "le conteneur physique ne s'ouvre jamais");
        waitUntil(() -> vaultOpen(player));
        return player.getOpenInventory().getTopInventory();
    }

    private static boolean vaultOpen(PlayerMock player) {
        Inventory top = player.getOpenInventory().getTopInventory();
        return top != null && top.getHolder() instanceof PersonalVaultHolder;
    }

    private void closeAndFlush(PlayerMock player) throws Exception {
        player.closeInventory();
        database.execute(connection -> null).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    @Test
    void declaredChestOpensTheClickersOwnVault() throws Exception {
        VaultAccessPointService service = newService();
        Block chest = chest(hubA, 1);
        assertEquals(BindOutcome.BOUND, await(service.bind(chest, VaultId.DEFAULT, ADMIN)));
        PlayerMock alice = server.addPlayer();

        Inventory top = openThrough(alice, chest);
        PersonalVaultHolder holder = assertInstanceOf(PersonalVaultHolder.class, top.getHolder());
        assertEquals(alice.getUniqueId(), holder.owner());
        assertEquals(VaultId.DEFAULT, holder.vaultId());
    }

    @Test
    void twoPlayersOnTheSameChestSeeIndependentContents() throws Exception {
        VaultAccessPointService service = newService();
        Block chest = chest(hubA, 1);
        await(service.bind(chest, VaultId.DEFAULT, ADMIN));
        PlayerMock alice = server.addPlayer();
        PlayerMock bob = server.addPlayer();

        Inventory aliceView = openThrough(alice, chest);
        Inventory bobView = openThrough(bob, chest);
        assertNotSame(aliceView, bobView, "chaque joueur a son propre inventaire, même ouvert simultanément");
        aliceView.setItem(0, new ItemStack(Material.DIAMOND, 3));
        assertNull(bobView.getItem(0));
        closeAndFlush(alice);
        closeAndFlush(bob);

        assertNull(openThrough(bob, chest).getItem(0), "Bob ne voit jamais le contenu d'Alice");
    }

    @Test
    void sameVaultIdInTwoHubsShowsTheSameContent() throws Exception {
        VaultAccessPointService service = newService();
        Block inHubA = chest(hubA, 1);
        Block inHubB = chest(hubB, 7);
        await(service.bind(inHubA, VaultId.DEFAULT, ADMIN));
        await(service.bind(inHubB, VaultId.DEFAULT, ADMIN));
        PlayerMock alice = server.addPlayer();

        openThrough(alice, inHubA).setItem(4, new ItemStack(Material.EMERALD, 12));
        closeAndFlush(alice);

        assertEquals(new ItemStack(Material.EMERALD, 12), openThrough(alice, inHubB).getItem(4));
        assertEquals(2, service.all().size(), "plusieurs points physiques vers le même vault_id");
    }

    @Test
    void movingOrRebindingAnAccessPointNeverTouchesStoredContents() throws Exception {
        VaultAccessPointService service = newService();
        Block oldChest = chest(hubA, 1);
        Block newChest = chest(hubA, 5);
        await(service.bind(oldChest, VaultId.DEFAULT, ADMIN));
        PlayerMock alice = server.addPlayer();
        openThrough(alice, oldChest).setItem(0, new ItemStack(Material.GOLD_INGOT, 8));
        closeAndFlush(alice);

        // Déplacement : nouveau point lié, ancien délié.
        assertEquals(BindOutcome.BOUND, await(service.bind(newChest, VaultId.DEFAULT, ADMIN)));
        assertEquals(UnbindOutcome.UNBOUND, await(service.unbind(oldChest)));
        assertEquals(new ItemStack(Material.GOLD_INGOT, 8), openThrough(alice, newChest).getItem(0));
        closeAndFlush(alice);

        // Reconfiguration vers vault_02 puis retour : vault_01 intact.
        assertEquals(BindOutcome.REBOUND, await(service.bind(newChest, VaultId.of("vault_02"), ADMIN)));
        assertNull(openThrough(alice, newChest).getItem(0));
        closeAndFlush(alice);
        await(service.bind(newChest, VaultId.DEFAULT, ADMIN));
        assertEquals(new ItemStack(Material.GOLD_INGOT, 8), openThrough(alice, newChest).getItem(0));
    }

    @Test
    void undeclaredChestKeepsVanillaBehaviour() throws Exception {
        VaultAccessPointService service = newService();
        await(service.bind(chest(hubA, 1), VaultId.DEFAULT, ADMIN));
        Block vanilla = chest(hubA, 3);
        PlayerMock alice = server.addPlayer();

        PlayerInteractEvent event = rightClick(alice, vanilla, EquipmentSlot.HAND);
        assertFalse(event.useInteractedBlock() == Event.Result.DENY);
        assertFalse(service.isAccessPoint(vanilla));
    }

    @Test
    void offHandIsCancelledWithoutOpeningAndAnEarlierDenialIsRespected() throws Exception {
        VaultAccessPointService service = newService();
        Block chest = chest(hubA, 1);
        await(service.bind(chest, VaultId.DEFAULT, ADMIN));
        PlayerMock alice = server.addPlayer();

        PlayerInteractEvent offHand = rightClick(alice, chest, EquipmentSlot.OFF_HAND);
        assertEquals(Event.Result.DENY, offHand.useInteractedBlock());
        server.getScheduler().performTicks(5);
        assertFalse(vaultOpen(alice));

        PlayerInteractEvent denied = new PlayerInteractEvent(alice, Action.RIGHT_CLICK_BLOCK, null, chest, BlockFace.UP,
                EquipmentSlot.HAND);
        denied.setUseInteractedBlock(Event.Result.DENY);
        server.getPluginManager().callEvent(denied);
        server.getScheduler().performTicks(5);
        assertFalse(vaultOpen(alice),
                "un refus posé par une autre protection est respecté");
    }

    @Test
    void bindingsSurviveARestart() throws Exception {
        VaultAccessPointService first = newService();
        Block chest = chest(hubB, 2);
        await(first.bind(chest, VaultId.of("vault_03"), ADMIN));
        first.stop();

        VaultAccessPointService restarted = newService();
        assertEquals(VaultId.of("vault_03"), restarted.vaultAt(chest).orElseThrow());
    }

    @Test
    void bindRefusesUnsupportedBlocksForbiddenWorldsAndNonEmptyContainers() throws Exception {
        VaultAccessPointService service = newService();
        Block stone = hubA.getBlockAt(9, 64, 9);
        stone.setType(Material.STONE);
        assertEquals(BindOutcome.UNSUPPORTED_BLOCK, await(service.bind(stone, VaultId.DEFAULT, ADMIN)));

        assertEquals(BindOutcome.FORBIDDEN_WORLD, await(service.bind(chest(wild, 1), VaultId.DEFAULT, ADMIN)));

        Block filled = chest(hubA, 4);
        ((Container) filled.getState()).getInventory().addItem(new ItemStack(Material.DIRT));
        assertEquals(BindOutcome.NOT_EMPTY, await(service.bind(filled, VaultId.DEFAULT, ADMIN)));

        Block chest = chest(hubA, 1);
        await(service.bind(chest, VaultId.DEFAULT, ADMIN));
        assertEquals(BindOutcome.ALREADY_BOUND, await(service.bind(chest, VaultId.DEFAULT, ADMIN)));
        assertEquals(UnbindOutcome.NOT_BOUND, await(service.unbind(stone)));
        assertEquals(1, service.all().size());
    }

    @Test
    void protectionBlocksBreakingExplosionsAndAdjacentChests() throws Exception {
        VaultAccessPointService service = newService();
        Block chest = chest(hubA, 1);
        await(service.bind(chest, VaultId.DEFAULT, ADMIN));
        PlayerMock builder = server.addPlayer();
        builder.setOp(true);

        BlockBreakEvent breakEvent = new BlockBreakEvent(chest, builder);
        server.getPluginManager().callEvent(breakEvent);
        assertTrue(breakEvent.isCancelled(), "même un builder/OP ne casse pas un point lié");

        Block other = hubA.getBlockAt(20, 64, 20);
        List<Block> blown = new ArrayList<>(List.of(chest, other));
        EntityExplodeEvent explosion = new EntityExplodeEvent(builder, chest.getLocation(), blown, 1f,
                org.bukkit.ExplosionResult.DESTROY);
        server.getPluginManager().callEvent(explosion);
        assertEquals(List.of(other), explosion.blockList());

        Block adjacent = hubA.getBlockAt(2, 64, 0);
        adjacent.setType(Material.CHEST);
        BlockPlaceEvent place = new BlockPlaceEvent(adjacent, adjacent.getState(), chest,
                new ItemStack(Material.CHEST), builder, true, EquipmentSlot.HAND);
        server.getPluginManager().callEvent(place);
        assertTrue(place.isCancelled(), "pas de coffre double non lié accolé à un point d'accès");
    }

    @Test
    void doubleChestPartnerIsDerivedFromBlockData() {
        Block left = hubA.getBlockAt(0, 70, 0);
        Block right = hubA.getBlockAt(-1, 70, 0);
        left.setType(Material.CHEST);
        right.setType(Material.CHEST);
        org.bukkit.block.data.type.Chest leftData = (org.bukkit.block.data.type.Chest) left.getBlockData();
        leftData.setFacing(BlockFace.SOUTH);
        leftData.setType(org.bukkit.block.data.type.Chest.Type.LEFT);
        left.setBlockData(leftData);

        // Orienté sud, la moitié LEFT a son partenaire à l'ouest (sens horaire de SOUTH).
        assertEquals(right, VaultAccessPointService.partnerOf(left).orElseThrow());
    }
}
