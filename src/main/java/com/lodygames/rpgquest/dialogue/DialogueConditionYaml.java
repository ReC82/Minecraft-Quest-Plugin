package com.lodygames.rpgquest.dialogue;

import com.lodygames.rpgquest.dialogue.model.DialogueCondition;
import com.lodygames.rpgquest.dialogue.model.HasInventorySpaceCondition;
import com.lodygames.rpgquest.dialogue.model.HasItemCondition;
import com.lodygames.rpgquest.dialogue.model.HasMainClaimCondition;
import com.lodygames.rpgquest.dialogue.model.HasPermissionCondition;
import com.lodygames.rpgquest.dialogue.model.LacksCustomItemCondition;
import com.lodygames.rpgquest.dialogue.model.NegatedCondition;
import com.lodygames.rpgquest.dialogue.model.NoMainClaimCondition;
import com.lodygames.rpgquest.dialogue.model.PendingDeliveryCondition;
import com.lodygames.rpgquest.dialogue.model.QuestAvailableCondition;
import com.lodygames.rpgquest.dialogue.model.QuestStateCondition;
import com.lodygames.rpgquest.dialogue.model.VariableEqualsCondition;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Lecture et écriture d'une condition joueur hors d'un fichier de dialogue (issue #567 : conditions
 * d'accès des portails). La lecture passe par <strong>le même</strong> code que les dialogues
 * ({@link DialogueDefinitionParser#parseCondition}) : mêmes clés YAML ({@code type}, {@code quest},
 * {@code state}, {@code material}, {@code amount}, {@code permission}, {@code key}, {@code value},
 * {@code negate}…), mêmes messages d'erreur, aucune syntaxe libre.
 */
public final class DialogueConditionYaml {

    private static final DialogueDefinitionParser PARSER = new DialogueDefinitionParser(Set.of());

    private DialogueConditionYaml() {
    }

    /** {@code null} si invalide ; les raisons sont ajoutées à {@code errors}. */
    public static DialogueCondition parse(ConfigurationSection section, String context, List<String> errors) {
        return PARSER.parseCondition(section, context, errors);
    }

    /** Clés YAML d'une condition, relisibles par {@link #parse} (ordre stable, {@code negate} seulement si vrai). */
    public static Map<String, Object> toMap(DialogueCondition condition) {
        boolean negate = condition instanceof NegatedCondition;
        DialogueCondition inner = negate ? ((NegatedCondition) condition).inner() : condition;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", inner.type().name());
        switch (inner) {
            case QuestStateCondition c -> {
                out.put("quest", c.questId().toString());
                out.put("state", c.state().name());
            }
            case QuestAvailableCondition c -> out.put("quest", c.questId().toString());
            case HasItemCondition c -> {
                out.put("material", c.material().name());
                out.put("amount", c.amount());
            }
            case HasPermissionCondition c -> out.put("permission", c.permission());
            case VariableEqualsCondition c -> {
                out.put("key", c.key());
                out.put("value", c.value());
            }
            case LacksCustomItemCondition c -> out.put("item", c.itemId().toString());
            case PendingDeliveryCondition c -> {
                if (c.npcId() != null && !c.npcId().isBlank()) {
                    out.put("npc", c.npcId());
                }
            }
            case HasInventorySpaceCondition c -> out.put("slots", c.slots());
            case NoMainClaimCondition ignored -> {
            }
            case HasMainClaimCondition ignored -> {
            }
            case NegatedCondition ignored -> throw new IllegalStateException("double négation");
        }
        if (negate) {
            out.put("negate", true);
        }
        return out;
    }
}
