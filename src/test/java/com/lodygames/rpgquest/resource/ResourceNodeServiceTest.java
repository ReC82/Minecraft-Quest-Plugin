package com.lodygames.rpgquest.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.database.DatabaseManager;
import com.lodygames.rpgquest.database.ResourceNodeRepository;
import com.lodygames.rpgquest.item.YamlCustomItemRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Item;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class ResourceNodeServiceTest {

    private static final long TIMEOUT_SECONDS = 5;
    private static final NamespacedKey SINGLE_DROP_TYPE = new NamespacedKey("rpgquest", "single_drop");
    private static final NamespacedKey TOOL_GATED_TYPE = new NamespacedKey("rpgquest", "tool_gated");

    @TempDir
    Path tempDir;

    private ServerMock server;
    private RPGQuestPlugin plugin;
    private World world;
    private DatabaseManager database;
    private ResourceNodeRepository repository;
    private ResourceNodeRegistry typeRegistry;
    private YamlCustomItemRegistry itemRegistry;
    private AtomicLong clock;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(RPGQuestPlugin.class);
        world = server.addSimpleWorld("world");

        database = new DatabaseManager(tempDir.resolve("data.db"));
        database.initialize().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        repository = new ResourceNodeRepository(database);

        Path itemsDir = tempDir.resolve("items");
        Files.createDirectories(itemsDir);
        itemRegistry = new YamlCustomItemRegistry(itemsDir, plugin.getSLF4JLogger());
        itemRegistry.start();

        Path nodesDir = tempDir.resolve("resource-nodes");
        Files.createDirectories(nodesDir);
        Files.writeString(nodesDir.resolve("single_drop.yml"), """
                id: rpgquest:single_drop
                active-material: EMERALD_ORE
                depleted-material: STONE
                respawn-seconds: 300
                drops:
                  - material: QUARTZ
                    weight: 1
                    min-amount: 2
                    max-amount: 2
                """);
        Files.writeString(nodesDir.resolve("tool_gated.yml"), """
                id: rpgquest:tool_gated
                active-material: EMERALD_ORE
                depleted-material: STONE
                required-tools:
                  - DIAMOND_PICKAXE
                respawn-seconds: 60
                drops:
                  - material: QUARTZ
                    weight: 1
                    min-amount: 1
                    max-amount: 1
                """);
        typeRegistry = new ResourceNodeRegistry(nodesDir, plugin.getSLF4JLogger());
        typeRegistry.start();

        clock = new AtomicLong(0L);
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
        MockBukkit.unmock();
    }

    private ResourceNodeService newService() {
        return newService(org.bukkit.Bukkit::getWorld, ChunkLoadedChecker.DEFAULT);
    }

    private ResourceNodeService newService(java.util.function.Function<String, World> worldResolver,
                                            ChunkLoadedChecker chunkLoadedChecker) {
        return new ResourceNodeService(plugin, typeRegistry, repository, itemRegistry,
                plugin.getSLF4JLogger(), clock::get, worldResolver, chunkLoadedChecker);
    }

    @Test
    void harvestingAnActiveNodeWithAllowedToolGivesConfiguredDropAndDepletesTheNode() {
        ResourceNodeService service = newService();
        Block block = world.getBlockAt(1, 64, 1);
        service.createNode(block, SINGLE_DROP_TYPE);
        assertEquals(Material.EMERALD_ORE, block.getType());

        PlayerMock player = server.addPlayer();
        int before = world.getEntitiesByClass(Item.class).size();

        BlockBreakEvent event = new BlockBreakEvent(block, player);
        service.handleBreak(event);

        assertTrue(event.isCancelled(), "on prend le contrôle total du cassage plutôt que de laisser vanilla agir");
        assertEquals(Material.STONE, block.getType(), "le bloc épuisé temporaire doit être posé");
        assertEquals(before + 1, world.getEntitiesByClass(Item.class).size(), "le drop configuré doit être déposé");
        assertTrue(service.inspectNode(block).orElseThrow().depleted());
    }

    @Test
    void wrongToolCancelsHarvestAndKeepsNodeActive() {
        ResourceNodeService service = newService();
        Block block = world.getBlockAt(2, 64, 2);
        service.createNode(block, TOOL_GATED_TYPE);

        PlayerMock player = server.addPlayer();
        player.getInventory().setItemInMainHand(new ItemStack(Material.WOODEN_PICKAXE));
        int before = world.getEntitiesByClass(Item.class).size();

        BlockBreakEvent event = new BlockBreakEvent(block, player);
        service.handleBreak(event);

        assertTrue(event.isCancelled());
        assertEquals(Material.EMERALD_ORE, block.getType(), "le bloc ne doit pas changer sans le bon outil");
        assertEquals(before, world.getEntitiesByClass(Item.class).size(), "aucun drop sans le bon outil");
        assertFalse(service.inspectNode(block).orElseThrow().depleted());
    }

    @Test
    void correctToolAllowsHarvest() {
        ResourceNodeService service = newService();
        Block block = world.getBlockAt(3, 64, 3);
        service.createNode(block, TOOL_GATED_TYPE);

        PlayerMock player = server.addPlayer();
        player.getInventory().setItemInMainHand(new ItemStack(Material.DIAMOND_PICKAXE));

        BlockBreakEvent event = new BlockBreakEvent(block, player);
        service.handleBreak(event);

        assertEquals(Material.STONE, block.getType());
        assertTrue(service.inspectNode(block).orElseThrow().depleted());
    }

    @Test
    void depletedNodeCancelsBreakWhileOnCooldown() {
        ResourceNodeService service = newService();
        Block block = world.getBlockAt(4, 64, 4);
        service.createNode(block, SINGLE_DROP_TYPE);
        block.setType(Material.STONE); // simule un nœud déjà épuisé, comme le ferait handleBreak
        service.putForTest(new ResourceNodeService.NodeKey("world", 4, 64, 4), SINGLE_DROP_TYPE, 300_000L);

        PlayerMock player = server.addPlayer();
        int before = world.getEntitiesByClass(Item.class).size();

        BlockBreakEvent event = new BlockBreakEvent(block, player);
        service.handleBreak(event);

        assertTrue(event.isCancelled(), "un nœud en cooldown ne doit jamais être récolté");
        assertEquals(before, world.getEntitiesByClass(Item.class).size());
    }

    @Test
    void alreadyCancelledEventIsIgnored() {
        ResourceNodeService service = newService();
        Block block = world.getBlockAt(5, 64, 5);
        service.createNode(block, SINGLE_DROP_TYPE);

        PlayerMock player = server.addPlayer();
        BlockBreakEvent event = new BlockBreakEvent(block, player);
        event.setCancelled(true);

        service.handleBreak(event);

        assertEquals(Material.EMERALD_ORE, block.getType(),
                "un événement déjà annulé par un autre plugin ne doit jamais être traité comme une récolte");
        assertFalse(service.inspectNode(block).orElseThrow().depleted());
    }

    @Test
    void secondSimultaneousBreakOnTheSameNodeIsIgnoredOnceDepleted() {
        ResourceNodeService service = newService();
        Block block = world.getBlockAt(6, 64, 6);
        service.createNode(block, SINGLE_DROP_TYPE);

        PlayerMock playerOne = server.addPlayer();
        PlayerMock playerTwo = server.addPlayer();
        int before = world.getEntitiesByClass(Item.class).size();

        // Deux BlockBreakEvent successifs sur la même position, comme deux clients qui cassent
        // "en même temps" — le bus d'événements Bukkit les traite séquentiellement sur le thread
        // principal, mais le nœud doit déjà se voir épuisé pour le second.
        service.handleBreak(new BlockBreakEvent(block, playerOne));
        int afterFirst = world.getEntitiesByClass(Item.class).size();
        service.handleBreak(new BlockBreakEvent(block, playerTwo));
        int afterSecond = world.getEntitiesByClass(Item.class).size();

        assertEquals(before + 1, afterFirst, "le premier cassage doit produire un drop");
        assertEquals(afterFirst, afterSecond, "le second cassage simultané ne doit produire aucun drop supplémentaire");
    }

    @Test
    void sweepSkipsNodesInUnloadedChunks() {
        ResourceNodeService service = newService(org.bukkit.Bukkit::getWorld, (w, cx, cz) -> false);
        Block block = world.getBlockAt(7, 64, 7);
        service.createNode(block, SINGLE_DROP_TYPE);
        block.setType(Material.STONE);
        ResourceNodeService.NodeKey key = new ResourceNodeService.NodeKey("world", 7, 64, 7);
        service.putForTest(key, SINGLE_DROP_TYPE, 1_000L); // déjà expiré

        int respawned = service.sweepRespawns(2_000L);

        assertEquals(0, respawned, "un chunk déchargé ne doit jamais être chargé de force pour respawn");
        assertEquals(Material.STONE, block.getType());
        assertTrue(service.getForTest(key).depletedUntilMillis() != null);
    }

    @Test
    void sweepSkipsNodesInAMissingWorld() {
        ResourceNodeService service = newService();
        ResourceNodeService.NodeKey key = new ResourceNodeService.NodeKey("ghost_world", 0, 64, 0);
        service.putForTest(key, SINGLE_DROP_TYPE, 1_000L); // déjà expiré, mais le monde n'existe plus

        int respawned = service.sweepRespawns(2_000L);

        assertEquals(0, respawned, "un monde supprimé/absent ne doit jamais lever d'exception");
        assertTrue(service.getForTest(key).depletedUntilMillis() != null, "le nœud reste suivi, respawn différé");
    }

    @Test
    void nodeStaysDepletedAcrossARestartUntilRespawnIsActuallyDue() {
        // Simule un redémarrage : le nœud est rechargé depuis la base avec un depletedAt déjà
        // enregistré, avant que le sweep périodique n'ait eu l'occasion de tourner. Chunk toujours
        // chargé ici par construction : ce test isole le minutage, pas la logique de chunk déchargé
        // (couverte séparément par sweepSkipsNodesInUnloadedChunks).
        ResourceNodeService service = newService(org.bukkit.Bukkit::getWorld, (w, cx, cz) -> true);
        Block block = world.getBlockAt(8, 64, 8);
        service.createNode(block, SINGLE_DROP_TYPE);
        block.setType(Material.STONE);
        ResourceNodeService.NodeKey key = new ResourceNodeService.NodeKey("world", 8, 64, 8);
        service.putForTest(key, SINGLE_DROP_TYPE, 300_000L); // respawn dans 300s

        int tooEarly = service.sweepRespawns(100_000L);
        assertEquals(0, tooEarly);
        assertEquals(Material.STONE, block.getType(), "toujours épuisé avant l'échéance, même après un redémarrage");

        int onTime = service.sweepRespawns(300_000L);
        assertEquals(1, onTime);
        assertEquals(Material.EMERALD_ORE, block.getType());
        assertTrue(service.getForTest(key).depletedUntilMillis() == null);
    }

    @Test
    void createNodeRejectsUnknownType() {
        ResourceNodeService service = newService();
        Block block = world.getBlockAt(9, 64, 9);

        ResourceNodeService.CreateOutcome outcome =
                service.createNode(block, new NamespacedKey("rpgquest", "does_not_exist"));

        assertEquals(ResourceNodeService.CreateOutcome.UNKNOWN_TYPE, outcome);
    }

    @Test
    void createNodeRejectsAlreadyExistingNode() {
        ResourceNodeService service = newService();
        Block block = world.getBlockAt(10, 64, 10);
        service.createNode(block, SINGLE_DROP_TYPE);

        assertEquals(ResourceNodeService.CreateOutcome.ALREADY_A_NODE, service.createNode(block, TOOL_GATED_TYPE));
    }

    @Test
    void removeNodeStopsTrackingWithoutTouchingThePhysicalBlock() {
        ResourceNodeService service = newService();
        Block block = world.getBlockAt(11, 64, 11);
        service.createNode(block, SINGLE_DROP_TYPE);

        assertTrue(service.removeNode(block));
        assertTrue(service.inspectNode(block).isEmpty());
        assertEquals(Material.EMERALD_ORE, block.getType(), "le bloc physique n'est pas touché par la suppression");
        assertFalse(service.removeNode(block), "un second retrait ne doit rien trouver");
    }
}
