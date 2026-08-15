package com.lodygames.rpgquest.dialogue.render;

import com.lodygames.rpgquest.dialogue.model.DialogueDefinition;
import com.lodygames.rpgquest.dialogue.model.DialogueNode;
import java.util.List;
import org.bukkit.entity.Player;
import org.slf4j.Logger;

/**
 * Décore un renderer principal avec un repli automatique vers
 * {@link ChatDialogueRenderer} : si {@code primary} lève une exception (par
 * exemple {@link PaperDialogRenderer}, qui repose sur une API Paper marquée
 * {@code @ApiStatus.Experimental}), le dialogue s'affiche quand même — dans
 * le chat — plutôt que de ne rien afficher du tout au joueur. N'attrape
 * jamais rien côté {@code ChatDialogueRenderer} lui-même : si le repli échoue
 * à son tour, l'exception remonte normalement (aucune API instable là).
 */
public final class FallbackDialogueRenderer implements DialogueRenderer {

    private final DialogueRenderer primary;
    private final ChatDialogueRenderer fallback;
    private final Logger logger;

    public FallbackDialogueRenderer(DialogueRenderer primary, ChatDialogueRenderer fallback, Logger logger) {
        this.primary = primary;
        this.fallback = fallback;
        this.logger = logger;
    }

    @Override
    public void render(Player player, DialogueDefinition dialogue, DialogueNode node, List<VisibleChoice> visibleChoices) {
        try {
            primary.render(player, dialogue, node, visibleChoices);
        } catch (RuntimeException e) {
            logger.error("Échec du renderer de dialogue principal ({}), repli sur le chat.",
                    primary.getClass().getSimpleName(), e);
            fallback.render(player, dialogue, node, visibleChoices);
        }
    }
}
