package com.lodygames.rpgquest.dialogue;

import com.lodygames.rpgquest.dialogue.model.AdvanceQuestAction;
import com.lodygames.rpgquest.dialogue.model.CloseAction;
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
import java.util.List;
import java.util.Map;

/**
 * Rendu <strong>fidèle et déterministe</strong> d'une {@link DialogueDefinition} <em>complète</em>
 * en texte YAML re-parsable à l'identique par {@link DialogueDefinitionParser} — base de l'éditeur
 * guidé {@code /dialogues} (issue #82). Contrairement à {@link DialogueDefinitionYaml} (limité au
 * squelette {@code DialogueDraft}), cette classe sérialise <strong>tous</strong> les types
 * d'actions (10) et de conditions (8 + négation) : une édition qui passe par ici ne perd donc
 * jamais une action {@code START_QUEST}, une condition {@code QUEST_STATE}, etc.
 *
 * <p>Purement fonctionnel : aucune dépendance Bukkit à l'exécution (au-delà de
 * {@code Material.name()} / {@code NamespacedKey.toString()}), aucun accès disque. Les
 * commentaires et la mise en forme d'origine du fichier ne sont pas conservés : le fichier édité
 * adopte le <strong>format canonique</strong> du panel (choix assumé, voir le rapport #82).</p>
 */
public final class DialogueDefinitionWriter {

    private DialogueDefinitionWriter() {
    }

