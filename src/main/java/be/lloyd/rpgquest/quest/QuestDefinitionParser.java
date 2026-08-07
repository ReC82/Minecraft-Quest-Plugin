package be.lloyd.rpgquest.quest;

import be.lloyd.rpgquest.quest.model.BreakBlockObjective;
import be.lloyd.rpgquest.quest.model.CollectItemObjective;
import be.lloyd.rpgquest.quest.model.CommandReward;
import be.lloyd.rpgquest.quest.model.CraftItemObjective;
import be.lloyd.rpgquest.quest.model.ExperienceReward;
import be.lloyd.rpgquest.quest.model.ItemReward;
import be.lloyd.rpgquest.quest.model.KillEntityObjective;
import be.lloyd.rpgquest.quest.model.LocalizedText;
import be.lloyd.rpgquest.quest.model.ObjectiveType;
import be.lloyd.rpgquest.quest.model.PlaceBlockObjective;
import be.lloyd.rpgquest.quest.model.QuestDefinition;
import be.lloyd.rpgquest.quest.model.QuestObjective;
import be.lloyd.rpgquest.quest.model.QuestReward;
import be.lloyd.rpgquest.quest.model.QuestStep;
import be.lloyd.rpgquest.quest.model.ReachLocationObjective;
import be.lloyd.rpgquest.quest.model.RewardType;
import be.lloyd.rpgquest.quest.model.TalkToNpcObjective;
import be.lloyd.rpgquest.quest.model.VariableReward;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;

/**
 * Valide et construit une {@link QuestDefinition} à partir d'un fichier YAML
 * déjà parsé. Purement structurel (un seul fichier à la fois) : les
 * vérifications inter-fichiers (doublons d'id, prérequis) sont du ressort de
 * {@link QuestLoader}. Ne dépend que de {@link ConfigurationSection} :
 * testable en JUnit pur, sans MockBukkit. Accumule toutes les erreurs
 * trouvées plutôt que de s'arrêter à la première, pour un rapport complet en
 * un seul passage.
 */
final class QuestDefinitionParser {

    static final String DEFAULT_NAMESPACE = "rpgquest";

