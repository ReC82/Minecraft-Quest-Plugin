package be.lloyd.rpgquest.webapi.store;

import be.lloyd.rpgquest.webapi.http.ApiHandler;
import be.lloyd.rpgquest.webapi.http.HttpResponses;
import be.lloyd.rpgquest.webapi.http.MalformedRequestException;
import be.lloyd.rpgquest.webapi.http.QueryParams;
import be.lloyd.rpgquest.webapi.site.Html;
import be.lloyd.rpgquest.webapi.site.PageLayout;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** {@code POST /store/checkout} — crée une commande {@code PENDING} et redirige vers la session de paiement sandbox. */
public final class CheckoutHandler implements ApiHandler {

    private final StoreService store;
    private final String siteTitle;

    public CheckoutHandler(StoreService store, String siteTitle) {
        this.store = store;
        this.siteTitle = siteTitle;
    }

    @Override
    public int handle(HttpExchange exchange) throws IOException {
        String rawBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> form = QueryParams.parse(rawBody);
        String productId = form.get("productId");
        String playerUuid = form.get("playerUuid");
        String playerName = form.get("playerName");
        if (productId == null || productId.isBlank() || playerUuid == null || playerUuid.isBlank()) {
            throw new MalformedRequestException("« productId » et « playerUuid » sont requis.");
        }

        CheckoutResult result = store.checkout(productId, playerUuid, playerName).join();
        return switch (result.outcome()) {
            case CREATED -> {
                exchange.getResponseHeaders().set("Location", result.redirectUrl());
                exchange.sendResponseHeaders(302, -1);
                yield 302;
            }
            case UNKNOWN_PRODUCT -> {
                HttpResponses.sendHtml(exchange, 404, errorPage("Produit inconnu."));
                yield 404;
            }
            case INVALID_PLAYER_UUID -> {
                HttpResponses.sendHtml(exchange, 400, errorPage("UUID de joueur invalide — doit être un UUID Minecraft complet."));
                yield 400;
            }
        };
    }

    private String errorPage(String message) {
        return PageLayout.render(siteTitle, "Erreur", true,
                "<p>" + Html.escape(message) + "</p><p><a href=\"/store\">Retour à la boutique</a></p>");
    }
}
