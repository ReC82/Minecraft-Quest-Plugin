package com.lodygames.rpgquest.webapi.site;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mise en forme du texte d'une actualité (#56) — volontairement minuscule, jamais de HTML brut :
 *
 * <ul>
 *   <li>une ligne vide sépare deux paragraphes ;</li>
 *   <li>{@code ## Titre} : intertitre ;</li>
 *   <li>{@code - élément} : liste à puces ;</li>
 *   <li>{@code **gras**} et {@code `commande`} ;</li>
 *   <li>{@code [texte](https://…)} ou {@code [texte](/chemin)} : lien ; tout autre schéma (et {@code //hôte}) reste du texte.</li>
 * </ul>
 *
 * <p>Tout est échappé avant d'être balisé : un {@code <script>} écrit dans un fichier s'affiche tel quel.</p>
 */
final class NewsMarkup {

    private static final Pattern BOLD = Pattern.compile("\\*\\*(.+?)\\*\\*");
    private static final Pattern CODE = Pattern.compile("`([^`]{1,200})`");
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]{1,200})\\]\\((https://[^\\s()<>\"']{1,500}|/(?![/\\\\])[^\\s()<>\\\\\"']{0,500})\\)");

    private NewsMarkup() {
    }

    static String render(String text) {
        StringBuilder html = new StringBuilder();
        for (String block : text.split("\\n\\s*\\n")) {
            String trimmed = block.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.startsWith("## ")) {
                html.append("<h2>").append(inline(trimmed.substring(3).strip())).append("</h2>");
                continue;
            }
            String[] lines = trimmed.split("\\n");
            if (allListItems(lines)) {
                html.append("<ul>");
                for (String line : lines) {
                    html.append("<li>").append(inline(line.strip().substring(2).strip())).append("</li>");
                }
                html.append("</ul>");
                continue;
            }
            html.append("<p>");
            for (int i = 0; i < lines.length; i++) {
                html.append(i > 0 ? "<br>" : "").append(inline(lines[i].strip()));
            }
            html.append("</p>");
        }
        return html.toString();
    }

    private static boolean allListItems(String[] lines) {
        for (String line : lines) {
            if (!line.strip().startsWith("- ")) {
                return false;
            }
        }
        return true;
    }

    /** Échappe d'abord, puis balise : les motifs ne voient que du texte déjà neutralisé. */
    static String inline(String raw) {
        String escaped = Html.escape(raw);
        Matcher link = LINK.matcher(escaped);
        StringBuilder linked = new StringBuilder();
        while (link.find()) {
            link.appendReplacement(linked, Matcher.quoteReplacement("<a href=\"" + link.group(2) + "\""
                    + (link.group(2).startsWith("https://") ? " rel=\"noopener nofollow\"" : "") + ">"
                    + link.group(1) + "</a>"));
        }
        link.appendTail(linked);
        String bold = BOLD.matcher(linked.toString()).replaceAll("<strong>$1</strong>");
        return CODE.matcher(bold).replaceAll("<code>$1</code>");
    }
}
