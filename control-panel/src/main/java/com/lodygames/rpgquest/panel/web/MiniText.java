package com.lodygames.rpgquest.panel.web;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rendu web des textes RPGQuest qui arrivent au format <strong>MiniMessage</strong>
 * (ex. {@code <gold>La chasse aux cristaux</gold>}, {@code <red>[TEST]</red> Histoire de test}).
 *
 * <p>Ce n'est <em>pas</em> un parseur MiniMessage complet : seul un sous-ensemble sûr est
 * interprété (couleurs nommées / hex, gras / italique / souligné / barré). Tout le reste
 * (gradients, hover, click, polices, {@code <lang>}…) est <strong>retiré proprement</strong> —
 * jamais affiché comme balise brute. Le texte réel est toujours échappé HTML : la sortie de
 * {@link #html(String)} est du HTML sûr et doit être insérée telle quelle (pas de re-échappement).</p>
 *
 * <p>{@link #prettifyTokens(String)} rend lisibles les identifiants Minecraft en capitales
 * ({@code AMETHYST_SHARD} → « Amethyst Shard ») trouvés au fil d'un texte ; {@link #prettifyId(String)}
 * fait de même pour un identifiant isolé ({@code rpgquest:crystal_hunt} → « Crystal Hunt »). Aucune
 * table de traduction : simple normalisation typographique.</p>
 */
public final class MiniText {

    private MiniText() {
    }

    /** Couleurs nommées MiniMessage → hex (palette chat Minecraft). */
    private static final Map<String, String> NAMED_COLORS = Map.ofEntries(
            Map.entry("black", "#3b3b3b"), // relevé : noir pur illisible sur fond sombre
            Map.entry("dark_blue", "#3b5bd6"),
            Map.entry("dark_green", "#2f9e44"),
            Map.entry("dark_aqua", "#22a5a5"),
            Map.entry("dark_red", "#c0392b"),
            Map.entry("dark_purple", "#9b59b6"),
            Map.entry("gold", "#d4a017"),
            Map.entry("gray", "#aab1bd"),
            Map.entry("grey", "#aab1bd"),
            Map.entry("dark_gray", "#8a929e"),
            Map.entry("dark_grey", "#8a929e"),
            Map.entry("blue", "#5b8dff"),
            Map.entry("green", "#43c463"),
            Map.entry("aqua", "#4bd6d6"),
            Map.entry("red", "#f06663"),
            Map.entry("light_purple", "#e06bd6"),
            Map.entry("yellow", "#e3c33b"),
            Map.entry("white", "#e6e8ec"));

    /**
     * Hex d'aperçu d'une couleur MiniMessage nommée, ou {@code null} si inconnue. Sert à la palette
     * de couleurs du formulaire de dialogue (issue #118) : l'aperçu du panel utilise exactement les
     * mêmes teintes que le rendu des textes déjà chargés.
     */
    public static String colorHex(String name) {
        return name == null ? null : NAMED_COLORS.get(name.toLowerCase(java.util.Locale.ROOT));
    }

    private static final Map<String, String> DECORATIONS = Map.ofEntries(
            Map.entry("bold", "b"), Map.entry("b", "b"),
            Map.entry("italic", "i"), Map.entry("i", "i"), Map.entry("em", "i"),
            Map.entry("underlined", "u"), Map.entry("u", "u"),
            Map.entry("strikethrough", "s"), Map.entry("st", "s"), Map.entry("s", "s"));

    private static final Pattern TAG = Pattern.compile("<(/?)([a-zA-Z0-9_#:]+)(?::([^>]*))?>");
    private static final Pattern HEX = Pattern.compile("#?[0-9a-fA-F]{6}");
    private static final Pattern SCREAMING = Pattern.compile("\\b[A-Z][A-Z0-9]{2,}(?:_[A-Z0-9]+)*\\b");
    private static final Set<String> KEEP_UPPER = Set.of("XP", "HP", "TNT", "NPC", "PNJ", "UUID", "ID", "RPG", "URL", "API");

    // ---------------------------------------------------------------------------------------

    /** Version texte pur : toutes les balises retirées, espaces normalisés. Non échappé. */
    public static String plain(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        return TAG.matcher(raw).replaceAll("").replaceAll("\\s+", " ").trim();
    }

    /**
     * Version HTML sûre : le texte est échappé, un sous-ensemble de MiniMessage est traduit en
     * {@code <span style="color:…">} / {@code <b>} / {@code <i>} / {@code <u>} / {@code <s>}, le
     * reste est supprimé. Les balises jamais fermées le sont en fin de chaîne.
     */
    public static String html(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        Deque<String> open = new ArrayDeque<>(); // "color" | "b" | "i" | "u" | "s"
        Matcher m = TAG.matcher(raw);
        int pos = 0;
        while (m.find()) {
            appendEscaped(out, raw.substring(pos, m.start()));
            pos = m.end();
            boolean closing = !m.group(1).isEmpty();
            String name = m.group(2).toLowerCase(java.util.Locale.ROOT);
            String arg = m.group(3);

            if (closing || name.equals("reset") || name.equals("r")) {
                closeUntil(out, open, closing ? tagKind(name, arg) : null);
                continue;
            }
            String color = resolveColor(name, arg);
            if (color != null) {
                open.push("color");
                out.append("<span style=\"color:").append(color).append("\">");
            } else if (DECORATIONS.containsKey(name)) {
                String d = DECORATIONS.get(name);
                open.push(d);
                out.append('<').append(htmlTag(d)).append('>');
            }
            // tag inconnu (gradient, hover, font, lang…) : ignoré silencieusement
        }
        appendEscaped(out, raw.substring(pos));
        while (!open.isEmpty()) {
            emitClose(out, open.pop());
        }
        return out.toString();
    }

    /** Sigle court à laisser tel quel (jamais « prettifié » ni traduit) : {@code XP}, {@code HP}… */
    public static boolean keepUpper(String token) {
        return token != null && KEEP_UPPER.contains(token);
    }

    /** {@code AMETHYST_SHARD} et autres jetons en capitales d'un texte deviennent « Amethyst Shard ». */
    public static String prettifyTokens(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        Matcher m = SCREAMING.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String token = m.group();
            m.appendReplacement(sb, Matcher.quoteReplacement(
                    KEEP_UPPER.contains(token) ? token : prettifyId(token)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** {@code rpgquest:crystal_hunt} / {@code hunt_spiders} → « Crystal Hunt » / « Hunt Spiders ». */
    public static String prettifyId(String id) {
        if (id == null || id.isBlank()) {
            return "";
        }
        String key = id.contains(":") ? id.substring(id.lastIndexOf(':') + 1) : id;
        String[] parts = key.replace('-', '_').split("_");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(p.charAt(0)))
              .append(p.substring(1).toLowerCase(java.util.Locale.ROOT));
        }
        return sb.isEmpty() ? key : sb.toString();
    }

    // ---- interne -------------------------------------------------------------------------

    private static String resolveColor(String name, String arg) {
        if (NAMED_COLORS.containsKey(name)) {
            return NAMED_COLORS.get(name);
        }
        if (name.equals("color") || name.equals("colour") || name.equals("c")) {
            if (arg != null && NAMED_COLORS.containsKey(arg.toLowerCase(java.util.Locale.ROOT))) {
                return NAMED_COLORS.get(arg.toLowerCase(java.util.Locale.ROOT));
            }
            if (arg != null && HEX.matcher(arg).matches()) {
                return "#" + arg.replace("#", "");
            }
            return null;
        }
        if (name.startsWith("#") && HEX.matcher(name).matches()) {
            return "#" + name.substring(1);
        }
        return null;
    }

    /** Quelle sorte de wrapper une balise ouvre/ferme : "color", "b", "i", "u", "s" ou null. */
    private static String tagKind(String name, String arg) {
        if (NAMED_COLORS.containsKey(name) || name.equals("color") || name.equals("colour")
                || name.equals("c") || (name.startsWith("#") && HEX.matcher(name).matches())) {
            return "color";
        }
        return DECORATIONS.get(name);
    }

    private static void closeUntil(StringBuilder out, Deque<String> open, String kind) {
        if (kind == null) { // <reset>
            while (!open.isEmpty()) {
                emitClose(out, open.pop());
            }
            return;
        }
        if (!open.contains(kind)) {
            return; // fermeture orpheline : on ignore
        }
        String popped;
        do {
            popped = open.pop();
            emitClose(out, popped);
        } while (!popped.equals(kind) && !open.isEmpty());
    }

    private static void emitClose(StringBuilder out, String kind) {
        out.append("color".equals(kind) ? "</span>" : "</" + htmlTag(kind) + ">");
    }

    private static String htmlTag(String decoration) {
        return switch (decoration) {
            case "b" -> "b";
            case "i" -> "i";
            case "u" -> "u";
            case "s" -> "s";
            default -> "span";
        };
    }

    private static void appendEscaped(StringBuilder out, String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
    }
}
