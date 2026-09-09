package com.lodygames.rpgquest.content.pack;

import java.util.List;

/**
 * Entrée « dialogue » d'un content pack (issue #108) — miroir déclaratif de
 * {@link com.lodygames.rpgquest.dialogue.model.DialogueDefinition}. Structure identique au schéma
 * YAML des dialogues RPGQuest (nœuds nommés, choix ordonnés, conditions et actions typées) :
 * consommable tel quel par {@code DialogueDefinitionParser}.
 *
 * <p>Relations préservées par identifiant : {@code Action.quest} / {@code Condition.quest}
 * (quêtes), {@code Action.dialogue} (dialogue), {@code Action.merchant} (marchand),
 * {@code Condition.item} (objet personnalisé).</p>
 */
public record DialoguePackEntry(String id, String start, List<Node> nodes) {

    public DialoguePackEntry {
        nodes = List.copyOf(nodes);
    }

    public record Node(String id, String speaker, PackText text, List<Choice> choices) {
        public Node {
            choices = List.copyOf(choices);
        }
    }

    public record Choice(PackText text, List<Condition> conditions, List<Action> actions, String next) {
        public Choice {
            conditions = List.copyOf(conditions);
            actions = List.copyOf(actions);
        }
    }

    /**
     * Action de choix. {@code type} ∈ {@code START_QUEST}, {@code ADVANCE_QUEST},
     * {@code TURN_IN_QUEST}, {@code GIVE_ITEM}, {@code TAKE_ITEM}, {@code SET_VARIABLE},
     * {@code RUN_SAFE_COMMAND}, {@code OPEN_DIALOGUE}, {@code OPEN_MERCHANT}, {@code CLOSE}.
     */
    public record Action(String type, String quest, String dialogue, String merchant, String material,
                         Integer amount, String key, String value, String command) {

        public static Action quest(String type, String questId) {
            return new Action(type, questId, null, null, null, null, null, null, null);
        }

        public static Action item(String type, String material, int amount) {
            return new Action(type, null, null, null, material, amount, null, null, null);
        }

        public static Action variable(String key, String value) {
            return new Action("SET_VARIABLE", null, null, null, null, null, key, value, null);
        }

        public static Action command(String command) {
            return new Action("RUN_SAFE_COMMAND", null, null, null, null, null, null, null, command);
        }

        public static Action openDialogue(String dialogueId) {
            return new Action("OPEN_DIALOGUE", null, dialogueId, null, null, null, null, null, null);
        }

        public static Action openMerchant(String merchantId) {
            return new Action("OPEN_MERCHANT", null, null, merchantId, null, null, null, null, null);
        }

        public static Action close() {
            return new Action("CLOSE", null, null, null, null, null, null, null, null);
        }
    }

    /**
     * Condition de choix. {@code type} ∈ {@code QUEST_STATE}, {@code HAS_ITEM},
     * {@code HAS_PERMISSION}, {@code VARIABLE_EQUALS}, {@code NO_MAIN_CLAIM}, {@code HAS_MAIN_CLAIM},
     * {@code LACKS_CUSTOM_ITEM}. {@code negate} rend la condition inverse.
     */
    public record Condition(String type, String quest, String state, String material, Integer amount,
                            String permission, String key, String value, String item, boolean negate) {

        public static Condition questState(String questId, String state, boolean negate) {
            return new Condition("QUEST_STATE", questId, state, null, null, null, null, null, null, negate);
        }

        public static Condition hasItem(String material, int amount, boolean negate) {
            return new Condition("HAS_ITEM", null, null, material, amount, null, null, null, null, negate);
        }

        public static Condition hasPermission(String permission, boolean negate) {
            return new Condition("HAS_PERMISSION", null, null, null, null, permission, null, null, null, negate);
        }

        public static Condition variableEquals(String key, String value, boolean negate) {
            return new Condition("VARIABLE_EQUALS", null, null, null, null, null, key, value, null, negate);
        }

        public static Condition flag(String type, boolean negate) {
            return new Condition(type, null, null, null, null, null, null, null, null, negate);
        }

        public static Condition lacksCustomItem(String item, boolean negate) {
            return new Condition("LACKS_CUSTOM_ITEM", null, null, null, null, null, null, null, item, negate);
        }
    }
}
