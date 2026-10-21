package com.lodygames.rpgquest.item;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.RPGQuestPlugin;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.DragType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.inventory.InventoryMock;
import org.mockbukkit.mockbukkit.inventory.SimpleInventoryViewMock;

/**
 * Issue #625 — refus de stockage des objets permanents liés au joueur : évènements construits à la
 * main puis passés directement aux gestionnaires (même patron que {@link SoulboundItemListenerTest}),
 * sur un coffre et des joueurs MockBukkit isolés — aucun coffre ni inventaire réel.
 */
class StorageLockedItemListenerTest {

    private static final int CHEST_SIZE = 27;

    @TempDir
    Path tempDir;

    private ServerMock server;
    private YamlCustomItemRegistry customItemRegistry;
    private StorageLockedItemListener listener;
    private final AtomicLong now = new AtomicLong(1_000_000L);

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        RPGQuestPlugin plugin = MockBukkit.load(RPGQuestPlugin.class);
        server.addSimpleWorld("world");

        customItemRegistry = new YamlCustomItemRegistry(tempDir.resolve("items"), plugin.getSLF4JLogger());
        customItemRegistry.start();

        SoulboundItemService service = new SoulboundItemService(customItemRegistry);
        service.registerStorageLocked(RpgItemKeys.PIERRE_RETOUR);
        service.registerStorageLocked(RpgItemKeys.ACTE_PROPRIETE);
        service.registerStorageLocked(RpgItemKeys.JOURNAL_QUETES);
        service.register(RpgItemKeys.RUNE_RAPPEL);
        listener = new StorageLockedItemListener(service, now::get);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private ItemStack item(NamespacedKey id) {
        return customItemRegistry.create(id, 1).orElseThrow();
    }

    private InventoryView openChest(PlayerMock player) {
        Inventory chest = server.createInventory(null, CHEST_SIZE);
        InventoryView view = player.openInventory(chest);
        assertNotNull(view);
        return view;
    }

    /** Vue de l'inventaire du joueur lui-même : en jeu, le haut est la grille 2×2 (CRAFTING). */
    private static InventoryView ownInventoryView(PlayerMock player) {
        return new SimpleInventoryViewMock(player, new InventoryMock(player, 5, InventoryType.CRAFTING),
                player.getInventory(), InventoryType.CRAFTING);
    }

    private InventoryClickEvent click(InventoryView view, int rawSlot, ClickType click, InventoryAction action) {
        return new InventoryClickEvent(view, InventoryType.SlotType.CONTAINER, rawSlot, click, action);
    }

    @Test
    void placingAPermanentItemFromTheCursorIntoAChestIsRefused() {
        for (NamespacedKey id : List.of(RpgItemKeys.JOURNAL_QUETES, RpgItemKeys.PIERRE_RETOUR, RpgItemKeys.ACTE_PROPRIETE)) {
            PlayerMock player = server.addPlayer();
            InventoryView view = openChest(player);
            view.setCursor(item(id));

            InventoryClickEvent event = click(view, 0, ClickType.LEFT, InventoryAction.PLACE_ALL);
            listener.onInventoryClick(event);

            assertTrue(event.isCancelled(), "dépôt au curseur refusé : " + id);
            assertNotNull(player.nextComponentMessage(), "le joueur est prévenu");
        }
    }

    @Test
    void rightClickAndCursorSwapIntoAChestAreRefused() {
        PlayerMock player = server.addPlayer();
        InventoryView view = openChest(player);
        view.setCursor(item(RpgItemKeys.JOURNAL_QUETES));

        InventoryClickEvent placeOne = click(view, 0, ClickType.RIGHT, InventoryAction.PLACE_ONE);
        listener.onInventoryClick(placeOne);
        InventoryClickEvent swap = click(view, 1, ClickType.LEFT, InventoryAction.SWAP_WITH_CURSOR);
        listener.onInventoryClick(swap);

        assertTrue(placeOne.isCancelled());
        assertTrue(swap.isCancelled());
    }

