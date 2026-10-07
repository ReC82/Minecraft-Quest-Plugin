package com.lodygames.rpgquest.travel;

import java.util.Objects;
import net.kyori.adventure.text.Component;

/**
 * Un bouton de l'avertissement d'entrée dans le Wild (issue #161) : un libellé déjà rendu et
 * l'action à exécuter si le joueur le choisit <strong>explicitement</strong>. Fermer le menu
 * n'exécute jamais aucune action — c'est précisément pourquoi aucune n'est marquée « par défaut ».
 */
public record WildEntryPromptButton(Component label, Runnable action) {

    public WildEntryPromptButton {
        Objects.requireNonNull(label, "label est obligatoire.");
        Objects.requireNonNull(action, "action est obligatoire.");
    }
}
