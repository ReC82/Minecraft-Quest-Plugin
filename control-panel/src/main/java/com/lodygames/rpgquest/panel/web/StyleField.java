package com.lodygames.rpgquest.panel.web;

import com.lodygames.rpgquest.panel.http.Http;
import java.util.List;
import java.util.Locale;

/**
 * Issue #195 — champ de texte <strong>stylé sans écrire de MiniMessage</strong> : couleur au clic,
 * cases gras / italique / souligné / barré, aperçu, et saisie en texte simple.
 *
 * <p><strong>Pourquoi ce composant.</strong> Jusqu'ici, colorer un nom de boss ou une réplique
 * imposait de taper du MiniMessage à la main ({@code <red>Roi des Marais</red>}), et le seul
 * embryon de palette présent dans les assets était du <em>code mort</em> : aucune page n'émettait
 * son balisage. MiniMessage reste le format stocké — c'est un détail interne — mais il n'est plus
 * nécessaire de le connaître pour un usage courant.</p>
 *
 * <p><strong>Contrat serveur inchangé.</strong> Le champ réellement soumis porte toujours le même
 * nom et la même valeur MiniMessage qu'avant : ni les actions agent, ni les validateurs, ni le
 * plugin ne voient de différence. Sans JavaScript, c'est un simple champ texte, donc la page reste
 * utilisable.</p>
 *
 * <p><strong>Textes multi-styles préservés.</strong> Un éditeur guidé « une couleur + des cases »
 * ne peut pas représenter {@code <red>Roi</red> <gold>des Marais</gold>} sans l'aplatir. Le
 * composant détecte donc si la valeur existante est <em>uniforme</em> (au plus une couleur et des
 * décorations englobant tout le texte) : si oui, mode guidé prérempli ; sinon, le texte reste
 * affiché tel quel en mode avancé, avec un avertissement, et basculer en mode guidé demande un
 * geste explicite. <strong>Aucune simplification silencieuse.</strong></p>
 */
final class StyleField {

    private StyleField() {
    }

    /** Couleurs proposées à la palette, dans l'ordre d'affichage (noms MiniMessage). */
    private static final List<String> SWATCHES = List.of(
            "white", "gray", "dark_gray", "black",
            "red", "dark_red", "gold", "yellow",
            "green", "dark_green", "aqua", "dark_aqua",
            "blue", "dark_blue", "light_purple", "dark_purple");

    /** Décorations proposées : nom de balise MiniMessage + libellé FR. */
    private static final List<String[]> DECORATIONS = List.of(
            new String[] {"bold", "Gras"},
            new String[] {"italic", "Italique"},
            new String[] {"underlined", "Souligné"},
            new String[] {"strikethrough", "Barré"});

    /**
     * @param name     nom du champ soumis (inchangé par rapport à l'existant)
     * @param id       id HTML de base (doit être unique dans la page)
     * @param label    intitulé affiché
     * @param value    valeur MiniMessage actuelle (peut être vide ou multi-styles)
     * @param required le champ est-il obligatoire côté métier
     * @param help     aide affichée sous le champ (HTML déjà échappé par l'appelant)
     */
    static String render(String name, String id, String label, String value, boolean required, String help) {
        String current = value == null || "null".equals(value) ? "" : value;
        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"mb-2 sf\" data-stylefield>");
        sb.append("<label class=\"form-label\" for=\"").append(id).append("-text\">")
                .append(Http.esc(label)).append("</label>");

        // Champ réellement soumis : la valeur MiniMessage. Visible et éditable sans JavaScript ;
        // panel.js le masque et le pilote dès qu'il prend la main.
        sb.append("<input class=\"form-control\" id=\"").append(id).append("-store\" type=\"text\" name=\"")
                .append(Http.esc(name)).append("\" value=\"").append(Http.esc(current)).append("\"")
                .append(required ? " required" : "").append(" data-sf-store>");

        // -- Mode guidé (construit par panel.js, masqué par défaut pour éviter tout doublon sans JS)
        sb.append("<div class=\"sf-guided\" data-sf-guided hidden>");
        sb.append("<input class=\"form-control sf-text\" id=\"").append(id)
                .append("-text\" type=\"text\" data-sf-text placeholder=\"Texte affiché, sans code\">");
        sb.append("<div class=\"sf-palette\" data-sf-palette>");
        sb.append("<button type=\"button\" class=\"sf-swatch sf-none\" data-sf-color=\"\" ")
                .append("title=\"Aucune couleur (couleur par défaut du jeu)\" aria-label=\"Aucune couleur\">∅</button>");
        for (String color : SWATCHES) {
            String hex = MiniText.colorHex(color);
            sb.append("<button type=\"button\" class=\"sf-swatch\" data-sf-color=\"").append(color)
                    .append("\" style=\"--sw:").append(hex == null ? "#888" : hex).append("\" title=\"")
                    .append(Http.esc(humanColor(color))).append("\" aria-label=\"")
                    .append(Http.esc(humanColor(color))).append("\"></button>");
        }
        sb.append("</div>");
        sb.append("<div class=\"sf-decos\">");
        for (String[] deco : DECORATIONS) {
            sb.append("<label class=\"sf-deco\"><input type=\"checkbox\" data-sf-deco=\"").append(deco[0])
                    .append("\"> ").append(Http.esc(deco[1])).append("</label>");
        }
        sb.append("</div></div>");

        // -- Avertissement multi-styles (affiché par panel.js uniquement si c'est le cas)
        sb.append("<p class=\"sf-warn\" data-sf-warn hidden></p>");
        sb.append("<button type=\"button\" class=\"btn btn-sm btn-outline-secondary sf-toggle\" ")
                .append("data-sf-toggle hidden></button>");

        sb.append("<p class=\"sf-preview\" data-sf-preview aria-live=\"polite\"></p>");
        if (help != null && !help.isBlank()) {
            sb.append("<div class=\"form-text\">").append(help).append("</div>");
        }
        sb.append("</div>");
        return sb.toString();
    }

    /** Libellé français d'une couleur nommée, pour l'infobulle et l'accessibilité. */
    private static String humanColor(String color) {
        return switch (color.toLowerCase(Locale.ROOT)) {
            case "white" -> "Blanc";
            case "gray" -> "Gris";
            case "dark_gray" -> "Gris foncé";
            case "black" -> "Noir";
            case "red" -> "Rouge";
            case "dark_red" -> "Rouge foncé";
            case "gold" -> "Or";
            case "yellow" -> "Jaune";
            case "green" -> "Vert";
            case "dark_green" -> "Vert foncé";
            case "aqua" -> "Cyan";
            case "dark_aqua" -> "Cyan foncé";
            case "blue" -> "Bleu";
            case "dark_blue" -> "Bleu foncé";
            case "light_purple" -> "Rose";
            case "dark_purple" -> "Violet";
            default -> color;
        };
    }
}
