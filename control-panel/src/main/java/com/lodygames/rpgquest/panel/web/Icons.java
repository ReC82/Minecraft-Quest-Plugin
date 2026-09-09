package com.lodygames.rpgquest.panel.web;

import java.util.Map;

/**
 * Iconographie du Control Panel. Depuis le lot Bootstrap : <strong>Bootstrap Icons</strong>
 * (police d'icônes servie localement sous {@code /assets/bootstrap-icons/}, aucun CDN, cohérent
 * CSP {@code default-src 'self'}). {@link #icon(String)} rend un {@code <i class="bi bi-…">} —
 * la signature est inchangée pour que tous les appels existants continuent de fonctionner.
 *
 * <p>Le nom logique passé aux appelants ({@code "dashboard"}, {@code "players"}, {@code "warning"}…)
 * est traduit ici vers le nom Bootstrap Icons. Les emojis ne sont jamais utilisés comme système
 * principal.</p>
 */
public final class Icons {

    private Icons() {
    }

    /** Nom logique → nom d'icône Bootstrap Icons (sans le préfixe {@code bi-}). */
    private static final Map<String, String> BI = Map.ofEntries(
            // navigation
            Map.entry("home", "grid-1x2"),
            Map.entry("dashboard", "speedometer2"),
            Map.entry("agents", "hdd-network"),
            Map.entry("players", "people"),
            Map.entry("npc", "person-badge"),
            Map.entry("quests", "journal-check"),
            Map.entry("stories", "book"),
            Map.entry("dialogues", "chat-dots"),
            Map.entry("docs", "file-earmark-text"),
            Map.entry("diagnostics", "activity"),
            Map.entry("admin", "shield-lock"),
            Map.entry("dev", "code-slash"),
            // état serveur / infra
            Map.entry("server", "hdd-rack"),
            Map.entry("online", "broadcast"),
            Map.entry("offline", "wifi-off"),
            Map.entry("world", "globe2"),
            Map.entry("uptime", "clock-history"),
            Map.entry("version", "tag"),
            // actions
            Map.entry("search", "search"),
            Map.entry("filter", "funnel"),
            Map.entry("edit", "pencil"),
            Map.entry("plus", "plus-lg"),
            Map.entry("trash", "trash"),
            Map.entry("copy", "clipboard"),
            Map.entry("save", "floppy"),
            Map.entry("open", "box-arrow-right"),
            Map.entry("back", "arrow-left"),
            Map.entry("chevron", "chevron-right"),
            Map.entry("up", "arrow-up"),
            Map.entry("down", "arrow-down"),
            Map.entry("menu", "list"),
            Map.entry("close", "x-lg"),
            Map.entry("link", "link-45deg"),
            Map.entry("bell", "bell"),
            Map.entry("history", "clock-history"),
            Map.entry("box", "box-seam"),
            Map.entry("refresh", "arrow-clockwise"),
            Map.entry("help", "question-circle"),
            Map.entry("wrench", "wrench-adjustable"),
            // sémantique
            Map.entry("warning", "exclamation-triangle"),
            Map.entry("error", "x-circle"),
            Map.entry("info", "info-circle"),
            Map.entry("check", "check-circle"),
            Map.entry("book", "book"),
            Map.entry("target", "bullseye"),
            Map.entry("gift", "gift"),
            Map.entry("lock", "lock"),
            Map.entry("clock", "clock"));

    /** {@code <i class="bi bi-…">} avec la classe utilitaire {@code ic}. Repli discret sur « info ». */
    public static String icon(String name) {
        return icon(name, "ic");
    }

    /**
     * @param name     nom logique (voir {@link #BI})
     * @param cssClass classe supplémentaire pour le dimensionnement contextuel ({@code nav-ic},
     *                 {@code ic}…) — conservée pour compatibilité avec le CSS existant
     */
    public static String icon(String name, String cssClass) {
        String bi = BI.getOrDefault(name, "info-circle");
        String cls = cssClass == null || cssClass.isBlank() ? "" : " " + cssClass;
        return "<i class=\"bi bi-" + bi + cls + "\" aria-hidden=\"true\"></i>";
    }

    /** Ancien sprite SVG local — plus utilisé (Bootstrap Icons est une police). Conservé neutre. */
    public static String sprite() {
        return "";
    }
}
