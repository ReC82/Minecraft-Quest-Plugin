package com.lodygames.rpgquest.item.delivery;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.command.CustomItemCommand;
import com.lodygames.rpgquest.item.RpgItemKeys;
import com.lodygames.rpgquest.item.YamlCustomItemRegistry;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.defaults.BukkitCommand;
import org.bukkit.entity.Item;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.slf4j.helpers.NOPLogger;

/**
 * Issue #318 : remise sécurisée commune. MockBukkit est nécessaire pour des {@link ItemStack} avec
 * métadonnées/PDC réels (objets custom) et un vrai inventaire de joueur.
 */
class ItemDeliveryTest {

    private static final NamespacedKey REFINED_CRYSTAL = new NamespacedKey("rpgquest", "refined_crystal");

    @TempDir
    Path tempDir;

    private ServerMock server;
    private YamlCustomItemRegistry registry;
    private PlayerMock player;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        registry = new YamlCustomItemRegistry(tempDir.resolve("items"), NOPLogger.NOP_LOGGER);
        registry.start();
        server.addSimpleWorld("world");
        player = server.addPlayer();
    }

    @AfterEach
    void tearDown() {
        registry.stop();
        MockBukkit.unmock();
    }

    @Test
    void emptyInventoryAcceptsEverything() {
        assertEquals(0, ItemDelivery.missingSlots(player.getInventory(), List.of(new ItemStack(Material.DIRT, 64))));
        assertTrue(ItemDelivery.deliver(player, new ItemStack(Material.DIRT, 64)).delivered());
        assertTrue(player.getInventory().contains(Material.DIRT, 64));
    }

    @Test
    void fullInventoryIsRefusedUntouchedAndNothingIsDropped() {
        fillWith(new ItemStack(Material.STONE, 64));
        ItemStack[] before = snapshot();

        ItemDelivery.Result result = ItemDelivery.deliver(player, registry.create(RpgItemKeys.PIERRE_RETOUR, 1).orElseThrow());

        assertFalse(result.delivered());
        assertFalse(result.dropped());
        assertEquals(1, result.missingSlots());
        assertArrayEquals(before, snapshot());
        assertEquals(0, droppedItems());
    }

    @Test
    void partialStackOfTheSameItemIsUsed() {
        fillWith(new ItemStack(Material.STONE, 64));
        player.getInventory().setItem(0, new ItemStack(Material.DIRT, 60));

        assertTrue(ItemDelivery.deliver(player, new ItemStack(Material.DIRT, 4)).delivered());
        assertEquals(64, player.getInventory().getItem(0).getAmount());
    }

    @Test
    void partialStackTooSmallMeansNothingIsDelivered() {
        fillWith(new ItemStack(Material.STONE, 64));
        player.getInventory().setItem(0, new ItemStack(Material.DIRT, 60));

        ItemDelivery.Result result = ItemDelivery.deliver(player, new ItemStack(Material.DIRT, 5));

        assertFalse(result.delivered());
        assertEquals(1, result.missingSlots());
        assertEquals(60, player.getInventory().getItem(0).getAmount(), "tout ou rien : pas de remise partielle");
    }

    @Test
    void nonStackableCustomItemsNeedOneSlotEach() {
        fillWith(new ItemStack(Material.STONE, 64));
        player.getInventory().setItem(0, null);
        ItemStack stone = registry.create(RpgItemKeys.PIERRE_RETOUR, 1).orElseThrow();

        assertEquals(1, ItemDelivery.missingSlots(player.getInventory(), List.of(stone, stone.clone())));
        assertFalse(ItemDelivery.deliver(player, List.of(stone, stone.clone())).delivered());
        assertEquals(null, player.getInventory().getItem(0), "aucun des deux n'a été remis");
    }

    @Test
    void customItemNeverStacksOnTheVanillaBaseMaterial() {
        fillWith(new ItemStack(Material.STONE, 64));
        ItemStack crystal = registry.create(REFINED_CRYSTAL, 1).orElseThrow();
        player.getInventory().setItem(0, new ItemStack(crystal.getType(), 1));

        assertEquals(1, ItemDelivery.missingSlots(player.getInventory(), List.of(crystal)),
                "même Material mais PDC différent : pas la même pile");
    }

    @Test
    void customItemStacksOnAnIdenticalCustomItemUpToItsOwnMaxStackSize() {
        fillWith(new ItemStack(Material.STONE, 64));
        ItemStack crystal = registry.create(REFINED_CRYSTAL, 15).orElseThrow();
        player.getInventory().setItem(0, crystal);

        assertTrue(ItemDelivery.fits(player.getInventory(), List.of(registry.create(REFINED_CRYSTAL, 1).orElseThrow())));
        assertFalse(ItemDelivery.fits(player.getInventory(), List.of(registry.create(REFINED_CRYSTAL, 2).orElseThrow())),
                "max-stack-size: 16 respecté");
    }

    @Test
    void severalItemsAreCheckedTogether() {
        fillWith(new ItemStack(Material.STONE, 64));
        player.getInventory().setItem(0, null);

        assertTrue(ItemDelivery.fits(player.getInventory(), List.of(new ItemStack(Material.DIRT, 1))));
        assertEquals(1, ItemDelivery.missingSlots(player.getInventory(),
                List.of(new ItemStack(Material.DIRT, 1), new ItemStack(Material.GOLD_INGOT, 1))));
    }

    @Test
    void restoreOrDropDropsOnlyAsLastResortAndSaysSo() {
        fillWith(new ItemStack(Material.STONE, 64));

        ItemDelivery.Result result = ItemDelivery.restoreOrDrop(player, List.of(new ItemStack(Material.DIRT, 3)),
                NOPLogger.NOP_LOGGER, "test");

        assertFalse(result.delivered());
        assertTrue(result.dropped());
        assertEquals(1, droppedItems());
        assertTrue(player.nextMessage().contains("tombée à tes pieds"));
    }

    @Test
    void giveCommandParserRecognisesOnlyCustomItemGive() {
        assertEquals(RpgItemKeys.PIERRE_RETOUR, registry.identify(
                CustomItemGiveCommands.itemFor("customitem give %player% rpgquest:pierre_retour 1", registry).orElseThrow())
                .orElseThrow());
        assertEquals(3, CustomItemGiveCommands.itemFor("/customitem give %player% refined_crystal 3", registry)
                .orElseThrow().getAmount());
        assertTrue(CustomItemGiveCommands.itemFor("claim admin sendhome %player%", registry).isEmpty());
        assertTrue(CustomItemGiveCommands.itemFor("customitem give %player% rpgquest:inconnu", registry).isEmpty());
        assertTrue(CustomItemGiveCommands.itemFor("customitem give %player% pierre_retour abc", registry).isEmpty());
    }

    /** Le cas exact du propriétaire : Jo remet une Pierre de retour par la console, inventaire plein. */
    @Test
    void customItemGiveFromConsoleWithFullInventoryDropsNothingAndNeverClaimsSuccess() {
        fillWith(new ItemStack(Material.STONE, 64));
        ItemStack[] before = snapshot();
        CustomItemCommand command = new CustomItemCommand(registry);

        giveFromConsole(command, "rpgquest:pierre_retour");

        assertArrayEquals(before, snapshot());
        assertEquals(0, droppedItems());
        String message = player.nextMessage();
        assertTrue(message.contains("inventaire est plein"), message);
        assertFalse(message.contains("reçu"));
    }

    @Test
    void customItemGiveWithRoomDeliversAndConfirms() {
        CustomItemCommand command = new CustomItemCommand(registry);

        giveFromConsole(command, "rpgquest:pierre_retour");

        assertTrue(Arrays.stream(player.getInventory().getStorageContents())
                .anyMatch(stack -> stack != null && registry.identify(stack).map(RpgItemKeys.PIERRE_RETOUR::equals).orElse(false)));
        assertTrue(player.nextMessage().contains("reçu"));
    }

    private void giveFromConsole(CustomItemCommand command, String itemId) {
        Command bukkitCommand = new BukkitCommand("customitem") {
            @Override
            public boolean execute(CommandSender sender, String label, String[] args) {
                return true;
            }
        };
        command.onCommand(server.getConsoleSender(), bukkitCommand, "customitem",
                new String[] {"give", player.getName(), itemId, "1"});
    }

    /**
     * Remplit TOUTES les cases, armure et seconde main comprises : MockBukkit, contrairement à Paper,
     * y range le surplus d'un {@code addItem} — sans cela un « inventaire plein » n'y serait pas plein.
     */
    private void fillWith(ItemStack stack) {
        ItemStack[] contents = new ItemStack[player.getInventory().getContents().length];
        for (int i = 0; i < contents.length; i++) {
            contents[i] = stack.clone();
        }
        player.getInventory().setContents(contents);
    }

    private ItemStack[] snapshot() {
        return Arrays.stream(player.getInventory().getStorageContents())
                .map(stack -> stack == null ? null : stack.clone())
                .toArray(ItemStack[]::new);
    }

    private long droppedItems() {
        return player.getWorld().getEntitiesByClass(Item.class).size();
    }
}
