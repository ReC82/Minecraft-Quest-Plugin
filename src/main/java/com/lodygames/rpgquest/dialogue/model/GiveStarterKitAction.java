package com.lodygames.rpgquest.dialogue.model;

/**
 * Remet le kit d'outils en bois de départ (issue #26, partie A) si le joueur y a droit — voir
 * {@code player.StarterToolKitService} pour la logique complète (droit renouvelé à chaque mort,
 * remise tout ou rien, anti double-clic). Aucun paramètre : le contenu du kit vit dans
 * {@code config.yml} (section {@code starter-tool-kit}), jamais dans le dialogue.
 */
public record GiveStarterKitAction() implements DialogueAction {

    @Override
    public ActionType type() {
        return ActionType.GIVE_STARTER_KIT;
    }
}
