package com.lodygames.rpgquest.city;

import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * L'outil de ville (issue #178), sur le modèle de l'outil d'emplacement de construction (#213) :
 * reconnu <strong>uniquement</strong> par sa clé PDC, jamais par son nom ni son matériau.
 *
 * <p>Une pelle en or : aucune wand connue ne l'utilise (WorldEdit prend la hache en bois, l'outil de
 * zone la tige de blaze, l'outil d'emplacement la houe en fer), et elle ne ressemble à aucun d'eux
 * dans une barre d'inventaire. Un clic droit désigne le centre ; il n'y a pas de second coin.</p>
 */
public final class CityTool {

    public static final NamespacedKey TOOL_KEY = new NamespacedKey("rpgquest", "city_tool");
    public static final Material TOOL_MATERIAL = Material.GOLDEN_SHOVEL;

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private CityTool() {
    }

    public static boolean isTool(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) {
            return false;
        }
        Byte marker = stack.getItemMeta().getPersistentDataContainer().get(TOOL_KEY, PersistentDataType.BYTE);
        return marker != null && marker == 1;
    }

    public static ItemStack create() {
        ItemStack stack = new ItemStack(TOOL_MATERIAL);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(plain("<gold>Outil de ville</gold>"));
        meta.lore(List.of(
                plain("<gray>Clic droit sur un bloc : choisit le</gray>"),
                plain("<gray>centre d'une nouvelle ville, puis son nom.</gray>"),
                plain("<gray>Après « Déplacer le centre » (panel ou</gray>"),
                plain("<gray>/rpgadmin ville deplacer) : déplace ce centre.</gray>"),
                plain("<dark_gray>Taille et réglages : PlugAdmin › Villes.</dark_gray>")));
        meta.getPersistentDataContainer().set(TOOL_KEY, PersistentDataType.BYTE, (byte) 1);
        stack.setItemMeta(meta);
        return stack;
    }

    private static Component plain(String miniMessage) {
        return MM.deserialize(miniMessage).decoration(TextDecoration.ITALIC, false);
    }
}
