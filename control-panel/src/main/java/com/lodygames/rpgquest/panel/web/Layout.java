package com.lodygames.rpgquest.panel.web;

import com.lodygames.rpgquest.panel.authz.Permission;
import com.lodygames.rpgquest.panel.http.Http;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Gabarit HTML unique du Control Panel. Refonte graphique #92 : thème clair, design system par
 * tokens CSS, shell moderne (topbar + sidebar groupée + icônes SVG locales + tiroir mobile
 * sans JS). CSS et sprite d'icônes en ligne (aucun asset externe, cohérent avec la CSP
 * {@code default-src 'self'}). Un outil d'administration professionnel, pas une interface
 * Minecraft décorative.
 *
 * <p>Issue #50 : la navigation est filtrée par permission — un lien dont la permission n'est pas
 * accordée n'est pas rendu, et un groupe entièrement filtré disparaît. Le contrôle backend reste
 * la seule vraie barrière : masquer un lien n'est jamais une sécurité.</p>
 */
public final class Layout {

    /**
     * Un lien de navigation.
     *
     * @param permission permission requise pour voir le lien ({@code null} = visible par toute
     *                   session authentifiée)
     * @param enabled    {@code false} = « à venir », non cliquable
     */
    public record NavItem(String label, String href, String icon, Permission permission, boolean enabled) {
    }

    /** Un groupe de navigation dans la sidebar. */
    public record NavGroup(String title, List<NavItem> items) {
    }

    public static List<NavGroup> nav() {
        return List.of(
                new NavGroup("Vue d'ensemble", List.of(
                        new NavItem("Accueil", "/home", "home", null, true),
                        new NavItem("Dashboard", "/dashboard", "dashboard", Permission.DASHBOARD_VIEW, true),
                        new NavItem("Agents", "/agents", "agents", Permission.DIAGNOSTICS_READ, true),
                        new NavItem("Historique", "/actions", "history", Permission.DIAGNOSTICS_READ, true))),
                new NavGroup("RPGQuest", List.of(
                        new NavItem("Joueurs", "/players", "players", Permission.PLAYERS_READ, true),
                        new NavItem("PNJ", "/npcs", "npc", Permission.NPC_READ, true),
                        new NavItem("Quêtes", "/quests", "quests", Permission.CONTENT_READ, true),
                        new NavItem("Stories", "/stories", "stories", Permission.CONTENT_READ, true),
                        new NavItem("Dialogues", "/dialogues", "dialogues", Permission.DIALOGUE_READ, true),
                        new NavItem("Export contenu", "/content/export", "export", Permission.CONTENT_EXPORT, true))),
                new NavGroup("Ressources", List.of(
                        new NavItem("Documentation", "/docs", "docs", Permission.DOCS_READ, true),
                        new NavItem("Diagnostics", "/diagnostics", "diagnostics", Permission.DIAGNOSTICS_READ, true))),
                new NavGroup("Administration", List.of(
                        new NavItem("Utilisateurs", "/users", "users", Permission.USER_MANAGE, true),
                        new NavItem("Développement", "/dev", "dev", Permission.DEV_MODULE, false))));
    }

    /**
     * Contexte du shell : cible/env, état serveur synthétique, utilisateur et son rôle. Champs
     * {@code null} = masqués.
     */
    public record Shell(String envLabel, String serverState, String username, String roleLabel) {
        public static Shell of(String envLabel, String serverState, String username) {
            return new Shell(envLabel, serverState, username, null);
        }

        public static Shell of(String envLabel, String serverState, String username, String roleLabel) {
            return new Shell(envLabel, serverState, username, roleLabel);
        }
    }

    /** Filtre de navigation : {@code true} = le lien est visible. */
    @FunctionalInterface
    public interface NavVisibility extends Predicate<NavItem> {
    }

    private static final NavVisibility ALL_VISIBLE = it -> true;

    public static String page(String title, String username, String activeHref, String content) {
        return page(title, activeHref, content, new Shell(null, null, username, null), ALL_VISIBLE);
    }

    public static String page(String title, String activeHref, String content, Shell shell) {
        return page(title, activeHref, content, shell, ALL_VISIBLE);
    }

    public static String page(String title, String activeHref, String content, Shell shell, Predicate<NavItem> visible) {
        Predicate<NavItem> canSee = visible == null ? ALL_VISIBLE : visible;
        StringBuilder side = new StringBuilder();
        for (NavGroup g : nav()) {
            List<NavItem> shown = new ArrayList<>();
            for (NavItem it : g.items()) {
                if (canSee.test(it)) {
                    shown.add(it);
                }
            }
            if (shown.isEmpty()) {
                continue;
            }
            side.append("<div class=\"nav-group\"><p class=\"nav-group-t\">").append(Http.esc(g.title())).append("</p>");
            for (NavItem it : shown) {
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
                + (shell.roleLabel() == null ? ""
                        : "<span class=\"userbox-r\">" + Http.esc(shell.roleLabel()) + "</span>")
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
     * + {@code plugadmin.css} (tokens et composants #92 par-dessus Bootstrap). Chaque URL porte
     * un {@code ?v=<hash>} du contenu réel : un asset modifié = nouvelle URL, jamais de version
     * en cache périmée après un déploiement (fix #93).
     */
    static final String HEAD_LINKS =
            "<link rel=\"stylesheet\" href=\"" + Assets.v("bootstrap/bootstrap.min.css") + "\">"
            + "<link rel=\"stylesheet\" href=\"" + Assets.v("bootstrap-icons/bootstrap-icons.min.css") + "\">"
            + "<link rel=\"stylesheet\" href=\"" + Assets.v("plugadmin.css") + "\">";

    /** Bootstrap JS (bundle Popper inclus) + script progressif du panel. Locaux, {@code defer}, versionnés. */
    static final String SCRIPTS =
            "<script src=\"" + Assets.v("bootstrap/bootstrap.bundle.min.js") + "\" defer></script>"
            + "<script src=\"" + Assets.v("panel.js") + "\" defer></script>";

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
