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
 * sans {@code player-data-public}, aucun pseudo n'est lu et l'aperçu des classements l'annonce.</p>
 */
public final class HomePageHandler implements ApiHandler {

    static final int LATEST_NEWS = 3;
    static final int PREVIEW = 3;

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
        return "<section class=\"hero\"><p><strong>" + Html.escape(site.title()) + "</strong> est un serveur "
                + "Minecraft d'aventure : des quêtes à accomplir, des personnages à rencontrer, des villes à "
                + "relier et cinq façons de progresser — combat, minage, agriculture, pêche et exploration.</p>"
                + "<p>Pas besoin de compte pour suivre la vie du serveur ici.</p>"
                + "<p><a class=\"button\" href=\"/infos#rejoindre\">Comment rejoindre</a>"
                + "<a class=\"button secondary\" href=\"/actualites\">Les actualités</a></p></section>";
    }

    /** Carte « Le serveur » (#421) : rouge seulement pour un arrêt annoncé par le serveur lui-même. */
    private static String status(Map<String, Object> state) {
        StringBuilder html = new StringBuilder("<section class=\"card\"><h2>Le serveur</h2>");
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
                ? "<p class=\"note\">Aucune saison ni aucun événement annoncé pour le moment.</p>"
                : "<ul>" + items + "</ul>";
        return "<section class=\"card\"><h2>Saison et événements</h2>" + content + "</section>";
    }

    private static String periodLine(String kind, Map<?, ?> period) {
        return "<li>" + kind + " <strong>" + Html.escape(String.valueOf(period.get("name"))) + "</strong> — "
                + Html.escape(StatLabels.status(String.valueOf(period.get("status")))) + " (du "
                + Html.escape(StatLabels.date(String.valueOf(period.get("start")))) + " au "
                + Html.escape(StatLabels.date(String.valueOf(period.get("end")))) + ")</li>";
    }

    private String latestNews() {
        List<NewsArticle> latest = news.published().stream().limit(LATEST_NEWS).toList();
        StringBuilder html = new StringBuilder("<h2>Dernières actualités</h2>");
        if (latest.isEmpty()) {
            return html.append("<p class=\"note\">Aucune actualité publiée pour le moment.</p>").toString();
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
        return html.isEmpty() ? "" : "<h2>Message du serveur</h2>" + html;
    }

    private String boardsPreview(Map<String, Object> state) {
        StringBuilder html = new StringBuilder("<h2>Classements</h2>");
        if (!playerDataPublic) {
            return html.append("<p class=\"note\">Les classements et les profils ouvriront quand les règles de "
                    + "confidentialité auront été validées. <a href=\"/confidentialite\">En savoir plus</a></p>").toString();
        }
        Map<String, List<Map<String, Object>>> boards =
                LeaderboardsPageHandler.boards(PublicPlayerData.statistics(state.get("statistics")).get("lifetime"));
        if (boards.isEmpty()) {
            return html.append("<p class=\"note\">Aucun classement disponible pour le moment.</p>").toString();
        }
        html.append("<div class=\"grid\">");
        boards.entrySet().stream().limit(2).forEach(board -> html.append("<section class=\"card\"><h3>")
                .append(Html.escape(StatLabels.title(board.getKey()))).append("</h3>")
                .append(BoardView.render(board.getValue(), board.getKey(), PREVIEW)).append("</section>"));
        return html.append("</div><p><a href=\"/classements\">Tous les classements →</a></p>").toString();
    }
}
