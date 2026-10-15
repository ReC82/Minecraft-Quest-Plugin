package com.lodygames.rpgquest.webapi.site;

import com.lodygames.rpgquest.webapi.http.ApiHandler;
import com.lodygames.rpgquest.webapi.http.HttpResponses;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;

/**
 * Remplace les pages qui montreraient des pseudos ({@code /joueurs}, {@code /leaderboards}) tant
 * que {@code player-data-public=false} (#52/#55) : aucune donnée joueur n'est lue, même si un
 * instantané est présent sur le disque.
 */
public final class UnpublishedPageHandler implements ApiHandler {

    private final Site site;
    private final String pageTitle;
    private final String section;

    public UnpublishedPageHandler(Site site, String pageTitle, String section) {
        this.site = site;
        this.pageTitle = pageTitle;
        this.section = section;
    }

    @Override
    public int handle(HttpExchange exchange) throws IOException {
        HttpResponses.sendHtml(exchange, 200, PageLayout.render(site, new PageMeta("", "", section), pageTitle, true,
                "<p>Cette page n'est pas encore publiée : les profils et classements des joueurs "
                        + "n'apparaîtront qu'après validation des règles de confidentialité.</p>"
                        + "<p><a href=\"/confidentialite\">En savoir plus</a></p>"));
        return 200;
    }
}