    @Test
    void shiftClickFromThePlayerInventoryIntoAChestIsRefused() {
        PlayerMock player = server.addPlayer();
        player.getInventory().setItem(9, item(RpgItemKeys.PIERRE_RETOUR));
        InventoryView view = openChest(player);

        // Raw 27 = première case du sac du joueur sous un coffre de 27 ; MockBukkit ne relie pas la
        // case brute à l'inventaire du joueur pour getCurrentItem(), d'où le setCurrentItem explicite.
        InventoryClickEvent event = click(view, CHEST_SIZE, ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY);
        event.setCurrentItem(player.getInventory().getItem(9));
        listener.onInventoryClick(event);

        assertTrue(event.isCancelled());
        assertTrue(player.getInventory().getItem(9) != null, "l'objet reste côté joueur");
    }

    @Test
    void numberKeyAndOffhandSwapIntoAChestAreRefused() {
        PlayerMock player = server.addPlayer();
        player.getInventory().setItem(0, item(RpgItemKeys.ACTE_PROPRIETE));
        player.getInventory().setItemInOffHand(item(RpgItemKeys.JOURNAL_QUETES));
        InventoryView view = openChest(player);

        InventoryClickEvent numberKey = new InventoryClickEvent(view, InventoryType.SlotType.CONTAINER, 3,
                ClickType.NUMBER_KEY, InventoryAction.HOTBAR_SWAP, 0);
        listener.onInventoryClick(numberKey);
        InventoryClickEvent offhand = new InventoryClickEvent(view, InventoryType.SlotType.CONTAINER, 4,
                ClickType.SWAP_OFFHAND, InventoryAction.HOTBAR_SWAP);
        listener.onInventoryClick(offhand);

        assertTrue(numberKey.isCancelled(), "touche numérique refusée");
        assertTrue(offhand.isCancelled(), "touche F refusée");
    }

    @Test
    void draggingAPermanentItemOverChestSlotsIsRefusedButNotInsideThePlayerInventory() {
        PlayerMock player = server.addPlayer();
        InventoryView view = openChest(player);
        ItemStack journal = item(RpgItemKeys.JOURNAL_QUETES);

        InventoryDragEvent overChest = new InventoryDragEvent(view, null, journal, false,
                Map.of(2, journal.clone(), CHEST_SIZE + 1, journal.clone()));
        listener.onInventoryDrag(overChest);
        InventoryDragEvent insidePlayer = new InventoryDragEvent(view, null, journal, false,
                Map.of(CHEST_SIZE + 1, journal.clone()));
        listener.onInventoryDrag(insidePlayer);

        assertTrue(overChest.isCancelled());
        assertFalse(insidePlayer.isCancelled());
    }

    @Test
    void ordinaryItemsAndTheConsumableRuneStillGoIntoChests() {
        PlayerMock player = server.addPlayer();
        for (ItemStack stack : List.of(new ItemStack(Material.DIAMOND, 5), item(RpgItemKeys.RUNE_RAPPEL))) {
            InventoryView view = openChest(player);
            view.setCursor(stack);
            InventoryClickEvent event = click(view, 0, ClickType.LEFT, InventoryAction.PLACE_ALL);
            listener.onInventoryClick(event);
            assertFalse(event.isCancelled(), "stockage inchangé : " + stack.getType());
            InventoryDragEvent drag = new InventoryDragEvent(view, null, stack, false, Map.of(1, stack.clone()));
            listener.onInventoryDrag(drag);
            assertFalse(drag.isCancelled());
        }
        assertNull(player.nextComponentMessage(), "aucun message pour un objet libre");
    }

