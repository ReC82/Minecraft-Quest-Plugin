package com.lodygames.rpgquest.webapi.site;

import com.lodygames.rpgquest.webapi.ServerState;
import com.lodygames.rpgquest.webapi.SnapshotStore;
import com.lodygames.rpgquest.webapi.http.ApiHandler;
import com.lodygames.rpgquest.webapi.http.HttpResponses;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;

/**
 * {@code GET /confidentialite} — ce que le portail publie, et comment s'en retirer (issue #55).
 *
 * <p>Texte factuel décrivant le comportement réel du code, pas une politique juridique : celle-ci
 * reste à valider avant toute ouverture publique (voir {@code docs/WEB_PORTAL.md}).</p>
 */
public final class PrivacyPageHandler implements ApiHandler {

    private final SnapshotStore store;
    private final String siteTitle;

    public PrivacyPageHandler(SnapshotStore store, String siteTitle) {
        this.store = store;
        this.siteTitle = siteTitle;
    }

    @Override
    public int handle(HttpExchange exchange) throws IOException {
        boolean online = ServerState.isOnline(ServerState.resolve(store));
        String body = """
                <h2>Ce que ce site publie</h2>
                <ul>
                <li>le <strong>pseudo Minecraft</strong> des joueurs qui figurent dans un classement ;</li>
                <li>leur <strong>XP par piste</strong> et leur <strong>nombre de quêtes terminées</strong>.</li>
                </ul>
                <h2>Ce qu'il ne publie jamais</h2>
                <ul>
                <li>l'identifiant technique (UUID), l'adresse IP, la position en jeu, l'inventaire ;</li>
                <li>les dates et heures de connexion ;</li>
                <li>aucune adresse e-mail : il n'existe pas encore de compte joueur sur ce site.</li>
                </ul>
                <h2>Se retirer</h2>
                <p>En jeu : <code>/profile web prive</code>. Le profil et les classements cessent de te
                montrer dès la prochaine mise à jour du site (quelques secondes à quelques minutes).
                <code>/profile web public</code> annule ce choix ; <code>/profile web</code> affiche ton réglage.</p>
                <h2>Comptes et mineurs</h2>
                <p>Ce site n'a ni compte, ni connexion, ni paiement. Avant leur ouverture, une politique de
                confidentialité complète — âge minimal, consentement parental, droits d'accès et d'effacement —
                sera publiée ici.</p>
                """;
        HttpResponses.sendHtml(exchange, 200, PageLayout.render(siteTitle, "Confidentialité", online, body));
        return 200;
    }
}
