package com.lodygames.rpgquest.panel.publish;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Les contenus retirés <strong>définitivement</strong> du jeu (issue #798, décision du propriétaire
 * du 23/10 dans #778).
 *
 * <p>Retirer un fichier de DEV et du JAR ne suffit pas : un ancien fichier peut traîner dans
 * l'espace de travail de PlugAdmin (c'était le cas : l'arbre source était resté sur une branche
 * antérieure à #778), et une publication — seule ou en lot — le ferait revenir en jeu. Cette liste
 * ferme cette porte côté panel ; le plugin porte la même liste et refuse aussi
 * ({@code com.lodygames.rpgquest.content.publish.RetiredContent}).</p>
 *
 * <p>Une entrée ne s'ajoute que sur décision explicite du propriétaire, avec le ticket qui la
 * justifie.</p>
 */
public final class RetiredContent {

    /** {@code famille → identifiants}, en minuscules, sans préfixe {@code rpgquest:}. */
    private static final Map<String, Set<String>> RETIRED = Map.of(
            "quests", Set.of("first_steps", "crystal_hunt"));

    private RetiredContent() {
    }

    /** Vrai si cette ressource ne doit plus jamais être publiée. */
    public static boolean isRetired(String kind, String slug) {
        if (kind == null || slug == null) {
            return false;
        }
        String clean = slug.trim().toLowerCase(Locale.ROOT);
        int colon = clean.indexOf(':');
        clean = colon < 0 ? clean : clean.substring(colon + 1);
        return RETIRED.getOrDefault(kind.trim().toLowerCase(Locale.ROOT), Set.of()).contains(clean);
    }

    /** Le motif affiché quand une publication est refusée pour cette raison. */
    public static String refusal(String slug) {
        return "« " + slug + " » a été retiré définitivement du jeu (#778) : il ne peut plus être publié. "
                + "Rien n'a été envoyé au serveur.";
    }
}
