package com.lodygames.rpgquest.content.publish;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Les contenus retirés <strong>définitivement</strong> du jeu (issue #798, décision du propriétaire
 * du 23/10 dans #778) : leur publication est refusée, seule ou en lot, quel que soit l'émetteur.
 *
 * <p>Seconde barrière : PlugAdmin refuse déjà avant l'envoi (même liste,
 * {@code com.lodygames.rpgquest.panel.publish.RetiredContent}). Ici, c'est le serveur qui écrit le
 * fichier : un ancien panel, une action forgée ou un espace de travail resté sur une vieille branche
 * ne peuvent donc pas faire revenir ces quêtes. Une entrée ne s'ajoute que sur décision du
 * propriétaire, avec son ticket.</p>
 */
public final class RetiredContent {

    private static final Map<PublishKind, Set<String>> RETIRED = Map.of(
            PublishKind.QUESTS, Set.of("first_steps", "crystal_hunt"));

    private RetiredContent() {
    }

    public static boolean isRetired(PublishKind kind, String slug) {
        return kind != null && slug != null
                && RETIRED.getOrDefault(kind, Set.of()).contains(slug.trim().toLowerCase(Locale.ROOT));
    }

    /** Le motif du refus, en clair. */
    public static String refusal(String slug) {
        return "« " + slug + " » a été retiré définitivement du jeu (#778) : publication refusée.";
    }
}
