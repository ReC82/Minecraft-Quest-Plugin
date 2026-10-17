package com.lodygames.rpgquest.travel;

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
import org.bukkit.entity.Player;

/**
 * Avertissement d'entrée dans le Wild rendu comme une vraie fenêtre Paper (issue #161) — même
 * API, mêmes réserves et même repli que {@code dialogue.render.PaperDialogRenderer} : {@code
 * io.papermc.paper.dialog.Dialog} est annotée {@code @ApiStatus.Experimental} par Paper, d'où
 * {@link FallbackWildEntryPromptPresenter}.
 *
 * <p>{@code canCloseWithEscape(true)} : fermer la fenêtre n'exécute <strong>aucune</strong> action,
 * ce qui équivaut exactement à annuler (le joueur reste au Hub, rien n'est mémorisé). Chaque bouton
 * est limité à un seul usage ({@code uses(1)}) pour qu'un double-clic ne lance jamais deux
 * départs.</p>
 */
@SuppressWarnings("UnstableApiUsage")
public final class PaperDialogWildEntryPromptPresenter implements WildEntryPromptPresenter {

    @Override
    public void present(Player player, WildEntryPrompt prompt) {
        // Issue #467 : même mise en page que les dialogues de PNJ — un bouton par ligne.
        DialogButtonLayout layout = DialogButtonLayout.of(
                prompt.buttons().stream().map(WildEntryPromptButton::label).toList());
        List<ActionButton> buttons = new ArrayList<>();
        for (int i = 0; i < prompt.buttons().size(); i++) {
            WildEntryPromptButton button = prompt.buttons().get(i);
            DialogButtonLayout.Button shown = layout.buttons().get(i);
            buttons.add(ActionButton.create(
                    shown.label(),
                    shown.tooltip(),
                    layout.width(),
                    DialogAction.customClick(
                            (view, audience) -> button.action().run(),
                            ClickCallback.Options.builder().uses(1).build())));
        }

        List<DialogBody> body = new ArrayList<>();
        prompt.body().forEach(line -> body.add(DialogBody.plainMessage(line, DialogButtonLayout.BODY_WIDTH)));
        layout.fullLabelsBody().forEach(line -> body.add(DialogBody.plainMessage(line, DialogButtonLayout.BODY_WIDTH)));
        Dialog dialog = Dialog.create(factory -> factory.empty()
                .base(DialogBase.builder(prompt.title())
                        .body(body)
                        .canCloseWithEscape(true)
                        .build())
                .type(DialogType.multiAction(buttons).columns(DialogButtonLayout.COLUMNS).build()));

        player.showDialog(dialog);
    }
}
