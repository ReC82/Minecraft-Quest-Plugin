package com.lodygames.rpgquest.hub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.config.HubConfig;
import com.lodygames.rpgquest.dialogue.model.DialogueCondition;
import com.lodygames.rpgquest.dialogue.model.HasPermissionCondition;
import com.lodygames.rpgquest.permission.AdminBypassMode;
import com.lodygames.rpgquest.settings.GameSettings;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.slf4j.helpers.NOPLogger;

/** Issue #675 (retour du 22/10) : postes de travail du Hub fermés par défaut, exceptions conditionnelles. */
class HubStationLockListenerTest {

    private static final String HUB = "hub_675_stations";
    private static final List<DialogueCondition> PERMISSION =
            List.of(new HasPermissionCondition("rpgquest.test.station"));

    private final AdminBypassMode bypass = new AdminBypassMode(() -> GameSettings.DEFAULTS);
    private final AtomicLong clock = new AtomicLong(10_000L);
    private final List<Runnable> mainThread = new ArrayList<>();
    private final AtomicReference<CompletableFuture<Boolean>> verdict =
            new AtomicReference<>(CompletableFuture.completedFuture(true));
    private final AtomicInteger evaluations = new AtomicInteger();
    private final List<Block> opened = new ArrayList<>();
    private ServerMock server;
    private RPGQuestPlugin plugin;
    private World hub;
    private World wild;
    private List<HubStationException> exceptions;
    private HubStationLockListener listener;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(RPGQuestPlugin.class);
        hub = server.addSimpleWorld(HUB);
        wild = server.addSimpleWorld("world_wild");
        exceptions = new ArrayList<>();
        listener = new HubStationLockListener(
                () -> new HubConfig(HUB, "guide", 8, 3, 2000, exceptions), bypass,
                (player, conditions) -> {
                    evaluations.incrementAndGet();
                    return verdict.get();
                },
                mainThread::add, new HubRefusalMessages(clock::get), NOPLogger.NOP_LOGGER,
                (player, block) -> opened.add(block));
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void everyStationIsClosedByDefaultInTheHubWithOneDiscreetMessage() {
        PlayerMock player = playerIn(hub);
        for (HubStation station : HubStation.values()) {
            for (Material type : station.materials()) {
                PlayerInteractEvent event = click(player, blockAt(hub, type), EquipmentSlot.HAND);
                assertEquals(Event.Result.DENY, event.useInteractedBlock(), type + " doit être fermé");
                assertNotEquals(Event.Result.DENY, event.useItemInHand(), "l'objet en main reste utilisable");
            }
        }
        assertNotNull(player.nextComponentMessage());
        assertNull(player.nextComponentMessage(), "un seul message par fenêtre de 2 s");
        PlayerInteractEvent off = click(player, blockAt(hub, Material.CRAFTING_TABLE), EquipmentSlot.OFF_HAND);
        assertEquals(Event.Result.DENY, off.useInteractedBlock());
    }

    @Test
    void wildStationsAndOrdinaryHubBlocksAreUnchanged() {
        PlayerMock player = playerIn(wild);
        PlayerInteractEvent inWild = click(player, blockAt(wild, Material.CRAFTING_TABLE), EquipmentSlot.HAND);
        assertNotEquals(Event.Result.DENY, inWild.useInteractedBlock());
        for (Material type : List.of(Material.ENDER_CHEST, Material.OAK_DOOR, Material.STONE_BUTTON)) {
            PlayerInteractEvent inHub = click(playerIn(hub), blockAt(hub, type), EquipmentSlot.HAND);
            assertNotEquals(Event.Result.DENY, inHub.useInteractedBlock(), type + " reste libre");
        }
    }

    @Test
    void claimsStationsAndResourceBlocksAreUnchanged() {
        World claims = server.addSimpleWorld("world_claims");
        PlayerMock player = playerIn(claims);
        for (Material type : List.of(Material.CRAFTING_TABLE, Material.FURNACE, Material.ANVIL,
                Material.BREWING_STAND, Material.SMOKER, Material.COMPOSTER, Material.ENCHANTING_TABLE)) {
            PlayerInteractEvent event = click(player, blockAt(claims, type), EquipmentSlot.HAND);
            assertNotEquals(Event.Result.DENY, event.useInteractedBlock(), type + " libre dans Claims");
        }
        assertEquals(0, evaluations.get(), "hors Hub, aucune exception n'est évaluée");
    }

