package com.lodygames.rpgquest.panel.web;

import com.lodygames.rpgquest.panel.authz.Permission;
import com.lodygames.rpgquest.panel.authz.PermissionService;
import com.lodygames.rpgquest.panel.http.Http;
import java.util.List;

/**
 * <strong>Home</strong> du Control Panel (issue #92, lot Bootstrap) : un <em>launcher</em> à
 * grandes tuiles, dans l'esprit d'un back-office DirectAdmin. C'est la page d'arrivée après
 * connexion — elle répond d'abord à « qu'est-ce que je veux gérer ? ». Le Dashboard n'est plus la
 * page d'accueil : il devient une tuile et reste sur {@code /dashboard}.
 *
 * <p>Fondation Bootstrap 5 (grid {@code row-cols-*}, {@code col}, utilitaires d'espacement) +
 * couche {@code plugadmin.css} pour l'identité. Chaque tuile activée est un vrai lien {@code <a>}
 * (accessible clavier, toute la carte cliquable) ; une tuile « à venir » est un bloc non
 * cliquable {@code aria-disabled}. Les infos synthétiques viennent du dernier relevé agent — aucune
 * requête déclenchée pour la décoration.</p>
 */
public final class HomePages {

    /** Un groupe de tuiles. */
    record Group(String title, List<Tile> tiles) {
    }

    /**
     * @param key        identifiant court (badge synthétique)
     * @param href       cible
     * @param icon       nom d'icône {@link Icons}
     * @param title      titre affiché
     * @param desc       description courte
     * @param permission permission requise ({@code null} = toujours visible)
     * @param enabled    {@code false} = tuile « à venir », non cliquable
     */
    record Tile(String key, String href, String icon, String title, String desc,
                Permission permission, boolean enabled) {
    }

    private static final List<Group> GROUPS = List.of(
            new Group("Vue d'ensemble", List.of(
                    new Tile("dashboard", "/dashboard", "dashboard", "Dashboard",
                            "État réel du serveur, mondes, santé et détails techniques.",
                            Permission.DASHBOARD_VIEW, true),
                    new Tile("agents", "/agents", "agents", "Agents",
                            "Canal d'administration distant : heartbeat, actions récentes, protocole.",
                            Permission.DIAGNOSTICS_READ, true),
                    new Tile("diagnostics", "/diagnostics", "diagnostics", "Diagnostics",
                            "Santé détaillée et anomalies de configuration regroupées.",
                            Permission.DIAGNOSTICS_READ, false))),
            new Group("Gestion du jeu", List.of(
                    new Tile("players", "/players", "players", "Joueurs",
                            "Voir les joueurs connectés, leur état et leur progression.",
                            Permission.PLAYERS_READ, true),
                    new Tile("npc", "/npcs", "npc", "PNJ",
                            "Définitions logiques, liaisons Citizens, donneurs de quête.",
                            Permission.NPC_READ, true),
                    new Tile("quests", "/quests", "quests", "Quêtes",
                            "Consulter et éditer les quêtes : objectifs, récompenses, prérequis.",
                            Permission.CONTENT_READ, true),
                    new Tile("stories", "/stories", "stories", "Stories",
                            "Enchaînements ordonnés de quêtes existantes.",
                            Permission.CONTENT_READ, true),
                    new Tile("dialogues", "/dialogues", "dialogues", "Dialogues",
                            "Arbres de dialogue des PNJ, choix, actions et diagnostics.",
                            Permission.DIALOGUE_READ, true))),
            new Group("Ressources", List.of(
                    new Tile("docs", "/docs", "docs", "Documentation",
                            "Fiches d'administration : PNJ, reset joueur, déploiement, Claims, Wild…",
                            Permission.DOCS_READ, true))),
            new Group("Administration", List.of(
                    new Tile("admin", "/admin", "admin", "Administration",
                            "Comptes, rôles et journal d'audit.",
                            Permission.AUDIT_READ, false),
                    new Tile("dev", "/dev", "dev", "Développement",
                            "GitHub, rapports Claude, tests, build et déploiements.",
                            Permission.DEV_MODULE, false))));

    private final PermissionService perms;

    public HomePages(PermissionService perms) {
        this.perms = perms;
    }

