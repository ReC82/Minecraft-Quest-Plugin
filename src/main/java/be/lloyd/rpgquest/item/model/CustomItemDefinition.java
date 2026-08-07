package be.lloyd.rpgquest.item.model;

import java.util.List;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;

/**
 * Définition immuable d'un objet personnalisé. « Correct par construction » :
 * comme {@code QuestDefinition}/{@code DialogueDefinition}, les invariants
 * structurels de base sont vérifiés ici (pas seulement par le parseur), un
 * texte d'erreur clair étant remonté sous forme d'{@link IllegalArgumentException}
 * que le parseur transforme en {@code ItemLoadIssue}.
 */
public record CustomItemDefinition(
        NamespacedKey id,
        CustomItemType type,
        Material baseMaterial,
        String displayName,
        List<String> lore,
        ItemRarity rarity,
        Integer customModelData,
        NamespacedKey itemModel,
        boolean stackable,
        int maxStackSize,
        Integer maxDurability,
        List<ItemAttributeSpec> attributes,
        List<ItemEnchantmentSpec> enchantments,
        List<String> gameplayTags,
        String specialBehavior,
        CraftingRestrictions crafting,
        WeaponBehavior weaponBehavior,
        ToolBehavior toolBehavior
) {

    private static final int VANILLA_MAX_STACK_SIZE = 99;

    public CustomItemDefinition {
        lore = List.copyOf(lore);
        attributes = List.copyOf(attributes);
        enchantments = List.copyOf(enchantments);
        gameplayTags = List.copyOf(gameplayTags);

        if (id == null) {
            throw new IllegalArgumentException("id ne peut pas être nul.");
        }
        if (type == null) {
            throw new IllegalArgumentException("type ne peut pas être nul.");
        }
        if (baseMaterial == null) {
            throw new IllegalArgumentException("material ne peut pas être nul.");
        }
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("name ne peut pas être vide.");
        }
        if (rarity == null) {
            throw new IllegalArgumentException("rarity ne peut pas être nulle.");
        }
        if (maxStackSize < 1 || maxStackSize > VANILLA_MAX_STACK_SIZE) {
            throw new IllegalArgumentException(
                    "max-stack-size doit être compris entre 1 et " + VANILLA_MAX_STACK_SIZE + ".");
        }
        if (maxDurability != null && maxDurability < 1) {
            throw new IllegalArgumentException("max-durability doit être strictement positif.");
        }
        if (maxDurability != null && stackable) {
            throw new IllegalArgumentException("un objet avec une durabilité ne peut pas être empilable.");
        }
        if (crafting == null) {
            throw new IllegalArgumentException("crafting ne peut pas être nul.");
        }
    }

    /** Taille de pile réellement applicable : toujours 1 si {@link #stackable()} vaut {@code false}. */
    public int effectiveMaxStackSize() {
        return stackable ? maxStackSize : 1;
    }
}
