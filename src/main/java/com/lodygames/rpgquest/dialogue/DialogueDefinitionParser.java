package com.lodygames.rpgquest.dialogue;

import com.lodygames.rpgquest.dialogue.model.ActionType;
import com.lodygames.rpgquest.dialogue.model.AdvanceQuestAction;
import com.lodygames.rpgquest.dialogue.model.CloseAction;
import com.lodygames.rpgquest.dialogue.model.ConditionType;
import com.lodygames.rpgquest.dialogue.model.DialogueAction;
import com.lodygames.rpgquest.dialogue.model.DialogueChoice;
import com.lodygames.rpgquest.dialogue.model.DialogueCondition;
import com.lodygames.rpgquest.dialogue.model.DialogueDefinition;
import com.lodygames.rpgquest.dialogue.model.DialogueNode;
import com.lodygames.rpgquest.dialogue.model.GiveItemAction;
import com.lodygames.rpgquest.dialogue.model.HasItemCondition;
import com.lodygames.rpgquest.dialogue.model.HasMainClaimCondition;
import com.lodygames.rpgquest.dialogue.model.HasPermissionCondition;
import com.lodygames.rpgquest.dialogue.model.LacksCustomItemCondition;
import com.lodygames.rpgquest.dialogue.model.NegatedCondition;
import com.lodygames.rpgquest.dialogue.model.NoMainClaimCondition;
import com.lodygames.rpgquest.dialogue.model.OpenDialogueAction;
import com.lodygames.rpgquest.dialogue.model.OpenMerchantAction;
import com.lodygames.rpgquest.dialogue.model.QuestStateCondition;
import com.lodygames.rpgquest.dialogue.model.RunSafeCommandAction;
import com.lodygames.rpgquest.dialogue.model.SetVariableAction;
import com.lodygames.rpgquest.dialogue.model.StartQuestAction;
import com.lodygames.rpgquest.dialogue.model.TakeItemAction;
import com.lodygames.rpgquest.dialogue.model.TurnInQuestAction;
import com.lodygames.rpgquest.dialogue.model.VariableEqualsCondition;
import com.lodygames.rpgquest.quest.model.LocalizedText;
import com.lodygames.rpgquest.quest.model.QuestState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;

/**
 * Valide et construit une {@link DialogueDefinition} à partir d'un fichier
 * YAML déjà parsé. Purement structurel (un seul fichier à la fois, un
 * dialogue = un fichier donc les références de nœuds {@code next} sont
 * vérifiables ici) : les références {@code OPEN_DIALOGUE} vers d'autres
 * dialogues et la détection de boucles entre dialogues sont du ressort de
 * {@link DialogueLoader}. Ne dépend que de {@link ConfigurationSection} :
 * testable en JUnit pur, sans MockBukkit. Accumule toutes les erreurs
 * trouvées plutôt que de s'arrêter à la première.
 */
final class DialogueDefinitionParser {

    static final String DEFAULT_NAMESPACE = "rpgquest";

    private final Set<String> allowedCommands;

    DialogueDefinitionParser(Set<String> allowedCommands) {
        this.allowedCommands = allowedCommands;
    }

    ParseResult parse(String fileName, ConfigurationSection section) {
        List<String> errors = new ArrayList<>();

        NamespacedKey id = parseId(section, errors);
        String start = parseStart(section, errors);
        Map<String, DialogueNode> nodes = parseNodes(section, errors);

        if (start != null && !nodes.isEmpty() && !nodes.containsKey(start)) {
            errors.add("« start » invalide : le nœud « " + start + " » n'existe pas.");
        }
        validateNextReferences(nodes, errors);

        if (!errors.isEmpty()) {
            return ParseResult.failure(errors.stream().map(m -> new DialogueLoadIssue(fileName, m)).toList());
        }

        try {
            return ParseResult.success(new DialogueDefinition(id, start, nodes));
        } catch (IllegalArgumentException e) {
            return ParseResult.failure(List.of(new DialogueLoadIssue(fileName, e.getMessage())));
        }
    }

    private void validateNextReferences(Map<String, DialogueNode> nodes, List<String> errors) {
        for (DialogueNode node : nodes.values()) {
            for (int i = 0; i < node.choices().size(); i++) {
                String next = node.choices().get(i).next();
                if (next != null && !nodes.containsKey(next)) {
                    errors.add("nodes." + node.id() + ".choices[" + i + "].next référence un nœud inexistant « " + next + " ».");
                }
            }
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
            errors.add("« id » invalide : \"" + raw + "\".");
        }
        return id;
    }

