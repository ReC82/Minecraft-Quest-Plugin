package com.lodygames.rpgquest.quest.progress;

import java.util.List;

/**
 * Rend l'état de remise d'un PNJ (issue #123) sous forme de texte MiniMessage, pour le marqueur
 * {@code %delivery_status%} d'un texte de nœud de dialogue — « ce qui a déjà été remis » et « ce
 * qui manque », en une ligne par matériau :
 *
 * <pre>
 * ✔ Bâton 1/1
 *   Pierre 1/2
 *   Cuir 2/4
 * </pre>
 *
 * <p>Les noms d'objets passent par la balise MiniMessage {@code <lang:…>} construite depuis la clé
 * de traduction vanilla du matériau : le client affiche donc « Cuir » ou « Leather » dans sa propre
 * langue, sans aucune table de correspondance à maintenir côté plugin. C'est la même raison que
 * {@code ui.ObjectiveLabels}, adaptée au fait qu'un marqueur de texte produit une <em>chaîne</em>
 * MiniMessage et non un {@code Component}.</p>
 *
 * <p>La phrase qui entoure cette liste n'est pas ici : elle vit dans le texte du nœud
 * ({@code dialogues/*.yml}), donc entièrement éditable depuis le Control Panel sans toucher au
 * code.</p>
 */
public final class DeliveryStatusText {

    /** Réponse quand ce PNJ n'attend rien de ce joueur — jamais une liste vide muette. */
    public static final String NOTHING_EXPECTED = "<gray>Je n'attends aucun matériau de ta part.</gray>";

    private DeliveryStatusText() {
    }

    public static String render(List<DeliveryLine> lines) {
        if (lines.isEmpty()) {
            return NOTHING_EXPECTED;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                sb.append("<newline>");
            }
            sb.append(line(lines.get(i)));
        }
        return sb.toString();
    }

    private static String line(DeliveryLine line) {
        String name = "<lang:" + line.material().translationKey() + ">";
        if (line.complete()) {
            return "<green>✔ " + name + " " + line.delivered() + "/" + line.required() + "</green>";
        }
        return "<white>• " + name + " " + line.delivered() + "/" + line.required() + "</white>";
    }
}
