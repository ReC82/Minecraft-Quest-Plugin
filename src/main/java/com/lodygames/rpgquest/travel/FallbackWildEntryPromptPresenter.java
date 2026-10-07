package com.lodygames.rpgquest.travel;

import org.bukkit.entity.Player;
import org.slf4j.Logger;

/**
 * Décore un présentateur principal d'un repli automatique vers {@link
 * ChatWildEntryPromptPresenter} — même conception que {@code
 * dialogue.render.FallbackDialogueRenderer}. Essentiel ici : si la fenêtre Paper échouait sans
 * repli, le joueur n'aurait <strong>aucun</strong> moyen de confirmer son départ et le portail
 * paraîtrait cassé.
 *
 * <p>N'attrape jamais rien côté repli lui-même (aucune API instable là) : une exception du chat
 * remonte normalement.</p>
 */
public final class FallbackWildEntryPromptPresenter implements WildEntryPromptPresenter {

    private final WildEntryPromptPresenter primary;
    private final ChatWildEntryPromptPresenter fallback;
    private final Logger logger;

    public FallbackWildEntryPromptPresenter(WildEntryPromptPresenter primary,
                                             ChatWildEntryPromptPresenter fallback, Logger logger) {
        this.primary = primary;
        this.fallback = fallback;
        this.logger = logger;
    }

    @Override
    public void present(Player player, WildEntryPrompt prompt) {
        try {
            primary.present(player, prompt);
        } catch (RuntimeException e) {
            logger.error("Échec de l'affichage de l'avertissement d'entrée dans le Wild ({}), repli sur le chat.",
                    primary.getClass().getSimpleName(), e);
            fallback.present(player, prompt);
        }
    }
}
