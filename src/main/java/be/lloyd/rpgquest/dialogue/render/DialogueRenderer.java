package be.lloyd.rpgquest.dialogue.render;

import be.lloyd.rpgquest.dialogue.model.DialogueDefinition;
import be.lloyd.rpgquest.dialogue.model.DialogueNode;
import java.util.List;
import org.bukkit.entity.Player;

/**
 * Abstraction derrière laquelle vit l'API {@code Dialog} native de Paper
 * (expérimentale dans cette version — voir {@code PaperDialogRenderer}),
 * avec {@code ChatDialogueRenderer} comme renderer de secours stable.
 */
public interface DialogueRenderer {

    void render(Player player, DialogueDefinition dialogue, DialogueNode node, List<VisibleChoice> visibleChoices);
}
