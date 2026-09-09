package com.lodygames.rpgquest.panel.web;

import com.lodygames.rpgquest.panel.http.Http;
import java.util.List;

/**
 * Gabarit HTML unique du Control Panel. Refonte graphique #92 : thème clair, design system par
 * tokens CSS, shell moderne (topbar + sidebar groupée + icônes SVG locales + tiroir mobile
 * sans JS). CSS et sprite d'icônes en ligne (aucun asset externe, cohérent avec la CSP
 * {@code default-src 'self'}). Un outil d'administration professionnel, pas une interface
 * Minecraft décorative.
 */
public final class Layout {

    /** Un lien de navigation. {@code icon} = nom d'icône {@link Icons} ; {@code enabled=false} = « à venir ». */
    public record NavItem(String label, String href, String icon, boolean enabled) {
    }

    /** Un groupe de navigation dans la sidebar. */
    public record NavGroup(String title, List<NavItem> items) {
    }

    public static List<NavGroup> nav() {
        return List.of(
                new NavGroup("Vue d'ensemble", List.of(
                        new NavItem("Accueil", "/home", "home", true),
                        new NavItem("Dashboard", "/dashboard", "dashboard", true),
                        new NavItem("Agents", "/agents", "agents", true),
                        new NavItem("Historique", "/actions", "history", true))),
                new NavGroup("RPGQuest", List.of(
                        new NavItem("Joueurs", "/players", "players", true),
                        new NavItem("PNJ", "/npcs", "npc", true),
                        new NavItem("Quêtes", "/quests", "quests", true),
                        new NavItem("Stories", "/stories", "stories", true),
                        new NavItem("Dialogues", "/dialogues", "dialogues", true))),
                new NavGroup("Ressources", List.of(
                        new NavItem("Documentation", "/docs", "docs", true),
                        new NavItem("Diagnostics", "/diagnostics", "diagnostics", false))),
                new NavGroup("Administration", List.of(
                        new NavItem("Admin", "/admin", "admin", false),
                        new NavItem("Développement", "/dev", "dev", false))));
    }

    /** Contexte du shell : cible/env, état serveur synthétique, utilisateur. Champs {@code null} = masqués. */
    public record Shell(String envLabel, String serverState, String username) {
        public static Shell of(String envLabel, String serverState, String username) {
            return new Shell(envLabel, serverState, username);
        }
    }

    public static String page(String title, String username, String activeHref, String content) {
        return page(title, activeHref, content, new Shell(null, null, username));
    }

    public static String page(String title, String activeHref, String content, Shell shell) {
        StringBuilder side = new StringBuilder();
        for (NavGroup g : nav()) {
            side.append("<div class=\"nav-group\"><p class=\"nav-group-t\">").append(Http.esc(g.title())).append("</p>");
            for (NavItem it : g.items()) {
                boolean active = it.href().equals(activeHref)
                        || (activeHref != null && activeHref.startsWith(it.href() + "/"));
                if (it.enabled()) {
                    side.append("<a class=\"navlink").append(active ? " active" : "").append("\" href=\"")
                            .append(it.href()).append("\">").append(Icons.icon(it.icon(), "nav-ic"))
                            .append("<span>").append(Http.esc(it.label())).append("</span></a>");
                } else {
                    side.append("<span class=\"navlink disabled\">").append(Icons.icon(it.icon(), "nav-ic"))
                            .append("<span>").append(Http.esc(it.label())).append("</span>")
                            .append("<em class=\"nav-soon\">bientôt</em></span>");
                }
            }
            side.append("</div>");
        }

        String envChip = shell.envLabel() == null ? ""
                : "<span class=\"env-chip\">" + Http.esc(shell.envLabel()) + "</span>";
        String statusChip = shell.serverState() == null ? "" : serverChip(shell.serverState());
        String userBox = shell.username() == null ? ""
                : "<div class=\"userbox\"><span class=\"userbox-n\">" + Http.esc(shell.username()) + "</span>"
                + "<form method=\"post\" action=\"/logout\">%CSRF%"
                + "<button class=\"iconbtn\" type=\"submit\" aria-label=\"Se déconnecter\" title=\"Se déconnecter\">"
                + Icons.icon("open") + "</button></form></div>";

        return """
                <!doctype html><html lang="fr"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>%TITLE% · PlugAdmin</title>
                %HEAD%
                </head><body>
                <input type="checkbox" id="nav-toggle" class="nav-toggle" hidden>
                <header class="topbar">
                  <label for="nav-toggle" class="burger" aria-label="Menu">%BURGER%</label>
                  <div class="brand"><span class="brand-mark">PA</span>
                    <span class="brand-t">Plug<strong>Admin</strong></span>%ENV%</div>
                  <div class="topbar-r">%NOTIF%%STATUS%%USERBOX%</div>
                </header>
                <div class="shell">
                  <label for="nav-toggle" class="scrim" aria-hidden="true"></label>
                  <nav class="side">%NAV%</nav>
                  <main class="main">%CONTENT%</main>
                </div>
                <div class="toast-container position-fixed top-0 end-0 p-3" id="toast-root" aria-live="polite" aria-atomic="true"></div>
                %SCRIPTS%
                </body></html>
                """
                .replace("%TITLE%", Http.esc(title))
                .replace("%HEAD%", HEAD_LINKS)
                .replace("%SCRIPTS%", SCRIPTS)
                .replace("%BURGER%", Icons.icon("menu"))
                .replace("%ENV%", envChip)
                .replace("%STATUS%", statusChip)
                .replace("%USERBOX%", userBox)
                .replace("%NAV%", side.toString())
                .replace("%CONTENT%", content);
    }

    /**
     * Feuilles de style servies par PlugAdmin lui-même (aucun CDN, cohérent CSP
     * {@code default-src 'self'}) : Bootstrap 5 (fondation) + Bootstrap Icons (police d'icônes)
     * + {@code plugadmin.css} (tokens et composants #92 par-dessus Bootstrap).
     */
    static final String HEAD_LINKS =
            "<link rel=\"stylesheet\" href=\"/assets/bootstrap/bootstrap.min.css\">"
            + "<link rel=\"stylesheet\" href=\"/assets/bootstrap-icons/bootstrap-icons.min.css\">"
            + "<link rel=\"stylesheet\" href=\"/assets/plugadmin.css\">";

    /** Bootstrap JS (bundle Popper inclus) + script progressif du panel. Locaux, {@code defer}. */
    static final String SCRIPTS =
            "<script src=\"/assets/bootstrap/bootstrap.bundle.min.js\" defer></script>"
            + "<script src=\"/assets/panel.js\" defer></script>";

    private static String serverChip(String state) {
        String s = state == null ? "" : state.trim().toUpperCase(java.util.Locale.ROOT);
        String kind = switch (s) {
            case "ONLINE" -> "ok";
            case "OFFLINE" -> "err";
            case "STALE" -> "warn";
            default -> "neutral";
        };
        String ic = "ONLINE".equals(s) ? "online" : "OFFLINE".equals(s) ? "offline" : "server";
        return "<span class=\"srv-chip srv-" + kind + "\">" + Icons.icon(ic) + "<span>" + Http.esc(s) + "</span></span>";
    }

    public static String bare(String title, String content) {
        return """
                <!doctype html><html lang="fr"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>%TITLE% · PlugAdmin</title>
                %HEAD%
                </head><body class="centered">
                <main class="card login">%CONTENT%</main></body></html>
                """
                .replace("%TITLE%", Http.esc(title))
                .replace("%HEAD%", HEAD_LINKS)
                .replace("%CONTENT%", content);
    }

}
