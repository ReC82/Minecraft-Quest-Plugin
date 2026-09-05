package com.lodygames.rpgquest.crafting;

import com.lodygames.rpgquest.crafting.model.CustomItemIngredient;
import com.lodygames.rpgquest.crafting.model.CustomItemResult;
import com.lodygames.rpgquest.crafting.model.RecipeDefinition;
import com.lodygames.rpgquest.crafting.model.RecipeIngredient;
import com.lodygames.rpgquest.crafting.model.RecipeIngredientEntry;
import com.lodygames.rpgquest.crafting.model.RecipeResult;
import com.lodygames.rpgquest.crafting.model.ShapedRecipeDefinition;
import com.lodygames.rpgquest.crafting.model.ShapelessRecipeDefinition;
import com.lodygames.rpgquest.crafting.model.VanillaIngredient;
import com.lodygames.rpgquest.crafting.model.VanillaResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Valide et construit une {@link RecipeDefinition} à partir d'un fichier YAML
 * déjà parsé. Même conception que {@code QuestDefinitionParser}/{@code
 * ItemDefinitionParser} : purement structurel (un seul fichier à la fois,
 * ne dépend que de {@link ConfigurationSection}), accumule toutes les
 * erreurs trouvées avant d'échouer. Ne vérifie <b>pas</b> qu'un id d'objet
 * personnalisé référencé existe réellement (le parseur ne connaît aucun
 * registre) — c'est le rôle de {@code YamlCraftingRegistry} au moment de
 * l'enregistrement effectif, une fois le registre d'objets disponible.
 */
final class RecipeDefinitionParser {

    static final String DEFAULT_NAMESPACE = "rpgquest";

    ParseResult parse(String fileName, ConfigurationSection section) {
        List<String> errors = new ArrayList<>();

        NamespacedKey id = parseId(section, errors);
        String rawType = section.getString("type");
        RecipeResult result = parseResult(section, errors);

        if (rawType == null || rawType.isBlank()) {
            errors.add("« type » est obligatoire (SHAPED ou SHAPELESS).");
            return ParseResult.failure(errors.stream().map(m -> new RecipeLoadIssue(fileName, m)).toList());
        }

        RecipeDefinition definition;
        try {
            definition = switch (rawType.toUpperCase(Locale.ROOT)) {
                case "SHAPED" -> parseShaped(section, id, result, errors);
                case "SHAPELESS" -> parseShapeless(section, id, result, errors);
                default -> {
                    errors.add("« type » inconnu : \"" + rawType + "\" (valides : SHAPED, SHAPELESS).");
                    yield null;
                }
            };
        } catch (IllegalArgumentException e) {
            return ParseResult.failure(List.of(new RecipeLoadIssue(fileName, e.getMessage())));
        }

        if (!errors.isEmpty() || definition == null) {
            return ParseResult.failure(errors.stream().map(m -> new RecipeLoadIssue(fileName, m)).toList());
        }
        return ParseResult.success(definition);
    }

    private RecipeDefinition parseShaped(
            ConfigurationSection section, NamespacedKey id, RecipeResult result, List<String> errors) {
        List<String> pattern = section.getStringList("pattern");
        if (pattern.isEmpty()) {
            errors.add("« pattern » est obligatoire et doit contenir au moins une ligne.");
        }

        Map<Character, RecipeIngredient> key = new LinkedHashMap<>();
        ConfigurationSection keySection = section.getConfigurationSection("key");
        if (keySection == null) {
            errors.add("« key » est obligatoire pour une recette SHAPED.");
        } else {
            for (String rawChar : keySection.getKeys(false)) {
                if (rawChar.length() != 1) {
                    errors.add("« key » : « " + rawChar + " » doit être un unique caractère.");
                    continue;
                }
                ConfigurationSection entry = keySection.getConfigurationSection(rawChar);
                RecipeIngredient ingredient = entry == null ? null
                        : parseIngredient(entry, "key." + rawChar, errors);
                if (ingredient != null) {
                    key.put(rawChar.charAt(0), ingredient);
                }
            }
        }

        if (id == null || result == null || !errors.isEmpty()) {
            return null;
        }
        return new ShapedRecipeDefinition(id, result, pattern, key);
    }

