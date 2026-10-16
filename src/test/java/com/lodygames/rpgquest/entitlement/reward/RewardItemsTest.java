package com.lodygames.rpgquest.entitlement.reward;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

/** Issue #177 — piles du catalogue : livre enchanté (stocké), objet enchanté, potion. */
class RewardItemsTest {

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private static Optional<ItemStack> create(RewardDefinition.Item line) {
        return RewardItems.create(line, (id, amount) -> Optional.empty());
    }

    @Test
    void anEnchantedBookStoresTheEnchantmentInsteadOfCarryingIt() {
        ItemStack book = create(new RewardDefinition.Item("ENCHANTED_BOOK", 1, Map.of("sharpness", 3), null))
                .orElseThrow();
        EnchantmentStorageMeta meta = (EnchantmentStorageMeta) book.getItemMeta();
        assertEquals(3, meta.getStoredEnchantLevel(Enchantment.SHARPNESS));
        assertTrue(book.getEnchantments().isEmpty());
    }

    @Test
    void anItemCarriesItsEnchantments() {
        ItemStack sword = create(new RewardDefinition.Item("DIAMOND_SWORD", 1, Map.of("sharpness", 5), null))
                .orElseThrow();
        assertEquals(5, sword.getEnchantmentLevel(Enchantment.SHARPNESS));
    }

    @Test
    void aPotionGetsItsType() {
        ItemStack potion = create(new RewardDefinition.Item("POTION", 2, Map.of(), "strength")).orElseThrow();
        assertEquals(PotionType.STRENGTH, ((PotionMeta) potion.getItemMeta()).getBasePotionType());
        assertEquals(2, potion.getAmount());
    }

    @Test
    void unknownEnchantmentOrPotionLeavesNothing() {
        assertTrue(create(new RewardDefinition.Item("DIAMOND_SWORD", 1, Map.of("nope", 1), null)).isEmpty());
        assertTrue(create(new RewardDefinition.Item("POTION", 1, Map.of(), "nope")).isEmpty());
        assertEquals(Material.DIAMOND, create(new RewardDefinition.Item("DIAMOND", 1)).orElseThrow().getType());
    }
}