    /**
     * @param role         nom de rôle de la session
     * @param serverState  ONLINE / OFFLINE / STALE / UNKNOWN (tuiles Dashboard et Agents)
     * @param summary      comptes synthétiques (voir {@link AgentPages#homeSummary})
     */
    public String render(String role, String serverState, AgentPages.HomeSummary summary) {
        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"home-head\">");
        sb.append("<h1>PlugAdmin</h1>");
        sb.append("<p class=\"home-sub\">Administration de LodyQuests</p>");
        sb.append("<p class=\"home-hint\">Choisissez une section pour gérer le serveur et son contenu.</p>");
        sb.append("<div class=\"home-search\">").append(Icons.icon("search"))
                .append("<input type=\"search\" disabled placeholder=\"Rechercher un joueur, un PNJ, une quête, une commande…\" ")
                .append("aria-label=\"Recherche globale (à venir)\"></div>");
        sb.append("</div>");

        for (Group g : GROUPS) {
            List<Tile> visible = g.tiles().stream()
                    .filter(t -> t.permission() == null || perms.can(role, t.permission()))
                    .toList();
            if (visible.isEmpty()) {
                continue;
            }
            sb.append("<section class=\"home-group\">");
            sb.append("<h2 class=\"home-group-t\">").append(Http.esc(g.title())).append("</h2>");
            sb.append("<div class=\"row row-cols-1 row-cols-md-2 row-cols-lg-3 row-cols-xl-4 g-3\">");
            for (Tile t : visible) {
                sb.append("<div class=\"col\">").append(tile(t, serverState, summary)).append("</div>");
            }
            sb.append("</div></section>");
        }
        return sb.toString();
    }

    private static String tile(Tile t, String serverState, AgentPages.HomeSummary s) {
        String meta = metaFor(t.key(), serverState, s);
        if (!t.enabled()) {
            return "<div class=\"home-tile is-disabled\" aria-disabled=\"true\">"
                    + "<span class=\"home-tile-ic\">" + Icons.icon(t.icon(), "") + "</span>"
                    + "<span class=\"home-tile-t\">" + Http.esc(t.title()) + "</span>"
                    + "<p class=\"home-tile-d\">" + Http.esc(t.desc()) + "</p>"
                    + "<span class=\"home-tile-meta\"><span class=\"home-pill soon\">À venir</span></span>"
                    + "</div>";
        }
        return "<a class=\"home-tile\" href=\"" + Http.esc(t.href()) + "\">"
                + "<span class=\"home-tile-ic\">" + Icons.icon(t.icon(), "") + "</span>"
                + "<span class=\"home-tile-cta\"><i class=\"bi bi-arrow-right\" aria-hidden=\"true\"></i></span>"
                + "<span class=\"home-tile-t\">" + Http.esc(t.title()) + "</span>"
                + "<p class=\"home-tile-d\">" + Http.esc(t.desc()) + "</p>"
                + "<span class=\"home-tile-meta\">" + meta + "</span>"
                + "</a>";
    }

    /** Badge synthétique d'une tuile — vide si la donnée n'est pas déjà disponible. */
    private static String metaFor(String key, String serverState, AgentPages.HomeSummary s) {
        return switch (key) {
            case "dashboard", "agents" -> statePill(serverState);
            case "players" -> s.playersOnline() < 0 ? ""
                    : pill("neutral", s.playersOnline() + " en ligne");
            case "npc" -> s.npcTotal() < 0 ? "" : pill("neutral", s.npcTotal() + " PNJ")
                    + (s.npcWarnings() > 0 ? " " + pill("warn", s.npcWarnings() + " alerte" + plural(s.npcWarnings())) : "");
            case "quests" -> s.quests() < 0 ? "" : pill("neutral", s.quests() + " quête" + plural(s.quests()));
            case "stories" -> s.stories() < 0 ? "" : pill("neutral", s.stories() + " story" + (s.stories() > 1 ? "s" : ""));
            case "dialogues" -> s.dialogues() < 0 ? "" : pill("neutral", s.dialogues() + " dialogue" + plural(s.dialogues()))
                    + (s.dialogueWarnings() > 0 ? " " + pill("warn", s.dialogueWarnings() + " alerte" + plural(s.dialogueWarnings())) : "");
            default -> "";
        };
    }

    private static String statePill(String state) {
        String s = state == null ? "" : state.trim().toUpperCase(java.util.Locale.ROOT);
        return switch (s) {
            case "ONLINE" -> pill("ok", "ONLINE");
            case "OFFLINE" -> pill("err", "OFFLINE");
            case "STALE" -> pill("warn", "STALE");
            default -> "";
        };
    }

    private static String pill(String kind, String label) {
        return "<span class=\"home-pill " + kind + "\">" + Http.esc(label) + "</span>";
    }

    private static String plural(int n) {
        return n > 1 ? "s" : "";
    }
}
