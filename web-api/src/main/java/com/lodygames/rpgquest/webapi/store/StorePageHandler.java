package com.lodygames.rpgquest.webapi.store;

import com.lodygames.rpgquest.webapi.http.ApiHandler;
import com.lodygames.rpgquest.webapi.http.HttpResponses;
import com.lodygames.rpgquest.webapi.site.Html;
import com.lodygames.rpgquest.webapi.site.PageLayout;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.List;

/**
 * {@code GET /store} — vitrine publique (mission point 8, "site minimal").
 * Aucune donnée de carte bancaire n'est jamais demandée ici (mission point
 * 4) : le formulaire ne demande que le produit et l'UUID Minecraft du
 * joueur à créditer.
 */
public final class StorePageHandler implements ApiHandler {

    private final StoreService store;
    private final String siteTitle;

    public StorePageHandler(StoreService store, String siteTitle) {
        this.store = store;
        this.siteTitle = siteTitle;
    }

    @Override
    public int handle(HttpExchange exchange) throws IOException {
        List<Product> products = store.catalog().all();

        StringBuilder body = new StringBuilder();
        body.append("<p>Boutique de confort — voir <a href=\"#policy\">notre politique pay-to-convenience</a> "
                + "avant d'acheter.</p>");
        if (products.isEmpty()) {
            body.append("<p><em>Aucun produit disponible pour le moment.</em></p>");
        }
        for (Product product : products) {
            body.append("<form method=\"post\" action=\"/store/checkout\">");
            body.append("<h3>").append(Html.escape(product.name())).append("</h3>");
            body.append("<p>").append(Html.escape(Money.format(product.priceCents(), product.currency()))).append("</p>");
            body.append("<input type=\"hidden\" name=\"productId\" value=\"").append(Html.escape(product.id())).append("\">");
            body.append("<label>UUID Minecraft : <input type=\"text\" name=\"playerUuid\" required></label><br>");
            body.append("<label>Pseudo (facultatif) : <input type=\"text\" name=\"playerName\"></label><br>");
            body.append("<button type=\"submit\">Acheter (sandbox)</button>");
            body.append("</form>");
        }
        body.append("<h2 id=\"policy\">Politique pay-to-convenience</h2>");
        body.append("<p>Uniquement du confort (stockage, cosmétique) — jamais d'avantage de combat, de vitesse "
                + "ou de dégâts. Environnement de paiement sandbox/test uniquement à ce stade. "
                + "Voir docs/STORE.md.</p>");

        HttpResponses.sendHtml(exchange, 200, PageLayout.render(siteTitle, "Boutique", true, body.toString()));
        return 200;
    }
}
