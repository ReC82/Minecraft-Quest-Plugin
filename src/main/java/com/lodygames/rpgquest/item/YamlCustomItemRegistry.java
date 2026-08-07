package com.lodygames.rpgquest.item;

import com.lodygames.rpgquest.item.model.CustomItemDefinition;
import com.lodygames.rpgquest.item.model.ItemAttributeSpec;
import com.lodygames.rpgquest.item.model.ItemEnchantmentSpec;
import com.lodygames.rpgquest.item.model.ItemRarity;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.slf4j.Logger;

/**
 * Implémentation {@code PluginService} de {@link CustomItemRegistry} : charge
 * les définitions d'objets YAML depuis {@code itemsDirectory}, centralise la
 * création des {@link ItemStack} et leur identification. Ne dépend que de
 * {@link Path}/{@link Logger} (pas de {@code JavaPlugin}), les exemples étant
 * lus depuis le classpath du plugin — même conception que
 * {@code YamlQuestEngine}/{@code YamlDialogueEngine}.
 *
 * <p><b>Identification</b> : chaque objet créé porte son id namespacé dans
 * son {@link org.bukkit.persistence.PersistentDataContainer} sous
 * {@link #ID_KEY}. C'est la <em>seule</em> source de vérité — {@link #identify}
 * ne regarde jamais le nom affiché ni le lore, donc un objet vanilla renommé
 * pour imiter un objet personnalisé n'est jamais reconnu.</p>
 */
public final class YamlCustomItemRegistry implements CustomItemRegistry {

    private static final String[] BUNDLED_EXAMPLES = {
            "forest_blade.yml", "miner_pickaxe.yml", "spider_fang.yml", "refined_crystal.yml"
    };

    /** Clé PDC portant l'id namespacé de l'objet, sous forme de chaîne (ex. {@code "rpgquest:forest_blade"}). */
    static final NamespacedKey ID_KEY = new NamespacedKey("rpgquest", "custom_item_id");

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final Path itemsDirectory;
    private final Logger logger;
    private final ItemLoader loader = new ItemLoader();

    private volatile List<CustomItemDefinition> items = List.of();
    private volatile ItemLoadReport lastReport = new ItemLoadReport(List.of(), List.of());

    public YamlCustomItemRegistry(Path itemsDirectory, Logger logger) {
        this.itemsDirectory = itemsDirectory;
        this.logger = logger;
    }

    @Override
    public void start() {
        ensureExamplesExist();
        reload();
    }

    @Override
    public void stop() {
        // Rien à libérer : les définitions vivent en mémoire, pas de ressource externe.
    }

    /** Recharge depuis le disque et remplace l'ensemble de définitions actif. */
    public ItemLoadReport reload() {
        ItemLoadReport report = loader.loadDirectory(itemsDirectory);
        this.items = report.loaded();
        this.lastReport = report;
        logReport("Chargement", report);
        return report;
    }

    /** Charge et valide depuis le disque sans toucher à l'ensemble actif (dry-run). */
    public ItemLoadReport validate() {
        ItemLoadReport report = loader.loadDirectory(itemsDirectory);
        logReport("Validation", report);
        return report;
    }

    public List<CustomItemDefinition> items() {
        return items;
    }

    public ItemLoadReport lastReport() {
        return lastReport;
    }

    public Optional<CustomItemDefinition> find(NamespacedKey id) {
        return items.stream().filter(item -> item.id().equals(id)).findFirst();
    }

    /**
     * Construit un {@link ItemStack} pour {@code id}. Retourne
     * {@link Optional#empty()} si l'id est inconnu <em>ou</em> si
     * {@code amount} est invalide (hors de {@code [1, effectiveMaxStackSize]})
     * — les deux cas sont volontairement indiscernables ici : c'est à
     * l'appelant (commande, dialogue) de distinguer « id inconnu » de
     * « quantité invalide » en consultant {@link #find(NamespacedKey)}
     * séparément avant d'appeler cette méthode.
     */
    public Optional<ItemStack> create(NamespacedKey id, int amount) {
        return find(id).flatMap(definition -> createFrom(definition, amount));
    }

