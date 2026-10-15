package com.lodygames.rpgquest.webapi.site;

import com.lodygames.rpgquest.webapi.ServerState;
import com.lodygames.rpgquest.webapi.SnapshotStore;
import com.lodygames.rpgquest.webapi.http.ApiHandler;
import com.lodygames.rpgquest.webapi.http.HttpResponses;
import com.lodygames.rpgquest.webapi.privacy.AgePolicy;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;

/**
 * {@code GET /confidentialite} — ce que le portail publie, et comment s'en retirer (issue #55).
 *
 * <p>Texte factuel décrivant le comportement réel du code, pas une politique juridique : celle-ci
 * reste à valider avant toute ouverture publique (voir {@code docs/PRIVACY.md}). Les deux phrases
 * d'état (pseudos publiés ou non, comptes ouverts ou non) suivent la configuration réelle.</p>
 */
public final class PrivacyPageHandler implements ApiHandler {

    private final SnapshotStore store;
    private final String siteTitle;
    private final boolean playerDataPublic;
    private final AgePolicy agePolicy;

    public PrivacyPageHandler(SnapshotStore store, String siteTitle, boolean playerDataPublic, AgePolicy agePolicy) {
        this.store = store;
        this.siteTitle = siteTitle;
        this.playerDataPublic = playerDataPublic;
        this.agePolicy = agePolicy;
    }

    @Override
    public int handle(HttpExchange exchange) throws IOException {
        boolean online = ServerState.isOnline(ServerState.resolve(store));
        String publication = playerDataPublic
                ? "<p><strong>Actuellement :</strong> les pseudos des joueurs qui l'ont accepté sont publiés.</p>"
                : "<p><strong>Actuellement :</strong> aucun pseudo n'est publié sur ce site.</p>";
        String accounts = agePolicy.accountsOpen()
                ? "<p>Les comptes sont soumis à un âge minimal selon le pays ; en dessous, l'accord d'un parent est "
                        + "demandé et le compte reste limité tant qu'il manque.</p>"
                : "<p>Ce site n'a ni compte, ni connexion, ni paiement.</p>";
        String body = """
                <h2>Ce que ce site publie</h2>
                %s
                <ul>
                <li>le <strong>pseudo Minecraft</strong> des joueurs qui ont choisi de se montrer ;</li>
                <li>leur <strong>XP par piste</strong> et leur <strong>nombre de quêtes terminées</strong>.</li>
                </ul>
                <h2>Ce qu'il ne publie jamais</h2>
                <ul>
                <li>l'identifiant technique (UUID), l'adresse IP, la position en jeu, l'inventaire ;</li>
                <li>les dates et heures de connexion ;</li>
                <li>ni adresse e-mail, ni âge, ni date de naissance.</li>
                </ul>
                <h2>Ton choix</h2>
                <p>Par défaut, ton profil est <strong>privé</strong> : tu n'apparais nulle part tant que tu ne
                l'as pas demandé. En jeu : <code>/profile web public</code> pour te montrer,
                <code>/profile web prive</code> pour te retirer (effet à la prochaine mise à jour du site,
                quelques secondes à quelques minutes), <code>/profile web</code> pour voir ton réglage.</p>
                <h2>Comptes et mineurs</h2>
                %s
                <p>Avant l'ouverture des comptes, une politique de confidentialité complète — âge minimal,
                consentement parental, accès, export et effacement de tes données — sera publiée ici.</p>
                """.formatted(publication, accounts);
        HttpResponses.sendHtml(exchange, 200, PageLayout.render(siteTitle, "Confidentialité", online, body));
        return 200;
    }
}