    @Test
    void resourceBlocksAreClosedEvenWithACatchAllException() {
        exceptions.add(exception("tout", Set.of(), List.of(), List.of()));
        PlayerMock player = playerIn(hub);
        for (Material type : List.of(Material.COMPOSTER, Material.JUKEBOX, Material.LAVA_CAULDRON,
                Material.CAULDRON, Material.POTTED_POPPY)) {
            PlayerInteractEvent event = click(player, blockAt(hub, type), EquipmentSlot.HAND);
            assertEquals(Event.Result.DENY, event.useInteractedBlock(), type + " doit être fermé");
        }
        assertEquals(Material.POTTED_POPPY, hub.getBlockAt(lastX, 64, 10).getType(), "aucun bloc modifié");
    }

    @Test
    void anUnconditionalExceptionOpensOnlyItsStationTypes() {
        exceptions.add(exception("salle_de_craft", Set.of(HubStation.CRAFTING_TABLE), List.of(), List.of()));
        PlayerMock player = playerIn(hub);
        assertNotEquals(Event.Result.DENY,
                click(player, blockAt(hub, Material.CRAFTING_TABLE), EquipmentSlot.HAND).useInteractedBlock());
        assertEquals(Event.Result.DENY,
                click(player, blockAt(hub, Material.FURNACE), EquipmentSlot.HAND).useInteractedBlock());
        assertEquals(0, evaluations.get(), "sans condition, rien n'est évalué");
    }

    @Test
    void aBlockExceptionOpensThatBlockOnly() {
        Block allowed = blockAt(hub, Material.ANVIL);
        exceptions.add(exception("enclume_forge", Set.of(HubStation.ANVIL),
                List.of(new HubStationException.BlockRef(HUB, allowed.getX(), allowed.getY(), allowed.getZ())),
                List.of()));
        PlayerMock player = playerIn(hub);
        assertNotEquals(Event.Result.DENY, click(player, allowed, EquipmentSlot.HAND).useInteractedBlock());
        assertEquals(Event.Result.DENY,
                click(player, blockAt(hub, Material.ANVIL), EquipmentSlot.HAND).useInteractedBlock());
    }

    @Test
    void conditionsKnownAtOnceLetVanillaOpenOrRefuseWithTheExceptionMessage() {
        exceptions.add(new HubStationException("four", Set.of(HubStation.FURNACE), List.of(), null, PERMISSION,
                "<gray>Réservé.</gray>"));
        PlayerMock player = playerIn(hub);
        assertNotEquals(Event.Result.DENY,
                click(player, blockAt(hub, Material.FURNACE), EquipmentSlot.HAND).useInteractedBlock());

        verdict.set(CompletableFuture.completedFuture(false));
        assertEquals(Event.Result.DENY,
                click(player, blockAt(hub, Material.FURNACE), EquipmentSlot.HAND).useInteractedBlock());
        assertEquals("Réservé.", plain(player));
        assertTrue(mainThread.isEmpty() && opened.isEmpty());
    }

    @Test
    void theRequiredLevelUsesTheSharedRpgLevelRule() {
        exceptions.add(new HubStationException("niveau", Set.of(), List.of(), 5, List.of(), ""));
        AtomicInteger level = new AtomicInteger(4);
        listener.setRequiredLevelRule(p -> level.get());
        PlayerMock player = playerIn(hub);
        assertEquals(Event.Result.DENY,
                click(player, blockAt(hub, Material.LOOM), EquipmentSlot.HAND).useInteractedBlock());
        level.set(5);
        assertNotEquals(Event.Result.DENY,
                click(player, blockAt(hub, Material.LOOM), EquipmentSlot.HAND).useInteractedBlock());
    }