    @Test
    void takingAnAlreadyStoredPermanentItemOutOfAChestStaysPossible() {
        PlayerMock player = server.addPlayer();
        InventoryView view = openChest(player);
        view.getTopInventory().setItem(0, item(RpgItemKeys.JOURNAL_QUETES));

        InventoryClickEvent pickup = click(view, 0, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        listener.onInventoryClick(pickup);
        InventoryClickEvent shiftOut = click(view, 0, ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY);
        listener.onInventoryClick(shiftOut);

        assertFalse(pickup.isCancelled(), "récupération manuelle permise");
        assertFalse(shiftOut.isCancelled(), "récupération manuelle permise");
    }

    @Test
    void shiftClickInsideThePlayersOwnInventoryStaysFree() {
        PlayerMock player = server.addPlayer();
        player.getInventory().setItem(9, item(RpgItemKeys.JOURNAL_QUETES));
        InventoryView own = ownInventoryView(player);

        InventoryClickEvent event = new InventoryClickEvent(own, InventoryType.SlotType.CONTAINER, 9,
                ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY);
        listener.onInventoryClick(event);

        assertFalse(event.isCancelled());
    }

    @Test
    void puttingAPermanentItemIntoABundleIsRefused() {
        PlayerMock player = server.addPlayer();
        InventoryView own = ownInventoryView(player);
        own.setCursor(item(RpgItemKeys.PIERRE_RETOUR));

        InventoryClickEvent event = new InventoryClickEvent(own, InventoryType.SlotType.CONTAINER, 9,
                ClickType.RIGHT, InventoryAction.PLACE_ALL_INTO_BUNDLE);
        listener.onInventoryClick(event);

        assertTrue(event.isCancelled());
    }

    @Test
    void hoppersNeverMoveAPermanentItemButStillMoveOrdinaryOnes() {
        Inventory source = server.createInventory(null, InventoryType.HOPPER);
        Inventory destination = server.createInventory(null, CHEST_SIZE);

        InventoryMoveItemEvent locked = new InventoryMoveItemEvent(source, item(RpgItemKeys.JOURNAL_QUETES), destination, true);
        listener.onInventoryMove(locked);
        InventoryMoveItemEvent ordinary = new InventoryMoveItemEvent(source, new ItemStack(Material.COBBLESTONE), destination, true);
        listener.onInventoryMove(ordinary);

        assertTrue(locked.isCancelled());
        assertFalse(ordinary.isCancelled());
    }

    @Test
    void rapidRepeatedAttemptsSendASingleMessageUntilTheCooldownElapses() {
        PlayerMock player = server.addPlayer();
        InventoryView view = openChest(player);
        view.setCursor(item(RpgItemKeys.JOURNAL_QUETES));

        for (int i = 0; i < 5; i++) {
            InventoryClickEvent event = click(view, i, ClickType.LEFT, InventoryAction.PLACE_ALL);
            listener.onInventoryClick(event);
            assertTrue(event.isCancelled(), "chaque tentative reste refusée");
        }
        assertNotNull(player.nextComponentMessage());
        assertNull(player.nextComponentMessage(), "pas de spam");

        now.addAndGet(StorageLockedItemListener.MESSAGE_COOLDOWN_MILLIS);
        listener.onInventoryClick(click(view, 0, ClickType.LEFT, InventoryAction.PLACE_ALL));
        assertNotNull(player.nextComponentMessage(), "message de nouveau après le délai");
    }

    @Test
    void onlyItemTakingBlocksAreGuardedOnRightClick() {
        assertTrue(InventoryTransfers.takesItemsOnClick(Material.CHISELED_BOOKSHELF));
        assertTrue(InventoryTransfers.takesItemsOnClick(Material.DECORATED_POT));
        assertTrue(InventoryTransfers.takesItemsOnClick(Material.OAK_SHELF));
        assertFalse(InventoryTransfers.takesItemsOnClick(Material.CHEST), "un coffre s'ouvre : le clic est filtré ensuite");
        assertFalse(InventoryTransfers.takesItemsOnClick(Material.STONE));
    }
}
