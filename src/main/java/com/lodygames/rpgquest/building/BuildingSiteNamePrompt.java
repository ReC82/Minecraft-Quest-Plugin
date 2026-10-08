package com.lodygames.rpgquest.building;

import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import java.util.List;
import java.util.Optional;

/**
 * La fenêtre de saisie du nom : une <strong>enclume vanilla</strong> (issue #227).
 *
 * <h2>Pourquoi une enclume, et ce que l'API publique permet vraiment</h2>
 *
 * <p>Audit de l'API Paper <strong>1.21.11 réellement installée</strong>, fait avant d'écrire une
 * ligne :</p>
 *
 * <ul>
 *   <li>{@code HumanEntity.openAnvil(Location, boolean)} — ouvre une enclume sans qu'il en existe
 *       une dans le monde. <strong>API publique Bukkit</strong>, aucun NMS.</li>
 *   <li>{@code AnvilView.getRenameText()} et {@code AnvilInventory.getRenameText()} — renvoient
 *       <strong>le texte que le client a tapé</strong>. C'est la pièce qui rend l'approche possible :
 *       sans elle, il faudrait deviner le nom depuis l'objet de résultat.</li>
 *   <li>{@code PrepareAnvilEvent} + {@code AnvilView.setRepairCost(int)} — permettent de forcer un
 *       objet de résultat et un coût nul, donc un bouton de validation toujours cliquable, sans
 *       exiger d'XP du joueur.</li>
 * </ul>
 *
 * <p>Le chat n'a donc pas été retenu, conformément à la préférence du ticket : il aurait obligé à
 * taper un message au milieu de la conversation du serveur, sans titre, sans valeur préremplie et
 * sans bouton d'annulation — alors que l'enclume donne les quatre gratuitement.</p>
 *
 * <h2>Ce que cette classe fait, et ce qu'elle ne fait pas</h2>
 *
 * <p>Elle fabrique et ouvre la fenêtre. Elle ne décide rien : l'état vit dans
 * {@link PendingBuildingSiteRegistry} et la règle dans {@link BuildingSiteName}, tous deux sans
 * Bukkit et donc réellement testables. C'est volontaire — ce qu'un test ne peut pas vérifier ici,
 * c'est le rendu chez le client, et aucune quantité de code ne le rendrait vérifiable.</p>
 */
public final class BuildingSiteNamePrompt {

    /** Marque l'objet posé dans l'enclume : il n'est à nous que s'il porte cette clé. */
    public static final NamespacedKey PROMPT_KEY =
            new NamespacedKey("rpgquest", "building_site_name_prompt");

    /** Emplacement du résultat dans une enclume : c'est lui qu'on clique pour valider. */
    public static final int RESULT_SLOT = 2;

    /** Valeur préremplie, pour que le joueur ait quelque chose à corriger plutôt qu'un vide. */
    public static final String PREFILL = "Nouvel emplacement";

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private BuildingSiteNamePrompt() {
    }

    /**
     * Ouvre la fenêtre de saisie pour ce joueur.
     *
     * <p>{@code force = true} parce qu'il n'y a aucune enclume dans le monde : on ouvre l'interface,
     * pas un bloc. Le joueur n'a besoin ni d'enclume, ni de niveaux, ni de matériaux.</p>
     *
     * @return la vue ouverte, vide si le serveur a refusé l'ouverture
     */
    public static Optional<InventoryView> open(Player player, PendingBuildingSite pending) {
        InventoryView view = player.openAnvil(player.getLocation(), true);
        if (view == null) {
            return Optional.empty();
        }
        Inventory top = view.getTopInventory();
        top.setItem(0, promptItem(pending));
        // Coût nul : valider un nom ne doit rien coûter, et un coût non nul rendrait le bouton
        // inutilisable pour un joueur en mode survie sans niveaux.
        if (top instanceof AnvilInventory anvil) {
            anvil.setRepairCost(0);
            anvil.setMaximumRepairCost(0);
        }
        return Optional.of(view);
    }

    /**
     * L'objet déposé à gauche dans l'enclume. C'est lui que le joueur renomme, et son nom affiché
     * fournit la valeur préremplie.
     *
     * <p>Un <strong>nom de donjon</strong> ({@code NAME_TAG}) : l'objet dont le sens est « ceci porte
     * un nom », et que l'enclume accepte de renommer sans discuter.</p>
     */
    public static ItemStack promptItem(PendingBuildingSite pending) {
        ItemStack stack = new ItemStack(Material.NAME_TAG);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(MM.deserialize("<white>" + PREFILL + "</white>")
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                lore("<gray>Écrivez le nom de l'emplacement,</gray>"),
                lore("<gray>puis cliquez le résultat à droite.</gray>"),
                lore("<dark_gray>" + pending.world() + " " + pending.positionLabel()
                        + " · " + pending.facing().label() + "</dark_gray>"),
                lore("<dark_gray>Fermer la fenêtre annule : rien ne sera créé.</dark_gray>")));
        meta.getPersistentDataContainer().set(PROMPT_KEY, PersistentDataType.BYTE, (byte) 1);
        stack.setItemMeta(meta);
        return stack;
    }

    /**
     * Cet objet est-il le nôtre ? Vérifié par PDC, jamais par type ni par nom — un joueur peut
     * poser un nom de donjon dans une vraie enclume, et cela ne doit rien déclencher.
     */
    public static boolean isPromptItem(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) {
            return false;
        }
        Byte marker = stack.getItemMeta().getPersistentDataContainer()
                .get(PROMPT_KEY, PersistentDataType.BYTE);
        return marker != null && marker == 1;
    }

    /** L'objet de résultat à droite : ce que le joueur clique pour valider. */
    public static ItemStack resultItem(String typedName) {
        ItemStack stack = new ItemStack(Material.NAME_TAG);
        ItemMeta meta = stack.getItemMeta();
        String shown = typedName == null || typedName.isBlank() ? PREFILL : typedName;
        meta.displayName(MM.deserialize("<green>" + escape(shown) + "</green>")
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                lore("<yellow>Cliquer pour créer l'emplacement</yellow>"),
                lore("<dark_gray>sous ce nom.</dark_gray>")));
        meta.getPersistentDataContainer().set(PROMPT_KEY, PersistentDataType.BYTE, (byte) 1);
        stack.setItemMeta(meta);
        return stack;
    }

    /**
     * Le texte réellement tapé par le joueur, lu sur l'enclume.
     *
     * <p>Vide si le joueur n'a rien tapé : la valeur préremplie n'est <strong>pas</strong>
     * substituée ici. Un nom doit être une décision, et accepter le prérempli par défaut
     * rouvrirait la porte aux emplacements « Nouvel emplacement » créés par inadvertance — ce que ce
     * lot existe précisément pour fermer.</p>
     */
    public static String typedName(Inventory top) {
        if (top instanceof AnvilInventory anvil) {
            String text = anvil.getRenameText();
            return text == null ? "" : text.trim();
        }
        return "";
    }

    private static net.kyori.adventure.text.Component lore(String miniMessage) {
        return MM.deserialize(miniMessage).decoration(TextDecoration.ITALIC, false);
    }

    /** Neutralise les balises MiniMessage d'un nom saisi : un libellé n'est pas du balisage. */
    private static String escape(String raw) {
        return raw.replace("<", "\\<");
    }
}
