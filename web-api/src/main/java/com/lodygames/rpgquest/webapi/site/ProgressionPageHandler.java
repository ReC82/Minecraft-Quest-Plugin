package com.lodygames.rpgquest.webapi.site;

import com.lodygames.rpgquest.webapi.http.ApiHandler;
import com.lodygames.rpgquest.webapi.http.HttpResponses;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;

/**
 * {@code GET /progression} — « Comment progresser » (#56) : les cinq pistes d'XP RPG, le niveau global,
 * les quêtes, les saisons. Page fixe, sans aucune donnée de joueur ; le contenu suit
 * {@code docs/PROGRESSION.md} sans citer de montants, qui se règlent dans la configuration du serveur.
 */
public final class ProgressionPageHandler implements ApiHandler {

    /** Piste : identifiant de métrique (pour l'icône), nom, comment gagner de l'XP. */
    private static final String[][] TRACKS = {
            {"RPG_XP_COMBAT", "Combat", "Vaincs des monstres. Ceux qui sortent d'une cage à monstres ne comptent pas."},
            {"RPG_XP_MINING", "Minage", "Casse des blocs naturels : pierre, minerais… Un bloc posé par un joueur ne rapporte rien."},
            {"RPG_XP_FARMING", "Agriculture", "Récolte des cultures mûres. Une pousse arrachée trop tôt ne compte pas."},
            {"RPG_XP_FISHING", "Pêche", "Attrape des poissons et des trésors avec ta canne à pêche."},
            {"RPG_XP_EXPLORATION", "Exploration", "Découvre de nouveaux lieux : chaque zone rapporte une seule fois, la première."},
    };

    private final Site site;

    public ProgressionPageHandler(Site site) {
        this.site = site;
    }

    @Override
    public int handle(HttpExchange exchange) throws IOException {
        if (!PageMeta.PROGRESSION.equals(exchange.getRequestURI().getPath())) {
            HttpResponses.sendHtml(exchange, 404, PageLayout.render(site, PageMeta.none(), "Page introuvable", true,
                    "<p>Cette page n'existe pas.</p><p><a href=\"/progression\">Comment progresser</a></p>"));
            return 404;
        }
        HttpResponses.sendHtml(exchange, 200, PageLayout.render(site, new PageMeta(PageMeta.PROGRESSION,
                "Comment progresser sur " + site.title() + " : combat, minage, agriculture, pêche, exploration et quêtes.",
                PageMeta.INFO), "Comment progresser", true, body()));
        return 200;
    }

    private static String body() {
        StringBuilder html = new StringBuilder("<section class=\"hero\"><div class=\"lq-hero-icons\">")
                .append(PortalIcons.icon("xp", 32)).append(PortalIcons.icon("sword", 32))
                .append(PortalIcons.icon("pickaxe", 32)).append(PortalIcons.icon("bread", 32))
                .append(PortalIcons.icon("fish", 32)).append(PortalIcons.icon("map", 32))
                .append("</div><p>Ici, pas de niveaux Minecraft classiques : tu gagnes de l'<strong>expérience RPG</strong> "
                        + "dans cinq domaines. Chaque domaine a son propre niveau, et ton <strong>niveau global</strong> "
                        + "grandit avec tous les autres.</p></section>");
        html.append(PortalIcons.heading("h2", "xp", "Les cinq pistes")).append("<ul class=\"tiles\">");
        for (String[] track : TRACKS) {
            html.append("<li class=\"tile-link\">").append(PortalIcons.tile(PortalIcons.forMetric(track[0]), true))
                    .append("<strong>").append(track[1]).append("</strong><span>").append(Html.escape(track[2]))
                    .append("</span></li>");
        }
        html.append("</ul>");
        html.append(PortalIcons.heading("h2", "scroll", "Quêtes et niveau global")).append("<ul>"
                + "<li>Chaque quête terminée donne un bonus d'expérience au niveau global.</li>"
                + "<li>Une partie de l'expérience de chaque piste compte aussi pour le niveau global.</li>"
                + "<li>Chaque niveau demande un peu plus d'expérience que le précédent.</li>"
                + "<li>Certains lieux et certains marchands demandent un niveau global minimum.</li></ul>");
        html.append(PortalIcons.heading("h2", "npc", "Voir ta progression en jeu")).append("<ul>"
                + "<li>Ouvre ton <strong>journal de quêtes</strong> (<code>/quests</code>) et clique sur ta bourse : "
                + "une jauge par piste.</li>"
                + "<li><code>/profile</code> résume tes niveaux, <code>/skills</code> détaille chaque piste.</li></ul>");
        html.append(PortalIcons.heading("h2", "trophy", "Saisons, événements et classements")).append("<p>Les "
                + "<a href=\"/evenements\">événements</a> sont des défis limités dans le temps ; les "
                + "<a href=\"/classements\">classements</a> montrent les meilleurs aventuriers qui ont choisi d'afficher "
                + "leur profil.</p>");
        return html.toString();
    }
}