    @SuppressWarnings("deprecation") // setCustomModelData(Integer) : legacy single-index CMD, gardé en plus du
    // component-based CustomModelDataComponent pour les resource packs qui utilisent encore des prédicats
    // "custom_model_data" simples ; le champ "item-model" (non déprécié) reste l'alternative moderne recommandée
    // à ce jour pour Paper 1.21.11, voir docs/ARCHITECTURE.md.
    private Optional<ItemStack> createFrom(CustomItemDefinition definition, int amount) {
        if (amount < 1 || amount > definition.effectiveMaxStackSize()) {
            return Optional.empty();
        }

        ItemStack stack = new ItemStack(definition.baseMaterial(), amount);
        ItemMeta meta = stack.getItemMeta();

        meta.displayName(MM.deserialize(definition.displayName()));
        meta.lore(buildLore(definition));

        if (definition.customModelData() != null) {
            meta.setCustomModelData(definition.customModelData());
        }
        if (definition.itemModel() != null) {
            meta.setItemModel(definition.itemModel());
        }
        meta.setMaxStackSize(definition.effectiveMaxStackSize());

        if (definition.maxDurability() != null && meta instanceof Damageable damageable) {
            damageable.setMaxDamage(definition.maxDurability());
        }

        int attributeIndex = 0;
        for (ItemAttributeSpec attribute : definition.attributes()) {
            NamespacedKey modifierId = new NamespacedKey("rpgquest",
                    definition.id().getKey() + "_" + attribute.attribute().getKey().getKey() + "_" + attributeIndex++);
            AttributeModifier modifier = new AttributeModifier(
                    modifierId, attribute.amount(), attribute.operation(), attribute.slot());
            meta.addAttributeModifier(attribute.attribute(), modifier);
        }

        for (ItemEnchantmentSpec enchantment : definition.enchantments()) {
            meta.addEnchant(enchantment.enchantment(), enchantment.level(), true);
        }

        applyBehaviorAttributes(definition, meta);

        // Seule source de vérité pour l'identification : jamais le nom, jamais le lore.
        meta.getPersistentDataContainer().set(ID_KEY, PersistentDataType.STRING, definition.id().toString());

        stack.setItemMeta(meta);
        return Optional.of(stack);
    }

    /**
     * {@code combat.attack-speed-bonus}/{@code tool.mining-speed-bonus} sont exposés comme des
     * commodités de configuration ; ils sont appliqués ici via le mécanisme d'attribut vanilla
     * existant ({@code ATTACK_SPEED}/{@code MINING_EFFICIENCY}, tous deux réels en 1.21+), plutôt
     * que réinventé — aucun listener n'est nécessaire pour cette partie du comportement.
     */
    private void applyBehaviorAttributes(CustomItemDefinition definition, ItemMeta meta) {
        if (definition.weaponBehavior() != null && definition.weaponBehavior().attackSpeedBonus() != null) {
            NamespacedKey modifierId = new NamespacedKey("rpgquest", definition.id().getKey() + "_attack_speed");
            meta.addAttributeModifier(Attribute.ATTACK_SPEED, new AttributeModifier(
                    modifierId, definition.weaponBehavior().attackSpeedBonus(),
                    AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND));
        }
        if (definition.toolBehavior() != null && definition.toolBehavior().miningSpeedBonus() != null) {
            NamespacedKey modifierId = new NamespacedKey("rpgquest", definition.id().getKey() + "_mining_efficiency");
            meta.addAttributeModifier(Attribute.MINING_EFFICIENCY, new AttributeModifier(
                    modifierId, definition.toolBehavior().miningSpeedBonus(),
                    AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND));
        }
    }

    private List<Component> buildLore(CustomItemDefinition definition) {
        List<Component> lore = new ArrayList<>();
        for (String line : definition.lore()) {
            lore.add(MM.deserialize(line));
        }
        lore.add(rarityLine(definition.rarity()));
        return lore;
    }

    private Component rarityLine(ItemRarity rarity) {
        String colorTag = rarity.colorTag();
        String closingTag = colorTag.replace("<", "</");
        return MM.deserialize(colorTag + "<rarity>" + closingTag, Placeholder.unparsed("rarity", rarity.name()));
    }

    /**
     * Lit l'id namespacé porté par le PDC de {@code stack}, sans jamais
     * regarder le nom affiché ni le lore. Ne vérifie pas que cet id
     * correspond encore à une définition chargée — voir {@link #resolve}.
     */
    public Optional<NamespacedKey> identify(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) {
            return Optional.empty();
        }
        ItemMeta meta = stack.getItemMeta();
        String raw = meta.getPersistentDataContainer().get(ID_KEY, PersistentDataType.STRING);
        if (raw == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(NamespacedKey.fromString(raw));
    }

    public boolean isCustomItem(ItemStack stack) {
        return identify(stack).isPresent();
    }

    /** {@link #identify(ItemStack)} suivi d'une résolution contre les définitions actuellement chargées. */
    public Optional<CustomItemDefinition> resolve(ItemStack stack) {
        return identify(stack).flatMap(this::find);
    }

    private void logReport(String action, ItemLoadReport report) {
        logger.info("{} des objets personnalisés : {} chargé(s), {} erreur(s).",
                action, report.loaded().size(), report.issues().size());
        for (ItemLoadIssue issue : report.issues()) {
            logger.warn("[{}] {}", issue.file(), issue.message());
        }
    }

    private void ensureExamplesExist() {
        try {
            Files.createDirectories(itemsDirectory);
        } catch (IOException e) {
            logger.error("Impossible de créer le dossier d'objets {}.", itemsDirectory, e);
            return;
        }

        for (String example : BUNDLED_EXAMPLES) {
            Path target = itemsDirectory.resolve(example);
            if (Files.exists(target)) {
                continue;
            }
            try (InputStream in = YamlCustomItemRegistry.class.getResourceAsStream("/items/" + example)) {
                if (in == null) {
                    logger.warn("Objet d'exemple introuvable dans le jar : {}", example);
                    continue;
                }
                Files.copy(in, target);
            } catch (IOException e) {
                logger.error("Impossible de générer l'objet d'exemple {}.", example, e);
            }
        }
    }
}
