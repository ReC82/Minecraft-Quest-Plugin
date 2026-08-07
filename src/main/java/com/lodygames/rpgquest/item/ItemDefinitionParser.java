package com.lodygames.rpgquest.item;

import com.lodygames.rpgquest.item.model.ConditionalEffect;
import com.lodygames.rpgquest.item.model.CraftingRestrictions;
import com.lodygames.rpgquest.item.model.CustomItemDefinition;
import com.lodygames.rpgquest.item.model.CustomItemType;
import com.lodygames.rpgquest.item.model.ItemAttributeSpec;
import com.lodygames.rpgquest.item.model.ItemEnchantmentSpec;
import com.lodygames.rpgquest.item.model.ItemRarity;
import com.lodygames.rpgquest.item.model.ToolBehavior;
import com.lodygames.rpgquest.item.model.ToolSpecialAbility;
import com.lodygames.rpgquest.item.model.WeaponBehavior;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.potion.PotionEffectType;

/**
 * Valide et construit une {@link CustomItemDefinition} à partir d'un fichier
 * YAML déjà parsé. Même conception que {@code QuestDefinitionParser}/
 * {@code DialogueDefinitionParser} : purement structurel (un seul fichier à
 * la fois), ne dépend que de {@link ConfigurationSection} (testable sans
 * MockBukkit), accumule toutes les erreurs trouvées avant d'échouer.
 */
final class ItemDefinitionParser {

    static final String DEFAULT_NAMESPACE = "rpgquest";
    private static final int VANILLA_MAX_STACK_SIZE = 99;

    ParseResult parse(String fileName, ConfigurationSection section) {
        List<String> errors = new ArrayList<>();

        NamespacedKey id = parseId(section, errors);
        CustomItemType type = parseType(section, errors);
        Material material = parseMaterial(section, errors);
        String name = parseName(section, errors);
        List<String> lore = parseLore(section, errors);
        ItemRarity rarity = parseRarity(section, errors);
        Integer customModelData = parseCustomModelData(section, errors);
        NamespacedKey itemModel = parseItemModel(section, errors);
        boolean stackable = parseStackable(section);
        int maxStackSize = parseMaxStackSize(section, material, stackable, errors);
        Integer maxDurability = parseMaxDurability(section, material, errors);
        List<ItemAttributeSpec> attributes = parseAttributes(section, errors);
        List<ItemEnchantmentSpec> enchantments = parseEnchantments(section, errors);
        List<String> gameplayTags = parseGameplayTags(section, errors);
        String specialBehavior = parseSpecialBehavior(section);
        CraftingRestrictions crafting = parseCrafting(section, errors);
        WeaponBehavior weaponBehavior = parseCombat(section, errors);
        ToolBehavior toolBehavior = parseTool(section, errors);

        if (!errors.isEmpty()) {
            return ParseResult.failure(errors.stream().map(message -> new ItemLoadIssue(fileName, message)).toList());
        }

        try {
            CustomItemDefinition item = new CustomItemDefinition(id, type, material, name, lore, rarity,
                    customModelData, itemModel, stackable, maxStackSize, maxDurability,
                    attributes, enchantments, gameplayTags, specialBehavior, crafting, weaponBehavior, toolBehavior);
            return ParseResult.success(item);
        } catch (IllegalArgumentException e) {
            return ParseResult.failure(List.of(new ItemLoadIssue(fileName, e.getMessage())));
        }
    }

    private NamespacedKey parseId(ConfigurationSection section, List<String> errors) {
        String raw = section.getString("id");
        if (raw == null || raw.isBlank()) {
            errors.add("« id » est obligatoire.");
            return null;
        }
        NamespacedKey id = toNamespacedKey(raw);
        if (id == null) {
            errors.add("« id » invalide : \"" + raw + "\" (namespace et clé doivent être en minuscules, "
                    + "sans espaces ni caractères spéciaux hors « . _ - / »).");
        }
        return id;
    }

