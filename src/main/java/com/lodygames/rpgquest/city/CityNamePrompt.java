package com.lodygames.rpgquest.city;

import java.util.List;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * Fenêtre de saisie du nom d'une ville : une enclume vanilla, exactement comme le nom d'un
 * emplacement de construction (#227, voir {@code building.BuildingSiteNamePrompt} pour l'audit de
 * l'API). Seule la clé PDC diffère, pour que les deux fenêtres ne se confondent jamais.
 */
public final class CityNamePrompt {

    public static final NamespacedKey PROMPT_KEY = new NamespacedKey("rpgquest", "city_name_prompt");
    public static final int RESULT_SLOT = 2;
    public static final String PREFILL = "Nouvelle ville";

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private CityNamePrompt() {
    }

    public static Optional<InventoryView> open(Player player, PendingCityRegistry.PendingCity pending) {
        InventoryView view = player.openAnvil(player.getLocation(), true);
        if (view == null) {
            return Optional.empty();
        }
        Inventory top = view.getTopInventory();
        top.setItem(0, item("<white>" + PREFILL + "</white>", List.of(
                lore("<gray>Écrivez le nom de la ville,</gray>"),
                lore("<gray>puis cliquez le résultat à droite.</gray>"),
                lore("<dark_gray>Centre : " + pending.world() + " " + pending.positionLabel() + "</dark_gray>"),
                lore("<dark_gray>Fermer la fenêtre annule : rien ne sera créé.</dark_gray>"))));
        if (top instanceof AnvilInventory anvil) {
            anvil.setRepairCost(0);
            anvil.setMaximumRepairCost(0);
        }
        return Optional.of(view);
    }

    public static boolean isPromptItem(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) {
            return false;
        }
        Byte marker = stack.getItemMeta().getPersistentDataContainer().get(PROMPT_KEY, PersistentDataType.BYTE);
        return marker != null && marker == 1;
    }

    public static ItemStack resultItem(String typedName) {
        String shown = typedName == null || typedName.isBlank() ? PREFILL : typedName;
        return item("<green>" + shown.replace("<", "\\<") + "</green>", List.of(
                lore("<yellow>Cliquer pour créer la ville</yellow>"),
                lore("<dark_gray>sous ce nom.</dark_gray>")));
    }

    /** Texte réellement tapé ; le prérempli n'est jamais substitué (un nom doit être une décision). */
    public static String typedName(Inventory top) {
        if (top instanceof AnvilInventory anvil) {
            String text = anvil.getRenameText();
            return text == null ? "" : text.trim();
        }
        return "";
    }

    private static ItemStack item(String name, List<Component> lore) {
        ItemStack stack = new ItemStack(Material.NAME_TAG);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(MM.deserialize(name).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        meta.getPersistentDataContainer().set(PROMPT_KEY, PersistentDataType.BYTE, (byte) 1);
        stack.setItemMeta(meta);
        return stack;
    }

    private static Component lore(String miniMessage) {
        return MM.deserialize(miniMessage).decoration(TextDecoration.ITALIC, false);
    }
}
