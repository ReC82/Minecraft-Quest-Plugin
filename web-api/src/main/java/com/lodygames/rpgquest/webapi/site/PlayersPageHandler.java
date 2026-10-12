package com.lodygames.rpgquest.webapi.site;

import com.lodygames.rpgquest.webapi.ServerState;
import com.lodygames.rpgquest.webapi.SnapshotStore;
import com.lodygames.rpgquest.webapi.http.ApiHandler;
import com.lodygames.rpgquest.webapi.http.HttpResponses;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * {@code GET /joueurs} et {@code GET /joueurs/<pseudo>} — profils publics limités (issue #56).
 *
 * <p>Ne montre que ce que l'instantané publie : les joueurs présents dans un classement et non
 * masqués (#55), avec leurs XP de pistes et leurs quêtes terminées. Jamais d'UUID, de position ou de
 * date de connexion — l'export ne les contient pas. Un pseudo inconnu ou masqué donne la même
 * réponse : « aucun profil public », sans dire s'il existe.</p>
 */
public final class PlayersPageHandler implements ApiHandler {

    /** Forme d'un pseudo Minecraft : 1 à 16 caractères, lettres, chiffres et « _ ». */
    static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");

    private final SnapshotStore store;
    private final String siteTitle;

    public PlayersPageHandler(SnapshotStore store, String siteTitle) {
        this.store = store;
        this.siteTitle = siteTitle;
    }

    @Override
    public int handle(HttpExchange exchange) throws IOException {
        Map<String, Object> state = ServerState.resolve(store);
        boolean online = ServerState.isOnline(state);
        List<?> profiles = state.get("profiles") instanceof List<?> list ? list : List.of();

        String path = exchange.getRequestURI().getRawPath();
        String rest = path.length() > "/joueurs".length() ? path.substring("/joueurs".length()) : "";
        rest = rest.startsWith("/") ? rest.substring(1) : rest;
        if (rest.isEmpty()) {
            HttpResponses.sendHtml(exchange, 200, PageLayout.render(siteTitle, "Joueurs", online, list(profiles)));
            return 200;
        }
        String name = URLDecoder.decode(rest, StandardCharsets.UTF_8);
        Optional<Map<?, ?>> profile = NAME.matcher(name).matches() ? find(profiles, name) : Optional.empty();
        if (profile.isEmpty()) {
            HttpResponses.sendHtml(exchange, 404, PageLayout.render(siteTitle, "Joueur", online,
                    "<p>Aucun profil public pour ce pseudo. Un joueur n'apparaît ici que s'il figure dans un "
                            + "classement et n'a pas masqué son profil.</p><p><a href=\"/joueurs\">Tous les "
                            + "profils publics</a></p>"));
            return 404;
        }
        HttpResponses.sendHtml(exchange, 200, PageLayout.render(siteTitle, String.valueOf(profile.get().get("name")),
                online, detail(profile.get())));
        return 200;
    }

    static Optional<Map<?, ?>> find(List<?> profiles, String name) {
        for (Object raw : profiles) {
            if (raw instanceof Map<?, ?> profile && name.equalsIgnoreCase(String.valueOf(profile.get("name")))) {
                return Optional.of(profile);
            }
        }
        return Optional.empty();
    }

    private static String list(List<?> profiles) {
        if (profiles.isEmpty()) {
            return "<p><em>Aucun profil public pour le moment.</em></p>";
        }
        StringBuilder body = new StringBuilder("<p>Joueurs présents dans au moins un classement. Un joueur peut "
                + "masquer son profil en jeu : <code>/profile web prive</code>.</p><ul>");
        for (Object raw : profiles) {
            if (raw instanceof Map<?, ?> profile) {
                body.append("<li>").append(link(String.valueOf(profile.get("name")))).append("</li>");
            }
        }
        return body.append("</ul>").toString();
    }

    static String link(String name) {
        return "<a href=\"/joueurs/" + URLEncoder.encode(name, StandardCharsets.UTF_8) + "\">"
                + Html.escape(name) + "</a>";
    }

    private static String detail(Map<?, ?> profile) {
        StringBuilder body = new StringBuilder("<table><tbody>");
        if (profile.get("skills") instanceof Map<?, ?> skills) {
            for (Map.Entry<?, ?> skill : skills.entrySet()) {
                body.append("<tr><th>XP ").append(Html.escape(LeaderboardsPageHandler.label(String.valueOf(skill.getKey()))))
                        .append("</th><td>").append(Html.escape(String.valueOf(skill.getValue()))).append("</td></tr>");
            }
        }
        if (profile.get("questsCompleted") != null) {
            body.append("<tr><th>Quêtes terminées</th><td>")
                    .append(Html.escape(String.valueOf(profile.get("questsCompleted")))).append("</td></tr>");
        }
        return body.append("</tbody></table><p class=\"note\">Valeurs « depuis toujours », telles que relevées "
                + "au dernier export du serveur. Seules les catégories où ce joueur figure au classement sont "
                + "affichées.</p><p><a href=\"/joueurs\">Tous les profils publics</a></p>").toString();
    }
}
