package com.lodygames.rpgquest.panel.docs;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rendu Markdown -&gt; HTML <strong>sûr</strong> pour le centre de documentation du Control Panel
 * (issue #49). Sous-ensemble volontairement restreint, suffisant pour des fiches opérationnelles :
 * titres, paragraphes, listes (2 niveaux), tableaux simples, {@code > } citations + callouts
 * {@code > [!NOTE]}/{@code > [!WARNING]}, blocs de code {@code ```} (avec bouton « copier »),
 * {@code ---} règle horizontale, et l'inline {@code `code`} / {@code **gras**} / {@code *italique*}
 * / {@code [texte](lien)}.
 *
 * <p><strong>Sécurité</strong> : tout le texte source est échappé HTML avant traitement — aucune
 * balise HTML brute du Markdown n'atteint jamais la sortie, aucun {@code <script>}, aucun
 * gestionnaire d'événement, aucune URL {@code javascript:} (les liens ne sont rendus que s'ils
 * pointent vers {@code /docs/…}, une ancre {@code #…}, ou {@code https://…}). La sortie est du HTML
 * déjà sûr, à insérer telle quelle.</p>
 */
public final class Markdown {

    private Markdown() {
    }

    private static final Pattern INLINE_CODE = Pattern.compile("`([^`]+)`");
    private static final Pattern BOLD = Pattern.compile("\\*\\*([^*]+)\\*\\*");
    private static final Pattern ITALIC = Pattern.compile("(?<![*\\w])\\*([^*\\n]+)\\*(?![*\\w])");
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]+)\\]\\(([^)\\s]+)\\)");
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*)$");
    /** Jeton (SOH) entourant l'index d'un span de code inline mis de côté — jamais présent dans du Markdown réel. */
    private static final Pattern CODE_TOKEN = Pattern.compile("\u0001CODE(\\d+)\u0001");

    /** Rend {@code markdown} en HTML sûr. */
    public static String render(String markdown) {
        String src = markdown == null ? "" : markdown.replace("\r\n", "\n").replace('\r', '\n')
                .replace('\u0001', ' ');
        List<String> lines = new ArrayList<>(List.of(src.split("\n", -1)));
        StringBuilder out = new StringBuilder();

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);

            // --- bloc de code ``` ---
            if (line.stripLeading().startsWith("```")) {
                List<String> code = new ArrayList<>();
                int j = i + 1;
                for (; j < lines.size(); j++) {
                    if (lines.get(j).stripLeading().startsWith("```")) {
                        break;
                    }
                    code.add(lines.get(j));
                }
                i = j; // saute la clôture
                renderCodeBlock(out, code);
                continue;
            }

            // --- règle horizontale ---
            if (line.strip().equals("---") || line.strip().equals("***")) {
                out.append("<hr>");
                continue;
            }

            // --- titre ---
            Matcher h = HEADING.matcher(line);
            if (h.matches()) {
                int level = Math.min(6, h.group(1).length());
                String text = inline(h.group(2).strip());
                String id = slug(stripTags(text));
                out.append("<h").append(level).append(" id=\"").append(id).append("\">")
                        .append(text).append("</h").append(level).append('>');
                continue;
            }

            // --- tableau ---
            if (isTableRow(line) && i + 1 < lines.size() && isTableDivider(lines.get(i + 1))) {
                int j = i + 2;
                List<String> rows = new ArrayList<>();
                for (; j < lines.size() && isTableRow(lines.get(j)); j++) {
                    rows.add(lines.get(j));
                }
                renderTable(out, line, rows);
                i = j - 1;
                continue;
            }

            // --- citation / callout ---
            if (line.stripLeading().startsWith(">")) {
                int j = i;
                List<String> quote = new ArrayList<>();
                for (; j < lines.size() && lines.get(j).stripLeading().startsWith(">"); j++) {
                    quote.add(lines.get(j).stripLeading().substring(1).stripLeading());
                }
                renderQuote(out, quote);
                i = j - 1;
                continue;
            }

            // --- liste ---
            if (isListItem(line)) {
                int j = i;
                List<String> items = new ArrayList<>();
                for (; j < lines.size() && (isListItem(lines.get(j)) || isListContinuation(lines.get(j))); j++) {
                    items.add(lines.get(j));
                }
                renderList(out, items);
                i = j - 1;
                continue;
            }

            // --- ligne vide ---
            if (line.strip().isEmpty()) {
                continue;
            }

            // --- paragraphe ---
            StringBuilder para = new StringBuilder(line.strip());
            int j = i + 1;
            for (; j < lines.size(); j++) {
                String next = lines.get(j);
                if (next.strip().isEmpty() || isBlockStart(next)) {
                    break;
                }
                para.append(' ').append(next.strip());
            }
            i = j - 1;
            out.append("<p>").append(inline(para.toString())).append("</p>");
        }
        return out.toString();
    }

    // ---- blocs -----------------------------------------------------------------------------

    private static void renderCodeBlock(StringBuilder out, List<String> code) {
        String joined = String.join("\n", code).stripTrailing();
        out.append("<div class=\"doc-cmd\">")
                .append("<button type=\"button\" class=\"doc-copy\" data-copy=\"").append(attr(joined))
                .append("\" title=\"Copier\"><svg class=\"ic\" aria-hidden=\"true\"><use href=\"#i-copy\"></use></svg>Copier</button>")
                .append("<pre><code>").append(esc(joined)).append("</code></pre></div>");
    }

    private static void renderTable(StringBuilder out, String headerLine, List<String> rows) {
        out.append("<div class=\"doc-tablewrap\"><table><thead><tr>");
        for (String cell : splitRow(headerLine)) {
            out.append("<th>").append(inline(cell)).append("</th>");
        }
        out.append("</tr></thead><tbody>");
        for (String row : rows) {
            out.append("<tr>");
            for (String cell : splitRow(row)) {
                out.append("<td>").append(inline(cell)).append("</td>");
            }
            out.append("</tr>");
        }
        out.append("</tbody></table></div>");
    }

    private static void renderQuote(StringBuilder out, List<String> quote) {
        List<String> lines = new ArrayList<>(quote);
        String first = lines.isEmpty() ? "" : lines.get(0);
        String upper = first.toUpperCase(Locale.ROOT);
        String cls = "doc-note";
        String label = null;
        if (upper.startsWith("[!WARNING]") || upper.startsWith("[!CAUTION]")) {
            cls = "doc-warn";
            label = "Attention";
            lines.set(0, first.replaceFirst("(?i)^\\[![a-z]+\\]", "").strip());
        } else if (upper.startsWith("[!NOTE]") || upper.startsWith("[!INFO]") || upper.startsWith("[!TIP]")) {
            cls = "doc-note";
            label = "À savoir";
            lines.set(0, first.replaceFirst("(?i)^\\[![a-z]+\\]", "").strip());
        }
        out.append("<div class=\"doc-callout ").append(cls).append("\">");
        if (label != null) {
            out.append("<span class=\"doc-callout-h\">").append(esc(label)).append("</span>");
        }
        out.append("<p>").append(inline(String.join(" ", lines).strip())).append("</p></div>");
    }

    private static void renderList(StringBuilder out, List<String> items) {
        boolean ordered = items.get(0).stripLeading().matches("^\\d+[.)]\\s+.*");
        out.append(ordered ? "<ol>" : "<ul>");
        StringBuilder current = null;
        List<String> nested = new ArrayList<>();
        for (String raw : items) {
            if (isNestedListItem(raw)) {
                nested.add(raw.stripLeading().replaceFirst("^[-*+]\\s+", "").replaceFirst("^\\d+[.)]\\s+", ""));
                continue;
            }
            if (isListContinuation(raw)) {
                if (current != null) {
                    current.append(' ').append(raw.strip());
                }
                continue;
            }
            flushItem(out, current, nested);
            current = new StringBuilder(raw.stripLeading()
                    .replaceFirst("^[-*+]\\s+", "").replaceFirst("^\\d+[.)]\\s+", ""));
            nested = new ArrayList<>();
        }
        flushItem(out, current, nested);
        out.append(ordered ? "</ol>" : "</ul>");
    }

    private static void flushItem(StringBuilder out, StringBuilder current, List<String> nested) {
        if (current == null) {
            return;
        }
        out.append("<li>").append(inline(current.toString().strip()));
        if (!nested.isEmpty()) {
            out.append("<ul>");
            for (String n : nested) {
                out.append("<li>").append(inline(n.strip())).append("</li>");
            }
            out.append("</ul>");
        }
        out.append("</li>");
    }

    // ---- inline ----------------------------------------------------------------------------

    /** Transformations inline sur une ligne logique déjà assemblée. */
    static String inline(String text) {
        // 1) code inline mis de côté : son contenu ne doit ni être re-parsé (gras/liens) ni
        //    ré-échappé. Jeton NUL, absent de tout Markdown réel.
        List<String> codeSpans = new ArrayList<>();
        Matcher cm = INLINE_CODE.matcher(text);
        StringBuilder tmp = new StringBuilder();
        while (cm.find()) {
            codeSpans.add(cm.group(1));
            cm.appendReplacement(tmp,
                    Matcher.quoteReplacement("\u0001CODE" + (codeSpans.size() - 1) + "\u0001"));
        }
        cm.appendTail(tmp);

        String escaped = esc(tmp.toString());
        escaped = applyLinks(escaped);
        escaped = BOLD.matcher(escaped).replaceAll("<strong>$1</strong>");
        escaped = ITALIC.matcher(escaped).replaceAll("<em>$1</em>");

        StringBuilder result = new StringBuilder();
        Matcher token = CODE_TOKEN.matcher(escaped);
        while (token.find()) {
            int idx = Integer.parseInt(token.group(1));
            token.appendReplacement(result,
                    Matcher.quoteReplacement("<code>" + esc(codeSpans.get(idx)) + "</code>"));
        }
        token.appendTail(result);
        return result.toString();
    }

    private static String applyLinks(String escapedText) {
        Matcher m = LINK.matcher(escapedText);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String label = m.group(1);
            String safeHref = safeHref(m.group(2));
            String replacement = safeHref == null
                    ? label // lien non sûr : on garde seulement le libellé, jamais l'URL
                    : "<a href=\"" + safeHref + "\">" + label + "</a>";
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** Autorise uniquement {@code /docs/…}, {@code /docs?…}, {@code /docs}, une ancre {@code #…} ou {@code https://…}. */
    private static String safeHref(String rawEscaped) {
        String href = rawEscaped.trim();
        String lower = href.toLowerCase(Locale.ROOT);
        if (href.equals("/docs") || href.startsWith("/docs/") || href.startsWith("/docs?")
                || href.startsWith("#")) {
            return href;
        }
        if (lower.startsWith("https://") && !href.contains("\"") && !href.contains("&quot;")
                && !href.contains(" ")) {
            return href;
        }
        return null;
    }

    // ---- helpers -------------------------------------------------------------------------

    private static boolean isBlockStart(String line) {
        String s = line.stripLeading();
        return s.startsWith("#") || s.startsWith("```") || s.startsWith(">") || isListItem(line)
                || s.equals("---") || s.equals("***") || isTableRow(line);
    }

    private static boolean isListItem(String line) {
        String s = line.stripLeading();
        return s.matches("^[-*+]\\s+.*") || s.matches("^\\d+[.)]\\s+.*");
    }

    private static boolean isNestedListItem(String line) {
        int indent = line.length() - line.stripLeading().length();
        return indent >= 2 && isListItem(line);
    }

    private static boolean isListContinuation(String line) {
        int indent = line.length() - line.stripLeading().length();
        return indent >= 2 && !line.strip().isEmpty() && !isListItem(line);
    }

    private static boolean isTableRow(String line) {
        String s = line.strip();
        return s.startsWith("|") && s.endsWith("|") && s.length() > 2;
    }

    private static boolean isTableDivider(String line) {
        String s = line.strip();
        return s.startsWith("|") && s.matches("\\|[\\s:|-]+\\|");
    }

    private static List<String> splitRow(String line) {
        String s = line.strip();
        s = s.substring(1, s.length() - 1);
        List<String> cells = new ArrayList<>();
        for (String cell : s.split("\\|", -1)) {
            cells.add(cell.strip());
        }
        return cells;
    }

    public static String slug(String text) {
        String s = text.toLowerCase(Locale.ROOT)
                .replaceAll("&[a-z0-9#]+;", "")
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
        return s.isEmpty() ? "section" : s;
    }

    private static String stripTags(String html) {
        return html.replaceAll("<[^>]+>", "");
    }

    static String esc(String raw) {
        return raw.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    /** Échappement pour valeur d'attribut HTML (entre guillemets doubles), retours ligne inclus. */
    private static String attr(String raw) {
        return esc(raw).replace("\n", "&#10;").replace("\t", "&#9;");
    }
}
