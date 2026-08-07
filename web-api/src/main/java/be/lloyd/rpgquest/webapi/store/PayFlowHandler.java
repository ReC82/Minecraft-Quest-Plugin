package be.lloyd.rpgquest.webapi.store;

import be.lloyd.rpgquest.webapi.http.ApiHandler;
import be.lloyd.rpgquest.webapi.http.HttpResponses;
import be.lloyd.rpgquest.webapi.site.Html;
import be.lloyd.rpgquest.webapi.site.PageLayout;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;

/**
 * Registré sur le préfixe {@code /store/pay/} : simule la page de paiement
 * hébergée d'un vrai prestataire (mission points 3/14, sandbox). Aucune
 * carte bancaire n'est jamais demandée (point 4) — seulement "Payer
 * (sandbox)" ou "Simuler un échec".
 */
public final class PayFlowHandler implements ApiHandler {

    private static final String PREFIX = "/store/pay/";

    private final StoreService store;
    private final String siteTitle;

    public PayFlowHandler(StoreService store, String siteTitle) {
        this.store = store;
        this.siteTitle = siteTitle;
    }

    @Override
    public int handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String remainder = path.startsWith(PREFIX) ? path.substring(PREFIX.length()) : "";
        String method = exchange.getRequestMethod();

        if ("GET".equals(method) && !remainder.isBlank() && !remainder.contains("/")) {
            return showPayPage(exchange, remainder);
        }
        if ("POST".equals(method) && remainder.endsWith("/confirm")) {
            return handleOutcome(exchange, stripSuffix(remainder, "/confirm"), true);
        }
        if ("POST".equals(method) && remainder.endsWith("/fail")) {
            return handleOutcome(exchange, stripSuffix(remainder, "/fail"), false);
        }
        HttpResponses.sendJson(exchange, 404, Map.of("error", "not_found"));
        return 404;
    }

    private String stripSuffix(String value, String suffix) {
        return value.substring(0, value.length() - suffix.length());
    }

    private int showPayPage(HttpExchange exchange, String sessionId) throws IOException {
        Optional<Order> orderOpt = store.orderForSession(sessionId).join();
        if (orderOpt.isEmpty()) {
            HttpResponses.sendHtml(exchange, 404, PageLayout.render(siteTitle, "Session introuvable", true,
                    "<p>Session de paiement introuvable ou expirée.</p>"));
            return 404;
        }
        Order order = orderOpt.get();
        String body = "<p>Commande <code>" + Html.escape(order.id()) + "</code> — "
                + Html.escape(Money.format(order.amountCents(), order.currency())) + "</p>"
                + "<p><em>Environnement sandbox : aucune carte bancaire n'est demandée ni stockée.</em></p>"
                + "<form method=\"post\" action=\"/store/pay/" + Html.escape(sessionId) + "/confirm\">"
                + "<button type=\"submit\">Payer (sandbox)</button></form>"
                + "<form method=\"post\" action=\"/store/pay/" + Html.escape(sessionId) + "/fail\">"
                + "<button type=\"submit\">Simuler un échec</button></form>";
        HttpResponses.sendHtml(exchange, 200, PageLayout.render(siteTitle, "Paiement (sandbox)", true, body));
        return 200;
    }

    private int handleOutcome(HttpExchange exchange, String sessionId, boolean succeeded) throws IOException {
        boolean dispatched = succeeded ? store.confirmPayment(sessionId).join() : store.failPayment(sessionId).join();
        if (!dispatched) {
            HttpResponses.sendHtml(exchange, 404,
                    PageLayout.render(siteTitle, "Session introuvable", true, "<p>Session de paiement introuvable.</p>"));
            return 404;
        }
        String message = succeeded
                ? "Paiement confirmé (sandbox). La livraison sera traitée par le serveur de jeu au prochain sondage."
                : "Paiement simulé en échec — aucune livraison ne sera créée.";
        HttpResponses.sendHtml(exchange, 200, PageLayout.render(siteTitle, "Résultat", true, "<p>" + Html.escape(message) + "</p>"));
        return 200;
    }
}
