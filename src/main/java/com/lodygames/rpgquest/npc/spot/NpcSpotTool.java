package com.lodygames.rpgquest.npc.spot;

import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * L'outil « Emplacements PNJ » : fabrication et reconnaissance (issue #381, correction du 15/10).
 *
 * <p>Même modèle que {@code BuildingSiteTool} : reconnu <strong>uniquement</strong> par la clé PDC
 * {@link #TOOL_KEY}. Un porte-armure renommé à l'enclume ne fait rien ; un porte-armure ordinaire
 * se pose normalement.</p>
 *
 * <h2>Pourquoi un porte-armure</h2>
 *
 * <p>Il évoque « une silhouette à placer », et n'est la wand d'aucun plugin connu : ni la hache en
 * bois / la boussole de WorldEdit, ni le bâton de sélection de Citizens, ni la tige de blaze de
 * l'outil de zone, la houe en fer des emplacements de construction ou la pelle en or des villes.
 * Son usage vanilla (le poser) est annulé par le listener quand l'objet porte la clé.</p>
 */
public final class NpcSpotTool {

    /** Clé PDC posée sur l'outil — seule façon de le reconnaître. */
    public static final NamespacedKey TOOL_KEY = new NamespacedKey("rpgquest", "npc_spot_tool");

    /** Matériau de l'outil. Choisi pour ne collider avec aucune wand connue (voir la classe). */
    public static final Material TOOL_MATERIAL = Material.ARMOR_STAND;

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private NpcSpotTool() {
    }

    /** {@code true} si cet objet est l'outil (PDC uniquement). */
    public static boolean isTool(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) {
            return false;
        }
        Byte marker = stack.getItemMeta().getPersistentDataContainer().get(TOOL_KEY, PersistentDataType.BYTE);
        return marker != null && marker == 1;
    }

    /** Un exemplaire de l'outil. Nom et lore guident l'humain ; ils n'identifient jamais l'objet. */
    public static ItemStack create() {
        ItemStack stack = new ItemStack(TOOL_MATERIAL);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(plain("<aqua>Emplacements PNJ</aqua>"));
        meta.lore(List.of(
                plain("<gray>Clic droit sur un bloc : enregistre un</gray>"),
                plain("<gray>emplacement de PNJ juste au-dessus.</gray>"),
                plain("<dark_gray>Nom, PNJ, déplacement : PlugAdmin › PNJ › Emplacements.</dark_gray>"),
                plain("<dark_gray>Ranger : /rpgadmin npc spot tool</dark_gray>")));
        meta.getPersistentDataContainer().set(TOOL_KEY, PersistentDataType.BYTE, (byte) 1);
        stack.setItemMeta(meta);
        return stack;
    }

    /**
     * Retire tous les exemplaires de l'outil de l'inventaire (main secondaire comprise).
     *
     * @return le nombre d'exemplaires retirés
     */
    public static int removeFrom(PlayerInventory inventory) {
        int removed = 0;
        ItemStack[] contents = inventory.getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            if (isTool(contents[slot])) {
                removed += contents[slot].getAmount();
                inventory.setItem(slot, null);
            }
        }
        return removed;
    }

    private static Component plain(String miniMessage) {
        return MM.deserialize(miniMessage).decoration(TextDecoration.ITALIC, false);
    }
}