    private NamespacedKey toNamespacedKey(String raw) {
        try {
            return raw.contains(":") ? NamespacedKey.fromString(raw) : new NamespacedKey(DEFAULT_NAMESPACE, raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Résout une clé de registre vanilla (attribut, enchantement) : {@code "attack_damage"} ou {@code "minecraft:attack_damage"}. */
    private NamespacedKey vanillaKey(String raw) {
        String lower = raw.toLowerCase(Locale.ROOT);
        try {
            return lower.contains(":") ? NamespacedKey.fromString(lower) : NamespacedKey.minecraft(lower);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private CustomItemType parseType(ConfigurationSection section, List<String> errors) {
        String raw = section.getString("type");
        if (raw == null || raw.isBlank()) {
            errors.add("« type » est obligatoire.");
            return null;
        }
        try {
            return CustomItemType.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            errors.add("« type » inconnu : \"" + raw + "\" (valides : " + List.of(CustomItemType.values()) + ").");
            return null;
        }
    }

    private Material parseMaterial(ConfigurationSection section, List<String> errors) {
        String raw = section.getString("material");
        if (raw == null || raw.isBlank()) {
            errors.add("« material » est obligatoire.");
            return null;
        }
        Material material = Material.matchMaterial(raw);
        if (material == null) {
            errors.add("« material » : matériau inconnu « " + raw + " ».");
            return null;
        }
        return material;
    }

    private String parseName(ConfigurationSection section, List<String> errors) {
        String name = section.getString("name");
        if (name == null || name.isBlank()) {
            errors.add("« name » est obligatoire.");
            return null;
        }
        return name;
    }

    private List<String> parseLore(ConfigurationSection section, List<String> errors) {
        if (!section.isSet("lore")) {
            return List.of();
        }
        if (!section.isList("lore")) {
            errors.add("« lore » doit être une liste de lignes.");
            return List.of();
        }
        List<String> lore = new ArrayList<>();
        for (String line : section.getStringList("lore")) {
            if (line == null) {
                errors.add("« lore » contient une ligne vide.");
                continue;
            }
            lore.add(line);
        }
        return lore;
    }

    private ItemRarity parseRarity(ConfigurationSection section, List<String> errors) {
        if (!section.isSet("rarity")) {
            return ItemRarity.COMMON;
        }
        String raw = section.getString("rarity");
        try {
            return ItemRarity.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            errors.add("« rarity » inconnue : \"" + raw + "\" (valides : " + List.of(ItemRarity.values()) + ").");
            return null;
        }
    }

    private Integer parseCustomModelData(ConfigurationSection section, List<String> errors) {
        if (!section.isSet("custom-model-data")) {
            return null;
        }
        if (!section.isInt("custom-model-data")) {
            errors.add("« custom-model-data » doit être un entier.");
            return null;
        }
        return section.getInt("custom-model-data");
    }

    private NamespacedKey parseItemModel(ConfigurationSection section, List<String> errors) {
        String raw = section.getString("item-model");
        if (raw == null || raw.isBlank()) {
            return null;
        }
        NamespacedKey key = toNamespacedKey(raw);
        if (key == null) {
            errors.add("« item-model » invalide : \"" + raw + "\".");
        }
        return key;
    }

    private boolean parseStackable(ConfigurationSection section) {
        return section.getBoolean("stackable", true);
    }

    private int parseMaxStackSize(ConfigurationSection section, Material material, boolean stackable, List<String> errors) {
        if (!stackable) {
            return 1;
        }
        int defaultValue = material != null ? material.getMaxStackSize() : 1;
        if (!section.isSet("max-stack-size")) {
            return Math.max(1, defaultValue);
        }
        if (!section.isInt("max-stack-size")) {
            errors.add("« max-stack-size » doit être un entier.");
            return 1;
        }
        int value = section.getInt("max-stack-size");
        if (value < 1 || value > VANILLA_MAX_STACK_SIZE) {
            errors.add("« max-stack-size » doit être compris entre 1 et " + VANILLA_MAX_STACK_SIZE + ".");
            return 1;
        }
        return value;
    }

    private Integer parseMaxDurability(ConfigurationSection section, Material material, List<String> errors) {
        if (!section.isSet("max-durability")) {
            return null;
        }
        if (!section.isInt("max-durability")) {
            errors.add("« max-durability » doit être un entier.");
            return null;
        }
        int value = section.getInt("max-durability");
        if (value < 1) {
            errors.add("« max-durability » doit être strictement positif.");
            return null;
        }
        if (material != null && material.getMaxDurability() <= 0) {
            errors.add("« max-durability » défini, mais « " + material + " » n'a pas de durabilité en vanilla.");
            return null;
        }
        return value;
    }

    private List<ItemAttributeSpec> parseAttributes(ConfigurationSection section, List<String> errors) {
        if (!section.isSet("attributes")) {
            return List.of();
        }
        List<ItemAttributeSpec> attributes = new ArrayList<>();
        List<Map<?, ?>> raw = section.getMapList("attributes");
        for (int i = 0; i < raw.size(); i++) {
            ItemAttributeSpec spec = parseAttribute(toSection(raw.get(i)), "attributes[" + i + "]", errors);
            if (spec != null) {
                attributes.add(spec);
            }
        }
        return attributes;
    }

    private ItemAttributeSpec parseAttribute(ConfigurationSection section, String context, List<String> errors) {
        String rawAttribute = section.getString("attribute");
        if (rawAttribute == null || rawAttribute.isBlank()) {
            errors.add(context + ": « attribute » est obligatoire.");
            return null;
        }
        NamespacedKey attributeKey = vanillaKey(rawAttribute);
        Attribute attribute = attributeKey == null ? null : Registry.ATTRIBUTE.get(attributeKey);
        if (attribute == null) {
            errors.add(context + ": attribut inconnu « " + rawAttribute + " ».");
            return null;
        }

        if (!section.isSet("amount") || !(section.isDouble("amount") || section.isInt("amount"))) {
            errors.add(context + ": « amount » est obligatoire et doit être un nombre.");
            return null;
        }
        double amount = section.getDouble("amount");

        String rawOperation = section.getString("operation", "ADD_NUMBER");
        AttributeModifier.Operation operation;
        try {
            operation = AttributeModifier.Operation.valueOf(rawOperation.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            errors.add(context + ": « operation » inconnue « " + rawOperation + " » (valides : "
                    + List.of(AttributeModifier.Operation.values()) + ").");
            return null;
        }

        String rawSlot = section.getString("slot", "any");
        EquipmentSlotGroup slot = EquipmentSlotGroup.getByName(rawSlot.toLowerCase(Locale.ROOT));
        if (slot == null) {
            errors.add(context + ": « slot » inconnu « " + rawSlot + " ».");
            return null;
        }

        return new ItemAttributeSpec(attribute, amount, operation, slot);
    }

    private List<ItemEnchantmentSpec> parseEnchantments(ConfigurationSection section, List<String> errors) {
        if (!section.isSet("enchantments")) {
            return List.of();
        }
        List<ItemEnchantmentSpec> enchantments = new ArrayList<>();
        List<Map<?, ?>> raw = section.getMapList("enchantments");
        for (int i = 0; i < raw.size(); i++) {
            ItemEnchantmentSpec spec = parseEnchantment(toSection(raw.get(i)), "enchantments[" + i + "]", errors);
            if (spec != null) {
                enchantments.add(spec);
            }
        }
        return enchantments;
    }

    private ItemEnchantmentSpec parseEnchantment(ConfigurationSection section, String context, List<String> errors) {
        String rawType = section.getString("type");
        if (rawType == null || rawType.isBlank()) {
            errors.add(context + ": « type » est obligatoire.");
            return null;
        }
        NamespacedKey enchantmentKey = vanillaKey(rawType);
        Enchantment enchantment = enchantmentKey == null ? null
                : RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT).get(enchantmentKey);
        if (enchantment == null) {
            errors.add(context + ": enchantement inconnu « " + rawType + " ».");
            return null;
        }
        if (!section.isSet("level") || !section.isInt("level")) {
            errors.add(context + ": « level » est obligatoire et doit être un entier.");
            return null;
        }
        int level = section.getInt("level");
        if (level < 1) {
            errors.add(context + ": « level » doit être strictement positif.");
            return null;
        }
        return new ItemEnchantmentSpec(enchantment, level);
    }

    private List<String> parseGameplayTags(ConfigurationSection section, List<String> errors) {
        if (!section.isSet("gameplay-tags")) {
            return List.of();
        }
        List<String> tags = new ArrayList<>();
        for (String tag : section.getStringList("gameplay-tags")) {
            if (tag == null || tag.isBlank()) {
                errors.add("« gameplay-tags » contient une entrée vide.");
                continue;
            }
            tags.add(tag);
        }
        return tags;
    }

    private String parseSpecialBehavior(ConfigurationSection section) {
        String raw = section.getString("special-behavior");
        return (raw == null || raw.isBlank()) ? null : raw;
    }

    private CraftingRestrictions parseCrafting(ConfigurationSection section, List<String> errors) {
        ConfigurationSection crafting = section.getConfigurationSection("crafting");
        if (crafting == null) {
            return CraftingRestrictions.notCraftable();
        }
        boolean craftable = crafting.getBoolean("craftable", false);
        List<String> permissions = new ArrayList<>();
        for (String permission : crafting.getStringList("required-permissions")) {
            if (permission == null || permission.isBlank()) {
                errors.add("« crafting.required-permissions » contient une entrée vide.");
                continue;
            }
            permissions.add(permission);
        }
        return new CraftingRestrictions(craftable, permissions);
    }

    private WeaponBehavior parseCombat(ConfigurationSection section, List<String> errors) {
        ConfigurationSection combat = section.getConfigurationSection("combat");
        if (combat == null) {
            return null;
        }

        Double baseDamage = parseOptionalDouble(combat, "base-damage", "combat.base-damage", errors);
        Double attackSpeedBonus = parseOptionalDouble(combat, "attack-speed-bonus", "combat.attack-speed-bonus", errors);
        double criticalChance = combat.getDouble("critical-chance", 0.0);
        double criticalMultiplier = combat.getDouble("critical-multiplier", 1.5);
        String hitMessage = combat.getString("hit-message");
        Particle particle = parseParticle(combat, "combat.particle", errors);
        int particleCount = combat.getInt("particle-count", 1);
        ConditionalEffect effect = parseConditionalEffect(combat, errors);

        try {
            return new WeaponBehavior(baseDamage, attackSpeedBonus, criticalChance, criticalMultiplier,
                    effect, hitMessage, particle, particleCount);
        } catch (IllegalArgumentException e) {
            errors.add(e.getMessage());
            return null;
        }
    }

    private ConditionalEffect parseConditionalEffect(ConfigurationSection combat, List<String> errors) {
        ConfigurationSection effect = combat.getConfigurationSection("effect");
        if (effect == null) {
            return null;
        }

        String abilityId = effect.getString("ability-id", "effect");
        String rawType = effect.getString("type");
        if (rawType == null || rawType.isBlank()) {
            errors.add("effect.type est obligatoire.");
            return null;
        }
        NamespacedKey typeKey = vanillaKey(rawType);
        PotionEffectType effectType = typeKey == null ? null
                : RegistryAccess.registryAccess().getRegistry(RegistryKey.MOB_EFFECT).get(typeKey);
        if (effectType == null) {
            errors.add("effect.type inconnu « " + rawType + " ».");
            return null;
        }
        int durationTicks = effect.getInt("duration-ticks", 20);
        int amplifier = effect.getInt("amplifier", 0);
        double chance = effect.getDouble("chance", 1.0);
        long cooldownMillis = effect.getLong("cooldown-seconds", 5) * 1000L;

        try {
            return new ConditionalEffect(abilityId, effectType, durationTicks, amplifier, chance, cooldownMillis);
        } catch (IllegalArgumentException e) {
            errors.add(e.getMessage());
            return null;
        }
    }

    private ToolBehavior parseTool(ConfigurationSection section, List<String> errors) {
        ConfigurationSection tool = section.getConfigurationSection("tool");
        if (tool == null) {
            return null;
        }

        Double miningSpeedBonus = parseOptionalDouble(tool, "mining-speed-bonus", "tool.mining-speed-bonus", errors);
        List<Material> allowedBlocks = parseAllowedBlocks(tool, errors);
        int durabilityCost = tool.getInt("durability-cost", 1);
        double harvestBonusChance = tool.getDouble("harvest-bonus-chance", 0.0);
        int harvestBonusAmount = tool.getInt("harvest-bonus-amount", 1);
        ToolSpecialAbility specialAbility = parseSpecialAbility(tool, errors);

        try {
            return new ToolBehavior(miningSpeedBonus, allowedBlocks, durabilityCost,
                    harvestBonusChance, harvestBonusAmount, specialAbility);
        } catch (IllegalArgumentException e) {
            errors.add(e.getMessage());
            return null;
        }
    }

    private List<Material> parseAllowedBlocks(ConfigurationSection tool, List<String> errors) {
        if (!tool.isSet("allowed-blocks")) {
            return List.of();
        }
        List<Material> blocks = new ArrayList<>();
        for (String raw : tool.getStringList("allowed-blocks")) {
            Material material = raw == null ? null : Material.matchMaterial(raw);
            if (material == null) {
                errors.add("tool.allowed-blocks : matériau inconnu « " + raw + " ».");
                continue;
            }
            blocks.add(material);
        }
        return blocks;
    }

    private ToolSpecialAbility parseSpecialAbility(ConfigurationSection tool, List<String> errors) {
        ConfigurationSection ability = tool.getConfigurationSection("special-ability");
        if (ability == null) {
            return null;
        }
        String abilityId = ability.getString("ability-id", "special");
        long cooldownMillis = ability.getLong("cooldown-seconds", 10) * 1000L;
        String activationMessage = ability.getString("activation-message");
        Particle particle = parseParticle(ability, "tool.special-ability.particle", errors);
        int particleCount = ability.getInt("particle-count", 1);

        try {
            return new ToolSpecialAbility(abilityId, cooldownMillis, activationMessage, particle, particleCount);
        } catch (IllegalArgumentException e) {
            errors.add(e.getMessage());
            return null;
        }
    }

    private Particle parseParticle(ConfigurationSection section, String context, List<String> errors) {
        String raw = section.getString("particle");
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Particle.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            errors.add(context + " : particule inconnue « " + raw + " ».");
            return null;
        }
    }

    private Double parseOptionalDouble(ConfigurationSection section, String key, String context, List<String> errors) {
        if (!section.isSet(key)) {
            return null;
        }
        if (!(section.isDouble(key) || section.isInt(key))) {
            errors.add("« " + context + " » doit être un nombre.");
            return null;
        }
        return section.getDouble(key);
    }

    private ConfigurationSection toSection(Map<?, ?> map) {
        var memory = new org.bukkit.configuration.MemoryConfiguration();
        for (var entry : map.entrySet()) {
            memory.set(String.valueOf(entry.getKey()), entry.getValue());
        }
        return memory;
    }

    record ParseResult(CustomItemDefinition item, List<ItemLoadIssue> issues) {

        static ParseResult success(CustomItemDefinition item) {
            return new ParseResult(item, List.of());
        }

        static ParseResult failure(List<ItemLoadIssue> issues) {
            return new ParseResult(null, issues);
        }

        boolean isSuccess() {
            return item != null;
        }
    }
}
