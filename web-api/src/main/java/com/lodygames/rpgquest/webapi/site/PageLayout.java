package com.lodygames.rpgquest.webapi.site;

import java.util.List;

/**
 * Squelette HTML commun des pages publiques (#56) : en-tête et menu, contenu, pied de page.
 *
 * <p>Conçu pour un public de 13 ans, sur téléphone comme sur ordinateur : une seule colonne qui
 * s'élargit, menu qui passe à la ligne, zones cliquables de 44 px, contrastes élevés, focus clavier
 * visible, lien d'évitement. Aucun script, aucune ressource externe (la CSP de nginx l'interdit de
 * toute façon) : le style est en ligne.</p>
 */
public final class PageLayout {

    private static final List<String[]> MENU = List.of(
            new String[] {PageMeta.HOME, "Accueil"},
            new String[] {PageMeta.NEWS, "Actualités"},
            new String[] {PageMeta.BOARDS, "Classements"},
            new String[] {PageMeta.PLAYERS, "Joueurs"},
            new String[] {PageMeta.INFO, "Infos & FAQ"},
            new String[] {PageMeta.ACCOUNT, "Mon compte"});

    private static final String STYLE = """
            :root { color-scheme: dark; --bg:#14161d; --panel:#1d212b; --line:#2e3442; --text:#ececec;
              --muted:#a9b1bd; --gold:#f2c14e; --link:#8fd3ff; --ok:#57d27a; --ko:#ff8a80; }
            * { box-sizing: border-box; }
            body { margin:0; font-family: system-ui, -apple-system, "Segoe UI", Roboto, sans-serif; line-height:1.55;
              background:var(--bg); color:var(--text); font-size:1.05rem; }
            a { color:var(--link); }
            a:focus-visible, button:focus-visible, input:focus-visible, select:focus-visible {
              outline:3px solid var(--gold); outline-offset:2px; }
            .skip { position:absolute; left:-999px; } .skip:focus { left:1rem; top:1rem; background:var(--panel); padding:.5rem; z-index:2; }
            header { background:#0f1117; border-bottom:2px solid var(--gold); }
            .bar { max-width:960px; margin:0 auto; padding:.5rem 1rem; }
            .brand { display:inline-block; font-weight:800; font-size:1.35rem; color:var(--gold); text-decoration:none;
              padding:.4rem 0; letter-spacing:.02em; }
            nav ul { list-style:none; margin:0; padding:0; display:flex; flex-wrap:wrap; gap:.25rem .5rem; }
            nav a { display:inline-block; min-height:44px; padding:.6rem .7rem; border-radius:.4rem; text-decoration:none;
              color:var(--text); }
            nav a:hover { background:var(--panel); }
            nav a[aria-current="page"] { background:var(--panel); color:var(--gold); font-weight:700; }
            main { max-width:960px; margin:0 auto; padding:1rem 1rem 2rem; }
            h1 { font-size:1.8rem; margin:.5rem 0 1rem; } h2 { font-size:1.35rem; margin:1.6rem 0 .6rem; }
            h3 { font-size:1.1rem; margin:1rem 0 .4rem; }
            .offline { background:#4a1f1f; border-left:4px solid var(--ko); padding:.6rem 1rem; border-radius:.3rem; }
            .notice { background:#1e3f2a; border-left:4px solid var(--ok); padding:.6rem 1rem; border-radius:.3rem; }
            .note, .meta { color:var(--muted); font-size:.92rem; }
            .grid { display:grid; grid-template-columns:repeat(auto-fit, minmax(260px, 1fr)); gap:1rem; }
            .card { background:var(--panel); border:1px solid var(--line); border-radius:.6rem; padding:1rem; }
            .card h2, .card h3 { margin-top:0; }
            .hero { background:linear-gradient(135deg,#2a2140,#1b2d3a); border:1px solid var(--line); border-radius:.8rem;
              padding:1.4rem 1.2rem; margin-bottom:1.2rem; }
            .hero p { font-size:1.1rem; margin:.4rem 0; }
            .button { display:inline-block; min-height:44px; padding:.65rem 1.1rem; border-radius:.5rem; background:var(--gold);
              color:#1a1300; font-weight:700; text-decoration:none; margin:.3rem .4rem .3rem 0; }
            .button.secondary { background:transparent; color:var(--gold); border:2px solid var(--gold); }
            .tag { display:inline-block; font-size:.8rem; padding:.1rem .5rem; border-radius:1rem; background:#2c3446;
              color:var(--text); margin-right:.3rem; }
            .board { list-style:none; margin:0; padding:0; }
            .board li { display:flex; align-items:center; gap:.7rem; padding:.45rem .2rem; border-bottom:1px solid var(--line); }
            .board .rank { flex:0 0 2.2rem; height:2.2rem; border-radius:50%; display:flex; align-items:center;
              justify-content:center; background:#2c3446; font-weight:700; }
            .board li:nth-child(1) .rank { background:var(--gold); color:#1a1300; }
            .board .who { flex:1 1 auto; overflow-wrap:anywhere; }
            .board .value { font-variant-numeric:tabular-nums; font-weight:700; white-space:nowrap; }
            .tabs { display:flex; flex-wrap:wrap; gap:.4rem; margin:.5rem 0 1rem; padding:0; list-style:none; }
            .tabs a { display:inline-block; min-height:44px; padding:.6rem .9rem; border:1px solid var(--line);
              border-radius:2rem; text-decoration:none; color:var(--text); }
            .tabs a[aria-current="page"] { border-color:var(--gold); color:var(--gold); font-weight:700; }
            .pager { display:flex; justify-content:space-between; gap:1rem; margin-top:1.5rem; }
            .avatar { width:3.5rem; height:3.5rem; border-radius:.6rem; background:#3a2f5a; color:var(--gold);
              display:inline-flex; align-items:center; justify-content:center; font-size:1.8rem; font-weight:800; }
            .stats { display:grid; grid-template-columns:repeat(auto-fit, minmax(150px, 1fr)); gap:.6rem; }
            .stat { background:var(--panel); border:1px solid var(--line); border-radius:.5rem; padding:.7rem; }
            .stat strong { display:block; font-size:1.4rem; color:var(--gold); }
            article .content p, article .content li { max-width:70ch; }
            table { border-collapse:collapse; width:100%; margin-top:1rem; }
            th, td { text-align:left; padding:.35rem .5rem; border-bottom:1px solid var(--line); }
            .code { background:#1c2230; padding:.5rem 1rem; border-radius:.25rem; }
            .code code { font-size:1.6rem; letter-spacing:.1rem; }
            code { background:#262c3a; padding:.05rem .3rem; border-radius:.25rem; }
            input, select, button { font-size:1rem; padding:.5rem; max-width:100%; min-height:44px; }
            footer { border-top:1px solid var(--line); color:var(--muted); font-size:.92rem; }
            footer ul { list-style:none; display:flex; flex-wrap:wrap; gap:.3rem 1rem; padding:0; margin:.5rem 0; }
            footer a { display:inline-block; padding:.4rem 0; }
            @media (max-width: 600px) { h1 { font-size:1.5rem; } .hero { padding:1rem; } body { font-size:1rem; } }
            """;

