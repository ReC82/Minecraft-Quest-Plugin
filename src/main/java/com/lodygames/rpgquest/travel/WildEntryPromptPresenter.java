package com.lodygames.rpgquest.travel;

import org.bukkit.entity.Player;

/**
 * Affiche l'avertissement d'entrée dans le Wild (issue #161). Même raison d'être que {@code
 * dialogue.render.DialogueRenderer} : l'API {@code Dialog} de Paper est marquée expérimentale, le
 * chat cliquable reste un repli stable, et {@link WildEntryWarningService} n'a pas à savoir lequel
 * des deux est actif.
 *
 * <p>Contrat : aucune action du {@link WildEntryPrompt} ne doit être exécutée sans un choix
 * explicite du joueur — fermer la fenêtre revient donc à annuler, sans effet de bord.</p>
 */
@FunctionalInterface
public interface WildEntryPromptPresenter {

    void present(Player player, WildEntryPrompt prompt);
}
