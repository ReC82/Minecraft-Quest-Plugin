package com.lodygames.rpgquest.npc.spot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.database.DatabaseManager;
import com.lodygames.rpgquest.database.NpcSpotRepository;
import com.lodygames.rpgquest.npc.NpcPlacementPlanner;
import com.lodygames.rpgquest.permission.RpgPermissions;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * Issue #381, correction du 15/10 — l'outil « Emplacements PNJ » : un clic droit sur un bloc avec
 * l'outil enregistre un emplacement, sans commande ni saisie ; rien d'autre n'en crée.
 * Vraie base SQLite temporaire, monde MockBukkit.
 */
@SuppressWarnings("deprecation")
// PlayerInteractEvent#isCancelled() est dépréciée (sémantique bloc/objet), même remarque que
// ZoneWandListenerTest : seule façon de lire l'annulation d'un événement construit à la main.
class NpcSpotToolListenerTest {

    private static final long TIMEOUT_MILLIS = 5_000;

    @TempDir
    Path tempDir;

    private ServerMock server;
    private Plugin plugin;
    private World world;
    private DatabaseManager database;
    private NpcSpotService service;
    private final AtomicLong clock = new AtomicLong(1_000_000);
    private Set<String> allowedWorlds = Set.of("world");
    private Optional<String> claim = Optional.empty();
    private NpcSpotToolListener listener;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        world = server.addSimpleWorld("world");
        database = new DatabaseManager(tempDir.resolve("data.db"));
        database.initialize().get(5, TimeUnit.SECONDS);
        service = new NpcSpotService(new NpcSpotRepository(database));
        service.load().get(5, TimeUnit.SECONDS);
        listener = new NpcSpotToolListener(service, () -> allowedWorlds, (w, x, y, z) -> claim,
                NpcSpotToolListenerTest::probe, Runnable::run, clock::get, Logger.getLogger("test"));
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
        MockBukkit.unmock();
    }

    /** Sonde par matériau : MockBukkit n'implémente pas {@code Block#isPassable}. */
    private static NpcPlacementPlanner.Probe probe(World world) {
        return new NpcPlacementPlanner.Probe() {
            @Override
            public NpcPlacementPlanner.BlockKind kindAt(int x, int y, int z) {
                Material type = world.getBlockAt(x, y, z).getType();
                if (type.isAir()) {
                    return NpcPlacementPlanner.BlockKind.PASSABLE;
                }
                return type.isSolid() ? NpcPlacementPlanner.BlockKind.SOLID : NpcPlacementPlanner.BlockKind.LIQUID;
            }

            @Override
            public boolean occupiedByNpc(int x, int y, int z) {
                return false;
            }
        };
    }

    private PlayerMock admin() {
        PlayerMock player = server.addPlayer();
        player.addAttachment(plugin, RpgPermissions.ADMIN_NPC_SPOT, true);
        return player;
    }

    /** Un bloc plein avec deux cases d'air au-dessus : un PNJ peut s'y tenir. */
    private Block ground(int x, int y, int z) {
        Block block = world.getBlockAt(x, y, z);
        block.setType(Material.STONE);
        world.getBlockAt(x, y + 1, z).setType(Material.AIR);
        world.getBlockAt(x, y + 2, z).setType(Material.AIR);
        return block;
    }

    private PlayerInteractEvent click(PlayerMock player, Action action, ItemStack item, Block block,
                                      EquipmentSlot hand) {
        PlayerInteractEvent event = new PlayerInteractEvent(player, action, item, block, null, hand);
        listener.onInteract(event);
        return event;
    }

    private PlayerInteractEvent rightClick(PlayerMock player, Block block) {
        return click(player, Action.RIGHT_CLICK_BLOCK, NpcSpotTool.create(), block, EquipmentSlot.HAND);
    }

    private void awaitSpots(int expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        while (service.all().size() < expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(expected, service.all().size());
    }

    private static String plain(Component message) {
        return message == null ? "" : PlainTextComponentSerializer.plainText().serialize(message);
    }

    private String awaitMessage(PlayerMock player) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        Component message = player.nextComponentMessage();
        while (message == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
            message = player.nextComponentMessage();
        }
        return plain(message);
    }

    @Test
    void aRightClickWithTheToolRecordsAFreeSpotAboveTheClickedBlockWithoutAnyName() throws Exception {
        PlayerMock player = admin();
        player.setLocation(new Location(world, 0.5, 65, 4.5));
        PlayerInteractEvent event = rightClick(player, ground(0, 64, 0));

        assertTrue(event.isCancelled(), "l'outil ne pose jamais de porte-armure");
        awaitSpots(1);
        NpcSpot spot = service.all().get(0);
        assertEquals("world", spot.world());
        assertEquals(0.5, spot.x());
        assertEquals(65.0, spot.y(), "au-dessus du bloc visé, pas aux pieds de l'administrateur");
        assertEquals(0.5, spot.z());
        assertEquals("Emplacement PNJ 1", spot.name(), "nom provisoire automatique numéroté");
        assertTrue(spot.free());
        assertEquals(player.getName(), spot.createdBy());
        assertEquals(0f, spot.yaw(), 0.01f, "le PNJ regarde l'administrateur (placé au sud, +Z)");
        assertTrue(awaitMessage(player).contains("Emplacement PNJ enregistré"));
    }

    @Test
    void twoDifferentBlocksGiveTwoDistinctSpots() throws Exception {
        PlayerMock player = admin();
        rightClick(player, ground(0, 64, 0));
        awaitSpots(1);
        clock.addAndGet(NpcSpotToolListener.DEBOUNCE_MILLIS);
        rightClick(player, ground(5, 64, 0));
        awaitSpots(2);

        List<NpcSpot> spots = service.all();
        assertNotEquals(spots.get(0).id(), spots.get(1).id());
        assertEquals("Emplacement PNJ 2", spots.get(1).name());
    }

    @Test
    void aDoubleClickOnTheSameBlockCreatesNoDuplicate() throws Exception {
        PlayerMock player = admin();
        Block block = ground(0, 64, 0);
        rightClick(player, block);
        rightClick(player, block); // second événement du même geste : absorbé par l'anti-rebond
        awaitSpots(1);
        assertTrue(awaitMessage(player).contains("enregistré"));
        assertTrue(player.nextComponentMessage() == null, "un seul message pour le double événement");

        clock.addAndGet(NpcSpotToolListener.DEBOUNCE_MILLIS);
        rightClick(player, block); // vrai second clic, plus tard : l'existant est signalé
        assertTrue(awaitMessage(player).contains("existe déjà"));
        assertEquals(1, service.all().size());
    }

    @Test
    void twoSimultaneousReservationsOfTheSameBlockWriteOnlyOne() throws Exception {
        var first = service.create("world", 0.5, 65, 0.5, 0f, 0f, "", "A", null);
        var second = service.create("world", 0.5, 65, 0.5, 0f, 0f, "", "B", null);
        first.get(5, TimeUnit.SECONDS);
        NpcSpotService.CreateOutcome secondOutcome = second.get(5, TimeUnit.SECONDS).outcome();

        assertTrue(secondOutcome == NpcSpotService.CreateOutcome.IN_PROGRESS
                || secondOutcome == NpcSpotService.CreateOutcome.ALREADY_THERE, secondOutcome.name());
        assertEquals(1, service.all().size());
    }

    @Test
    void anOrdinaryArmorStandOrARenamedOneIsNeverTheTool() {
        PlayerMock player = admin();
        ItemStack renamed = new ItemStack(NpcSpotTool.TOOL_MATERIAL);
        ItemMeta meta = renamed.getItemMeta();
        meta.displayName(Component.text("Emplacements PNJ"));
        renamed.setItemMeta(meta);

        for (ItemStack item : List.of(new ItemStack(NpcSpotTool.TOOL_MATERIAL), renamed,
                new ItemStack(Material.WOODEN_AXE), new ItemStack(Material.STICK))) {
            PlayerInteractEvent event = click(player, Action.RIGHT_CLICK_BLOCK, item, ground(0, 64, 0),
                    EquipmentSlot.HAND);
            assertFalse(event.isCancelled(), "aucune interférence avec " + item.getType());
        }
        assertTrue(service.all().isEmpty());
    }

    @Test
    void theToolInTheOffHandOrALeftClickCreatesNothing() {
        PlayerMock player = admin();
        Block block = ground(0, 64, 0);
        click(player, Action.RIGHT_CLICK_BLOCK, NpcSpotTool.create(), block, EquipmentSlot.OFF_HAND);
        PlayerInteractEvent left = click(player, Action.LEFT_CLICK_BLOCK, NpcSpotTool.create(), block,
                EquipmentSlot.HAND);

        assertTrue(left.isCancelled(), "l'outil ne casse pas de bloc");
        assertTrue(service.all().isEmpty());
    }

    @Test
    void withoutThePermissionTheToolCreatesNothing() {
        PlayerMock player = server.addPlayer();
        PlayerInteractEvent event = rightClick(player, ground(0, 64, 0));

        assertTrue(event.isCancelled());
        assertTrue(service.all().isEmpty());
        assertTrue(plain(player.nextComponentMessage()).contains(RpgPermissions.ADMIN_NPC_SPOT));
    }

    @Test
    void aBlockWhereNoNpcCanStandIsRefusedClearly() {
        PlayerMock player = admin();
        Block block = ground(0, 64, 0);
        world.getBlockAt(0, 66, 0).setType(Material.STONE); // une seule case d'air au-dessus

        rightClick(player, block);

        assertTrue(service.all().isEmpty());
        assertTrue(plain(player.nextComponentMessage()).contains("Aucun emplacement créé"));
    }

    @Test
    void aWorldWithoutNpcsIsRefused() {
        allowedWorlds = Set.of("hub");
        PlayerMock player = admin();
        rightClick(player, ground(0, 64, 0));

        assertTrue(service.all().isEmpty());
        assertTrue(plain(player.nextComponentMessage()).contains("n'accepte pas de PNJ"));
    }

    @Test
    void aPlayerClaimIsRefused() {
        claim = Optional.of("claim_42");
        PlayerMock player = admin();
        rightClick(player, ground(0, 64, 0));

        assertTrue(service.all().isEmpty());
        assertTrue(plain(player.nextComponentMessage()).contains("claim_42"));
    }

    @Test
    void theNpcFacesTheAdministratorWhoClicked() {
        Location west = new Location(world, -10, 65, 0.5);
        assertEquals(90f, NpcSpotToolListener.yawTowards(0.5, 0.5, west), 0.01f);
        Location north = new Location(world, 0.5, 65, -10);
        assertEquals(180f, Math.abs(NpcSpotToolListener.yawTowards(0.5, 0.5, north)), 0.01f);
    }

    @Test
    void theToolIsRecognisedByItsTagAndCanBePutAway() {
        PlayerMock player = admin();
        ItemStack tool = NpcSpotTool.create();
        assertTrue(NpcSpotTool.isTool(tool));
        assertNotNull(tool.getItemMeta().displayName());
        player.getInventory().addItem(tool);
        player.getInventory().addItem(new ItemStack(NpcSpotTool.TOOL_MATERIAL));

        assertEquals(1, NpcSpotTool.removeFrom(player.getInventory()));
        assertTrue(player.getInventory().contains(NpcSpotTool.TOOL_MATERIAL), "le porte-armure ordinaire reste");
        assertFalse(java.util.Arrays.stream(player.getInventory().getContents()).anyMatch(NpcSpotTool::isTool));
    }
}
