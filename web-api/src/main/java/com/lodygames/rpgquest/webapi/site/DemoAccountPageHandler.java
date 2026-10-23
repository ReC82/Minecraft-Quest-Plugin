package com.lodygames.rpgquest.webapi.site;

import com.lodygames.rpgquest.webapi.account.LinkedProgressView;
import com.lodygames.rpgquest.webapi.http.ApiHandler;
import com.lodygames.rpgquest.webapi.http.HttpResponses;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.time.Instant;

/**
 * {@code /demo/compte} (issue #53) : aperçu de la page « Mon compte » d'un joueur relié, avec la
 * progression <strong>fictive</strong> de {@code Demo_Aube} ({@link DemoData}). Bandeau de
 * démonstration et {@code noindex} ; aucun compte, aucune session, aucune donnée du serveur de jeu.
 */
public final class DemoAccountPageHandler implements ApiHandler {

    public static final String PATH = "/demo/compte";

    private final Site site;

    public DemoAccountPageHandler(Site site) {
        this.site = site;
    }

    @Override
    public int handle(HttpExchange exchange) throws IOException {
        Instant now = Instant.now();
        String body = DemoData.BANNER
                + "<p>Connecté en tant que <strong>aube-demo</strong>.</p>"
                + "<h2>Compte Minecraft</h2><p>Relié à <strong>" + Html.escape(DemoData.PREFIX + "Aube")
                + "</strong> grâce au code tapé en jeu avec <code>/lien</code>.</p>"
                + LinkedProgressView.render(DemoData.PREFIX + "Aube", DemoData.personalProgress(now), now, true)
                + "<p><a href=\"/compte\">Mon vrai compte</a> · <a href=\"" + SiteRoutes.DEMO.boards()
                + "\">Classements (démonstration)</a></p>";
        HttpResponses.sendHtml(exchange, 200, PageLayout.render(site,
                PageMeta.demo("Aperçu de l'espace joueur relié, avec des données fictives.", PageMeta.ACCOUNT),
                "Mon compte (démonstration)", true, body));
        return 200;
    }
}
