package com.lodygames.rpgquest.quest.progress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.lodygames.rpgquest.RPGQuestPlugin;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * Issue #123 — le retrait lui-même, isolé du moteur de quêtes : jamais plus que demandé, plusieurs
 * piles additionnées, nombre retourné égal au nombre réellement retiré, objets personnalisés
 * RPGQuest épargnés.
 */
class QuestItemWithdrawalTest {

    private static final NamespacedKey CUSTOM_ID = new NamespacedKey("rpgquest", "custom_item_id");

    private ServerMock server;
    private PlayerMock player;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        MockBukkit.load(RPGQuestPlugin.class);
        player = server.addPlayer();
        player.getInventory().clear();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private PlayerInventory inventory() {
        return player.getInventory();
    }

    private ItemStack custom(Material material, int amount) {
        ItemStack stack = new ItemStack(material, amount);
        var meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(CUSTOM_ID, PersistentDataType.STRING, "rpgquest:relique");
        stack.setItemMeta(meta);
        return stack;
    }

    private int countIn(Material material) {
        int total = 0;
        for (ItemStack stack : inventory().getStorageContents()) {
            if (stack != null && stack.getType() == material) {
                total += stack.getAmount();
            }
        }
        return total;
    }

    @Test
    void withdrawingFromAnEmptyInventoryTakesNothing() {
        assertEquals(0, QuestItemWithdrawal.withdraw(inventory(), Material.LEATHER, 4));
        assertEquals(0, QuestItemWithdrawal.available(inventory(), Material.LEATHER));
    }

    @Test
    void aNonPositiveMaximumNeverTouchesTheInventory() {
        inventory().setItem(0, new ItemStack(Material.LEATHER, 5));

        assertEquals(0, QuestItemWithdrawal.withdraw(inventory(), Material.LEATHER, 0));
        assertEquals(0, QuestItemWithdrawal.withdraw(inventory(), Material.LEATHER, -3));
        assertEquals(5, countIn(Material.LEATHER));
    }

    @Test
    void withdrawingLessThanAStackLeavesTheRemainder() {
        inventory().setItem(0, new ItemStack(Material.LEATHER, 5));

        assertEquals(2, QuestItemWithdrawal.withdraw(inventory(), Material.LEATHER, 2));

        assertEquals(3, countIn(Material.LEATHER));
    }

    @Test
    void withdrawingTheWholeStackEmptiesTheSlot() {
        inventory().setItem(3, new ItemStack(Material.LEATHER, 2));

        assertEquals(2, QuestItemWithdrawal.withdraw(inventory(), Material.LEATHER, 2));

        assertNull(inventory().getItem(3), "une pile entièrement prise doit libérer l'emplacement");
    }

    @Test
    void severalPartialStacksAreAddedUpAndConsumedInOrder() {
        inventory().setItem(0, new ItemStack(Material.LEATHER, 2));
        inventory().setItem(4, new ItemStack(Material.LEATHER, 2));
        inventory().setItem(8, new ItemStack(Material.LEATHER, 2));

        assertEquals(6, QuestItemWithdrawal.available(inventory(), Material.LEATHER));
        assertEquals(5, QuestItemWithdrawal.withdraw(inventory(), Material.LEATHER, 5));

        assertEquals(1, countIn(Material.LEATHER), "exactement 5 retirés sur 6 disponibles");
    }

    @Test
    void askingForMoreThanAvailableTakesEverythingAndReportsTheRealCount() {
        inventory().setItem(0, new ItemStack(Material.LEATHER, 3));

        assertEquals(3, QuestItemWithdrawal.withdraw(inventory(), Material.LEATHER, 10),
                "le nombre renvoyé est le nombre RÉELLEMENT retiré, jamais la demande");
        assertEquals(0, countIn(Material.LEATHER));
    }

    @Test
    void anotherMaterialIsNeverTouched() {
        inventory().setItem(0, new ItemStack(Material.LEATHER, 4));
        inventory().setItem(1, new ItemStack(Material.STICK, 4));

        assertEquals(4, QuestItemWithdrawal.withdraw(inventory(), Material.LEATHER, 4));

        assertEquals(4, countIn(Material.STICK));
    }

    @Test
    void aCustomRpgquestItemIsNeitherCountedNorConsumed() {
        inventory().setItem(0, custom(Material.LEATHER, 3));
        inventory().setItem(1, new ItemStack(Material.LEATHER, 2));

        assertEquals(2, QuestItemWithdrawal.available(inventory(), Material.LEATHER),
                "un objet personnalisé n'est jamais remisable, même avec le bon matériau");
        assertEquals(2, QuestItemWithdrawal.withdraw(inventory(), Material.LEATHER, 5));

        ItemStack kept = inventory().getItem(0);
        assertNotNull(kept);
        assertEquals(3, kept.getAmount(), "l'exemplaire personnalisé est intact");
        assertNull(inventory().getItem(1));
    }

    @Test
    void armourAndOffHandAreNeverTouched() {
        inventory().setItem(0, new ItemStack(Material.LEATHER, 1));
        inventory().setItemInOffHand(new ItemStack(Material.LEATHER, 5));
        inventory().setHelmet(new ItemStack(Material.LEATHER_HELMET, 1));

        assertEquals(1, QuestItemWithdrawal.withdraw(inventory(), Material.LEATHER, 6),
                "seul le stockage normal est remisable");

        assertEquals(5, inventory().getItemInOffHand().getAmount(), "la main secondaire est épargnée");
        assertNotNull(inventory().getHelmet());
    }
}
