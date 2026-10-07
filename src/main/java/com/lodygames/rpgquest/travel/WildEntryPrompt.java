package com.lodygames.rpgquest.travel;

import java.util.List;
import java.util.Objects;
import net.kyori.adventure.text.Component;

/**
 * Contenu de l'avertissement affiché avant une entrée dans le Wild (issue #161), indépendant de la
 * façon de l'afficher : {@link WildEntryPromptPresenter} en fait une fenêtre Paper ou des lignes
 * cliquables dans le chat. Le texte vit dans {@link WildEntryWarningService} (une seule source),
 * la mise en forme ici.
 */
public record WildEntryPrompt(Component title, List<Component> body, List<WildEntryPromptButton> buttons) {

    public WildEntryPrompt {
        Objects.requireNonNull(title, "title est obligatoire.");
        body = List.copyOf(body);
        buttons = List.copyOf(buttons);
        if (buttons.isEmpty()) {
            throw new IllegalArgumentException("un avertissement sans bouton ne laisserait aucune issue au joueur.");
        }
    }
}
