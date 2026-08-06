package be.lloyd.rpgquest.dialogue.render;

import be.lloyd.rpgquest.dialogue.model.DialogueDefinition;
import be.lloyd.rpgquest.dialogue.model.DialogueNode;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;

/**
 * Renderer de secours : dialogue affiché en MiniMessage dans le chat, choix
 * sous forme de lignes numérotées cliquables ({@link ClickEvent#callback}) —
 * fonctionne avec n'importe quel client, aucune API expérimentale.
 */
public final class ChatDialogueRenderer implements DialogueRenderer {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final DialogueChoiceHandler handler;

    public ChatDialogueRenderer(DialogueChoiceHandler handler) {
        this.handler = handler;
    }

    @Override
    public void render(Player player, DialogueDefinition dialogue, DialogueNode node, List<VisibleChoice> visibleChoices) {
        player.sendMessage(MM.deserialize(
                "<gold><bold><speaker></bold></gold>",
                Placeholder.unparsed("speaker", node.speaker())));
        player.sendMessage(MM.deserialize(
                "<gray><text></gray>",
                Placeholder.unparsed("text", node.text().base())));

        for (VisibleChoice choice : visibleChoices) {
            Component line = MM.deserialize(
                    "<yellow><number>.</yellow> <white><label></white>",
                    Placeholder.unparsed("number", String.valueOf(choice.index() + 1)),
                    Placeholder.unparsed("label", choice.label()));
            player.sendMessage(line.clickEvent(ClickEvent.callback(
                    audience -> handler.onChoiceSelected(player, dialogue.id(), node.id(), choice.index()))));
        }
    }
}
