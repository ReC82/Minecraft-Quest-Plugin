package com.lodygames.rpgquest.content.pack;

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
import com.lodygames.rpgquest.npc.model.NpcDefinition;
import com.lodygames.rpgquest.quest.model.BreakBlockObjective;
import com.lodygames.rpgquest.quest.model.CollectItemObjective;
import com.lodygames.rpgquest.quest.model.CommandReward;
import com.lodygames.rpgquest.quest.model.CraftItemObjective;
import com.lodygames.rpgquest.quest.model.ExperienceReward;
import com.lodygames.rpgquest.quest.model.ItemReward;
import com.lodygames.rpgquest.quest.model.KillEntityObjective;
import com.lodygames.rpgquest.quest.model.LocalizedText;
import com.lodygames.rpgquest.quest.model.PlaceBlockObjective;
import com.lodygames.rpgquest.quest.model.QuestDefinition;
import com.lodygames.rpgquest.quest.model.QuestObjective;
import com.lodygames.rpgquest.quest.model.QuestReward;
import com.lodygames.rpgquest.quest.model.QuestStep;
import com.lodygames.rpgquest.quest.model.ReachLocationObjective;
import com.lodygames.rpgquest.quest.model.TalkToNpcObjective;
import com.lodygames.rpgquest.quest.model.VariableReward;
import com.lodygames.rpgquest.story.model.StoryDefinition;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.NamespacedKey;

/**
 * Traduit les modèles runtime RPGQuest ({@code QuestDefinition}, {@code StoryDefinition},
 * {@code DialogueDefinition}, {@code NpcDefinition}) en DTO de content pack (issue #108).
 *
 * <p><strong>Aucune sérialisation directe d'une classe runtime</strong> : chaque champ déclaratif
 * est recopié explicitement dans un DTO stable. Aucun accès disque, aucun état runtime, aucune
 * donnée joueur — les modèles source ne contiennent que du contenu éditorial.</p>
 */
public final class ContentPackMapper {

    private ContentPackMapper() {
    }

    // ---- Quêtes -----------------------------------------------------------------------------

    public static QuestPackEntry toQuestEntry(QuestDefinition q) {
        List<QuestPackEntry.Step> steps = new ArrayList<>();
        for (QuestStep step : q.steps()) {
            List<QuestPackEntry.Objective> objectives = new ArrayList<>();
            for (QuestObjective o : step.objectives()) {
                objectives.add(toObjective(o));
            }
            steps.add(new QuestPackEntry.Step(step.id(), objectives));
        }
        List<QuestPackEntry.Reward> rewards = new ArrayList<>();
        for (QuestReward r : q.rewards()) {
            rewards.add(toReward(r));
        }
        return new QuestPackEntry(
                q.id().toString(),
                text(q.title()),
                text(q.description()),
                q.category(),
                q.icon().name(),
                q.repeatable(),
                q.secret(),
                q.giver(),
                q.prerequisites().stream().map(NamespacedKey::toString).toList(),
                steps,
                rewards,
                q.variables());
    }

    private static QuestPackEntry.Objective toObjective(QuestObjective o) {
        return switch (o) {
            case BreakBlockObjective b -> QuestPackEntry.Objective.countable("BREAK_BLOCK", b.material().name(), b.amount());
            case PlaceBlockObjective b -> QuestPackEntry.Objective.countable("PLACE_BLOCK", b.material().name(), b.amount());
            case CollectItemObjective b -> QuestPackEntry.Objective.countable("COLLECT_ITEM", b.material().name(), b.amount());
            case CraftItemObjective b -> QuestPackEntry.Objective.countable("CRAFT_ITEM", b.material().name(), b.amount());
            case KillEntityObjective b -> QuestPackEntry.Objective.kill(b.entity().name(), b.amount());
            case TalkToNpcObjective b -> QuestPackEntry.Objective.talk(b.npcId());
            case ReachLocationObjective b -> QuestPackEntry.Objective.reach(b.world(), b.x(), b.y(), b.z(), b.radius());
        };
    }

    private static QuestPackEntry.Reward toReward(QuestReward r) {
        return switch (r) {
            case ExperienceReward x -> QuestPackEntry.Reward.experience(x.amount());
            case ItemReward x -> QuestPackEntry.Reward.item(x.material().name(), x.amount());
            case VariableReward x -> QuestPackEntry.Reward.variable(x.key(), x.value());
            case CommandReward x -> QuestPackEntry.Reward.command(x.command());
        };
    }

