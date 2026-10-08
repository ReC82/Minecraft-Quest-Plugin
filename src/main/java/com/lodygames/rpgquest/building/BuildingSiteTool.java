package com.lodygames.rpgquest.building;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import java.util.List;

/**
 * L'outil d'emplacement de construction : fabrication et reconnaissance (issue #213).
 *
 * <h2>Reconnu par son PDC, jamais par son nom ni son matériau</h2>
 *
 * <p>Règle permanente du projet, et ici elle a une conséquence concrète : un joueur peut nommer une
 * houe « Outil d'emplacement de construction » dans une enclume, et elle ne fera rien. Seul un objet
 * portant la clé {@link #TOOL_KEY} est l'outil.</p>
 *
 * <h2>Pourquoi une houe en fer, et pas une hache en bois</h2>
 *
 * <p>La hache en bois est l'outil de sélection par défaut de WorldEdit, qui la reconnaît <em>par son
 * type d'objet</em> et non par PDC. Les deux plugins se disputeraient le même clic — c'est le piège
 * dans lequel l'outil de zone est déjà tombé, et la raison pour laquelle il utilise une tige de
 * blaze. On évite aussi la tige de blaze : deux outils d'administration qui se ressemblent dans une
 * barre d'inventaire finissent par être confondus. Une houe en fer n'est associée à aucune wand par
 * défaut, et le ticket demande explicitement de ne pas perturber la hache WorldEdit.</p>
 */
public final class BuildingSiteTool {

    /** Clé PDC posée sur l'outil — seule façon de le reconnaître. */
    public static final NamespacedKey TOOL_KEY = new NamespacedKey("rpgquest", "building_site_tool");

    /** Matériau de l'outil. Choisi pour ne collider avec aucune wand connue (voir la classe). */
    public static final Material TOOL_MATERIAL = Material.IRON_HOE;

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private BuildingSiteTool() {
    }

    /** {@code true} si cet objet est l'outil d'emplacement (PDC uniquement). */
    public static boolean isTool(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = stack.getItemMeta();
        Byte marker = meta.getPersistentDataContainer().get(TOOL_KEY, PersistentDataType.BYTE);
        return marker != null && marker == 1;
    }

    /**
     * Construit un exemplaire de l'outil. Le nom et le lore sont là pour l'humain qui l'a dans sa
     * barre ; ils ne servent <strong>jamais</strong> à l'identification.
     */
    public static ItemStack create() {
        ItemStack stack = new ItemStack(TOOL_MATERIAL);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(MM.deserialize(
                "<gold>Outil d'emplacement de construction</gold>").decoration(
                net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        meta.lore(List.of(
                plain("<gray>Clic droit sur un bloc : crée un emplacement</gray>"),
                plain("<gray>à la case libre contre la face cliquée,</gray>"),
                plain("<gray>orienté selon votre regard.</gray>"),
                plain("<dark_gray>Renommage depuis le Control Panel.</dark_gray>")));
        meta.getPersistentDataContainer().set(TOOL_KEY, PersistentDataType.BYTE, (byte) 1);
        stack.setItemMeta(meta);
        return stack;
    }

    private static Component plain(String miniMessage) {
        return MM.deserialize(miniMessage)
                .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false);
    }
}
