package com.lodygames.rpgquest.dialogue.render;

import com.lodygames.rpgquest.dialogue.model.DialogueDefinition;
import com.lodygames.rpgquest.dialogue.model.DialogueNode;
import com.lodygames.rpgquest.ui.DialogButtonLayout;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import java.util.ArrayList;
import java.util.List;
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
 *
 * <p>Issue #467 : un bouton par ligne, largeur commune et libellés jamais tronqués en silence —
 * voir {@link DialogButtonLayout}, partagé avec l'invite d'entrée dans le Wild.</p>
 */
@SuppressWarnings("UnstableApiUsage")
public final class PaperDialogRenderer implements DialogueRenderer {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final DialogueChoiceHandler handler;

    public PaperDialogRenderer(DialogueChoiceHandler handler) {
        this.handler = handler;
    }

    @Override
    public void render(Player player, DialogueDefinition dialogue, DialogueNode node, List<VisibleChoice> visibleChoices) {
        DialogButtonLayout layout = DialogButtonLayout.of(
                visibleChoices.stream().map(choice -> MM.deserialize(choice.label())).toList());
        List<ActionButton> buttons = new ArrayList<>();
        for (int i = 0; i < visibleChoices.size(); i++) {
            VisibleChoice choice = visibleChoices.get(i);
            DialogButtonLayout.Button button = layout.buttons().get(i);
            buttons.add(ActionButton.create(
                    button.label(),
                    button.tooltip(),
                    layout.width(),
                    DialogAction.customClick(
                            (view, audience) -> handler.onChoiceSelected(player, dialogue.id(), node.id(), choice.index()),
                            ClickCallback.Options.builder().uses(1).build())));
        }

        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(MM.deserialize(node.text().base()), DialogButtonLayout.BODY_WIDTH));
        layout.fullLabelsBody().forEach(line -> body.add(DialogBody.plainMessage(line, DialogButtonLayout.BODY_WIDTH)));

        Dialog dialog = Dialog.create(factory -> factory.empty()
                .base(DialogBase.builder(MM.deserialize(node.speaker()))
                        .body(body)
                        .canCloseWithEscape(true)
                        .build())
                .type(DialogType.multiAction(buttons).columns(DialogButtonLayout.COLUMNS).build()));

        player.showDialog(dialog);
    }
}