    private String parseStart(ConfigurationSection section, List<String> errors) {
        String start = section.getString("start");
        if (start == null || start.isBlank()) {
            errors.add("« start » est obligatoire.");
            return null;
        }
        return start;
    }

    private Map<String, DialogueNode> parseNodes(ConfigurationSection section, List<String> errors) {
        ConfigurationSection nodesSection = section.getConfigurationSection("nodes");
        if (nodesSection == null || nodesSection.getKeys(false).isEmpty()) {
            errors.add("« nodes » est obligatoire et doit contenir au moins un nœud.");
            return Map.of();
        }

        Map<String, DialogueNode> nodes = new LinkedHashMap<>();
        for (String nodeId : nodesSection.getKeys(false)) {
            ConfigurationSection nodeSection = nodesSection.getConfigurationSection(nodeId);
            if (nodeSection == null) {
                errors.add("nodes." + nodeId + " doit être une table.");
                continue;
            }
            DialogueNode node = parseNode(nodeId, nodeSection, errors);
            if (node != null) {
                nodes.put(nodeId, node);
            }
        }
        return nodes;
    }

    private DialogueNode parseNode(String nodeId, ConfigurationSection section, List<String> errors) {
        String context = "nodes." + nodeId;

        String speaker = section.getString("speaker");
        if (speaker == null || speaker.isBlank()) {
            errors.add(context + ": « speaker » est obligatoire.");
        }

        LocalizedText text = parseLocalizedText(section, "text", context, errors);
        List<DialogueChoice> choices = parseChoices(section, context, errors);

        if (speaker == null || speaker.isBlank() || text == null || choices.isEmpty()) {
            return null;
        }
        try {
            return new DialogueNode(nodeId, speaker, text, choices);
        } catch (IllegalArgumentException e) {
            errors.add(context + ": " + e.getMessage());
            return null;
        }
    }

    private List<DialogueChoice> parseChoices(ConfigurationSection nodeSection, String context, List<String> errors) {
        if (!nodeSection.isList("choices") || nodeSection.getMapList("choices").isEmpty()) {
            errors.add(context + ": « choices » est obligatoire et doit contenir au moins un choix.");
            return List.of();
        }

        List<DialogueChoice> choices = new ArrayList<>();
        List<Map<?, ?>> rawChoices = nodeSection.getMapList("choices");
        for (int i = 0; i < rawChoices.size(); i++) {
            DialogueChoice choice = parseChoice(toSection(rawChoices.get(i)), context + ".choices[" + i + "]", errors);
            if (choice != null) {
                choices.add(choice);
            }
        }
        return choices;
    }

    private DialogueChoice parseChoice(ConfigurationSection section, String context, List<String> errors) {
        LocalizedText text = parseLocalizedText(section, "text", context, errors);
        List<DialogueCondition> conditions = parseConditions(section, context, errors);
        List<DialogueAction> actions = parseActions(section, context, errors);
        String next = section.getString("next");

        if (text == null) {
            return null;
        }
        return new DialogueChoice(text, conditions, actions, (next == null || next.isBlank()) ? null : next);
    }

    private List<DialogueCondition> parseConditions(ConfigurationSection choiceSection, String context, List<String> errors) {
        if (!choiceSection.isSet("conditions")) {
            return List.of();
        }
        List<DialogueCondition> conditions = new ArrayList<>();
        List<Map<?, ?>> raw = choiceSection.getMapList("conditions");
        for (int i = 0; i < raw.size(); i++) {
            DialogueCondition condition = parseCondition(toSection(raw.get(i)), context + ".conditions[" + i + "]", errors);
            if (condition != null) {
                conditions.add(condition);
            }
        }
        return conditions;
    }

