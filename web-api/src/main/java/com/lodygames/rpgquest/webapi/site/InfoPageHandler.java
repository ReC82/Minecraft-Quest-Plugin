package com.lodygames.rpgquest.webapi.site;

import com.lodygames.rpgquest.webapi.SiteSettings;
import com.lodygames.rpgquest.webapi.http.ApiHandler;
import com.lodygames.rpgquest.webapi.http.HttpResponses;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;

/**
 * {@code GET /infos} — rejoindre le serveur, premiers pas, règles de base et FAQ (#56).
 *
 * <p>Page statique, lisible sans le serveur de jeu. L'adresse et la version viennent de
 * {@code server-address} / {@code minecraft-version} ; vides, la page le dit au lieu d'inventer.</p>
 */
public final class InfoPageHandler implements ApiHandler {

    private final Site site;
    private final SiteSettings settings;

    public InfoPageHandler(Site site, SiteSettings settings) {
        this.site = site;
        this.settings = settings;
    }

    @Override
    public int handle(HttpExchange exchange) throws IOException {
        if (!"/infos".equals(exchange.getRequestURI().getPath())) {
            HttpResponses.sendHtml(exchange, 404, PageLayout.render(site, PageMeta.none(), "Page introuvable", true,
                    "<p>Cette page n'existe pas.</p><p><a href=\"/infos\">Infos &amp; FAQ</a></p>"));
            return 404;
        }
        HttpResponses.sendHtml(exchange, 200, PageLayout.render(site, new PageMeta(PageMeta.INFO,
                "Comment rejoindre " + site.title() + ", premiers pas, règles de base et questions fréquentes.",
                PageMeta.INFO), "Infos & FAQ", true, join() + firstSteps() + rules() + faq()));
        return 200;
    }

    private String join() {
        String address = settings.serverAddress().isEmpty()
                ? "<p>L'adresse du serveur sera communiquée ici dès son ouverture au public.</p>"
                : "<p>Adresse du serveur : <code>" + Html.escape(settings.serverAddress()) + "</code></p>";
        String version = settings.minecraftVersion().isEmpty()
                ? ""
                : "<p>Version de Minecraft : <strong>" + Html.escape(settings.minecraftVersion()) + "</strong> (Java Edition).</p>";
        return "<section id=\"rejoindre\">" + PortalIcons.heading("h2", "waypoint", "Rejoindre le serveur") + address + version
                + "<ol><li>Lance Minecraft Java Edition.</li><li>Va dans <em>Multijoueur</em> puis "
                + "<em>Ajouter un serveur</em>.</li><li>Colle l'adresse du serveur, valide, et rejoins la partie.</li></ol>"
                + "</section>";
    }

    private static String firstSteps() {
        return "<section>" + PortalIcons.heading("h2", "npc", "Premiers pas en jeu") + "<ul>"
                + "<li>Parle aux personnages (clic droit) : certains te proposent des quêtes.</li>"
                + "<li><code>/quests</code> ouvre ton journal de quêtes.</li>"
                + "<li><code>/profile</code> résume tes niveaux ; <code>/skills</code> détaille tes compétences.</li>"
                + "<li><code>/profile web public</code> affiche ton profil et ton nom dans les classements de ce site ; "
                + "<code>/profile web prive</code> les masque à nouveau. Par défaut, ton profil est privé.</li>"
                + "</ul></section>";
    }

    private static String rules() {
        return "<section>" + PortalIcons.heading("h2", "shield", "Règles de base") + "<ul>"
                + "<li>Respecte les autres joueurs : pas d'insultes, pas de harcèlement.</li>"
                + "<li>Ne partage jamais tes informations personnelles (nom, adresse, école, mot de passe).</li>"
                + "<li>Pas de triche ni de logiciel qui donne un avantage.</li>"
                + "<li>Un souci avec un joueur ? Préviens l'équipe du serveur.</li>"
                + "</ul></section>";
    }

    private String faq() {
        return "<section>" + PortalIcons.heading("h2", "talk", "Questions fréquentes")
                + "<h3>Faut-il un compte sur ce site pour jouer ?</h3><p>Non. Le site sert à suivre la vie du serveur ; "
                + "on joue directement dans Minecraft.</p>"
                + "<h3>Pourquoi je ne me vois pas dans les classements ?</h3><p>Ton profil est privé par défaut. Tape "
                + "<code>/profile web public</code> en jeu pour apparaître, puis attends la prochaine mise à jour du site. "
                + "Il faut aussi avoir progressé assez pour entrer dans un classement.</p>"
                + "<h3>Qu'est-ce que le site montre sur moi ?</h3><p>Seulement ton pseudo Minecraft, tes points "
                + "d'expérience et ton nombre de quêtes terminées, et seulement si tu l'as choisi. Jamais ta position, "
                + "ton inventaire ou tes heures de connexion. Détails : <a href=\"/confidentialite\">confidentialité</a>.</p>"
                + "<h3>Le site dit « état inconnu » ou « hors ligne ».</h3><p>« État inconnu » : le site n'a pas reçu de "
                + "nouvelles récentes du serveur de jeu, qui tourne peut-être normalement. « Hors ligne » : le serveur "
                + "a lui-même annoncé son arrêt, souvent pour redémarrer. Les actualités restent lisibles en attendant.</p>"
                + "<h3>Que sont les saisons et les événements ?</h3><p>Des classements limités dans le temps : seule "
                + "l'expérience gagnée pendant la période compte. Ils apparaissent sur la page "
                + "<a href=\"/classements\">Classements</a> quand " + Html.escape(site.title()) + " en organise.</p>"
                + "</section>";
    }
}
