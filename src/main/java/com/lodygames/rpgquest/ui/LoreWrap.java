package com.lodygames.rpgquest.ui;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Issue #475 — coupe un texte MiniMessage en lignes d'infobulle lisibles. Une ligne de lore n'est
 * jamais repliée par le client : une description de quête d'une seule ligne débordait de l'écran
 * et sa fin était coupée.
 *
 * <ul>
 *   <li>Coupure aux espaces, au plus {@code maxChars} caractères <em>visibles</em> par ligne (les
 *       balises ne comptent pas) ; un mot plus long que la limite reste entier.</li>
 *   <li>Les balises ouvertes au moment d'une coupure sont rouvertes en tête de la ligne suivante :
 *       la couleur d'une description {@code <gray>…</gray>} vaut sur toutes ses lignes.</li>
 *   <li>{@code <newline>} / {@code <br>} forcent un retour à la ligne ; un {@code <lang:…>} compte
 *       pour un mot.</li>
 * </ul>
 */
public final class LoreWrap {

    /** Largeur par défaut d'une ligne d'infobulle du journal, en caractères visibles. */
    public static final int DEFAULT_WIDTH = 40;

    private static final Pattern TAG = Pattern.compile("<(/?)([!?#a-zA-Z0-9_-]+)((?::[^>]*)?)>");

    private LoreWrap() {
    }

    public static List<String> wrap(String miniMessage, int maxChars) {
        List<String> lines = new ArrayList<>();
        if (miniMessage == null || miniMessage.isBlank()) {
            return lines;
        }
        String text = miniMessage.replace('\n', ' ').replaceAll(" {2,}", " ").strip();
        Deque<OpenTag> open = new ArrayDeque<>();
        StringBuilder line = new StringBuilder();
        int visible = 0;
        int i = 0;
        while (i < text.length()) {
            Matcher tag = TAG.matcher(text).region(i, text.length());
            if (tag.lookingAt()) {
                String name = tag.group(2).toLowerCase(java.util.Locale.ROOT);
                if (tag.group(1).isEmpty() && (name.equals("newline") || name.equals("br"))) {
                    lines.add(line.toString());
                    line = reopen(open);
                    visible = 0;
                } else if (!tag.group(1).isEmpty()) {
                    open.stream().filter(o -> o.name().equals(name)).findFirst().ifPresent(open::remove);
                    line.append(tag.group());
                } else if (name.equals("lang")) {
                    visible += 6;
                    line.append(tag.group());
                } else if (name.equals("reset")) {
                    open.clear();
                    line.append(tag.group());
                } else {
                    open.push(new OpenTag(name, tag.group()));
                    line.append(tag.group());
                }
                i = tag.end();
                continue;
            }
            int end = nextBoundary(text, i);
            String word = text.substring(i, end);
            if (word.equals(" ")) {
                if (visible > 0) {
                    line.append(' ');
                    visible++;
                }
                i = end;
                continue;
            }
            if (visible > 0 && visible + word.length() > maxChars) {
                // retire l'espace de fin avant de couper
                if (line.length() > 0 && line.charAt(line.length() - 1) == ' ') {
                    line.setLength(line.length() - 1);
                }
                lines.add(line.toString());
                line = reopen(open);
                visible = 0;
            }
            line.append(word);
            visible += word.length();
            i = end;
        }
        if (visible > 0 || lines.isEmpty()) {
            lines.add(line.toString().stripTrailing());
        }
        return lines;
    }

    /** Fin du mot courant : prochain espace ou prochaine balise ; un espace seul est son propre « mot ». */
    private static int nextBoundary(String text, int from) {
        if (text.charAt(from) == ' ') {
            return from + 1;
        }
        int j = from;
        while (j < text.length() && text.charAt(j) != ' ' && !(text.charAt(j) == '<' && TAG.matcher(text)
                .region(j, text.length()).lookingAt())) {
            j++;
        }
        return j;
    }

    /** Balises encore ouvertes, dans l'ordre d'ouverture, à rouvrir en tête d'une nouvelle ligne. */
    private static StringBuilder reopen(Deque<OpenTag> open) {
        StringBuilder sb = new StringBuilder();
        var it = open.descendingIterator();
        while (it.hasNext()) {
            sb.append(it.next().text());
        }
        return sb;
    }

    private record OpenTag(String name, String text) {
    }
}
