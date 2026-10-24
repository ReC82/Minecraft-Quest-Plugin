package com.lodygames.rpgquest.webapi.site;

import com.lodygames.rpgquest.webapi.ServerState;
import com.lodygames.rpgquest.webapi.ServerStatus;
import com.lodygames.rpgquest.webapi.SnapshotStore;
import com.lodygames.rpgquest.webapi.http.ApiHandler;
import com.lodygames.rpgquest.webapi.http.HttpResponses;
import com.lodygames.rpgquest.webapi.news.NewsArticle;
import com.lodygames.rpgquest.webapi.news.NewsRepository;
import com.lodygames.rpgquest.webapi.privacy.PublicPlayerData;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * {@code GET /} — accueil du portail public (#56) : présentation, statut du serveur, dernières
 * actualités, saison et événements, aperçu des classements, appel à rejoindre.
 *
 * <p>Chaque bloc se dégrade seul : sans instantané, les actualités et la présentation restent ;
 * sans {@code player-data-public}, aucun pseudo n'est lu et l'aperçu des classements l'annonce, avec un
 * lien vers l'aperçu fictif {@code /demo/classements}.</p>
 *
 * <p>Refonte (#56) : bandeau d'accueil, « Commence en 3 étapes », tuiles d'accès rapide, puis l'état du
 * serveur, la saison, les actualités et le podium des classements.</p>
 */
public final class HomePageHandler implements ApiHandler {

    static final int LATEST_NEWS = 3;
    static final int PREVIEW = 3;
    /** Classements mis en avant au plus sur l'accueil (#735). */
    static final int FEATURED_MAX = 3;
    /** Tuiles d'accès rapide : adresse, icône, titre, accroche. */
    private static final String[][] TILES = {
            {PageMeta.EVENTS, "boss", "Événements", "Défis du moment et à venir"},
            {PageMeta.BOARDS, "trophy", "Classements", "Les meilleurs aventuriers"},
            {PageMeta.PROGRESSION, "xp", "Progresser", "Les cinq pistes d'expérience"},
            {PageMeta.NEWS, "scroll", "Actualités", "Nouveautés du serveur"},
            {PageMeta.PLAYERS, "npc", "Joueurs", "Profils publics"},
            {PageMeta.INFO, "map", "Guide", "Rejoindre, règles et FAQ"},
    };
    /** Les cinq façons de progresser, puis les quêtes (#428). */
    private static final String HERO_ICONS = PortalIcons.icon("sword", 32) + PortalIcons.icon("pickaxe", 32)
            + PortalIcons.icon("bread", 32) + PortalIcons.icon("fish", 32) + PortalIcons.icon("map", 32)
            + PortalIcons.icon("scroll", 32);

    private final SnapshotStore store;
    private final NewsRepository news;
    private final Site site;
    private final boolean playerDataPublic;

    public HomePageHandler(SnapshotStore store, NewsRepository news, Site site, boolean playerDataPublic) {
        this.store = store;
        this.news = news;
        this.site = site;
        this.playerDataPublic = playerDataPublic;
    }

    @Override
    public int handle(HttpExchange exchange) throws IOException {
        // "/" est enregistrée comme préfixe le plus court : elle sert aussi de retombée
        // pour toute page inconnue non capturée par les autres contextes plus spécifiques.
        if (!"/".equals(exchange.getRequestURI().getPath())) {
            HttpResponses.sendHtml(exchange, 404, PageLayout.render(site, PageMeta.none(), "Page introuvable", true,
                    "<p>Cette page n'existe pas.</p><p><a href=\"/\">Retour à l'accueil</a></p>"));
            return 404;
        }
        Map<String, Object> state = ServerState.resolve(store);
        StringBuilder body = new StringBuilder(hero());
        body.append(steps()).append(tiles());
        body.append("<div class=\"grid\">").append(status(state)).append(periods(state)).append("</div>");
        body.append(latestNews());
        body.append(serverMessages(state));
        body.append(boardsPreview(state));
        HttpResponses.sendHtml(exchange, 200, PageLayout.render(site, new PageMeta(PageMeta.HOME,
                site.title() + " : serveur Minecraft d'aventure. Quêtes, personnages, progression, classements et "
                        + "actualités de la communauté.", PageMeta.HOME), site.title(), true, body.toString()));
        return 200;
    }

    private String hero() {
        return "<section class=\"hero\"><div class=\"lq-hero-icons\">" + HERO_ICONS + "</div><p><strong>" + Html.escape(site.title()) + "</strong> est un serveur "
                + "Minecraft d'aventure : des quêtes à accomplir, des personnages à rencontrer, des villes à "
                + "relier et cinq façons de progresser — combat, minage, agriculture, pêche et exploration.</p>"
                + "<p>Pas besoin de compte pour suivre la vie du serveur ici.</p>"
                + "<p><a class=\"button\" href=\"/infos#rejoindre\">Rejoindre l'aventure</a>"
                + "<a class=\"button secondary\" href=\"/evenements\">Les événements</a></p></section>";
    }

    private static String steps() {
        return PortalIcons.heading("h2", "waypoint", "Commence en 3 étapes") + "<ol class=\"steps\">"
                + "<li><strong>Rejoins le serveur</strong><br>Minecraft Java Edition, menu Multijoueur. "
                + "<a href=\"/infos#rejoindre\">Comment faire</a></li>"
                + "<li><strong>Parle aux personnages</strong><br>Clic droit sur un personnage : certains ont une quête pour toi.</li>"
                + "<li><strong>Progresse</strong><br>Gagne de l'expérience dans cinq domaines. "
                + "<a href=\"/progression\">Comment progresser</a></li></ol>";
    }

    private static String tiles() {
        StringBuilder html = new StringBuilder("<nav aria-label=\"Accès rapide\"><ul class=\"tiles\">");
        for (String[] tile : TILES) {
            html.append("<li><a class=\"tile-link\" href=\"").append(tile[0]).append("\">")
                    .append(PortalIcons.tile(tile[1], true)).append("<strong>").append(tile[2])
                    .append("</strong><span class=\"note\">").append(tile[3]).append("</span></a></li>");
        }
        return html.append("</ul></nav>").toString();
    }

    /** Carte « Le serveur » (#421) : rouge seulement pour un arrêt annoncé par le serveur lui-même. */
    private static String status(Map<String, Object> state) {
        StringBuilder html = new StringBuilder("<section class=\"card\">")
                .append(PortalIcons.heading("h2", "waypoint", "Le serveur"));
        ServerStatus status = ServerStatus.of(state);
        switch (status.kind()) {
            case ONLINE -> {
                Map<?, ?> server = state.get("server") instanceof Map<?, ?> map ? map : Map.of();
                html.append("<p><strong style=\"color:var(--ok)\">● En ligne</strong></p><p>")
                        .append(StatLabels.number(server.get("playerCount"))).append(" joueur(s) connecté(s) sur ")
                        .append(StatLabels.number(server.get("maxPlayers"))).append(".</p>");
            }
            case OFFLINE -> html.append("<p><strong style=\"color:var(--ko)\">● Hors ligne</strong></p>"
                    + "<p class=\"note\">Arrêt annoncé par le serveur le ").append(Html.escape(status.lastUpdateText()))
                    .append(".</p>");
            case STALE -> html.append("<p><strong style=\"color:var(--muted)\">● État inconnu</strong></p>"
                    + "<p class=\"note\">Informations non actualisées depuis le ").append(Html.escape(status.lastUpdateText()))
                    .append(".</p>");
            case UNKNOWN -> html.append("<p><strong style=\"color:var(--muted)\">● État inconnu</strong></p>"
                    + "<p class=\"note\">Aucune donnée reçue du serveur de jeu pour le moment.</p>");
        }
        return html.append("<p><a href=\"/status\">Détails</a></p></section>").toString();
    }

    /** Saison et événements (noms et dates seulement, aucun pseudo) — rien si {@code statistics.yml} n'en définit pas. */
    private static String periods(Map<String, Object> state) {
        Map<String, Object> statistics = PublicPlayerData.statistics(state.get("statistics"));
        StringBuilder items = new StringBuilder();
        if (statistics.get("season") instanceof Map<?, ?> season) {
            items.append(periodLine("Saison", season));
        }
        if (statistics.get("events") instanceof List<?> events) {
            for (Object event : events) {
                if (event instanceof Map<?, ?> period && !"ARCHIVED".equals(period.get("status"))) {
                    items.append(periodLine("Événement", period));
                }
            }
        }
        String content = items.isEmpty()
                ? PortalIcons.empty("trophy", "Aucune saison ni aucun événement annoncé pour le moment.")
                : "<ul>" + items + "</ul>";
        return "<section class=\"card\">" + PortalIcons.heading("h2", "boss", "Saison et événements") + content
                + "<p><a href=\"/evenements\">Tous les événements</a></p></section>";
    }

    private static String periodLine(String kind, Map<?, ?> period) {
        return "<li>" + kind + " <strong>" + Html.escape(String.valueOf(period.get("name"))) + "</strong> — "
                + Html.escape(StatLabels.status(String.valueOf(period.get("status")))) + " (du "
                + Html.escape(StatLabels.date(String.valueOf(period.get("start")))) + " au "
                + Html.escape(StatLabels.date(String.valueOf(period.get("end")))) + ")</li>";
    }

    private String latestNews() {
        List<NewsArticle> latest = news.published().stream().limit(LATEST_NEWS).toList();
        StringBuilder html = new StringBuilder(PortalIcons.heading("h2", "scroll", "Dernières actualités"));
        if (latest.isEmpty()) {
            return html.append(PortalIcons.empty("scroll", "Aucune actualité publiée pour le moment.")).toString();
        }
        for (NewsArticle article : latest) {
            html.append(NewsPageHandler.card(article, "h3"));
        }
        return html.append("<p><a href=\"/actualites\">Toutes les actualités →</a></p>").toString();
    }

    /** Annonces configurées côté plugin ({@code web-export.announcements}), relayées telles quelles mais échappées. */
    private static String serverMessages(Map<String, Object> state) {
        List<?> announcements = state.get("announcements") instanceof List<?> list ? list : List.of();
        StringBuilder html = new StringBuilder();
        for (Object item : announcements) {
            if (item instanceof Map<?, ?> announcement && announcement.get("title") instanceof String title) {
                html.append("<div class=\"card\" style=\"margin-bottom:1rem\"><h3>").append(Html.escape(title))
                        .append("</h3><p>").append(Html.escape(String.valueOf(announcement.get("body")))).append("</p></div>");
            }
        }
        return html.isEmpty() ? "" : PortalIcons.heading("h2", "talk", "Message du serveur") + html;
    }

    /**
     * Classements mis en avant depuis PlugAdmin (#735, {@code featured}), dans leur ordre d'affichage :
     * au plus {@link #FEATURED_MAX}. Vide s'il n'y en a aucun (l'aperçu historique prend le relais).
     */
    @SuppressWarnings("unchecked")
    static String featuredBoards(Map<String, Object> statistics) {
        if (!(statistics.get("boards") instanceof List<?> list)) {
            return "";
        }
        Map<String, Object> catalog = statistics.get("catalog") instanceof Map<?, ?> map
                ? (Map<String, Object>) map : Map.of();
        List<Map<String, Object>> featured = list.stream().filter(Map.class::isInstance)
                .map(item -> (Map<String, Object>) item)
                .filter(board -> Boolean.TRUE.equals(board.get("featured"))).limit(FEATURED_MAX).toList();
        if (featured.isEmpty()) {
            return "";
        }
        StringBuilder html = new StringBuilder("<div class=\"grid boards\">");
        featured.forEach(board -> html.append(BoardCatalogPage.card(board, catalog, SiteRoutes.LIVE)));
        return html.append("</div>").toString();
    }

    private String boardsPreview(Map<String, Object> state) {
        StringBuilder html = new StringBuilder(PortalIcons.heading("h2", "trophy", "Classements"));
        if (!playerDataPublic) {
            return html.append(PortalIcons.empty("shield", "Les classements et les profils ouvriront quand les règles de "
                    + "confidentialité auront été validées. <a href=\"/confidentialite\">En savoir plus</a>"))
                    .append("<p><a class=\"button secondary\" href=\"").append(SiteRoutes.DEMO.boards())
                    .append("\">Voir un aperçu avec des joueurs fictifs</a></p>").toString();
        }
        String unavailable = LeaderboardsPageHandler.unavailable(state);
        if (!unavailable.isEmpty()) {
            return html.append(unavailable).toString();
        }
        Map<String, Object> statistics = PublicPlayerData.statistics(state.get("statistics"));
        String featured = featuredBoards(statistics);
        if (!featured.isEmpty()) {
            return html.append(featured).append("<p><a href=\"/classements\">Tous les classements →</a></p>").toString();
        }
        Map<String, List<Map<String, Object>>> boards = LeaderboardsPageHandler.boards(statistics.get("lifetime"));
        if (boards.isEmpty()) {
            return html.append(PortalIcons.empty("trophy", "Aucun classement disponible pour le moment.")).toString();
        }
        html.append("<div class=\"grid boards\">");
        boards.entrySet().stream().limit(2).forEach(board -> html.append("<section class=\"card\">")
                .append(PortalIcons.heading("h3", PortalIcons.forMetric(board.getKey()), StatLabels.title(board.getKey())))
                .append(BoardView.render(board.getValue(), board.getKey(), PREVIEW, SiteRoutes.LIVE)).append("</section>"));
        return html.append("</div><p><a href=\"/classements\">Tous les classements →</a></p>").toString();
    }
}
