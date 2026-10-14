package com.lodygames.rpgquest.panel.content;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lecteur YAML <strong>minimal</strong> — juste ce qu'il faut pour relire la forme produite par
 * {@link QuestYaml} et {@link StoryYaml} dans le garde-fou round-trip (issue #46). Gère :
 * indentation par espaces, {@code clé: valeur}, blocs imbriqués (map / liste), listes {@code - }
 * (scalaire ou map en ligne {@code - clé: valeur}), scalaires entre guillemets doubles/simples,
 * {@code true}/{@code false}/{@code null}, commentaires {@code #} hors chaînes, et listes en ligne
 * de scalaires {@code [a, "b c"]} (issue #240 : c'est la forme que {@link QuestYaml} émet pour un
 * champ liste, et la relire comme du texte cassait le garde-fou round-trip), et scalaires blocs
 * {@code |} / {@code >} avec leur chomping {@code -} / {@code +} (issue #373 : le dialogue du Garde
 * les emploie, et leur absence tronquait la lecture).
 *
 * <p>Ce n'est <strong>pas</strong> un parseur YAML conforme : pas d'ancres, pas de flow-maps {@code {a: 1}}, pas de listes en ligne imbriquées. Une ligne que ce lecteur ne comprend pas lève une
 * {@link IllegalArgumentException} (issue #373) : jamais de document tronqué en silence.</p>
 */
public final class MiniYaml {

    private MiniYaml() {
    }

    private record Line(int indent, String text) {
    }

    /**
     * Public depuis #224 : l'atelier IA doit relire la proposition qu'il vient d'obtenir pour
     * vérifier lui-même les contraintes du formulaire, et il vit dans un autre paquet. Rien à
     * protéger ici — la méthode ne fait que lire du texte, sans accès disque ni effet de bord.
     */
    public static Object parse(String yaml) {
        List<Line> lines = new ArrayList<>();
        String norm = (yaml == null ? "" : yaml).replace("\r\n", "\n").replace('\r', '\n');
        String[] raws = norm.split("\n", -1);
        for (int r = 0; r < raws.length; r++) {
            String noComment = stripComment(raws[r]);
            if (noComment.strip().isEmpty()) {
                continue;
            }
            int indent = indentOf(noComment);
            String text = noComment.substring(indent).stripTrailing();
            BlockHeader header = blockHeader(text, indent);
            if (header != null) {
                // Issue #373 : le scalaire bloc est lu sur les lignes BRUTES (un « # » y est du texte,
                // une ligne vide y compte), puis remplacé par une seule ligne « clé: "valeur" ».
                int end = blockEnd(raws, r + 1, header.parentIndent());
                String value = blockValue(raws, r + 1, end, header);
                lines.add(new Line(indent, header.prefix() + quoteForLine(value)));
                r = end - 1;
                continue;
            }
            lines.add(new Line(indent, text));
        }
        if (lines.isEmpty()) {
            return new LinkedHashMap<String, Object>();
        }
        int[] idx = {0};
        Object root = parseBlock(lines, idx, lines.get(0).indent());
        if (idx[0] < lines.size()) {
            // Issue #373 : avant, la lecture s'arrêtait là en silence et rendait un document
            // TRONQUÉ (le dialogue du Garde réduit à deux répliques). Toute ligne non comprise est
            // désormais une erreur : l'appelant passe en lecture seule au lieu de réécrire une
            // version partielle.
            throw new IllegalArgumentException("ligne non comprise (indentation ou écriture non prise en charge) : « "
                    + lines.get(idx[0]).text() + " »");
        }
        return root;
    }

    // ---- scalaires blocs « | » et « > » (issue #373) -------------------------------------

    /**
     * En-tête d'un scalaire bloc.
     *
     * @param prefix       ce qui précède l'indicateur ({@code "text: "}, {@code "- "}…)
     * @param parentIndent colonne du nœud parent : le contenu doit être plus indenté
     * @param folded       {@code >} (replié) plutôt que {@code |} (littéral)
     * @param chomping     {@code '-'} (retirer), {@code '+'} (garder) ou {@code ' '} (un seul saut final)
     */
    private record BlockHeader(String prefix, int parentIndent, boolean folded, char chomping) {
    }

    private static final java.util.regex.Pattern BLOCK_INDICATOR =
            java.util.regex.Pattern.compile("[|>]([1-9]?)([+-]?)([1-9]?)");

    /** L'en-tête si la ligne se termine par {@code |} / {@code >} (avec chomping), sinon {@code null}. */
    private static BlockHeader blockHeader(String text, int indent) {
        int keyCol = indent;
        String rest = text;
        while (rest.startsWith("- ")) {
            String after = rest.substring(2);
            int pad = indentOf(after);
            keyCol += 2 + pad;
            rest = after.substring(pad);
        }
        String indicator;
        int parentIndent;
        if (BLOCK_INDICATOR.matcher(rest).matches()) {
            // « - > » : élément de liste scalaire, le parent est la liste.
            indicator = rest;
            parentIndent = keyCol - 2;
        } else {
            int colon = findColon(rest);
            if (colon < 0) {
                return null;
            }
            indicator = rest.substring(colon + 1).strip();
            parentIndent = keyCol;
        }
        java.util.regex.Matcher m = BLOCK_INDICATOR.matcher(indicator);
        if (!m.matches()) {
            return null;
        }
        if (!m.group(1).isEmpty() || !m.group(3).isEmpty()) {
            throw new IllegalArgumentException("indicateur d'indentation explicite non pris en charge : « " + text + " »");
        }
        String chomp = m.group(2);
        return new BlockHeader(text.substring(0, text.length() - indicator.length()), parentIndent,
                indicator.charAt(0) == '>', chomp.isEmpty() ? ' ' : chomp.charAt(0));
    }

    /** Première ligne brute après le bloc : non vide et pas plus indentée que le parent. */
    private static int blockEnd(String[] raws, int from, int parentIndent) {
        int r = from;
        while (r < raws.length && (raws[r].isBlank() || indentOf(raws[r]) > parentIndent)) {
            r++;
        }
        return r;
    }

    /** Valeur d'un scalaire bloc, selon les règles YAML (celles du moteur, SnakeYAML). */
    private static String blockValue(String[] raws, int from, int end, BlockHeader h) {
        int contentIndent = -1;
        for (int r = from; r < end; r++) {
            if (!raws[r].isBlank()) {
                contentIndent = indentOf(raws[r]);
                break;
            }
        }
        List<String> content = new ArrayList<>();
        for (int r = from; r < end; r++) {
            String raw = raws[r];
            content.add(contentIndent < 0 || raw.length() <= contentIndent ? (raw.isBlank() ? "" : raw.strip())
                    : raw.substring(contentIndent));
        }
        int last = content.size();
        while (last > 0 && content.get(last - 1).isEmpty()) {
            last--;
        }
        int trailingBreaks = content.size() - last;
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < last; i++) {
            String line = content.get(i);
            if (i > 0) {
                body.append(lineBreak(content, i, h.folded()));
            }
            body.append(line);
        }
        if (last == 0) {
            return h.chomping() == '+' ? "\n".repeat(trailingBreaks) : "";
        }
        return switch (h.chomping()) {
            case '-' -> body.toString();
            case '+' -> body + "\n".repeat(1 + trailingBreaks);
            default -> body + "\n";
        };
    }

    /**
     * Séparateur entre la ligne {@code i - 1} et la ligne {@code i}. Littéral : un saut de ligne.
     * Replié : une espace entre deux lignes ordinaires, un saut de ligne autour d'une ligne vide ou
     * plus indentée (une ligne vide est elle-même un saut, déjà compté par son contenu vide).
     */
    private static String lineBreak(List<String> content, int i, boolean folded) {
        if (!folded) {
            return "\n";
        }
        String prev = content.get(i - 1);
        String cur = content.get(i);
        boolean prevMore = prev.startsWith(" ") || prev.startsWith("\t");
        boolean curMore = cur.startsWith(" ") || cur.startsWith("\t");
        if (prevMore || curMore) {
            return "\n";
        }
        if (cur.isEmpty()) {
            // Première ligne vide après une ligne ordinaire : le saut de celle-ci est absorbé.
            return prev.isEmpty() ? "\n" : "";
        }
        if (prev.isEmpty()) {
            return "\n";
        }
        return " ";
    }

    /** Chaîne entre guillemets doubles, échappée pour {@link #unescape}. */
    private static String quoteForLine(String value) {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
                .replace("\t", "\\t").replace("\r", "\\r") + '"';
    }

    private static int indentOf(String s) {
        int indent = 0;
        while (indent < s.length() && s.charAt(indent) == ' ') {
            indent++;
        }
        return indent;
    }

    private static Object parseBlock(List<Line> lines, int[] idx, int indent) {
        return lines.get(idx[0]).text().startsWith("-")
                ? parseList(lines, idx, indent)
                : parseMap(lines, idx, indent);
    }

    private static Map<String, Object> parseMap(List<Line> lines, int[] idx, int indent) {
        Map<String, Object> out = new LinkedHashMap<>();
        while (idx[0] < lines.size()) {
            Line line = lines.get(idx[0]);
            if (line.indent() != indent || line.text().startsWith("- ") || line.text().equals("-")) {
                break;
            }
            int colon = findColon(line.text());
            if (colon < 0) {
                break;
            }
            String key = unquote(line.text().substring(0, colon).strip());
            String val = line.text().substring(colon + 1).strip();
            idx[0]++;
            if (!val.isEmpty()) {
                out.put(key, scalar(val));
                continue;
            }
            if (idx[0] < lines.size() && lines.get(idx[0]).indent() > indent) {
                out.put(key, parseBlock(lines, idx, lines.get(idx[0]).indent()));
            } else if (idx[0] < lines.size() && lines.get(idx[0]).indent() == indent
                    && lines.get(idx[0]).text().startsWith("-")) {
                out.put(key, parseList(lines, idx, indent));
            } else {
                out.put(key, "");
            }
        }
        return out;
    }

    private static List<Object> parseList(List<Line> lines, int[] idx, int indent) {
        List<Object> out = new ArrayList<>();
        while (idx[0] < lines.size()) {
            Line line = lines.get(idx[0]);
            if (line.indent() != indent || !line.text().startsWith("-")) {
                break;
            }
            String content = line.text().length() > 1 ? line.text().substring(1).strip() : "";
            if (content.isEmpty()) {
                idx[0]++;
                if (idx[0] < lines.size() && lines.get(idx[0]).indent() > indent) {
                    out.add(parseBlock(lines, idx, lines.get(idx[0]).indent()));
                } else {
                    out.add("");
                }
            } else if (findColon(content) >= 0) {
                int contentCol = line.indent() + line.text().indexOf(content);
                lines.set(idx[0], new Line(contentCol, content));
                out.add(parseMap(lines, idx, contentCol));
            } else {
                out.add(scalar(content));
                idx[0]++;
            }
        }
        return out;
    }

    // ---- scalaires / helpers ---------------------------------------------------------------

    private static Object scalar(String v) {
        String s = v.strip();
        if (s.length() >= 2 && s.charAt(0) == '"' && s.endsWith("\"")) {
            return unescape(s.substring(1, s.length() - 1));
        }
        if (s.length() >= 2 && s.charAt(0) == '\'' && s.endsWith("'")) {
            return s.substring(1, s.length() - 1).replace("''", "'");
        }
        if (s.equals("true") || s.equals("false")) {
            return Boolean.valueOf(s);
        }
        if (s.equals("null") || s.equals("~")) {
            return null;
        }
        if (s.length() >= 2 && s.charAt(0) == '[' && s.endsWith("]")) {
            List<Object> items = flowSequence(s.substring(1, s.length() - 1));
            if (items != null) {
                return items;
            }
        }
        return s;
    }

    /**
     * {@code a, "b, c", 'd'} → trois scalaires. {@code null} si la liste n'est pas une simple liste
     * de scalaires (imbrication, guillemet non fermé) : le texte est alors rendu tel quel, et le
     * garde-fou round-trip signale la divergence au lieu qu'on devine.
     */
    private static List<Object> flowSequence(String body) {
        List<Object> out = new ArrayList<>();
        if (body.isBlank()) {
            return out;
        }
        StringBuilder item = new StringBuilder();
        boolean inD = false;
        boolean inS = false;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '"' && !inS && (i == 0 || body.charAt(i - 1) != '\\')) {
                inD = !inD;
            } else if (c == '\'' && !inD) {
                inS = !inS;
            } else if (!inD && !inS && (c == '[' || c == ']' || c == '{' || c == '}')) {
                return null;
            } else if (c == ',' && !inD && !inS) {
                if (item.toString().isBlank()) {
                    return null;
                }
                out.add(scalar(item.toString()));
                item.setLength(0);
                continue;
            }
            item.append(c);
        }
        if (inD || inS || item.toString().isBlank()) {
            return null;
        }
        out.add(scalar(item.toString()));
        return out;
    }

    private static String unquote(String s) {
        Object v = scalar(s);
        return v == null ? "" : String.valueOf(v);
    }

    private static String unescape(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                b.append(switch (n) {
                    case 'n' -> '\n';
                    case 't' -> '\t';
                    case 'r' -> '\r';
                    default -> n;
                });
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }

    /** Premier {@code :} de premier niveau suivi d'une espace ou en fin de ligne (hors guillemets). */
    private static int findColon(String s) {
        boolean inD = false;
        boolean inS = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' && !inS) {
                inD = !inD;
            } else if (c == '\'' && !inD) {
                inS = !inS;
            } else if (c == ':' && !inD && !inS && (i + 1 == s.length() || s.charAt(i + 1) == ' ')) {
                return i;
            }
        }
        return -1;
    }

    private static String stripComment(String s) {
        boolean inD = false;
        boolean inS = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' && !inS) {
                inD = !inD;
            } else if (c == '\'' && !inD) {
                inS = !inS;
            } else if (c == '#' && !inD && !inS && (i == 0 || s.charAt(i - 1) == ' ' || s.charAt(i - 1) == '\t')) {
                return s.substring(0, i);
            }
        }
        return s;
    }
}
