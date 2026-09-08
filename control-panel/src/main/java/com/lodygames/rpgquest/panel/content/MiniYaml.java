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
 * {@code true}/{@code false}/{@code null}, commentaires {@code #} hors chaînes.
 *
 * <p>Ce n'est <strong>pas</strong> un parseur YAML conforme : pas d'ancres, pas de scalaires
 * repliés, pas de flow-maps {@code {a: 1}}. Un fichier écrit ailleurs avec ces constructions se
 * relira mal — l'éditeur le détecte via la comparaison round-trip et refuse d'écraser à l'aveugle.</p>
 */
final class MiniYaml {

    private MiniYaml() {
    }

    private record Line(int indent, String text) {
    }

    static Object parse(String yaml) {
        List<Line> lines = new ArrayList<>();
        String norm = (yaml == null ? "" : yaml).replace("\r\n", "\n").replace('\r', '\n');
        for (String raw : norm.split("\n", -1)) {
            String noComment = stripComment(raw);
            if (noComment.strip().isEmpty()) {
                continue;
            }
            int indent = 0;
            while (indent < noComment.length() && noComment.charAt(indent) == ' ') {
                indent++;
            }
            lines.add(new Line(indent, noComment.substring(indent)));
        }
        if (lines.isEmpty()) {
            return new LinkedHashMap<String, Object>();
        }
        int[] idx = {0};
        return parseBlock(lines, idx, lines.get(0).indent());
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
        return s;
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
