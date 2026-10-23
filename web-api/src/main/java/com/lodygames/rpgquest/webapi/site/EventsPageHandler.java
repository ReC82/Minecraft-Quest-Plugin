package com.lodygames.rpgquest.webapi.site;

import com.lodygames.rpgquest.webapi.ServerState;
import com.lodygames.rpgquest.webapi.ServerStatus;
import com.lodygames.rpgquest.webapi.SnapshotStore;
import com.lodygames.rpgquest.webapi.http.ApiHandler;
import com.lodygames.rpgquest.webapi.http.HttpResponses;
import com.lodygames.rpgquest.webapi.privacy.PublicPlayerData;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code GET /evenements} — saison et événements du serveur (#56, modèle #54/#57), rangés en « En cours »,
 * « À venir » et « Terminés » : nom, dates, compte à rebours, règles, récompenses annoncées et objectif commun.
 *
 * <p>Aucun pseudo ni aucun score individuel : seul le total agrégé d'un objectif commun est affiché.
 * La page reste donc publique même avec {@code player-data-public=false} ; le lien vers le classement
 * d'une période n'apparaît que si les classements sont publiés. Sous {@code /demo/evenements}, les
 * périodes fictives de {@link DemoData}, avec bandeau et {@code noindex}.</p>
 */
public final class EventsPageHandler implements ApiHandler {

    private final SnapshotStore store;
    private final Site site;
    private final SiteRoutes routes;
    private final boolean boardsPublished;

    public EventsPageHandler(SnapshotStore store, Site site, SiteRoutes routes, boolean boardsPublished) {
        this.store = store;
        this.site = site;
        this.routes = routes;
        this.boardsPublished = boardsPublished || routes.demo();
    }

    @Override
    public int handle(HttpExchange exchange) throws IOException {
        Map<String, Object> live = ServerState.resolve(store);
        ServerStatus serverStatus = ServerStatus.of(live);
        String description = "Saisons et événements de " + site.title() + " : dates, règles et récompenses.";
        PageMeta meta = routes.demo() ? PageMeta.demo(description, PageMeta.EVENTS)
                : new PageMeta(PageMeta.EVENTS, description, PageMeta.EVENTS);
        if (!routes.events().equals(exchange.getRequestURI().getPath())) {
            HttpResponses.sendHtml(exchange, 404, PageLayout.render(site, meta, "Page introuvable", serverStatus,
                    "<p>Cette page n'existe pas.</p><p><a href=\"" + routes.events() + "\">Les événements</a></p>"));
            return 404;
        }
        Map<String, Object> state = routes.demo() ? DemoData.state(Instant.now()) : live;
        Map<String, Object> statistics = PublicPlayerData.statistics(state.get("statistics"));
        HttpResponses.sendHtml(exchange, 200, PageLayout.render(site, meta,
                routes.demo() ? "Événements (démonstration)" : "Événements", serverStatus,
                routes.banner() + body(periods(statistics))));
        return 200;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> periods(Map<String, Object> statistics) {
        List<Map<String, Object>> periods = new ArrayList<>();
        if (statistics.get("season") instanceof Map<?, ?> season) {
            Map<String, Object> copy = new java.util.LinkedHashMap<>((Map<String, Object>) season);
            copy.put("kind", "Saison");
            periods.add(copy);
        }
        if (statistics.get("events") instanceof List<?> events) {
            for (Object event : events) {
                Map<String, Object> copy = new java.util.LinkedHashMap<>((Map<String, Object>) event);
                copy.put("kind", "Événement");
                periods.add(copy);
            }
        }
        return periods;
    }

    private String body(List<Map<String, Object>> periods) {
        StringBuilder html = new StringBuilder("<p>Les saisons et les événements sont des défis limités dans le temps : "
                + "seul ce qui est gagné pendant la période compte.</p>");
        if (periods.isEmpty()) {
            html.append(PortalIcons.empty("boss", "Aucune saison ni aucun événement annoncé pour le moment. "
                    + "Les prochains seront annoncés ici et dans les <a href=\"/actualites\">actualités</a>."));
            if (!routes.demo()) {
                html.append("<p><a href=\"").append(SiteRoutes.DEMO.events())
                        .append("\">Voir un exemple avec des événements fictifs</a></p>");
            }
            return html.toString();
        }
        html.append(group("En cours", "boss", periods, List.of("ACTIVE")));
        html.append(group("À venir", "map", periods, List.of("SCHEDULED")));
        html.append(group("Terminés", "trophy", periods, List.of("ENDED", "ARCHIVED", "CANCELLED")));
        return html.toString();
    }

    private String group(String title, String icon, List<Map<String, Object>> periods, List<String> statuses) {
        List<Map<String, Object>> kept = periods.stream().filter(period -> statuses.contains(period.get("status"))).toList();
        if (kept.isEmpty()) {
            return "";
        }
        StringBuilder html = new StringBuilder(PortalIcons.heading("h2", icon, title)).append("<div class=\"grid\">");
        for (Map<String, Object> period : kept) {
            html.append(card(period));
        }
        return html.append("</div>").toString();
    }

    private String card(Map<String, Object> period) {
        String status = String.valueOf(period.get("status"));
        String id = String.valueOf(period.get("id"));
        StringBuilder html = new StringBuilder("<article class=\"card\">")
                .append(PortalIcons.heading("h3", "Saison".equals(period.get("kind")) ? "trophy" : "boss",
                        String.valueOf(period.getOrDefault("name", id))))
                .append("<p><span class=\"chip ").append(chip(status)).append("\">")
                .append(Html.escape(StatLabels.status(status))).append("</span> <span class=\"note\">")
                .append(Html.escape(String.valueOf(period.get("kind")))).append("</span></p>")
                .append("<p class=\"note\">Du ").append(Html.escape(StatLabels.dateTime(String.valueOf(period.get("start")))))
                .append(" au ").append(Html.escape(StatLabels.dateTime(String.valueOf(period.get("end"))))).append("</p>")
                .append(LeaderboardsPageHandler.countdown(status, period, Instant.now()))
                .append(LeaderboardsPageHandler.rules(period));
        if (!"CANCELLED".equals(status)) {
            html.append(LeaderboardsPageHandler.community(period));
        }
        if (boardsPublished && !"SCHEDULED".equals(status) && !"CANCELLED".equals(status)) {
            html.append("<p><a class=\"button secondary\" href=\"").append(routes.boards()).append("?periode=")
                    .append(URLEncoder.encode(id, StandardCharsets.UTF_8)).append("\">Voir le classement</a></p>");
        }
        return html.append("</article>").toString();
    }

    private static String chip(String status) {
        return switch (status) {
            case "ACTIVE" -> "active";
            case "SCHEDULED" -> "scheduled";
            case "CANCELLED" -> "cancelled";
            default -> "ended";
        };
    }
}