    private RecipeDefinition parseShapeless(
            ConfigurationSection section, NamespacedKey id, RecipeResult result, List<String> errors) {
        if (!section.isList("ingredients") || section.getMapList("ingredients").isEmpty()) {
            errors.add("« ingredients » est obligatoire et doit contenir au moins une entrée.");
        }

        List<RecipeIngredientEntry> ingredients = new ArrayList<>();
        List<Map<?, ?>> raw = section.getMapList("ingredients");
        for (int i = 0; i < raw.size(); i++) {
            ConfigurationSection entrySection = toSection(raw.get(i));
            String context = "ingredients[" + i + "]";
            RecipeIngredient ingredient = parseIngredient(entrySection, context, errors);
            int amount = entrySection.getInt("amount", 1);
            if (amount <= 0) {
                errors.add(context + ": « amount » doit être strictement positif.");
                continue;
            }
            if (ingredient != null) {
                ingredients.add(new RecipeIngredientEntry(ingredient, amount));
            }
        }

        if (id == null || result == null || !errors.isEmpty()) {
            return null;
        }
        return new ShapelessRecipeDefinition(id, result, ingredients);
    }

    private RecipeIngredient parseIngredient(ConfigurationSection section, String context, List<String> errors) {
        boolean hasCustomItem = section.isSet("custom-item");
        boolean hasMaterial = section.isSet("material");
        if (hasCustomItem == hasMaterial) {
            errors.add(context + ": exactement un de « custom-item » ou « material » est requis.");
            return null;
        }
        if (hasCustomItem) {
            String raw = section.getString("custom-item");
            NamespacedKey itemId = raw == null ? null : toNamespacedKey(raw);
            if (itemId == null) {
                errors.add(context + ": « custom-item » invalide « " + raw + " ».");
                return null;
            }
            return new CustomItemIngredient(itemId);
        }
        String raw = section.getString("material");
        Material material = raw == null ? null : Material.matchMaterial(raw);
        if (material == null) {
            errors.add(context + ": matériau inconnu « " + raw + " ».");
            return null;
        }
        return new VanillaIngredient(material);
    }

    private RecipeResult parseResult(ConfigurationSection section, List<String> errors) {
        ConfigurationSection result = section.getConfigurationSection("result");
        if (result == null) {
            errors.add("« result » est obligatoire.");
            return null;
        }
        boolean hasCustomItem = result.isSet("custom-item");
        boolean hasMaterial = result.isSet("material");
        if (hasCustomItem == hasMaterial) {
            errors.add("« result » : exactement un de « custom-item » ou « material » est requis.");
            return null;
        }
        int amount = result.getInt("amount", 1);
        if (amount <= 0) {
            errors.add("« result.amount » doit être strictement positif.");
            return null;
        }
        if (hasCustomItem) {
            String raw = result.getString("custom-item");
            NamespacedKey itemId = raw == null ? null : toNamespacedKey(raw);
            if (itemId == null) {
                errors.add("« result.custom-item » invalide « " + raw + " ».");
                return null;
            }
            return new CustomItemResult(itemId, amount);
        }
        String raw = result.getString("material");
        Material material = raw == null ? null : Material.matchMaterial(raw);
        if (material == null) {
            errors.add("« result.material » : matériau inconnu « " + raw + " ».");
            return null;
        }
        return new VanillaResult(material, amount);
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

    private ConfigurationSection toSection(Map<?, ?> map) {
        var memory = new org.bukkit.configuration.MemoryConfiguration();
        for (var entry : map.entrySet()) {
            memory.set(String.valueOf(entry.getKey()), entry.getValue());
        }
        return memory;
    }

    record ParseResult(RecipeDefinition recipe, List<RecipeLoadIssue> issues) {

        static ParseResult success(RecipeDefinition recipe) {
            return new ParseResult(recipe, List.of());
        }

        static ParseResult failure(List<RecipeLoadIssue> issues) {
            return new ParseResult(null, issues);
        }

        boolean isSuccess() {
            return recipe != null;
        }
    }
}