    private DialogueCondition parseCondition(ConfigurationSection section, String context, List<String> errors) {
        String rawType = section.getString("type");
        if (rawType == null || rawType.isBlank()) {
            errors.add(context + ": « type » est obligatoire.");
            return null;
        }
        ConditionType type;
        try {
            type = ConditionType.valueOf(rawType.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            errors.add(context + ": type de condition inconnu « " + rawType + " » (valides : "
                    + List.of(ConditionType.values()) + ").");
            return null;
        }

        DialogueCondition condition = switch (type) {
            case QUEST_STATE -> {
                NamespacedKey questId = parseQuestRef(section, "quest", context, errors);
                QuestState state = parseQuestState(section, context, errors);
                yield (questId != null && state != null) ? new QuestStateCondition(questId, state) : null;
            }
            case HAS_ITEM -> {
                Material material = parseMaterial(section, context, errors);
                Integer amount = parsePositiveInt(section, "amount", context, errors);
                yield (material != null && amount != null) ? new HasItemCondition(material, amount) : null;
            }
            case HAS_PERMISSION -> {
                String permission = section.getString("permission");
                if (permission == null || permission.isBlank()) {
                    errors.add(context + ": « permission » est obligatoire.");
                    yield null;
                }
                yield new HasPermissionCondition(permission);
            }
            case VARIABLE_EQUALS -> {
                String key = section.getString("key");
                String value = section.getString("value");
                if (key == null || key.isBlank()) {
                    errors.add(context + ": « key » est obligatoire.");
                    yield null;
                }
                yield new VariableEqualsCondition(key, value == null ? "" : value);
            }
            case NO_MAIN_CLAIM -> new NoMainClaimCondition();
            case HAS_MAIN_CLAIM -> new HasMainClaimCondition();
            case LACKS_CUSTOM_ITEM -> {
                String raw = section.getString("item");
                if (raw == null || raw.isBlank()) {
                    errors.add(context + ": « item » est obligatoire.");
                    yield null;
                }
                NamespacedKey itemId = toNamespacedKey(raw);
                if (itemId == null) {
                    errors.add(context + ": « item » invalide : \"" + raw + "\".");
                    yield null;
                }
                yield new LacksCustomItemCondition(itemId);
            }
        };

        // « negate: true » enrobe n'importe quelle condition pour en inverser le verdict (voir
        // NegatedCondition) — appliqué ici, après construction, pour rester indépendant du type.
        if (condition != null && section.getBoolean("negate", false)) {
            return new NegatedCondition(condition);
        }
        return condition;
    }

    private List<DialogueAction> parseActions(ConfigurationSection choiceSection, String context, List<String> errors) {
        if (!choiceSection.isSet("actions")) {
            return List.of();
        }
        List<DialogueAction> actions = new ArrayList<>();
        List<Map<?, ?>> raw = choiceSection.getMapList("actions");
        for (int i = 0; i < raw.size(); i++) {
            DialogueAction action = parseAction(toSection(raw.get(i)), context + ".actions[" + i + "]", errors);
            if (action != null) {
                actions.add(action);
            }
        }
        return actions;
    }

    private DialogueAction parseAction(ConfigurationSection section, String context, List<String> errors) {
        String rawType = section.getString("type");
        if (rawType == null || rawType.isBlank()) {
            errors.add(context + ": « type » est obligatoire.");
            return null;
        }
        ActionType type;
        try {
            type = ActionType.valueOf(rawType.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            errors.add(context + ": type d'action inconnu « " + rawType + " » (valides : "
                    + List.of(ActionType.values()) + ").");
            return null;
        }

        return switch (type) {
            case START_QUEST -> {
                NamespacedKey questId = parseQuestRef(section, "quest", context, errors);
                yield questId != null ? new StartQuestAction(questId) : null;
            }
            case ADVANCE_QUEST -> {
                NamespacedKey questId = parseQuestRef(section, "quest", context, errors);
                yield questId != null ? new AdvanceQuestAction(questId) : null;
            }
            case TURN_IN_QUEST -> {
                NamespacedKey questId = parseQuestRef(section, "quest", context, errors);
                yield questId != null ? new TurnInQuestAction(questId) : null;
            }
            case GIVE_ITEM -> {
                Material material = parseMaterial(section, context, errors);
                Integer amount = parsePositiveInt(section, "amount", context, errors);
                yield (material != null && amount != null) ? new GiveItemAction(material, amount) : null;
            }
            case TAKE_ITEM -> {
                Material material = parseMaterial(section, context, errors);
                Integer amount = parsePositiveInt(section, "amount", context, errors);
                yield (material != null && amount != null) ? new TakeItemAction(material, amount) : null;
            }
            case SET_VARIABLE -> {
                String key = section.getString("key");
                String value = section.getString("value");
                if (key == null || key.isBlank()) {
                    errors.add(context + ": « key » est obligatoire.");
                    yield null;
                }
                yield new SetVariableAction(key, value == null ? "" : value);
            }
            case RUN_SAFE_COMMAND -> parseRunSafeCommand(section, context, errors);
            case OPEN_DIALOGUE -> {
                String raw = section.getString("dialogue");
                if (raw == null || raw.isBlank()) {
                    errors.add(context + ": « dialogue » est obligatoire.");
                    yield null;
                }
                NamespacedKey dialogueId = toNamespacedKey(raw);
                if (dialogueId == null) {
                    errors.add(context + ": « dialogue » invalide : \"" + raw + "\".");
                    yield null;
                }
                yield new OpenDialogueAction(dialogueId);
            }
            case OPEN_MERCHANT -> {
                String raw = section.getString("merchant");
                if (raw == null || raw.isBlank()) {
                    errors.add(context + ": « merchant » est obligatoire.");
                    yield null;
                }
                NamespacedKey merchantId = toNamespacedKey(raw);
                if (merchantId == null) {
                    errors.add(context + ": « merchant » invalide : \"" + raw + "\".");
                    yield null;
                }
                yield new OpenMerchantAction(merchantId);
            }
            case CLOSE -> new CloseAction();
        };
    }

    private DialogueAction parseRunSafeCommand(ConfigurationSection section, String context, List<String> errors) {
        String command = section.getString("command");
        if (command == null || command.isBlank()) {
            errors.add(context + ": « command » est obligatoire.");
            return null;
        }
        String commandName = command.strip().split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        if (!allowedCommands.contains(commandName)) {
            errors.add(context + ": commande non autorisée « " + commandName
                    + " » (liste blanche config.yml → dialogue.allowed-commands : " + allowedCommands + ").");
            return null;
        }
        return new RunSafeCommandAction(command);
    }

    private NamespacedKey parseQuestRef(ConfigurationSection section, String key, String context, List<String> errors) {
        String raw = section.getString(key);
        if (raw == null || raw.isBlank()) {
            errors.add(context + ": « " + key + " » est obligatoire.");
            return null;
        }
        NamespacedKey questId = toNamespacedKey(raw);
        if (questId == null) {
            errors.add(context + ": « " + key + " » invalide : \"" + raw + "\".");
        }
        return questId;
    }

    private QuestState parseQuestState(ConfigurationSection section, String context, List<String> errors) {
        String raw = section.getString("state");
        if (raw == null || raw.isBlank()) {
            errors.add(context + ": « state » est obligatoire.");
            return null;
        }
        try {
            return QuestState.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            errors.add(context + ": état de quête inconnu « " + raw + " » (valides : " + List.of(QuestState.values()) + ").");
            return null;
        }
    }

    private Material parseMaterial(ConfigurationSection section, String context, List<String> errors) {
        String raw = section.getString("material");
        if (raw == null || raw.isBlank()) {
            errors.add(context + ": « material » est obligatoire.");
            return null;
        }
        Material material = Material.matchMaterial(raw);
        if (material == null) {
            errors.add(context + ": matériau inconnu « " + raw + " ».");
        }
        return material;
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

    private LocalizedText parseLocalizedText(ConfigurationSection section, String key, String context, List<String> errors) {
        if (!section.isSet(key)) {
            errors.add(context + ": « " + key + " » est obligatoire.");
            return null;
        }
        if (section.isString(key)) {
            String value = section.getString(key);
            if (value == null || value.isBlank()) {
                errors.add(context + ": « " + key + " » ne peut pas être vide.");
                return null;
            }
            return LocalizedText.of(value);
        }
        ConfigurationSection translations = section.getConfigurationSection(key);
        if (translations == null) {
            errors.add(context + ": « " + key + " » doit être soit un texte, soit une table de traductions.");
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
            errors.add(context + ": « " + key + ".default » est obligatoire quand « " + key + " » est une table de traductions.");
            return null;
        }
        return new LocalizedText(byLocale);
    }

    private NamespacedKey toNamespacedKey(String raw) {
        try {
            return raw.contains(":") ? NamespacedKey.fromString(raw) : new NamespacedKey(DEFAULT_NAMESPACE, raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private ConfigurationSection toSection(Map<?, ?> map) {
        MemoryConfiguration memory = new MemoryConfiguration();
        for (var entry : map.entrySet()) {
            memory.set(String.valueOf(entry.getKey()), entry.getValue());
        }
        return memory;
    }

    record ParseResult(DialogueDefinition dialogue, List<DialogueLoadIssue> issues) {

        static ParseResult success(DialogueDefinition dialogue) {
            return new ParseResult(dialogue, List.of());
        }

        static ParseResult failure(List<DialogueLoadIssue> issues) {
            return new ParseResult(null, issues);
        }

        boolean isSuccess() {
            return dialogue != null;
        }
    }
}
