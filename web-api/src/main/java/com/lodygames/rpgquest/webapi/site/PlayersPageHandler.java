package com.lodygames.rpgquest.webapi.site;

import com.lodygames.rpgquest.webapi.ServerState;
import com.lodygames.rpgquest.webapi.ServerStatus;
import com.lodygames.rpgquest.webapi.SnapshotStore;
import com.lodygames.rpgquest.webapi.http.ApiHandler;
import com.lodygames.rpgquest.webapi.http.HttpResponses;
import com.lodygames.rpgquest.webapi.http.QueryParams;
import com.lodygames.rpgquest.webapi.privacy.PublicPlayerData;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * {@code GET /joueurs} et {@code GET /joueurs/<pseudo>} — profils publics limités (#56).
 *
 * <p>Ne montre que ce que l'instantané publie, après la liste blanche {@link PublicPlayerData} : les
 * joueurs présents dans un classement et non masqués (#55), leurs XP de pistes, leurs quêtes terminées
 * et leurs rangs. Jamais d'UUID, de position ni de date de connexion — l'export ne les contient pas.
 * Pas d'avatar de skin : il faudrait appeler un service tiers avec l'UUID du joueur ; une initiale en
 * tient lieu. Un pseudo inconnu ou masqué donne la même réponse : « aucun profil public ».</p>
 */
public final class PlayersPageHandler implements ApiHandler {

    /** Forme d'un pseudo Minecraft : 1 à 16 caractères, lettres, chiffres et « _ ». */
    static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    static final int PAGE_SIZE = 60;

    private static final List<String> TRACKS = List.of("COMBAT", "MINING", "FARMING", "FISHING", "EXPLORATION");

    private final SnapshotStore store;
    private final Site site;
    private final SiteRoutes routes;

    public PlayersPageHandler(SnapshotStore store, Site site) {
        this(store, site, SiteRoutes.LIVE);
    }

    public PlayersPageHandler(SnapshotStore store, Site site, SiteRoutes routes) {
        this.store = store;
        this.site = site;
        this.routes = routes;
    }

    @Override
    public int handle(HttpExchange exchange) throws IOException {
        Map<String, Object> live = ServerState.resolve(store);
        ServerStatus serverStatus = ServerStatus.of(live);
        Map<String, Object> state = routes.demo() ? DemoData.state(Instant.now()) : live;
        List<Map<String, Object>> profiles = PublicPlayerData.profiles(state.get("profiles"));

        String path = exchange.getRequestURI().getRawPath();
        String rest = path.length() > routes.players().length() ? path.substring(routes.players().length()) : "";
        rest = rest.startsWith("/") ? rest.substring(1) : rest;
        if (rest.isEmpty()) {
            Map<String, String> query = QueryParams.parse(exchange.getRequestURI().getRawQuery());
            String wanted = query.getOrDefault("pseudo", "").trim();
            if (NAME.matcher(wanted).matches()) {
                exchange.getResponseHeaders().set("Location", routes.players() + "/" + wanted);
                HttpResponses.sendHtml(exchange, 303, "");
                return 303;
            }
            int page = SitePaging.page(query);
            int pages = SitePaging.pageCount(profiles.size(), PAGE_SIZE);
            if (page > pages) {
                return notFound(exchange, serverStatus);
            }
            String description = "Les aventuriers de " + site.title() + " qui ont choisi d'afficher leur profil.";
            HttpResponses.sendHtml(exchange, 200, PageLayout.render(site, routes.demo()
                            ? PageMeta.demo(description, PageMeta.PLAYERS)
                            : new PageMeta(PageMeta.PLAYERS, description, PageMeta.PLAYERS),
                    routes.demo() ? "Joueurs (démonstration)" : "Joueurs", serverStatus,
                    routes.banner() + list(profiles, page, pages)));
            return 200;
        }
        String name = rest.length() <= 48 ? URLDecoder.decode(rest, StandardCharsets.UTF_8) : "";
        Optional<Map<String, Object>> profile = NAME.matcher(name).matches() ? find(profiles, name) : Optional.empty();
        if (profile.isEmpty()) {
            return notFound(exchange, serverStatus);
        }
        String shown = String.valueOf(profile.get().get("name"));
        String description = "Profil public de " + shown + " sur " + site.title() + " : expérience, quêtes et classements.";
        HttpResponses.sendHtml(exchange, 200, PageLayout.render(site, routes.demo()
                        ? PageMeta.demo(description, PageMeta.PLAYERS)
                        : new PageMeta(routes.players() + "/" + URLEncoder.encode(shown, StandardCharsets.UTF_8),
                                description, PageMeta.PLAYERS),
                shown, serverStatus, routes.banner() + detail(profile.get(), state)));
        return 200;
    }

    private int notFound(HttpExchange exchange, ServerStatus serverStatus) throws IOException {
        HttpResponses.sendHtml(exchange, 404, PageLayout.render(site, new PageMeta("", "", PageMeta.PLAYERS,
                routes.demo()), "Joueur", serverStatus, "<p>Aucun profil public pour ce pseudo. Un joueur n'apparaît "
                + "ici que s'il figure dans un classement et a choisi d'afficher son profil.</p><p><a href=\""
                + routes.players() + "\">Tous les profils publics</a></p>"));
        return 404;
    }

    static <T extends Map<?, ?>> Optional<T> find(List<T> profiles, String name) {
        for (T profile : profiles) {
            if (name.equalsIgnoreCase(String.valueOf(profile.get("name")))) {
                return Optional.of(profile);
            }
        }
        return Optional.empty();
    }

    private String list(List<Map<String, Object>> profiles, int page, int pages) {
        StringBuilder body = new StringBuilder("<form method=\"get\" action=\"" + routes.players() + "\" role=\"search\">"
                + "<label for=\"pseudo\">Chercher un pseudo</label> <input id=\"pseudo\" name=\"pseudo\" "
                + "maxlength=\"16\" pattern=\"[A-Za-z0-9_]{1,16}\" autocomplete=\"off\"> <button type=\"submit\">"
                + "Chercher</button></form>");
        if (profiles.isEmpty()) {
            return body.append(PortalIcons.empty("npc", "Aucun profil public pour le moment.")).toString();
        }
        body.append(routes.demo() ? "<p>Joueurs fictifs de démonstration.</p>"
                : "<p>Joueurs présents dans au moins un classement et qui ont choisi d'afficher leur profil. "
                        + "Pour masquer le tien : <code>/profile web prive</code> en jeu.</p>");
        body.append("<ul class=\"grid\" style=\"list-style:none;padding:0\">");
        List<Map<String, Object>> sorted = profiles.stream()
                .sorted(Comparator.comparing(profile -> String.valueOf(profile.get("name")).toLowerCase(Locale.ROOT)))
                .skip((long) (page - 1) * PAGE_SIZE).limit(PAGE_SIZE).toList();
        for (Map<String, Object> profile : sorted) {
            String name = String.valueOf(profile.get("name"));
            Map<?, ?> skills = profile.get("skills") instanceof Map<?, ?> map ? map : Map.of();
            body.append("<li class=\"card player-card\">").append(avatar(name)).append("<div>").append(link(routes, name));
            if (skills.get("GLOBAL") != null) {
                body.append("<br><span class=\"note\">").append(StatLabels.number(skills.get("GLOBAL"))).append(" XP</span>");
            }
            body.append("</div></li>");
        }
        return body.append("</ul>").append(SitePaging.pager(routes.players() + "?", page, pages)).toString();
    }

    /** Lien vers le profil réel (compatibilité des appels historiques). */
    static String link(String name) {
        return link(SiteRoutes.LIVE, name);
    }

    static String link(SiteRoutes routes, String name) {
        return "<a href=\"" + routes.players() + "/" + URLEncoder.encode(name, StandardCharsets.UTF_8) + "\">"
                + Html.escape(name) + "</a>";
    }

    private static String avatar(String name) {
        return "<span class=\"avatar\" aria-hidden=\"true\">" + Html.escape(name.substring(0, 1).toUpperCase(Locale.ROOT))
                + "</span>";
    }

    private String detail(Map<String, Object> profile, Map<String, Object> state) {
        String name = String.valueOf(profile.get("name"));
        Map<?, ?> skills = profile.get("skills") instanceof Map<?, ?> map ? map : Map.of();
        List<String[]> ranks = ranks(name, state);

        StringBuilder body = new StringBuilder("<section class=\"profile-hero\">").append(avatar(name)).append("<div>");
        if (skills.get("GLOBAL") != null) {
            body.append("<p><span class=\"big\">").append(StatLabels.number(skills.get("GLOBAL")))
                    .append(" XP</span> d'expérience totale</p>");
        }
        if (profile.get("questsCompleted") != null) {
            body.append("<p>").append(PortalIcons.icon("scroll", 16)).append(" <strong>")
                    .append(StatLabels.number(profile.get("questsCompleted"))).append("</strong> quêtes terminées</p>");
        }
        ranks.stream().mapToLong(rank -> Long.parseLong(rank[1])).min().ifPresent(best ->
                body.append("<p>").append(PortalIcons.icon("trophy", 16)).append(" Meilleur rang : <strong>")
                        .append(ordinal(best)).append("</strong></p>"));
        body.append("</div></section>");

        body.append(tracks(skills));
        if (!ranks.isEmpty()) {
            body.append(PortalIcons.heading("h2", "trophy", "Médailles de classement")).append("<ul class=\"medals\">");
            for (String[] rank : ranks) {
                long value = Long.parseLong(rank[1]);
                body.append("<li><span class=\"rank").append(BoardView.podium(value)).append("\">").append(value)
                        .append("</span><span>").append(Html.escape(StatLabels.title(rank[0]))).append("<br><span class=\"note\">")
                        .append(ordinal(value)).append("</span></span></li>");
            }
            body.append("</ul>");
        }
        return body.append("<p class=\"note\">Valeurs « depuis toujours »")
                .append(routes.demo() ? " (fictives)." : ", relevées au dernier export du serveur. Seules les catégories "
                        + "où ce joueur figure au classement sont affichées.")
                .append("</p><p><a href=\"").append(routes.players()).append("\">Tous les profils</a> · <a href=\"")
                .append(routes.boards()).append("\">Classements</a></p>").toString();
    }

    /** Pistes de progression, chacune comparée à la plus forte du joueur (aucun niveau recalculé ici). */
    private static String tracks(Map<?, ?> skills) {
        long best = 0;
        for (String track : TRACKS) {
            if (skills.get(track) instanceof Number number) {
                best = Math.max(best, number.longValue());
            }
        }
        if (best == 0) {
            return "";
        }
        StringBuilder html = new StringBuilder(PortalIcons.heading("h2", "xp", "Pistes de progression"))
                .append("<ul class=\"tracks\">");
        for (String track : TRACKS) {
            if (skills.get(track) instanceof Number number) {
                long percent = Math.round(number.longValue() * 100.0 / best);
                html.append("<li>").append(PortalIcons.tile(PortalIcons.forMetric("RPG_XP_" + track), false))
                        .append("<strong>").append(Html.escape(StatLabels.label(track))).append("</strong><span>")
                        .append(StatLabels.number(number)).append(" XP</span><span class=\"meter\" aria-hidden=\"true\">"
                                + "<span style=\"width:").append(percent).append("%\"></span></span></li>");
            }
        }
        return html.append("</ul><p class=\"note\">Chaque barre est comparée à la piste la plus forte du joueur.</p>")
                .toString();
    }

    private static String ordinal(long rank) {
        return rank + (rank == 1 ? "<sup>er</sup>" : "<sup>e</sup>");
    }

    /** Rangs du joueur dans les classements « depuis toujours » où il figure : {métrique, rang}. */
    private static List<String[]> ranks(String name, Map<String, Object> state) {
        Map<String, Object> statistics = PublicPlayerData.statistics(state.get("statistics"));
        List<String[]> ranks = new java.util.ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> board
                : LeaderboardsPageHandler.boards(statistics.get("lifetime")).entrySet()) {
            List<Map<String, Object>> rows = board.getValue();
            for (int i = 0; i < rows.size(); i++) {
                if (name.equals(rows.get(i).get("name"))) {
                    Object rank = rows.get(i).get("rank") instanceof Number number ? number : i + 1;
                    ranks.add(new String[] {board.getKey(), String.valueOf(((Number) rank).longValue())});
                    break;
                }
            }
        }
        return ranks;
    }
}