    // ---- Stories ---------------------------------------------------------------------------

    public static StoryPackEntry toStoryEntry(StoryDefinition s) {
        return new StoryPackEntry(
                s.id(),
                text(s.name()),
                s.secret(),
                s.questIds().stream().map(NamespacedKey::toString).toList());
    }

    // ---- Dialogues -----------------------------------------------------------------------

    public static DialoguePackEntry toDialogueEntry(DialogueDefinition d) {
        List<DialoguePackEntry.Node> nodes = new ArrayList<>();
        for (DialogueNode node : d.nodes().values()) {
            List<DialoguePackEntry.Choice> choices = new ArrayList<>();
            for (DialogueChoice choice : node.choices()) {
                choices.add(toChoice(choice));
            }
            nodes.add(new DialoguePackEntry.Node(node.id(), node.speaker(), text(node.text()), choices));
        }
        return new DialoguePackEntry(d.id().toString(), d.startNodeId(), nodes);
    }

    private static DialoguePackEntry.Choice toChoice(DialogueChoice choice) {
        List<DialoguePackEntry.Condition> conditions = new ArrayList<>();
        for (DialogueCondition condition : choice.conditions()) {
            conditions.add(toCondition(condition));
        }
        List<DialoguePackEntry.Action> actions = new ArrayList<>();
        for (DialogueAction action : choice.actions()) {
            actions.add(toAction(action));
        }
        return new DialoguePackEntry.Choice(text(choice.text()), conditions, actions,
                blankToNull(choice.next()));
    }

    private static DialoguePackEntry.Action toAction(DialogueAction action) {
        return switch (action) {
            case StartQuestAction a -> DialoguePackEntry.Action.quest("START_QUEST", a.questId().toString());
            case AdvanceQuestAction a -> DialoguePackEntry.Action.quest("ADVANCE_QUEST", a.questId().toString());
            case TurnInQuestAction a -> DialoguePackEntry.Action.quest("TURN_IN_QUEST", a.questId().toString());
            case GiveItemAction a -> DialoguePackEntry.Action.item("GIVE_ITEM", a.material().name(), a.amount());
            case TakeItemAction a -> DialoguePackEntry.Action.item("TAKE_ITEM", a.material().name(), a.amount());
            case SetVariableAction a -> DialoguePackEntry.Action.variable(a.key(), a.value());
            case RunSafeCommandAction a -> DialoguePackEntry.Action.command(a.command());
            case OpenDialogueAction a -> DialoguePackEntry.Action.openDialogue(a.dialogueId().toString());
            case OpenMerchantAction a -> DialoguePackEntry.Action.openMerchant(a.merchantId().toString());
            case CloseAction ignored -> DialoguePackEntry.Action.close();
        };
    }

    private static DialoguePackEntry.Condition toCondition(DialogueCondition condition) {
        boolean negate = condition instanceof NegatedCondition;
        DialogueCondition inner = negate ? ((NegatedCondition) condition).inner() : condition;
        return switch (inner) {
            case QuestStateCondition c ->
                    DialoguePackEntry.Condition.questState(c.questId().toString(), c.state().name(), negate);
            case HasItemCondition c -> DialoguePackEntry.Condition.hasItem(c.material().name(), c.amount(), negate);
            case HasPermissionCondition c -> DialoguePackEntry.Condition.hasPermission(c.permission(), negate);
            case VariableEqualsCondition c -> DialoguePackEntry.Condition.variableEquals(c.key(), c.value(), negate);
            case NoMainClaimCondition ignored -> DialoguePackEntry.Condition.flag("NO_MAIN_CLAIM", negate);
            case HasMainClaimCondition ignored -> DialoguePackEntry.Condition.flag("HAS_MAIN_CLAIM", negate);
            case LacksCustomItemCondition c -> DialoguePackEntry.Condition.lacksCustomItem(c.itemId().toString(), negate);
            case NegatedCondition ignored -> throw new IllegalStateException("négation déjà dépliée");
        };
    }

    // ---- PNJ -------------------------------------------------------------------------------

    public static NpcPackEntry toNpcEntry(NpcDefinition n) {
        return new NpcPackEntry(n.id(), n.displayName(), n.description(), n.dialogueId(), n.role(), n.enabled());
    }

    // ---- Communs -------------------------------------------------------------------------

    private static PackText text(LocalizedText t) {
        return new PackText(t.byLocale());
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v;
    }
}
