package be.lloyd.rpgquest.dialogue.render;

import be.lloyd.rpgquest.dialogue.model.DialogueDefinition;
import be.lloyd.rpgquest.dialogue.model.DialogueNode;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;

/**
 * Renderer basé sur l'API Dialog native de Paper. {@code
 * io.papermc.paper.dialog.Dialog} porte l'annotation {@code
 * @ApiStatus.Experimental} dans cette version (vérifié par inspection du
 * bytecode de paper-api 1.21.11) — utilisable, mais pas le choix par défaut
 * (voir {@code config.yml} → {@code dialogue.renderer}, et
 * {@code ChatDialogueRenderer} pour le renderer de secours stable).
 */
@SuppressWarnings("UnstableApiUsage")
public final class PaperDialogRenderer implements DialogueRenderer {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final int BUTTON_WIDTH = 150;

    private final DialogueChoiceHandler handler;

    public PaperDialogRenderer(DialogueChoiceHandler handler) {
        this.handler = handler;
    }

    @Override
    public void render(Player player, DialogueDefinition dialogue, DialogueNode node, List<VisibleChoice> visibleChoices) {
        List<ActionButton> buttons = new ArrayList<>();
        for (VisibleChoice choice : visibleChoices) {
            buttons.add(ActionButton.create(
                    MM.deserialize(choice.label()),
                    Component.empty(),
                    BUTTON_WIDTH,
                    DialogAction.customClick(
                            (view, audience) -> handler.onChoiceSelected(player, dialogue.id(), node.id(), choice.index()),
                            ClickCallback.Options.builder().uses(1).build())));
        }

        Dialog dialog = Dialog.create(factory -> factory.empty()
                .base(DialogBase.builder(MM.deserialize(node.speaker()))
                        .body(List.of(DialogBody.plainMessage(MM.deserialize(node.text().base()))))
                        .canCloseWithEscape(true)
                        .build())
                .type(DialogType.multiAction(buttons).build()));

        player.showDialog(dialog);
    }
}
