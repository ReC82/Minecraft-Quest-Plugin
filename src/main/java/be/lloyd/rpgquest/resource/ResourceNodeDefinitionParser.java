package be.lloyd.rpgquest.resource;

import be.lloyd.rpgquest.resource.model.CustomItemDrop;
import be.lloyd.rpgquest.resource.model.ResourceDrop;
import be.lloyd.rpgquest.resource.model.ResourceNodeDefinition;
import be.lloyd.rpgquest.resource.model.VanillaItemDrop;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Valide et construit un {@link ResourceNodeDefinition} à partir d'un fichier
 * YAML déjà parsé. Même conception que {@code QuestDefinitionParser}/{@code
 * ItemDefinitionParser} : purement structurel (un seul fichier à la fois),
 * ne dépend que de {@link ConfigurationSection} (testable sans MockBukkit),
 * accumule toutes les erreurs trouvées avant d'échouer.
 */
final class ResourceNodeDefinitionParser {

    static final String DEFAULT_NAMESPACE = "rpgquest";

    ParseResult parse(String fileName, ConfigurationSection section) {
        List<String> errors = new ArrayList<>();

        NamespacedKey id = parseId(section, errors);
        Material activeMaterial = parseMaterial(section, "active-material", errors);
        Material depletedMaterial = parseMaterial(section, "depleted-material", errors);
        List<Material> requiredTools = parseRequiredTools(section, errors);
        Integer respawnSeconds = parsePositiveInt(section, "respawn-seconds", errors);
        List<ResourceDrop> drops = parseDrops(section, errors);

        if (!errors.isEmpty()) {
            return ParseResult.failure(
                    errors.stream().map(message -> new ResourceNodeLoadIssue(fileName, message)).toList());
        }

        try {
            ResourceNodeDefinition definition = new ResourceNodeDefinition(
                    id, activeMaterial, depletedMaterial, requiredTools, respawnSeconds, drops);
            return ParseResult.success(definition);
        } catch (IllegalArgumentException e) {
            return ParseResult.failure(List.of(new ResourceNodeLoadIssue(fileName, e.getMessage())));
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

    private Material parseMaterial(ConfigurationSection section, String key, List<String> errors) {
        String raw = section.getString(key);
        if (raw == null || raw.isBlank()) {
            errors.add("« " + key + " » est obligatoire.");
            return null;
        }
        Material material = Material.matchMaterial(raw);
        if (material == null) {
            errors.add("« " + key + " » : matériau inconnu « " + raw + " ».");
            return null;
        }
        if (!material.isBlock()) {
            errors.add("« " + key + " » doit être un bloc, « " + raw + " » n'en est pas un.");
            return null;
        }
        return material;
    }

    private List<Material> parseRequiredTools(ConfigurationSection section, List<String> errors) {
        if (!section.isSet("required-tools")) {
            return List.of();
        }
        List<Material> tools = new ArrayList<>();
        for (String raw : section.getStringList("required-tools")) {
            Material material = raw == null ? null : Material.matchMaterial(raw);
            if (material == null) {
                errors.add("« required-tools » : matériau inconnu « " + raw + " ».");
                continue;
            }
            tools.add(material);
        }
        return tools;
    }

    private Integer parsePositiveInt(ConfigurationSection section, String key, List<String> errors) {
        if (!section.isSet(key)) {
            errors.add("« " + key + " » est obligatoire.");
            return null;
        }
        if (!section.isInt(key)) {
            errors.add("« " + key + " » doit être un entier, valeur trouvée : " + section.get(key));
            return null;
        }
        int value = section.getInt(key);
        if (value <= 0) {
            errors.add("« " + key + " » doit être strictement positif, valeur trouvée : " + value);
            return null;
        }
        return value;
    }

    private List<ResourceDrop> parseDrops(ConfigurationSection section, List<String> errors) {
        if (!section.isList("drops") || section.getMapList("drops").isEmpty()) {
            errors.add("« drops » est obligatoire et doit contenir au moins une entrée.");
            return List.of();
        }
        List<ResourceDrop> drops = new ArrayList<>();
        List<Map<?, ?>> raw = section.getMapList("drops");
        for (int i = 0; i < raw.size(); i++) {
            ResourceDrop drop = parseDrop(toSection(raw.get(i)), "drops[" + i + "]", errors);
            if (drop != null) {
                drops.add(drop);
            }
        }
        return drops;
    }

    private ResourceDrop parseDrop(ConfigurationSection section, String context, List<String> errors) {
        boolean hasCustomItem = section.isSet("custom-item");
        boolean hasMaterial = section.isSet("material");
        if (hasCustomItem == hasMaterial) {
            errors.add(context + ": exactement un de « custom-item » ou « material » est requis.");
            return null;
        }

        Integer weight = parsePositiveInt(section, "weight", errors, context);
        Integer minAmount = parsePositiveInt(section, "min-amount", errors, context);
        Integer maxAmount = parsePositiveInt(section, "max-amount", errors, context);
        if (weight == null || minAmount == null || maxAmount == null) {
            return null;
        }
        if (maxAmount < minAmount) {
            errors.add(context + ": « max-amount » doit être supérieur ou égal à « min-amount ».");
            return null;
        }

        if (hasCustomItem) {
            String raw = section.getString("custom-item");
            NamespacedKey id = raw == null ? null : toNamespacedKey(raw);
            if (id == null) {
                errors.add(context + ": « custom-item » invalide « " + raw + " ».");
                return null;
            }
            return new CustomItemDrop(id, weight, minAmount, maxAmount);
        }

        String raw = section.getString("material");
        Material material = raw == null ? null : Material.matchMaterial(raw);
        if (material == null) {
            errors.add(context + ": matériau inconnu « " + raw + " ».");
            return null;
        }
        return new VanillaItemDrop(material, weight, minAmount, maxAmount);
    }

    private Integer parsePositiveInt(ConfigurationSection section, String key, List<String> errors, String context) {
        if (!section.isSet(key)) {
            errors.add(context + ": « " + key + " » est obligatoire.");
            return null;
        }
        if (!section.isInt(key)) {
            errors.add(context + ": « " + key + " » doit être un entier, valeur trouvée : " + section.get(key));
            return null;
        }
        int value = section.getInt(key);
        if (value <= 0) {
            errors.add(context + ": « " + key + " » doit être strictement positif, valeur trouvée : " + value);
            return null;
        }
        return value;
    }

    private ConfigurationSection toSection(Map<?, ?> map) {
        var memory = new org.bukkit.configuration.MemoryConfiguration();
        for (var entry : map.entrySet()) {
            memory.set(String.valueOf(entry.getKey()), entry.getValue());
        }
        return memory;
    }

    record ParseResult(ResourceNodeDefinition definition, List<ResourceNodeLoadIssue> issues) {

        static ParseResult success(ResourceNodeDefinition definition) {
            return new ParseResult(definition, List.of());
        }

        static ParseResult failure(List<ResourceNodeLoadIssue> issues) {
            return new ParseResult(null, issues);
        }

        boolean isSuccess() {
            return definition != null;
        }
    }
}
