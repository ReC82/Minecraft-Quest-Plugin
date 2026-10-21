package com.lodygames.rpgquest.travel.model;

import com.lodygames.rpgquest.dialogue.model.ConditionType;
import com.lodygames.rpgquest.dialogue.model.DialogueCondition;
import com.lodygames.rpgquest.dialogue.model.HasItemCondition;
import com.lodygames.rpgquest.dialogue.model.HasPermissionCondition;
import com.lodygames.rpgquest.dialogue.model.NegatedCondition;
import com.lodygames.rpgquest.dialogue.model.QuestStateCondition;
import com.lodygames.rpgquest.dialogue.model.VariableEqualsCondition;
import java.util.List;
import java.util.Set;

/**
 * Conditions d'accès d'un portail entre mondes (issue #567, #228-P4), vérifiées <strong>par le
 * serveur</strong> au franchissement, après les gardes Claims / Wild : niveau RPG minimum ({@code
 * RequiredLevelRule}, #532), conditions joueur existantes ({@link DialogueCondition}, même
 * évaluateur que les dialogues) et délai entre deux passages (table {@code portal_cooldowns}).
 *
 * <p>Seuls les types de {@link #ALLOWED_TYPES} sont admis — aucune condition texte libre. Chaque
 * règle porte son message de refus (MiniMessage, vide = message par défaut).</p>
 *
 * @param requiredLevel   niveau RPG minimum ({@code null} = aucun)
 * @param levelMessage    refus « niveau » ({@code ""} = message par défaut)
 * @param rules           conditions joueur, toutes exigées
 * @param cooldownSeconds délai entre deux passages réussis d'un même joueur (0 = aucun)
 * @param cooldownMessage refus « délai » ({@code ""} = message par défaut ; {@code %seconds%} = secondes restantes)
 */
public record PortalAccess(Integer requiredLevel, String levelMessage, List<Rule> rules, int cooldownSeconds,
                           String cooldownMessage) {

    public static final PortalAccess NONE = new PortalAccess(null, "", List.of(), 0, "");

    /** Types de conditions joueur proposés pour un portail (#567). */
    public static final Set<ConditionType> ALLOWED_TYPES = Set.of(ConditionType.QUEST_STATE, ConditionType.HAS_ITEM,
            ConditionType.HAS_PERMISSION, ConditionType.VARIABLE_EQUALS, ConditionType.HAS_MAIN_CLAIM);
    public static final int MAX_RULES = 8;
    public static final int MAX_LEVEL = 1000;
    public static final int MAX_COOLDOWN_SECONDS = 86_400;
    public static final int MAX_MESSAGE_LENGTH = 200;

    public static final String DEFAULT_LEVEL_MESSAGE = "<red>Il te faut le niveau %level% pour emprunter ce portail.</red>";
    public static final String DEFAULT_COOLDOWN_MESSAGE =
            "<red>Ce portail se recharge : réessaie dans %seconds% s.</red>";

    /** Une condition joueur et son message de refus. */
    public record Rule(DialogueCondition condition, String message) {

        public Rule {
            if (condition == null) {
                throw new IllegalArgumentException("condition obligatoire.");
            }
            if (!ALLOWED_TYPES.contains(condition.type())) {
                throw new IllegalArgumentException("condition « " + condition.type() + " » non proposée pour un portail.");
            }
            message = checkMessage(message);
        }

        /** Message montré au joueur refusé : celui saisi, sinon un message par défaut selon la condition. */
        public String refusal() {
            return message.isEmpty() ? defaultMessage(condition) : message;
        }
    }

    public PortalAccess {
        if (requiredLevel != null && (requiredLevel < 1 || requiredLevel > MAX_LEVEL)) {
            throw new IllegalArgumentException("le niveau minimum doit être entre 1 et " + MAX_LEVEL + ".");
        }
        levelMessage = checkMessage(levelMessage);
        rules = rules == null ? List.of() : List.copyOf(rules);
        if (rules.size() > MAX_RULES) {
            throw new IllegalArgumentException(MAX_RULES + " conditions au plus par portail.");
        }
        if (cooldownSeconds < 0 || cooldownSeconds > MAX_COOLDOWN_SECONDS) {
            throw new IllegalArgumentException("le délai doit être entre 0 et " + MAX_COOLDOWN_SECONDS + " secondes.");
        }
        cooldownMessage = checkMessage(cooldownMessage);
    }

    /** Aucune condition : le portail se comporte exactement comme avant #567. */
    public boolean isEmpty() {
        return requiredLevel == null && rules.isEmpty() && cooldownSeconds == 0;
    }

    public String levelRefusal() {
        return levelMessage.isEmpty() ? DEFAULT_LEVEL_MESSAGE : levelMessage;
    }

    public String cooldownRefusal() {
        return cooldownMessage.isEmpty() ? DEFAULT_COOLDOWN_MESSAGE : cooldownMessage;
    }

    /** Libellé lisible d'une condition (fiche PlugAdmin, journal, simulation). */
    public static String describe(DialogueCondition condition) {
        if (condition instanceof NegatedCondition n) {
            return "NON (" + describe(n.inner()) + ")";
        }
        return switch (condition) {
            case QuestStateCondition c -> "Quête " + c.questId() + " à l'état " + c.state();
            case HasItemCondition c -> "Avoir " + c.amount() + " × " + c.material().name();
            case HasPermissionCondition c -> "Permission " + c.permission();
            case VariableEqualsCondition c -> "Variable " + c.key() + " = « " + c.value() + " »";
            default -> switch (condition.type()) {
                case HAS_MAIN_CLAIM -> "Avoir un claim principal";
                default -> condition.type().name();
            };
        };
    }

    static String defaultMessage(DialogueCondition condition) {
        if (condition instanceof NegatedCondition) {
            return "<red>Tu ne remplis pas les conditions de ce portail.</red>";
        }
        return switch (condition.type()) {
            case QUEST_STATE -> "<red>Ce portail nécessite une progression de quête différente.</red>";
            case HAS_ITEM -> "<red>Il te manque un objet pour emprunter ce portail.</red>";
            case HAS_PERMISSION -> "<red>Tu n'as pas l'autorisation d'emprunter ce portail.</red>";
            case VARIABLE_EQUALS -> "<red>Tu ne remplis pas encore les conditions de ce portail.</red>";
            case HAS_MAIN_CLAIM -> "<red>Il te faut un claim principal pour emprunter ce portail.</red>";
            default -> "<red>Tu ne remplis pas les conditions de ce portail.</red>";
        };
    }

    private static String checkMessage(String message) {
        String m = message == null ? "" : message.strip();
        if (m.length() > MAX_MESSAGE_LENGTH) {
            throw new IllegalArgumentException("message de refus trop long : " + MAX_MESSAGE_LENGTH + " caractères au plus.");
        }
        return m;
    }
}