    private PageLayout() {
    }

    /** Page sans métadonnées (pages historiques : statut, wiki, compte, confidentialité). */
    public static String render(String siteTitle, String pageTitle, boolean online, String bodyHtml) {
        return render(new Site(siteTitle, ""), PageMeta.none(), pageTitle, online, bodyHtml);
    }

    public static String render(Site site, PageMeta meta, String pageTitle, boolean online, String bodyHtml) {
        String banner = online
                ? ""
                : "<p class=\"offline\">Le serveur de jeu ne répond pas pour le moment : les données du jeu "
                        + "(statut, classements, profils) sont indisponibles ou en retard.</p>";
        String fullTitle = pageTitle.equals(site.title()) ? site.title() : pageTitle + " — " + site.title();
        StringBuilder head = new StringBuilder();
        if (!meta.description().isEmpty()) {
            head.append("<meta name=\"description\" content=\"").append(Html.escape(meta.description())).append("\">\n");
            head.append("<meta property=\"og:description\" content=\"").append(Html.escape(meta.description())).append("\">\n");
        }
        head.append("<meta property=\"og:title\" content=\"").append(Html.escape(fullTitle)).append("\">\n");
        head.append("<meta property=\"og:site_name\" content=\"").append(Html.escape(site.title())).append("\">\n");
        if (!meta.path().isEmpty() && !site.baseUrl().isEmpty()) {
            String url = site.baseUrl() + meta.path();
            head.append("<link rel=\"canonical\" href=\"").append(Html.escape(url)).append("\">\n");
            head.append("<meta property=\"og:url\" content=\"").append(Html.escape(url)).append("\">\n");
        }
        return """
                <!doctype html>
                <html lang="fr">
                <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>%s</title>
                %s<style>%s</style>
                </head>
                <body>
                <a class="skip" href="#contenu">Aller au contenu</a>
                <header><div class="bar">
                <a class="brand" href="/">%s</a>
                <nav aria-label="Menu principal"><ul>%s</ul></nav>
                </div></header>
                <main id="contenu">
                <h1>%s</h1>
                %s
                %s
                </main>
                <footer><div class="bar">
                <ul><li><a href="/status">Statut du serveur</a></li><li><a href="/wiki">Wiki</a></li><li><a href="/infos">Règles et FAQ</a></li><li><a href="/confidentialite">Confidentialité</a></li></ul>
                <p>%s est un serveur Minecraft communautaire. Site non officiel, sans lien avec Mojang ou Microsoft.</p>
                </div></footer>
                </body>
                </html>
                """.formatted(Html.escape(fullTitle), head, STYLE, Html.escape(site.title()), menu(meta.section()),
                Html.escape(pageTitle), banner, bodyHtml, Html.escape(site.title()));
    }

    private static String menu(String section) {
        StringBuilder items = new StringBuilder();
        for (String[] entry : MENU) {
            items.append("<li><a href=\"").append(entry[0]).append('"');
            if (entry[0].equals(section)) {
                items.append(" aria-current=\"page\"");
            }
            items.append('>').append(entry[1]).append("</a></li>");
        }
        return items.toString();
    }
}
