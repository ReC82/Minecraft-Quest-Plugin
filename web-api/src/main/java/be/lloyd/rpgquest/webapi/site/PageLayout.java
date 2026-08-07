package be.lloyd.rpgquest.webapi.site;

/** Squelette HTML commun aux 4 pages du portail minimal (mission point 8). */
public final class PageLayout {

    private PageLayout() {
    }

    public static String render(String siteTitle, String pageTitle, boolean online, String bodyHtml) {
        String banner = online
                ? ""
                : "<p class=\"offline\">Serveur hors-ligne — données indisponibles pour le moment.</p>";
        return """
                <!doctype html>
                <html lang="fr">
                <head>
                <meta charset="utf-8">
                <title>%s — %s</title>
                <style>
                body { font-family: system-ui, sans-serif; max-width: 800px; margin: 2rem auto; padding: 0 1rem; background:#12141a; color:#e8e8e8; }
                a { color: #7fd0ff; }
                nav a { margin-right: 1rem; }
                .offline { background:#5a1e1e; padding: .5rem 1rem; border-radius: .25rem; }
                table { border-collapse: collapse; width: 100%%; margin-top: 1rem; }
                th, td { text-align: left; padding: .25rem .5rem; border-bottom: 1px solid #333; }
                </style>
                </head>
                <body>
                <nav><a href="/">Accueil</a><a href="/status">Statut</a><a href="/leaderboards">Classements</a><a href="/wiki">Wiki</a></nav>
                <h1>%s</h1>
                %s
                %s
                </body>
                </html>
                """.formatted(Html.escape(siteTitle), Html.escape(pageTitle), Html.escape(pageTitle), banner, bodyHtml);
    }
}
