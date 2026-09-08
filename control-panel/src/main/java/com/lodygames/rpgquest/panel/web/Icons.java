package com.lodygames.rpgquest.panel.web;

import java.util.Map;

/**
 * Iconographie du Control Panel (issue #92) — jeu d'icônes SVG <strong>local</strong>, trait
 * uniforme (24×24, {@code stroke=currentColor}, sans remplissage), embarqué une seule fois par
 * page comme sprite {@code <symbol>} et référencé par {@code <svg><use href="#i-…"></svg>}. Aucune
 * dépendance, aucun CDN, aucune police d'icônes — cohérent avec la CSP {@code default-src 'self'}.
 * Les emojis ne sont jamais utilisés comme système principal.
 */
public final class Icons {

    private Icons() {
    }

    /** Chemins {@code <path d="…">} par nom d'icône (le {@code viewBox} est 0 0 24 24). */
    private static final Map<String, String> PATHS = Map.ofEntries(
            Map.entry("dashboard", "<path d='M4 13h7V4H4v9Zm0 7h7v-5H4v5Zm9 0h7V11h-7v9Zm0-16v5h7V4h-7Z'/>"),
            Map.entry("agents", "<path d='M12 2a5 5 0 0 0-5 5c0 2 1 3 1 3H8a4 4 0 0 0-4 4v3h16v-3a4 4 0 0 0-4-4h-1s1-1 1-3a5 5 0 0 0-5-5Z'/><path d='M9 22h6'/>"),
            Map.entry("players", "<circle cx='9' cy='8' r='3.2'/><path d='M3.5 20c0-3.3 2.6-5.5 5.5-5.5s5.5 2.2 5.5 5.5'/><path d='M16 4.5a3 3 0 0 1 0 6M18 20c0-2.7-1.2-4.6-3-5.4'/>"),
            Map.entry("npc", "<circle cx='12' cy='7' r='3.4'/><path d='M5.5 20c0-3.6 2.9-6 6.5-6s6.5 2.4 6.5 6'/><path d='M12 14v6'/>"),
            Map.entry("quests", "<path d='M6 3h9l4 4v14H6z'/><path d='M15 3v4h4'/><path d='M9 12h7M9 16h7M9 8h3'/>"),
            Map.entry("stories", "<path d='M4 5h11a3 3 0 0 1 3 3v11'/><path d='M4 5v13a2 2 0 0 0 2 2h9'/><path d='M8 9h7M8 13h5'/>"),
            Map.entry("dialogues", "<path d='M4 5h16v10H9l-5 4z'/><path d='M8 9h8M8 12h5'/>"),
            Map.entry("docs", "<path d='M6 3h8l4 4v14H6z'/><path d='M14 3v4h4'/><path d='M9 13h6M9 17h6'/>"),
            Map.entry("diagnostics", "<path d='M3 12h4l2 5 4-12 2 7h6'/>"),
            Map.entry("admin", "<circle cx='12' cy='12' r='3'/><path d='M12 3v3M12 18v3M3 12h3M18 12h3M5.6 5.6l2.1 2.1M16.3 16.3l2.1 2.1M18.4 5.6l-2.1 2.1M7.7 16.3l-2.1 2.1'/>"),
            Map.entry("dev", "<path d='m8 8-4 4 4 4M16 8l4 4-4 4M13.5 5l-3 14'/>"),
            Map.entry("server", "<rect x='3.5' y='4' width='17' height='7' rx='1.5'/><rect x='3.5' y='13' width='17' height='7' rx='1.5'/><path d='M7 7.5h.01M7 16.5h.01'/>"),
            Map.entry("online", "<circle cx='12' cy='12' r='9'/><path d='m8.5 12 2.5 2.5 4.5-5'/>"),
            Map.entry("offline", "<circle cx='12' cy='12' r='9'/><path d='m9 9 6 6M15 9l-6 6'/>"),
            Map.entry("world", "<circle cx='12' cy='12' r='9'/><path d='M3 12h18M12 3c3 3 3 15 0 18M12 3c-3 3-3 15 0 18'/>"),
            Map.entry("uptime", "<circle cx='12' cy='12' r='8.5'/><path d='M12 7v5l3.5 2'/>"),
            Map.entry("version", "<path d='M4 7h16M4 12h16M4 17h10'/>"),
            Map.entry("search", "<circle cx='11' cy='11' r='6.5'/><path d='m20 20-4.2-4.2'/>"),
            Map.entry("filter", "<path d='M4 5h16l-6 8v6l-4-2v-4z'/>"),
            Map.entry("edit", "<path d='M4 20h4L19 9l-4-4L4 16z'/><path d='M14 6l4 4'/>"),
            Map.entry("plus", "<path d='M12 5v14M5 12h14'/>"),
            Map.entry("trash", "<path d='M4 7h16M9 7V4h6v3M6 7l1 13h10l1-13'/>"),
            Map.entry("warning", "<path d='M12 3 2 20h20z'/><path d='M12 9v5M12 17h.01'/>"),
            Map.entry("error", "<circle cx='12' cy='12' r='9'/><path d='M12 7v6M12 16h.01'/>"),
            Map.entry("info", "<circle cx='12' cy='12' r='9'/><path d='M12 11v6M12 8h.01'/>"),
            Map.entry("check", "<path d='m4 12 5 5L20 6'/>"),
            Map.entry("copy", "<rect x='9' y='9' width='11' height='11' rx='2'/><path d='M5 15V5a2 2 0 0 1 2-2h8'/>"),
            Map.entry("save", "<path d='M5 4h11l3 3v13H5z'/><path d='M8 4v5h7V4M8 20v-6h8v6'/>"),
            Map.entry("open", "<path d='M9 6h9v9M18 6 6 18'/>"),
            Map.entry("back", "<path d='M14 6l-6 6 6 6M8 12h11'/>"),
            Map.entry("chevron", "<path d='m9 6 6 6-6 6'/>"),
            Map.entry("up", "<path d='m6 15 6-6 6 6'/>"),
            Map.entry("down", "<path d='m6 9 6 6 6-6'/>"),
            Map.entry("menu", "<path d='M4 7h16M4 12h16M4 17h16'/>"),
            Map.entry("close", "<path d='M6 6l12 12M18 6 6 18'/>"),
            Map.entry("link", "<path d='M9 15 15 9M8 12l-2 2a3.5 3.5 0 0 0 5 5l2-2M16 12l2-2a3.5 3.5 0 0 0-5-5l-2 2'/>"),
            Map.entry("book", "<path d='M5 4h12a2 2 0 0 1 2 2v14H7a2 2 0 0 1-2-2z'/><path d='M9 4v12'/>"),
            Map.entry("target", "<circle cx='12' cy='12' r='8.5'/><circle cx='12' cy='12' r='4'/><circle cx='12' cy='12' r='.6'/>"),
            Map.entry("gift", "<rect x='4' y='9' width='16' height='11' rx='1.5'/><path d='M4 13h16M12 9v11M8.5 9C6 9 6 5 9 5c2 0 3 4 3 4M15.5 9C18 9 18 5 15 5c-2 0-3 4-3 4'/>"),
            Map.entry("lock", "<rect x='5' y='11' width='14' height='9' rx='1.5'/><path d='M8 11V8a4 4 0 0 1 8 0v3'/>"),
            Map.entry("clock", "<circle cx='12' cy='12' r='8.5'/><path d='M12 7v5l3 2'/>"));

    /** {@code <svg class="ic">…</svg>} référençant le symbole {@code #i-<name>}. Repli discret sur « info ». */
    public static String icon(String name) {
        return icon(name, "ic");
    }

    public static String icon(String name, String cssClass) {
        String id = PATHS.containsKey(name) ? name : "info";
        return "<svg class=\"" + cssClass + "\" aria-hidden=\"true\"><use href=\"#i-" + id + "\"></use></svg>";
    }

    /** Sprite complet à insérer une fois par page (dans {@code <body>}), invisible. */
    public static String sprite() {
        StringBuilder sb = new StringBuilder(
                "<svg width=\"0\" height=\"0\" style=\"position:absolute\" aria-hidden=\"true\"><defs>");
        for (Map.Entry<String, String> e : PATHS.entrySet()) {
            sb.append("<symbol id=\"i-").append(e.getKey())
                    .append("\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.9\" ")
                    .append("stroke-linecap=\"round\" stroke-linejoin=\"round\">")
                    .append(e.getValue()).append("</symbol>");
        }
        return sb.append("</defs></svg>").toString();
    }
}