    @Test
    void anAsyncVerdictHoldsTheClickThenOpensTheStationOnce() {
        exceptions.add(exception("quete", Set.of(HubStation.SMITHING_TABLE), List.of(), PERMISSION));
        CompletableFuture<Boolean> pending = new CompletableFuture<>();
        verdict.set(pending);
        PlayerMock player = playerIn(hub);
        Block table = blockAt(hub, Material.SMITHING_TABLE);
        player.teleport(table.getLocation().clone().add(1.5, 0, 0.5));

        assertEquals(Event.Result.DENY, click(player, table, EquipmentSlot.HAND).useInteractedBlock());
        assertEquals(Event.Result.DENY, click(player, table, EquipmentSlot.OFF_HAND).useInteractedBlock());
        click(player, table, EquipmentSlot.HAND);
        assertEquals(2, evaluations.get(), "chaque clic de la main principale est évalué");

        pending.complete(true);
        assertEquals(1, mainThread.size(), "une seule réouverture en attente");
        mainThread.remove(0).run();
        assertEquals(List.of(table), opened);
        assertNull(player.nextComponentMessage());
    }

    @Test
    void anAsyncRefusalOrADepartureNeverOpensTheStation() {
        exceptions.add(exception("quete", Set.of(), List.of(), PERMISSION));
        PlayerMock player = playerIn(hub);
        Block table = blockAt(hub, Material.STONECUTTER);
        player.teleport(table.getLocation().clone().add(1.5, 0, 0.5));

        CompletableFuture<Boolean> refused = new CompletableFuture<>();
        verdict.set(refused);
        click(player, table, EquipmentSlot.HAND);
        refused.complete(false);
        mainThread.remove(0).run();
        assertEquals(HubStationLockListener.EXCEPTION_REFUSED.replaceAll("<[^>]+>", ""), plain(player));

        CompletableFuture<Boolean> failed = new CompletableFuture<>();
        verdict.set(failed);
        clock.addAndGet(HubRefusalMessages.COOLDOWN_MILLIS);
        click(player, table, EquipmentSlot.HAND);
        failed.completeExceptionally(new IllegalStateException("base indisponible"));
        mainThread.remove(0).run();
        assertNotNull(player.nextComponentMessage(), "une erreur de lecture refuse");

        CompletableFuture<Boolean> late = new CompletableFuture<>();
        verdict.set(late);
        click(player, table, EquipmentSlot.HAND);
        player.setLocation(table.getLocation().clone().add(30, 0, 0));
        late.complete(true);
        mainThread.remove(0).run();
        assertTrue(opened.isEmpty(), "trop loin : rien n'est ouvert");
    }

    @Test
    void theAdminBypassNeedsTheNodeAndAnExplicitSession() {
        PlayerMock admin = playerIn(hub);
        admin.addAttachment(plugin, HubResourceLockListener.BYPASS_PERMISSION, true);
        assertEquals(Event.Result.DENY,
                click(admin, blockAt(hub, Material.ENCHANTING_TABLE), EquipmentSlot.HAND).useInteractedBlock());
        admin.addAttachment(plugin, AdminBypassMode.PERMISSION, true);
        bypass.enable(admin);
        assertNotEquals(Event.Result.DENY,
                click(admin, blockAt(hub, Material.ENCHANTING_TABLE), EquipmentSlot.HAND).useInteractedBlock());
        assertNotEquals(Event.Result.DENY,
                click(admin, blockAt(hub, Material.COMPOSTER), EquipmentSlot.HAND).useInteractedBlock());
    }

    // ---- Utilitaires ---------------------------------------------------------------------------------

    private static HubStationException exception(String id, Set<HubStation> stations,
                                                 List<HubStationException.BlockRef> blocks,
                                                 List<DialogueCondition> conditions) {
        return new HubStationException(id, stations, blocks, null, conditions, "");
    }

    private PlayerMock playerIn(World world) {
        PlayerMock player = server.addPlayer();
        player.teleport(new Location(world, 0, 64, 0));
        return player;
    }

    private int lastX;

    private Block blockAt(World world, Material type) {
        lastX += 2;
        Block block = world.getBlockAt(lastX, 64, 10);
        block.setType(type);
        return block;
    }

    private PlayerInteractEvent click(PlayerMock player, Block block, EquipmentSlot hand) {
        PlayerInteractEvent event = new PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK, null, block,
                BlockFace.UP, hand);
        listener.onUse(event);
        return event;
    }

    private static String plain(PlayerMock player) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(player.nextComponentMessage());
    }
}
