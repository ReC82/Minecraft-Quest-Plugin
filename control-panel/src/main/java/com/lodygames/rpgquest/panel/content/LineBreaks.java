package com.lodygames.rpgquest.panel.content;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Issue #728 — retours à la ligne dans une réplique de dialogue.
 *
 * <p><strong>Ce que le jeu accepte réellement</strong> (vérifié sur MiniMessage 4.26) : un vrai saut
 * de ligne dans le texte, ou les balises {@code <br>} / {@code <newline>} (et {@code <br/>}), qui
 * produisent exactement le même composant. Le chat comme la fenêtre de dialogue Paper passent alors
 * à la ligne. En revanche {@code </br>}, {@code </newline>} et un {@code \n} tapé à la main
 * s'affichent <em>tels quels</em> au joueur.</p>
 *
 * <p>Dans PlugAdmin, la façon simple est donc d'appuyer sur Entrée dans le champ. Le navigateur
 * envoie ce saut de ligne sous la forme {@code \r\n} ; {@link #normalize} le ramène à {@code \n}
 * avant l'enregistrement, pour qu'aucun {@code \r} n'arrive dans le fichier ni en jeu.</p>
 */
public final class LineBreaks {

    /** Écritures qui ressemblent à un retour à la ligne mais s'affichent telles quelles en jeu. */
    private static final List<String> LITERAL_LOOKALIKES = List.of("</br>", "</newline>", "\\n");

    private LineBreaks() {
    }

    /** {@code \r\n} et {@code \r} isolé deviennent {@code \n} ; le reste du texte est inchangé. */
    public static String normalize(String text) {
        if (text == null || text.indexOf('\r') < 0) {
            return text;
        }
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    /**
     * Les fausses balises de retour à la ligne présentes dans {@code text}, dans l'ordre de
     * {@link #LITERAL_LOOKALIKES}, sans doublon. Liste vide si le texte n'en contient aucune.
     */
    public static List<String> literalLookalikes(String text) {
        List<String> found = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return found;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String token : LITERAL_LOOKALIKES) {
            if (lower.contains(token)) {
                found.add(token);
            }
        }
        return found;
    }
}