    /**
     * @param definition dialogue complet à sérialiser
     * @param nodeOrder  ordre d'écriture des nœuds (les nœuds absents de cette liste sont ajoutés
     *                   à la fin, triés par id ; un id de la liste absent du dialogue est ignoré)
     */
    public static String render(DialogueDefinition definition, List<String> nodeOrder) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Dialogue RPGQuest — écrit par l'éditeur guidé du Control Panel (issue #82).\n");
        sb.append("# Format canonique : commentaires et mise en forme d'origine non conservés.\n");
        sb.append("# Édition sûre uniquement : chaque écriture est re-parsée et rechargée avant validation.\n");
        sb.append("id: ").append(definition.id().toString()).append('\n');
        sb.append("start: ").append(definition.startNodeId()).append('\n');
        sb.append("nodes:\n");
        for (String nodeId : orderedNodeIds(definition, nodeOrder)) {
            renderNode(sb, definition.nodes().get(nodeId));
        }
        return sb.toString();
    }

    private static List<String> orderedNodeIds(DialogueDefinition definition, List<String> nodeOrder) {
        java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
        if (nodeOrder != null) {
            for (String id : nodeOrder) {
                if (definition.nodes().containsKey(id)) {
                    ids.add(id);
                }
            }
        }
        definition.nodes().keySet().stream().sorted().forEach(ids::add);
        return List.copyOf(ids);
    }

    private static void renderNode(StringBuilder sb, DialogueNode node) {
        sb.append("  ").append(node.id()).append(":\n");
        sb.append("    speaker: ").append(quote(node.speaker())).append('\n');
        renderText(sb, node.text());
        sb.append("    choices:\n");
        for (DialogueChoice choice : node.choices()) {
            renderChoice(sb, choice);
        }
    }

    private static void renderText(StringBuilder sb, LocalizedText text) {
        Map<String, String> byLocale = text.byLocale();
        if (byLocale.size() == 1 && byLocale.containsKey(LocalizedText.DEFAULT_KEY)) {
            sb.append("    text: ").append(quote(byLocale.get(LocalizedText.DEFAULT_KEY))).append('\n');
            return;
        }
        sb.append("    text:\n");
        // « default » d'abord (obligatoire), puis les autres locales triées pour un rendu stable.
        sb.append("      ").append(LocalizedText.DEFAULT_KEY).append(": ")
                .append(quote(byLocale.get(LocalizedText.DEFAULT_KEY))).append('\n');
        byLocale.keySet().stream().filter(k -> !k.equals(LocalizedText.DEFAULT_KEY)).sorted()
                .forEach(k -> sb.append("      ").append(k).append(": ").append(quote(byLocale.get(k))).append('\n'));
    }

    private static void renderChoice(StringBuilder sb, DialogueChoice choice) {
        sb.append("      - text: ").append(quote(choice.text().base())).append('\n');
        if (!choice.conditions().isEmpty()) {
            sb.append("        conditions:\n");
            for (DialogueCondition condition : choice.conditions()) {
                renderCondition(sb, condition);
            }
        }
        if (!choice.actions().isEmpty()) {
            sb.append("        actions:\n");
            for (DialogueAction action : choice.actions()) {
                renderAction(sb, action);
            }
        }
        if (choice.next() != null && !choice.next().isBlank()) {
            sb.append("        next: ").append(choice.next()).append('\n');
        }
    }

    private static void renderAction(StringBuilder sb, DialogueAction action) {
        switch (action) {
            case StartQuestAction a -> {
                line(sb, "type: START_QUEST");
                line(sb, "quest: " + a.questId());
            }
            case AdvanceQuestAction a -> {
                line(sb, "type: ADVANCE_QUEST");
                line(sb, "quest: " + a.questId());
            }
            case TurnInQuestAction a -> {
                line(sb, "type: TURN_IN_QUEST");
                line(sb, "quest: " + a.questId());
            }
            case GiveItemAction a -> {
                line(sb, "type: GIVE_ITEM");
                line(sb, "material: " + a.material().name());
                line(sb, "amount: " + a.amount());
            }
            case TakeItemAction a -> {
                line(sb, "type: TAKE_ITEM");
                line(sb, "material: " + a.material().name());
                line(sb, "amount: " + a.amount());
            }
            case SetVariableAction a -> {
                line(sb, "type: SET_VARIABLE");
                line(sb, "key: " + quote(a.key()));
                line(sb, "value: " + quote(a.value()));
            }
            case RunSafeCommandAction a -> {
                line(sb, "type: RUN_SAFE_COMMAND");
                line(sb, "command: " + quote(a.command()));
            }
            case OpenDialogueAction a -> {
                line(sb, "type: OPEN_DIALOGUE");
                line(sb, "dialogue: " + a.dialogueId());
            }
            case OpenMerchantAction a -> {
                line(sb, "type: OPEN_MERCHANT");
                line(sb, "merchant: " + a.merchantId());
            }
            case CloseAction ignored -> line(sb, "type: CLOSE");
        }
    }

    private static void renderCondition(StringBuilder sb, DialogueCondition condition) {
        boolean negate = condition instanceof NegatedCondition;
        DialogueCondition inner = negate ? ((NegatedCondition) condition).inner() : condition;
        switch (inner) {
            case QuestStateCondition c -> {
                line(sb, "type: QUEST_STATE");
                line(sb, "quest: " + c.questId());
                line(sb, "state: " + c.state().name());
            }
            case HasItemCondition c -> {
                line(sb, "type: HAS_ITEM");
                line(sb, "material: " + c.material().name());
                line(sb, "amount: " + c.amount());
            }
            case HasPermissionCondition c -> {
                line(sb, "type: HAS_PERMISSION");
                line(sb, "permission: " + quote(c.permission()));
            }
            case VariableEqualsCondition c -> {
                line(sb, "type: VARIABLE_EQUALS");
                line(sb, "key: " + quote(c.key()));
                line(sb, "value: " + quote(c.value()));
            }
            case NoMainClaimCondition ignored -> line(sb, "type: NO_MAIN_CLAIM");
            case HasMainClaimCondition ignored -> line(sb, "type: HAS_MAIN_CLAIM");
            case LacksCustomItemCondition c -> {
                line(sb, "type: LACKS_CUSTOM_ITEM");
                line(sb, "item: " + c.itemId());
            }
            case NegatedCondition ignored -> throw new IllegalStateException("négation déjà dépliée");
        }
        if (negate) {
            line(sb, "negate: true");
        }
    }

    /** Une entrée de liste sous {@code conditions:} / {@code actions:} : {@code "          - "} ou {@code "            "}. */
    private static void line(StringBuilder sb, String content) {
        boolean firstOfEntry = content.startsWith("type: ");
        sb.append(firstOfEntry ? "          - " : "            ").append(content).append('\n');
    }

    /** Toujours entre guillemets doubles ; échappe {@code \ " \n \t \r} (double-quoted YAML). */
    private static String quote(String value) {
        String escaped = value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\t", "\\t")
                .replace("\r", "\\r");
        return '"' + escaped + '"';
    }
}
