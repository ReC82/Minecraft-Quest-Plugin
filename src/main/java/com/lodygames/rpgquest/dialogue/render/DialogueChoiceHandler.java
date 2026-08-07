package com.lodygames.rpgquest.dialogue.render;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;

/**
 * Cible des clics/choix d'un renderer — implémentée par le moteur de
 * session ({@code dialogue.session}). Les renderers ne dépendent que de
 * cette petite interface, jamais du moteur concret : {@code dialogue.render}
 * n'a aucune dépendance vers {@code dialogue.session}.
 */
@FunctionalInterface
public interface DialogueChoiceHandler {

    void onChoiceSelected(Player player, NamespacedKey dialogueId, String nodeId, int choiceIndex);
}
