package com.lodygames.rpgquest.webapi.site;

import com.lodygames.rpgquest.webapi.ServerState;
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

    private final SnapshotStore store;
    private final Site site;

    public PlayersPageHandler(SnapshotStore store, Site site) {
        this.store = store;
        this.site = site;
    }

    @Override
    public int handle(HttpExchange exchange) throws IOException {
        Map<String, Object> state = ServerState.resolve(store);
        boolean online = ServerState.isOnline(state);
        List<Map<String, Object>> profiles = PublicPlayerData.profiles(state.get("profiles"));

        String path = exchange.getRequestURI().getRawPath();
        String rest = path.length() > "/joueurs".length() ? path.substring("/joueurs".length()) : "";
        rest = rest.startsWith("/") ? rest.substring(1) : rest;
        if (rest.isEmpty()) {
            Map<String, String> query = QueryParams.parse(exchange.getRequestURI().getRawQuery());
            String wanted = query.getOrDefault("pseudo", "").trim();
            if (NAME.matcher(wanted).matches()) {
                exchange.getResponseHeaders().set("Location", "/joueurs/" + wanted);
                HttpResponses.sendHtml(exchange, 303, "");
                return 303;
            }
            int page = SitePaging.page(query);
            int pages = SitePaging.pageCount(profiles.size(), PAGE_SIZE);
            if (page > pages) {
                return notFound(exchange, online);
            }
            HttpResponses.sendHtml(exchange, 200, PageLayout.render(site, new PageMeta(PageMeta.PLAYERS,
                    "Les aventuriers de " + site.title() + " qui ont choisi d'afficher leur profil.", PageMeta.PLAYERS),
                    "Joueurs", online, list(profiles, page, pages)));
            return 200;
        }
        String name = rest.length() <= 48 ? URLDecoder.decode(rest, StandardCharsets.UTF_8) : "";
        Optional<Map<String, Object>> profile = NAME.matcher(name).matches() ? find(profiles, name) : Optional.empty();
        if (profile.isEmpty()) {
            return notFound(exchange, online);
        }
        String shown = String.valueOf(profile.get().get("name"));
        HttpResponses.sendHtml(exchange, 200, PageLayout.render(site, new PageMeta(
                "/joueurs/" + URLEncoder.encode(shown, StandardCharsets.UTF_8),
                "Profil public de " + shown + " sur " + site.title() + " : expérience, quêtes et classements.",
                PageMeta.PLAYERS), shown, online, detail(profile.get(), state)));
        return 200;
    }

    private int notFound(HttpExchange exchange, boolean online) throws IOException {
        HttpResponses.sendHtml(exchange, 404, PageLayout.render(site, new PageMeta("", "", PageMeta.PLAYERS), "Joueur",
                online, "<p>Aucun profil public pour ce pseudo. Un joueur n'apparaît ici que s'il figure dans un "
                        + "classement et a choisi d'afficher son profil.</p><p><a href=\"/joueurs\">Tous les "
                        + "profils publics</a></p>"));
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

    private static String list(List<Map<String, Object>> profiles, int page, int pages) {
        StringBuilder body = new StringBuilder("<form method=\"get\" action=\"/joueurs\" role=\"search\">"
                + "<label for=\"pseudo\">Chercher un pseudo</label> <input id=\"pseudo\" name=\"pseudo\" "
                + "maxlength=\"16\" pattern=\"[A-Za-z0-9_]{1,16}\" autocomplete=\"off\"> <button type=\"submit\">"
                + "Chercher</button></form>");
        if (profiles.isEmpty()) {
            return body.append("<p class=\"note\">Aucun profil public pour le moment.</p>").toString();
        }
        body.append("<p>Joueurs présents dans au moins un classement et qui ont choisi d'afficher leur profil. "
                + "Pour masquer le tien : <code>/profile web prive</code> en jeu.</p><ul class=\"grid\" "
                + "style=\"list-style:none;padding:0\">");
        List<Map<String, Object>> sorted = profiles.stream()
                .sorted(Comparator.comparing(profile -> String.valueOf(profile.get("name")).toLowerCase(Locale.ROOT)))
                .skip((long) (page - 1) * PAGE_SIZE).limit(PAGE_SIZE).toList();
        for (Map<String, Object> profile : sorted) {
            body.append("<li class=\"card\">").append(link(String.valueOf(profile.get("name")))).append("</li>");
        }
        return body.append("</ul>").append(SitePaging.pager("/joueurs?", page, pages)).toString();
    }

    static String link(String name) {
        return "<a href=\"/joueurs/" + URLEncoder.encode(name, StandardCharsets.UTF_8) + "\">"
                + Html.escape(name) + "</a>";
    }

    private static String detail(Map<String, Object> profile, Map<String, Object> state) {
        String name = String.valueOf(profile.get("name"));
        StringBuilder body = new StringBuilder("<p><span class=\"avatar\" aria-hidden=\"true\">")
                .append(Html.escape(name.substring(0, 1).toUpperCase(Locale.ROOT))).append("</span></p>");
        body.append("<div class=\"stats\">");
        Map<?, ?> skills = profile.get("skills") instanceof Map<?, ?> map ? map : Map.of();
        if (skills.get("GLOBAL") != null) {
            body.append(stat("Expérience totale", StatLabels.number(skills.get("GLOBAL")) + " XP"));
        }
        if (profile.get("questsCompleted") != null) {
            body.append(stat("Quêtes terminées", StatLabels.number(profile.get("questsCompleted"))));
        }
        for (Map.Entry<?, ?> skill : skills.entrySet()) {
            if (!"GLOBAL".equals(skill.getKey())) {
                body.append(stat("XP " + StatLabels.label(String.valueOf(skill.getKey())),
                        StatLabels.number(skill.getValue()) + " XP"));
            }
        }
        body.append("</div>");
        body.append(ranks(name, state));
        return body.append("<p class=\"note\">Valeurs « depuis toujours », relevées au dernier export du serveur. "
                + "Seules les catégories où ce joueur figure au classement sont affichées.</p>"
                + "<p><a href=\"/joueurs\">Tous les profils publics</a> · <a href=\"/classements\">Classements</a></p>")
                .toString();
    }

    private static String stat(String label, String value) {
        return "<div class=\"stat\"><strong>" + Html.escape(value) + "</strong>" + Html.escape(label) + "</div>";
    }

    /** Rangs du joueur dans les classements « depuis toujours » où il figure. */
    private static String ranks(String name, Map<String, Object> state) {
        Map<String, Object> statistics = PublicPlayerData.statistics(state.get("statistics"));
        StringBuilder items = new StringBuilder();
        for (Map.Entry<String, List<Map<String, Object>>> board
                : LeaderboardsPageHandler.boards(statistics.get("lifetime")).entrySet()) {
            List<Map<String, Object>> rows = board.getValue();
            for (int i = 0; i < rows.size(); i++) {
                if (name.equals(rows.get(i).get("name"))) {
                    Object rank = rows.get(i).get("rank") instanceof Number number ? number : i + 1;
                    items.append("<li>").append(Html.escape(StatLabels.title(board.getKey()))).append(" : <strong>")
                            .append(rank).append(rank.toString().equals("1") ? "<sup>er</sup>" : "<sup>e</sup>")
                            .append("</strong></li>");
                    break;
                }
            }
        }
        return items.isEmpty() ? "" : "<h2>Classements</h2><ul>" + items + "</ul>";
    }
}
