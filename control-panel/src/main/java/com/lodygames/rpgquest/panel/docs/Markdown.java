package com.lodygames.rpgquest.panel.docs;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rendu Markdown -&gt; HTML <strong>sûr</strong> pour le centre de documentation du Control Panel
 * (issue #49, moteur revu par #780). Sous-ensemble volontairement restreint, suffisant pour des
 * fiches opérationnelles : titres, paragraphes, listes imbriquées (profondeur libre, éléments sur
 * plusieurs lignes, paragraphes de suite), tableaux simples, {@code > } citations + callouts
 * {@code > [!NOTE]}/{@code > [!WARNING]} (leur contenu est rendu comme le reste : listes comprises),
 * blocs de code {@code ```} (avec bouton « copier »), {@code ---} règle horizontale, et l'inline
 * {@code `code`} / {@code **gras**} / {@code *italique*} / {@code [texte](lien)}.
 *
 * <p><strong>Listes (#780)</strong> : un élément est rattaché à sa liste par la colonne de sa puce ;
 * une ligne de suite appartient à l'élément le plus profond dont la puce est à gauche de la ligne
 * (ou, sans indentation, à l'élément qu'elle prolonge directement). Une phrase coupée sur plusieurs
 * lignes, liens compris, reste donc dans le bon élément, à n'importe quel niveau.</p>
 *
 * <p><strong>Sécurité</strong> : tout le texte source est échappé HTML avant traitement — aucune
 * balise HTML brute du Markdown n'atteint jamais la sortie, aucun {@code <script>}, aucun
 * gestionnaire d'événement, aucune URL {@code javascript:} (les liens ne sont rendus que s'ils
 * pointent vers {@code /docs/…}, une autre fiche par son identifiant ({@code fiche} ou
 * {@code fiche#section}, réécrit en {@code /docs/fiche#section}), une ancre {@code #…}, ou
 * {@code https://…}). La sortie est du HTML déjà sûr, à insérer telle quelle.</p>
 *
 * <p>{@link #lint} signale ce que ce moteur ne sait pas rendre proprement (tests de la
 * bibliothèque : une fiche non rendable est refusée).</p>
 */
public final class Markdown {

    private Markdown() {
    }

    /** Un titre rendu : niveau, texte brut (non échappé) et ancre {@code id} réellement émise. */
    public record Heading(int level, String text, String id) {
    }

    private static final Pattern INLINE_CODE = Pattern.compile("`([^`]+)`");
    private static final Pattern BOLD = Pattern.compile("\\*\\*([^*]+)\\*\\*");
    private static final Pattern ITALIC = Pattern.compile("(?<![*\\w])\\*([^*\\n]+)\\*(?![*\\w])");
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]+)\\]\\(([^)\\s]+)\\)");
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*)$");
    private static final Pattern BULLET = Pattern.compile("^[-*+]\\s+");
    private static final Pattern NUMBER = Pattern.compile("^(\\d+)[.)]\\s+");
    /** Lien vers une autre fiche par son identifiant ({@code fiche} ou {@code fiche#section}). */
    private static final Pattern DOC_REF = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*(?:#[a-z0-9-]+)?");
    private static final Pattern RAW_TAG = Pattern.compile(
            "</?(?:a|b|br|i|u|p|em|strong|span|div|img|script|style|iframe|table|tr|td|th|ul|ol|li|code|pre|kbd"
                    + "|h[1-6]|sup|sub|small|font|center|hr)(?:\\s[^>]*)?/?>", Pattern.CASE_INSENSITIVE);
    /** Jeton (SOH) entourant l'index d'un span de code inline mis de côté — jamais présent dans du Markdown réel. */
    private static final Pattern CODE_TOKEN = Pattern.compile("\u0001CODE(\\d+)\u0001");
    /** Profondeur de liste au-delà de laquelle une fiche devient illisible (règle du gabarit, #780). */
    public static final int MAX_LIST_DEPTH = 3;

    /** Résultat d'un rendu : HTML + titres réellement émis (mêmes ancres, dans l'ordre). */
    private record Rendered(String html, List<Heading> headings) {
    }

    /** Rend {@code markdown} en HTML sûr. */
    public static String render(String markdown) {
        return parse(markdown).html();
    }

    /**
     * Titres de la fiche avec leurs ancres <strong>telles que rendues</strong> (dédoublonnées :
     * un second titre identique reçoit {@code -2}, le premier garde son ancre). Source unique du
     * sommaire et des liens d'aide : un sommaire ne peut plus pointer vers une ancre absente.
     */
    public static List<Heading> headings(String markdown) {
        return parse(markdown).headings();
    }

    private static Rendered parse(String markdown) {
        String src = markdown == null ? "" : markdown.replace("\r\n", "\n").replace('\r', '\n')
                .replace('\u0001', ' ');
        src = stripLeadingH1(src);
        List<String> lines = List.of(src.split("\n", -1));
        List<Heading> headings = new ArrayList<>();
        StringBuilder out = new StringBuilder();
        renderBlocks(out, lines, headings, new HashSet<>());
        return new Rendered(out.toString(), List.copyOf(headings));
    }

    private static void renderBlocks(StringBuilder out, List<String> lines, List<Heading> headings,
                                     Set<String> usedIds) {
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
                String id = uniqueId(slug(stripTags(text)), usedIds);
                headings.add(new Heading(level, unescape(stripTags(text)), id));
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
                    String q = lines.get(j).stripLeading().substring(1);
                    quote.add(q.startsWith(" ") ? q.substring(1) : q);
                }
                renderQuote(out, quote, headings, usedIds);
                i = j - 1;
                continue;
            }

            // --- liste ---
            if (isListItem(line)) {
                int j = listEnd(lines, i);
                renderList(out, lines.subList(i, j));
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
    }

    /**
     * Fin (exclue) du bloc liste commençant en {@code start} : éléments, lignes de suite indentées,
     * suites non indentées collées à un élément, et lignes vides suivies d'une suite indentée ou
     * d'un élément de même nature (liste « aérée »).
     */
    static int listEnd(List<String> lines, int start) {
        boolean ordered = isOrdered(lines.get(start));
        int j = start + 1;
        while (j < lines.size()) {
            String l = lines.get(j);
            if (isListItem(l) || (indent(l) >= 2 && !l.isBlank())) {
                j++;
                continue;
            }
            if (l.isBlank()) {
                int k = j;
                while (k < lines.size() && lines.get(k).isBlank()) {
                    k++;
                }
                if (k < lines.size() && (indent(lines.get(k)) >= 2
                        || (isListItem(lines.get(k)) && isOrdered(lines.get(k)) == ordered))) {
                    j = k;
                    continue;
                }
                break;
            }
            if (isBlockStart(l)) {
                break;
            }
            j++; // suite non indentée (« paresseuse ») de l'élément précédent
        }
        return j;
    }

    /**
     * Retire un unique titre de niveau 1 ({@code # …}) en tête de document. Le centre de
     * documentation rend déjà le titre de la fiche dans un {@code <h1>} propre
     * ({@code DocsPages}) ; un {@code # …} d'ouverture dans le corps Markdown ferait double
     * emploi. Seul le PREMIER titre, et seulement s'il est le premier contenu non vide et de
     * niveau 1, est retiré — les {@code ##} / {@code ###} ne sont jamais touchés.
     */
    static String stripLeadingH1(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return markdown == null ? "" : markdown;
        }
        List<String> lines = new ArrayList<>(List.of(markdown.split("\n", -1)));
        int i = 0;
        while (i < lines.size() && lines.get(i).strip().isEmpty()) {
            i++;
        }
        if (i >= lines.size() || !lines.get(i).strip().matches("#\\s+\\S.*")) {
            return markdown; // premier contenu absent ou != titre H1 : rien à faire
        }
        lines.remove(i);
        return String.join("\n", lines).stripLeading();
    }

    // ---- blocs -----------------------------------------------------------------------------

    private static void renderCodeBlock(StringBuilder out, List<String> code) {
        String joined = String.join("\n", code).stripTrailing();
        out.append("<div class=\"doc-cmd\">")
                .append("<button type=\"button\" class=\"doc-copy\" data-copy=\"").append(attr(joined))
                .append("\" title=\"Copier dans le presse-papiers\">Copier</button>")
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

    private static void renderQuote(StringBuilder out, List<String> quote, List<Heading> headings,
                                    Set<String> usedIds) {
        List<String> lines = new ArrayList<>(quote);
        String first = lines.isEmpty() ? "" : lines.get(0).strip();
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
        renderBlocks(out, lines, headings, usedIds);
        out.append("</div>");
    }

    /** Un élément de liste : son texte d'ouverture, puis des paragraphes de suite et des sous-listes. */
    private static final class Item {
        final List<Object> parts = new ArrayList<>(); // StringBuilder (texte) ou ListNode
    }

    private static final class ListNode {
        final int indent;
        final boolean ordered;
        final int start;
        final List<Item> items = new ArrayList<>();

        ListNode(int indent, boolean ordered, int start) {
            this.indent = indent;
            this.ordered = ordered;
            this.start = start;
        }
    }

    private static void renderList(StringBuilder out, List<String> raw) {
        List<ListNode> roots = new ArrayList<>();
        Deque<ListNode> open = new ArrayDeque<>(); // pile des listes ouvertes, la plus profonde en tête
        boolean blankBefore = false;
        for (String line : raw) {
            if (line.isBlank()) {
                blankBefore = true;
                continue;
            }
            int ind = indent(line);
            if (isListItem(line)) {
                boolean ordered = isOrdered(line);
                while (open.size() > 1 && open.peek().indent > ind) {
                    open.pop();
                }
                ListNode top = open.peek();
                if (top == null || (open.size() == 1 && ind <= top.indent && ordered != top.ordered)) {
                    open.clear();
                    top = new ListNode(ind, ordered, startNumber(line));
                    roots.add(top);
                    open.push(top);
                } else if (ind > top.indent) {
                    ListNode child = new ListNode(ind, ordered, startNumber(line));
                    lastItem(top).parts.add(child);
                    open.push(child);
                    top = child;
                } else if (ordered != top.ordered) {
                    open.pop();
                    ListNode child = new ListNode(ind, ordered, startNumber(line));
                    lastItem(open.peek()).parts.add(child);
                    open.push(child);
                    top = child;
                }
                Item item = new Item();
                item.parts.add(new StringBuilder(stripMarker(line)));
                top.items.add(item);
            } else {
                // Ligne de suite : élément le plus profond dont la puce est à gauche de la ligne.
                if (ind >= 2) {
                    while (open.size() > 1 && open.peek().indent >= ind) {
                        open.pop();
                    }
                }
                Item item = lastItem(open.peek());
                Object last = item.parts.get(item.parts.size() - 1);
                if (!blankBefore && last instanceof StringBuilder text) {
                    text.append(' ').append(line.strip());
                } else {
                    item.parts.add(new StringBuilder(line.strip()));
                }
            }
            blankBefore = false;
        }
        for (ListNode root : roots) {
            renderListNode(out, root);
        }
    }

    private static void renderListNode(StringBuilder out, ListNode list) {
        out.append(list.ordered ? (list.start > 1 ? "<ol start=\"" + list.start + "\">" : "<ol>") : "<ul>");
        for (Item item : list.items) {
            out.append("<li>");
            boolean firstText = true;
            for (Object part : item.parts) {
                if (part instanceof ListNode child) {
                    renderListNode(out, child);
                } else if (firstText) {
                    out.append(inline(part.toString().strip()));
                    firstText = false;
                } else {
                    out.append("<p>").append(inline(part.toString().strip())).append("</p>");
                }
            }
            out.append("</li>");
        }
        out.append(list.ordered ? "</ol>" : "</ul>");
    }

    private static Item lastItem(ListNode list) {
        return list.items.get(list.items.size() - 1);
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

    /**
     * Autorise uniquement {@code /docs/…}, {@code /docs?…}, {@code /docs}, une ancre {@code #…},
     * l'identifiant d'une autre fiche ({@code fiche}, {@code fiche#section} → {@code /docs/…}) ou
     * {@code https://…}. Tout le reste ({@code javascript:}, chemins relatifs {@code ../}…) → {@code null}.
     */
    static String safeHref(String rawEscaped) {
        String href = rawEscaped.trim();
        String lower = href.toLowerCase(Locale.ROOT);
        if (href.equals("/docs") || href.startsWith("/docs/") || href.startsWith("/docs?")
                || href.startsWith("#")) {
            return href;
        }
        if (DOC_REF.matcher(href).matches()) {
            return "/docs/" + href;
        }
        if (lower.startsWith("https://") && !href.contains("\"") && !href.contains("&quot;")
                && !href.contains(" ")) {
            return href;
        }
        return null;
    }

    // ---- contrôle de structure (#780) ---------------------------------------------------

    /**
     * Problèmes de structure que ce moteur ne rend pas proprement — ou que le gabarit des fiches
     * refuse parce qu'ils rendent la lecture difficile. Chaque entrée : {@code ligne N : explication}
     * (numéros de ligne du Markdown fourni). Vide = fiche rendable.
     */
    public static List<String> lint(String markdown) {
        List<String> problems = new ArrayList<>();
        String src = markdown == null ? "" : markdown.replace("\r\n", "\n").replace('\r', '\n');
        List<String> lines = List.of(src.split("\n", -1));
        boolean inFence = false;
        int fenceLine = 0;
        int lastHeading = 0;
        Deque<Integer> listIndents = new ArrayDeque<>();
        boolean inList = false;
        boolean prevListText = false;
        for (int n = 0; n < lines.size(); n++) {
            String line = lines.get(n);
            String s = line.strip();
            int no = n + 1;
            if (s.startsWith("```")) {
                if (!inFence && indent(line) > 0) {
                    problems.add("ligne " + no + " : bloc de code indenté (dans une liste ?) — "
                            + "il casserait la liste ; sortez-le de la liste, sans indentation");
                }
                inFence = !inFence;
                fenceLine = no;
                inList = false;
                continue;
            }
            if (inFence) {
                continue;
            }
            if (!s.isEmpty() && line.substring(0, indent(line)).indexOf('\t') >= 0) {
                problems.add("ligne " + no + " : tabulation en début de ligne — indentez avec des espaces");
            }
            Matcher h = HEADING.matcher(line);
            if (h.matches()) {
                int level = h.group(1).length();
                if (lastHeading > 0 && level > lastHeading + 1) {
                    problems.add("ligne " + no + " : titre de niveau " + level + " juste après un niveau "
                            + lastHeading + " — un niveau a été sauté");
                }
                lastHeading = level;
                inList = false;
                continue;
            }
            if (isTableRow(line) && n + 1 < lines.size() && isTableDivider(lines.get(n + 1))) {
                int cols = splitRow(line).size();
                for (int k = n + 2; k < lines.size() && isTableRow(lines.get(k)); k++) {
                    if (splitRow(lines.get(k)).size() != cols) {
                        problems.add("ligne " + (k + 1) + " : " + splitRow(lines.get(k)).size()
                                + " cellules au lieu de " + cols + " — colonne décalée (un « | » dans le texte ?)");
                    }
                }
            }
            if (isListItem(line)) {
                int ind = indent(line);
                if (!inList) {
                    listIndents.clear();
                    if (ind > 0) {
                        problems.add("ligne " + no + " : liste qui commence indentée — retirez l'indentation");
                    }
                }
                while (!listIndents.isEmpty() && listIndents.peek() > ind) {
                    listIndents.pop();
                }
                if (listIndents.isEmpty() || listIndents.peek() < ind) {
                    if (!listIndents.isEmpty() && ind - listIndents.peek() < 2) {
                        problems.add("ligne " + no + " : sous-liste indentée d'un seul espace — "
                                + "alignez la puce sur le texte de l'élément parent");
                    }
                    listIndents.push(ind);
                }
                if (listIndents.size() > MAX_LIST_DEPTH) {
                    problems.add("ligne " + no + " : liste sur " + listIndents.size() + " niveaux (maximum "
                            + MAX_LIST_DEPTH + ") — découpez en étapes ou en sous-section");
                }
                inList = true;
                prevListText = true;
            } else if (s.isEmpty()) {
                prevListText = false;
            } else if (inList && indent(line) >= 2) {
                prevListText = true;
            } else if (inList && prevListText && !isBlockStart(line)) {
                problems.add("ligne " + no + " : suite d'un élément de liste non indentée — "
                        + "indentez-la sous le texte de l'élément");
            } else {
                inList = false;
                prevListText = false;
            }
            Matcher rawTag = RAW_TAG.matcher(INLINE_CODE.matcher(line).replaceAll(""));
            if (rawTag.find()) {
                problems.add("ligne " + no + " : balise HTML « " + rawTag.group() + " » affichée telle quelle — "
                        + "mettez-la entre `accents graves` ou retirez-la");
            }
            Matcher link = LINK.matcher(INLINE_CODE.matcher(line).replaceAll(""));
            while (link.find()) {
                if (safeHref(esc(link.group(2))) == null) {
                    problems.add("ligne " + no + " : lien « " + link.group(2) + " » non autorisé — "
                            + "il serait rendu sans lien (utilisez /docs/…, fiche#section, #section ou https://)");
                }
            }
        }
        if (inFence) {
            problems.add("ligne " + fenceLine + " : bloc de code ``` jamais refermé");
        }
        return problems;
    }

    /** Liens d'une fiche, résolus comme au rendu ({@code /docs/fiche#section} ou {@code #section}). */
    public static List<String> links(String markdown) {
        List<String> hrefs = new ArrayList<>();
        boolean inFence = false;
        for (String line : (markdown == null ? "" : markdown).split("\n", -1)) {
            if (line.strip().startsWith("```")) {
                inFence = !inFence;
                continue;
            }
            if (inFence) {
                continue;
            }
            Matcher link = LINK.matcher(INLINE_CODE.matcher(line).replaceAll(""));
            while (link.find()) {
                String safe = safeHref(esc(link.group(2)));
                if (safe != null) {
                    hrefs.add(safe);
                }
            }
        }
        return hrefs;
    }

    // ---- helpers -------------------------------------------------------------------------

    private static boolean isBlockStart(String line) {
        String s = line.stripLeading();
        return s.startsWith("#") || s.startsWith("```") || s.startsWith(">") || isListItem(line)
                || s.equals("---") || s.equals("***") || isTableRow(line);
    }

    private static boolean isListItem(String line) {
        String s = line.stripLeading();
        return BULLET.matcher(s).find() || NUMBER.matcher(s).find();
    }

    private static boolean isOrdered(String line) {
        return NUMBER.matcher(line.stripLeading()).find();
    }

    private static int startNumber(String line) {
        Matcher m = NUMBER.matcher(line.stripLeading());
        if (!m.find()) {
            return 1;
        }
        try {
            return Integer.parseInt(m.group(1));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private static String stripMarker(String line) {
        String s = line.stripLeading();
        Matcher bullet = BULLET.matcher(s);
        return bullet.find() ? s.substring(bullet.end()) : NUMBER.matcher(s).replaceFirst("");
    }

    private static int indent(String line) {
        return line.length() - line.stripLeading().length();
    }

    private static String uniqueId(String id, Set<String> used) {
        String candidate = id;
        for (int n = 2; !used.add(candidate); n++) {
            candidate = id + "-" + n;
        }
        return candidate;
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
        String folded = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        String s = folded.toLowerCase(Locale.ROOT)
                .replaceAll("&[a-z0-9#]+;", "")
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
        return s.isEmpty() ? "section" : s;
    }

    private static String stripTags(String html) {
        return html.replaceAll("<[^>]+>", "");
    }

    private static String unescape(String html) {
        return html.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&#39;", "'").replace("&amp;", "&");
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
