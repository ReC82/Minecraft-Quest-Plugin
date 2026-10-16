package com.lodygames.rpgquest.entitlement.reward;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;

/**
 * Pile d'une ligne du catalogue de récompenses (#422) : matériau Paper ou objet custom (PDC), avec
 * enchantements et type de potion éventuels (#177). Vide = objet, enchantement ou potion inconnu : le
 * droit reste en attente, jamais remplacé par autre chose.
 */
public final class RewardItems {

    private RewardItems() {
    }

    public static Optional<ItemStack> create(RewardDefinition.Item line,
                                             BiFunction<NamespacedKey, Integer, Optional<ItemStack>> customItems) {
        if (line.custom()) {
            NamespacedKey id = NamespacedKey.fromString(line.itemId());
            return id == null ? Optional.empty() : customItems.apply(id, line.amount());
        }
        Material material = Material.matchMaterial(line.itemId());
        if (material == null || material.isAir()) {
            return Optional.empty();
        }
        ItemStack stack = new ItemStack(material, line.amount());
        if (line.enchantments().isEmpty() && line.potion() == null) {
            return Optional.of(stack);
        }
        ItemMeta meta = stack.getItemMeta();
        for (Map.Entry<String, Integer> e : line.enchantments().entrySet()) {
            Enchantment enchantment = RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT)
                    .get(NamespacedKey.minecraft(e.getKey()));
            if (enchantment == null) {
                return Optional.empty();
            }
            if (meta instanceof EnchantmentStorageMeta book) {
                // Livre enchanté : l'enchantement est STOCKÉ dans le livre, jamais appliqué ailleurs.
                book.addStoredEnchant(enchantment, e.getValue(), true);
            } else {
                meta.addEnchant(enchantment, e.getValue(), true);
            }
        }
        if (line.potion() != null) {
            PotionType type = RegistryAccess.registryAccess().getRegistry(RegistryKey.POTION)
                    .get(NamespacedKey.minecraft(line.potion()));
            if (type == null || !(meta instanceof PotionMeta potionMeta)) {
                return Optional.empty();
            }
            potionMeta.setBasePotionType(type);
        }
        stack.setItemMeta(meta);
        return Optional.of(stack);
    }
}
