package com.lodygames.rpgquest.dialogue.model;

import org.bukkit.NamespacedKey;

/**
 * Vraie si la quête peut être acceptée MAINTENANT par ce joueur (issue #13) : mêmes règles que
 * l'acceptation elle-même — inconnue, déjà active, terminée non répétable, prérequis manquants ou
 * délai de ré-acceptation ({@code cooldown:}) en cours la rendent fausse. Sert à n'afficher une
 * offre de quête renouvelable que lorsqu'elle peut réellement être prise.
 */
public record QuestAvailableCondition(NamespacedKey questId) implements DialogueCondition {

    @Override
    public ConditionType type() {
        return ConditionType.QUEST_AVAILABLE;
    }
}