    ParseResult parse(String fileName, ConfigurationSection section) {
        List<String> errors = new ArrayList<>();

        NamespacedKey id = parseId(section, errors);
        LocalizedText title = parseLocalizedText(section, "title", errors);
        LocalizedText description = parseLocalizedText(section, "description", errors);
        String category = parseCategory(section, errors);
        Material icon = parseIcon(section, errors);
        boolean repeatable = parseRepeatable(section, errors);
        List<NamespacedKey> prerequisites = parsePrerequisites(section, id, errors);
        List<QuestStep> steps = parseSteps(section, errors);
        List<QuestReward> rewards = parseRewards(section, errors);
        Map<String, String> variables = parseVariables(section, errors);

        if (!errors.isEmpty()) {
            return ParseResult.failure(errors.stream().map(message -> new QuestLoadIssue(fileName, message)).toList());
        }

        try {
            QuestDefinition quest = new QuestDefinition(
                    id, title, description, category, icon, repeatable, prerequisites, steps, rewards, variables);
            return ParseResult.success(quest);
        } catch (IllegalArgumentException e) {
            return ParseResult.failure(List.of(new QuestLoadIssue(fileName, e.getMessage())));
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

    private LocalizedText parseLocalizedText(ConfigurationSection section, String key, List<String> errors) {
        if (!section.isSet(key)) {
            errors.add("« " + key + " » est obligatoire.");
            return null;
        }
        if (section.isString(key)) {
            String value = section.getString(key);
            if (value == null || value.isBlank()) {
                errors.add("« " + key + " » ne peut pas être vide.");
                return null;
            }
            return LocalizedText.of(value);
        }
        ConfigurationSection translations = section.getConfigurationSection(key);
        if (translations == null) {
            errors.add("« " + key + " » doit être soit un texte, soit une table de traductions.");
            return null;
        }
        Map<String, String> byLocale = new LinkedHashMap<>();
        for (String locale : translations.getKeys(false)) {
            String value = translations.getString(locale);
            if (value != null && !value.isBlank()) {
                byLocale.put(locale, value);
            }
        }
        if (!byLocale.containsKey(LocalizedText.DEFAULT_KEY)) {
            errors.add("« " + key + ".default » est obligatoire quand « " + key + " » est une table de traductions.");
            return null;
        }
        return new LocalizedText(byLocale);
    }

    private String parseCategory(ConfigurationSection section, List<String> errors) {
        String category = section.getString("category");
        if (category == null || category.isBlank()) {
            errors.add("« category » est obligatoire.");
            return null;
        }
        return category;
    }

    private static final Material DEFAULT_ICON = Material.BOOK;

    private Material parseIcon(ConfigurationSection section, List<String> errors) {
        if (!section.isSet("icon")) {
            return DEFAULT_ICON;
        }
        String raw = section.getString("icon");
        Material material = raw == null ? null : Material.matchMaterial(raw);
        if (material == null) {
            errors.add("« icon » : matériau inconnu « " + raw + " ».");
            return DEFAULT_ICON;
        }
        return material;
    }

    private boolean parseRepeatable(ConfigurationSection section, List<String> errors) {
        if (!section.isSet("repeatable")) {
            return false;
        }
        if (!section.isBoolean("repeatable")) {
            errors.add("« repeatable » doit être un booléen (true/false), valeur trouvée : " + section.get("repeatable"));
            return false;
        }
        return section.getBoolean("repeatable");
    }

    private List<NamespacedKey> parsePrerequisites(ConfigurationSection section, NamespacedKey ownId, List<String> errors) {
        if (!section.isSet("prerequisites")) {
            return List.of();
        }
        List<String> raw = section.getStringList("prerequisites");
        List<NamespacedKey> prerequisites = new ArrayList<>();
        for (String entry : raw) {
            NamespacedKey key = toNamespacedKey(entry);
            if (key == null) {
                errors.add("« prerequisites » contient une référence invalide : \"" + entry + "\".");
                continue;
            }
            if (key.equals(ownId)) {
                errors.add("« prerequisites » ne peut pas référencer la quête elle-même (« " + key + " »).");
                continue;
            }
            prerequisites.add(key);
        }
        return prerequisites;
    }

    private List<QuestStep> parseSteps(ConfigurationSection section, List<String> errors) {
        if (!section.isList("steps") || section.getMapList("steps").isEmpty()) {
            errors.add("« steps » est obligatoire et doit contenir au moins une étape.");
            return List.of();
        }

        List<QuestStep> steps = new ArrayList<>();
        Set<String> seenIds = new LinkedHashSet<>();
        List<Map<?, ?>> rawSteps = section.getMapList("steps");
        for (int i = 0; i < rawSteps.size(); i++) {
            String context = "steps[" + i + "]";
            ConfigurationSection stepSection = toSection(rawSteps.get(i));

            String stepId = stepSection.getString("id");
            if (stepId == null || stepId.isBlank()) {
                errors.add(context + ": « id » est obligatoire.");
                continue;
            }
            if (!seenIds.add(stepId)) {
                errors.add(context + ": id d'étape dupliqué « " + stepId + " » dans la même quête.");
                continue;
            }

            List<QuestObjective> objectives = parseObjectives(stepSection, context + " (" + stepId + ")", errors);
            if (objectives.isEmpty()) {
                errors.add(context + " (" + stepId + "): doit avoir au moins un objectif valide.");
                continue;
            }
            steps.add(new QuestStep(stepId, objectives));
        }
        return steps;
    }

    private List<QuestObjective> parseObjectives(ConfigurationSection stepSection, String context, List<String> errors) {
        if (!stepSection.isList("objectives") || stepSection.getMapList("objectives").isEmpty()) {
            errors.add(context + ": « objectives » est obligatoire et doit contenir au moins un objectif.");
            return List.of();
        }

        List<QuestObjective> objectives = new ArrayList<>();
        List<Map<?, ?>> rawObjectives = stepSection.getMapList("objectives");
        for (int i = 0; i < rawObjectives.size(); i++) {
            QuestObjective objective = parseObjective(
                    toSection(rawObjectives.get(i)), context + ".objectives[" + i + "]", errors);
            if (objective != null) {
                objectives.add(objective);
            }
        }
        return objectives;
    }

    private QuestObjective parseObjective(ConfigurationSection section, String context, List<String> errors) {
        String rawType = section.getString("type");
        if (rawType == null || rawType.isBlank()) {
            errors.add(context + ": « type » est obligatoire.");
            return null;
        }

        ObjectiveType type;
        try {
            type = ObjectiveType.valueOf(rawType.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            errors.add(context + ": type d'objectif inconnu « " + rawType + " » (valides : "
                    + List.of(ObjectiveType.values()) + ").");
            return null;
        }

        return switch (type) {
            case BREAK_BLOCK -> {
                Material material = parseMaterial(section, "material", context, errors);
                Integer amount = parsePositiveInt(section, "amount", context, errors);
                yield (material != null && amount != null) ? new BreakBlockObjective(material, amount) : null;
            }
            case PLACE_BLOCK -> {
                Material material = parseMaterial(section, "material", context, errors);
                Integer amount = parsePositiveInt(section, "amount", context, errors);
                yield (material != null && amount != null) ? new PlaceBlockObjective(material, amount) : null;
            }
            case COLLECT_ITEM -> {
                Material material = parseMaterial(section, "material", context, errors);
                Integer amount = parsePositiveInt(section, "amount", context, errors);
                yield (material != null && amount != null) ? new CollectItemObjective(material, amount) : null;
            }
            case CRAFT_ITEM -> {
                Material material = parseMaterial(section, "material", context, errors);
                Integer amount = parsePositiveInt(section, "amount", context, errors);
                yield (material != null && amount != null) ? new CraftItemObjective(material, amount) : null;
            }
            case KILL_ENTITY -> {
                EntityType entity = parseEntityType(section, context, errors);
                Integer amount = parsePositiveInt(section, "amount", context, errors);
                yield (entity != null && amount != null) ? new KillEntityObjective(entity, amount) : null;
            }
            case TALK_TO_NPC -> {
                String npc = section.getString("npc");
                if (npc == null || npc.isBlank()) {
                    errors.add(context + ": « npc » est obligatoire.");
                    yield null;
                }
                yield new TalkToNpcObjective(npc);
            }
            case REACH_LOCATION -> parseReachLocation(section, context, errors);
        };
    }

    private QuestObjective parseReachLocation(ConfigurationSection section, String context, List<String> errors) {
        String world = section.getString("world");
        if (world == null || world.isBlank()) {
            errors.add(context + ": « world » est obligatoire.");
            return null;
        }
        if (!section.isSet("x") || !section.isSet("y") || !section.isSet("z")) {
            errors.add(context + ": « x », « y » et « z » sont obligatoires.");
            return null;
        }
        double radius = section.getDouble("radius", 1.0);
        if (radius <= 0) {
            errors.add(context + ": « radius » doit être strictement positif, valeur trouvée : " + radius);
            return null;
        }
        return new ReachLocationObjective(world, section.getDouble("x"), section.getDouble("y"), section.getDouble("z"), radius);
    }

    private Material parseMaterial(ConfigurationSection section, String key, String context, List<String> errors) {
        String raw = section.getString(key);
        if (raw == null || raw.isBlank()) {
            errors.add(context + ": « " + key + " » est obligatoire.");
            return null;
        }
        Material material = Material.matchMaterial(raw);
        if (material == null) {
            errors.add(context + ": matériau inconnu « " + raw + " ».");
            return null;
        }
        return material;
    }

    private EntityType parseEntityType(ConfigurationSection section, String context, List<String> errors) {
        String raw = section.getString("entity");
        if (raw == null || raw.isBlank()) {
            errors.add(context + ": « entity » est obligatoire.");
            return null;
        }
        EntityType entity = EntityType.fromName(raw.toLowerCase(Locale.ROOT));
        if (entity == null) {
            errors.add(context + ": type d'entité inconnu « " + raw + " ».");
            return null;
        }
        return entity;
    }

    private Integer parsePositiveInt(ConfigurationSection section, String key, String context, List<String> errors) {
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

    private List<QuestReward> parseRewards(ConfigurationSection section, List<String> errors) {
        if (!section.isSet("rewards")) {
            return List.of();
        }
        List<QuestReward> rewards = new ArrayList<>();
        List<Map<?, ?>> rawRewards = section.getMapList("rewards");
        for (int i = 0; i < rawRewards.size(); i++) {
            QuestReward reward = parseReward(toSection(rawRewards.get(i)), "rewards[" + i + "]", errors);
            if (reward != null) {
                rewards.add(reward);
            }
        }
        return rewards;
    }

    private QuestReward parseReward(ConfigurationSection section, String context, List<String> errors) {
        String rawType = section.getString("type");
        if (rawType == null || rawType.isBlank()) {
            errors.add(context + ": « type » est obligatoire.");
            return null;
        }

        RewardType type;
        try {
            type = RewardType.valueOf(rawType.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            errors.add(context + ": type de récompense inconnu « " + rawType + " » (valides : "
                    + List.of(RewardType.values()) + ").");
            return null;
        }

        return switch (type) {
            case EXPERIENCE -> {
                Integer amount = parsePositiveInt(section, "amount", context, errors);
                yield amount != null ? new ExperienceReward(amount) : null;
            }
            case ITEM -> {
                Material material = parseMaterial(section, "material", context, errors);
                Integer amount = parsePositiveInt(section, "amount", context, errors);
                yield (material != null && amount != null) ? new ItemReward(material, amount) : null;
            }
            case VARIABLE -> {
                String key = section.getString("key");
                String value = section.getString("value");
                if (key == null || key.isBlank()) {
                    errors.add(context + ": « key » est obligatoire.");
                    yield null;
                }
                if (value == null) {
                    errors.add(context + ": « value » est obligatoire.");
                    yield null;
                }
                yield new VariableReward(key, value);
            }
            case COMMAND -> {
                String command = section.getString("command");
                if (command == null || command.isBlank()) {
                    errors.add(context + ": « command » est obligatoire.");
                    yield null;
                }
                yield new CommandReward(command);
            }
        };
    }

    private Map<String, String> parseVariables(ConfigurationSection section, List<String> errors) {
        ConfigurationSection variables = section.getConfigurationSection("variables");
        if (variables == null) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (String key : variables.getKeys(false)) {
            result.put(key, String.valueOf(variables.get(key)));
        }
        return result;
    }

    private ConfigurationSection toSection(Map<?, ?> map) {
        var memory = new org.bukkit.configuration.MemoryConfiguration();
        for (var entry : map.entrySet()) {
            memory.set(String.valueOf(entry.getKey()), entry.getValue());
        }
        return memory;
    }

    record ParseResult(QuestDefinition quest, List<QuestLoadIssue> issues) {

        static ParseResult success(QuestDefinition quest) {
            return new ParseResult(quest, List.of());
        }

        static ParseResult failure(List<QuestLoadIssue> issues) {
            return new ParseResult(null, issues);
        }

        boolean isSuccess() {
            return quest != null;
        }
    }
}
