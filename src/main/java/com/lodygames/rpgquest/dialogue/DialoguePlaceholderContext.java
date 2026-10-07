package com.lodygames.rpgquest.dialogue;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;

/**
 * Ce qu'une valeur dynamique de texte de dialogue peut connaître au moment où le nœud est rendu
 * (voir {@link DialogueTextPlaceholders}) : le joueur qui va lire le texte, et le dialogue d'où il
 * vient.
 *
 * <p>Le dialogue est nécessaire parce que certaines valeurs dépendent du <strong>PNJ</strong> qui
 * parle — l'état de remise d'objets, par exemple (issue #123) — et que la clé du dialogue est
 * précisément l'id de ce PNJ (voir {@link DialogueNpcResolution}). Une valeur qui n'en a pas besoin,
 * comme l'état du Wild (issue #24), ignore simplement le contexte.</p>
 */
public record DialoguePlaceholderContext(Player player, NamespacedKey dialogueId) {

    /** PNJ porteur du dialogue courant, ou {@code null} si le dialogue est inconnu. */
    public String npcId() {
        return DialogueNpcResolution.resolve(null, dialogueId);
    }
}
