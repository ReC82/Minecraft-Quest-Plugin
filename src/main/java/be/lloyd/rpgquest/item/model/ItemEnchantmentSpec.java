package be.lloyd.rpgquest.item.model;

import org.bukkit.enchantments.Enchantment;

/** Un enchantement à appliquer à la création. {@code level} n'est volontairement pas plafonné au maximum vanilla. */
public record ItemEnchantmentSpec(Enchantment enchantment, int level) {

    public ItemEnchantmentSpec {
        if (enchantment == null) {
            throw new IllegalArgumentException("enchantment ne peut pas être nul.");
        }
        if (level < 1) {
            throw new IllegalArgumentException("level doit être strictement positif.");
        }
    }
}
