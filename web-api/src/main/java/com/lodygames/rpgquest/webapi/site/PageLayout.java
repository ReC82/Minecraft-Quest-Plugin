package com.lodygames.rpgquest.webapi.site;

import com.lodygames.rpgquest.webapi.ServerStatus;
import java.util.List;

/**
 * Squelette HTML commun des pages publiques (#56) : en-tête et menu, contenu, pied de page.
 *
 * <p>Conçu pour un public de 13 ans, sur téléphone comme sur ordinateur : une seule colonne qui
 * s'élargit, menu qui passe à la ligne, zones cliquables de 44 px, contrastes élevés, focus clavier
 * visible, lien d'évitement. Aucun script, aucune ressource externe (la CSP de nginx l'interdit de
 * toute façon) : le style est en ligne.</p>
 *
 * <p>Identité visuelle #428, variante B « RPG fantasy voxel » : nuit bleutée, cadres bois et or,
 * tuiles biseautées, titres serif système (jamais de police Minecraft), icônes pixel art
 * originales de {@link PortalIcons}. Contrastes de texte ≥ 4,5:1.</p>
 */
public final class PageLayout {

    private static final List<String[]> MENU = List.of(
            new String[] {PageMeta.HOME, "Accueil", "grass"},
            new String[] {PageMeta.NEWS, "Actualités", "scroll"},
            new String[] {PageMeta.EVENTS, "Événements", "boss"},
            new String[] {PageMeta.BOARDS, "Classements", "trophy"},
            new String[] {PageMeta.PLAYERS, "Joueurs", "npc"},
            new String[] {PageMeta.INFO, "Guide", "map"},
            new String[] {PageMeta.ACCOUNT, "Compte", "shield"});

    private static final String STYLE = """
            :root { color-scheme: dark; --bg:#121626; --panel:#1d2236; --line:#6b4c2c; --wood:#3a2a1a; --wood-dark:#1e140a;
              --text:#ececec; --muted:#b4bccb; --gold:#f5c95c; --title:#f5d98a; --link:#8fd3ff; --ok:#57d27a; --ko:#ff8a80;
              --silver:#d5dbe5; --bronze:#d9925b; --serif: Georgia, "Times New Roman", serif; }
            * { box-sizing: border-box; }
            body { margin:0; font-family: system-ui, -apple-system, "Segoe UI", Roboto, sans-serif; line-height:1.55;
              background:#121626 radial-gradient(circle at 50% 0, #2a2f52 0, #121626 900px) no-repeat; color:var(--text);
              font-size:1.05rem; }
            a { color:var(--link); }
            a:focus-visible, button:focus-visible, input:focus-visible, select:focus-visible {
              outline:3px solid var(--gold); outline-offset:2px; }
            .skip { position:absolute; left:-999px; } .skip:focus { left:1rem; top:1rem; background:var(--panel); padding:.5rem; z-index:2; }
            .lq-ico { display:inline-block; flex:none; vertical-align:middle; image-rendering:pixelated; }
            .lq-16 { width:16px; height:16px; } .lq-32 { width:32px; height:32px; } .lq-48 { width:48px; height:48px; }
            .lq-tile { display:inline-flex; align-items:center; justify-content:center; flex:none; width:24px; height:24px;
              border-radius:3px; background:var(--wood); border:1px solid var(--wood-dark);
              box-shadow:inset 2px 2px 0 #6b4c2c, inset -2px -2px 0 var(--wood-dark); }
            .lq-tile-l { width:44px; height:44px; }
            .lq-h { display:flex; align-items:center; gap:.6rem; }
            .lq-empty { display:flex; align-items:center; gap:.8rem; background:var(--panel); border:2px dashed var(--line);
              border-radius:6px; padding:.8rem 1rem; margin:.5rem 0; }
            .lq-empty p { margin:0; }
            .lq-hero-icons { display:flex; flex-wrap:wrap; gap:.5rem; margin-bottom:.6rem; }
            header { background:linear-gradient(#3a2a1a, #2a1d11); border-bottom:4px solid #8b6234; box-shadow:0 2px 0 var(--gold); }
            .bar { max-width:960px; margin:0 auto; padding:.5rem 1rem; }
            .brand { display:inline-flex; align-items:center; gap:.5rem; font-family:var(--serif); font-weight:800;
              font-size:1.4rem; color:var(--gold); text-decoration:none; padding:.4rem 0; letter-spacing:.04em; }
            nav ul { list-style:none; margin:0; padding:0; display:flex; flex-wrap:wrap; gap:.25rem .5rem; }
            nav a { display:inline-flex; align-items:center; gap:.4rem; min-height:44px; padding:.6rem .7rem; border-radius:.4rem;
              text-decoration:none; color:#f3e6c8; }
            nav a:hover { background:#4a3520; }
            nav a[aria-current="page"] { background:#4a3520; color:var(--gold); font-weight:700; }
            main { max-width:960px; margin:0 auto; padding:1rem 1rem 2rem; }
            h1, h2, h3 { font-family:var(--serif); color:var(--title); }
            h1 { font-size:1.9rem; margin:.5rem 0 1rem; } h2 { font-size:1.4rem; margin:1.6rem 0 .6rem; }
            h3 { font-size:1.15rem; margin:1rem 0 .4rem; }
            .offline { background:#4a1f1f; border-left:4px solid var(--ko); padding:.6rem 1rem; border-radius:.3rem; }
            .unknown { background:#2c3446; border-left:4px solid var(--muted); padding:.6rem 1rem; border-radius:.3rem; }
            .notice { background:#1e3f2a; border-left:4px solid var(--ok); padding:.6rem 1rem; border-radius:.3rem; }
            .note, .meta { color:var(--muted); font-size:.92rem; }
            .grid { display:grid; grid-template-columns:repeat(auto-fit, minmax(260px, 1fr)); gap:1rem; }
            .card { background:var(--panel); border:3px solid var(--line); border-radius:6px; box-shadow:0 0 0 2px var(--wood-dark);
              padding:1rem; min-width:0; }
            .card h2, .card h3 { margin-top:0; }
            .hero { background:linear-gradient(135deg, #3b2a55, #1f3a4a); border:3px solid #8b6234; border-radius:6px;
              padding:1.4rem 1.2rem; margin-bottom:1.2rem; }
            .hero p { font-size:1.1rem; margin:.4rem 0; }
            .button { display:inline-block; min-height:44px; padding:.65rem 1.1rem; border-radius:4px; background:var(--gold);
              color:#1a1300; font-weight:700; text-decoration:none; margin:.3rem .4rem .3rem 0; box-shadow:inset 0 -3px 0 #b07f1a; }
            .button.secondary { background:transparent; color:var(--gold); border:2px solid var(--gold); box-shadow:none; }
            .tag { display:inline-block; font-size:.8rem; padding:.1rem .5rem; border-radius:1rem; background:#2c3446;
              color:var(--text); margin-right:.3rem; }
            .board { list-style:none; margin:0; padding:0; }
            .board li { display:flex; align-items:center; gap:.7rem; padding:.45rem .2rem; border-bottom:1px solid #2e3442; }
            .board .rank { flex:0 0 2.2rem; height:2.2rem; border-radius:50%; display:flex; align-items:center;
              justify-content:center; background:#2c3446; font-weight:700; }
            .board .podium-1 { background:var(--gold); color:#1a1300; }
            .board .podium-2 { background:var(--silver); color:#1a1d24; }
            .board .podium-3 { background:var(--bronze); color:#1f1206; }
            .board .who { flex:1 1 auto; overflow-wrap:anywhere; min-width:0; }
            .board .value { font-variant-numeric:tabular-nums; font-weight:700; white-space:nowrap; }
            .state { display:flex; align-items:center; gap:.5rem; font-weight:700; }
            .tabs { display:flex; flex-wrap:wrap; gap:.4rem; margin:.5rem 0 1rem; padding:0; list-style:none; }
            .tabs a { display:inline-block; min-height:44px; padding:.6rem .9rem; border:2px solid var(--line);
              border-radius:4px; text-decoration:none; color:var(--text); background:var(--panel); }
            .tabs a[aria-current="page"] { border-color:var(--gold); color:var(--gold); font-weight:700; }
            .pager { display:flex; justify-content:space-between; gap:1rem; margin-top:1.5rem; }
            .avatar { width:3.5rem; height:3.5rem; border-radius:6px; background:#3a2f5a; color:var(--gold); border:3px solid var(--line);
              display:inline-flex; align-items:center; justify-content:center; font-size:1.8rem; font-weight:800; }
            .stats { display:grid; grid-template-columns:repeat(auto-fit, minmax(150px, 1fr)); gap:.6rem; }
            .stat { background:var(--panel); border:2px solid var(--line); border-radius:6px; padding:.7rem; }
            .stat strong { display:block; font-size:1.4rem; color:var(--gold); }
            article .content p, article .content li { max-width:70ch; }
            table { border-collapse:collapse; width:100%; margin-top:1rem; }
            th, td { text-align:left; padding:.35rem .5rem; border-bottom:1px solid #2e3442; }
            .code { background:#1c2230; padding:.5rem 1rem; border-radius:.25rem; }
            .code code { font-size:1.6rem; letter-spacing:.1rem; }
            code { background:#262c3a; padding:.05rem .3rem; border-radius:.25rem; overflow-wrap:anywhere; }
            input, select, button { font-size:1rem; padding:.5rem; max-width:100%; min-height:44px; }
            footer { border-top:3px solid var(--line); color:var(--muted); font-size:.92rem; background:#0f121e; }
            footer ul { list-style:none; display:flex; flex-wrap:wrap; gap:.3rem 1rem; padding:0; margin:.5rem 0; }
            footer a { display:inline-block; padding:.4rem 0; }
            .legal { font-size:.8rem; letter-spacing:.02em; }
            .demo-banner { display:flex; align-items:center; gap:.8rem; margin:0 0 1rem; padding:.8rem 1rem; border-radius:6px;
              background:repeating-linear-gradient(135deg, #3b2f12 0 14px, #2f2610 14px 28px); border:3px solid var(--gold); }
            .demo-banner p { margin:0; }
            .tiles { display:grid; grid-template-columns:repeat(auto-fit, minmax(150px, 1fr)); gap:.8rem; margin:1rem 0;
              list-style:none; padding:0; }
            .tile-link { display:flex; flex-direction:column; align-items:center; gap:.4rem; text-align:center; height:100%;
              padding:1rem .6rem; background:var(--panel); border:3px solid var(--line); border-radius:6px;
              text-decoration:none; color:var(--text); }
            .tile-link strong { color:var(--title); font-family:var(--serif); font-size:1.1rem; }
            .tile-link span.note { font-size:.85rem; }
            .tile-link:hover { border-color:var(--gold); }
            .steps { counter-reset:step; list-style:none; padding:0; display:grid; gap:.8rem;
              grid-template-columns:repeat(auto-fit, minmax(220px, 1fr)); }
            .steps li { counter-increment:step; background:var(--panel); border:2px solid var(--line); border-radius:6px;
              padding:.8rem 1rem .8rem 3.4rem; position:relative; }
            .steps li::before { content:counter(step); position:absolute; left:.8rem; top:.8rem; width:2rem; height:2rem;
              border-radius:4px; background:var(--gold); color:#1a1300; font-weight:800; display:flex; align-items:center;
              justify-content:center; }
            .podium { display:grid; grid-template-columns:repeat(3, minmax(0, 1fr)); align-items:end; gap:.4rem;
              margin:.4rem 0 .8rem; padding:0; list-style:none; }
            .podium li { display:flex; flex-direction:column; align-items:center; text-align:center; min-width:0; }
            .podium .who { overflow-wrap:anywhere; font-weight:700; font-size:.95rem; }
            .podium .value { font-variant-numeric:tabular-nums; font-size:.85rem; color:var(--muted); }
            .podium .step { width:100%; margin-top:.3rem; border-radius:4px 4px 0 0; display:flex; align-items:flex-start;
              justify-content:center; padding-top:.3rem; font-weight:800; font-size:1.3rem; color:#1a1300;
              box-shadow:inset 0 3px 0 rgba(255,255,255,.35), inset 0 -4px 0 rgba(0,0,0,.25); }
            .podium .p1 .step { height:5.5rem; background:var(--gold); }
            .podium .p2 .step { height:4rem; background:var(--silver); }
            .podium .p3 .step { height:3rem; background:var(--bronze); }
            .podium .crown { color:var(--gold); }
            .grid.boards { grid-template-columns:repeat(auto-fit, minmax(min(100%, 340px), 1fr)); }
            .podium .who, .board .who { overflow-wrap:break-word; }
            .podium .pos1 { order:2; } .podium .pos2 { order:1; } .podium .pos3 { order:3; }
            .chip { display:inline-block; font-size:.85rem; font-weight:700; padding:.15rem .6rem; border-radius:1rem;
              background:#2c3446; color:var(--text); }
            .chip.active { background:#1e5a34; color:#d8ffe4; } .chip.scheduled { background:#1f4466; color:#d9eeff; }
            .chip.ended { background:#3a3f4c; color:#e1e4ea; } .chip.cancelled { background:#5a2323; color:#ffe1de; }
            .profile-hero { display:flex; flex-wrap:wrap; align-items:center; gap:1rem; background:linear-gradient(135deg,
              #3b2a55, #1f3a4a); border:3px solid #8b6234; border-radius:6px; padding:1rem 1.2rem; margin-bottom:1rem; }
            .profile-hero .avatar { width:5rem; height:5rem; font-size:2.6rem; }
            .profile-hero p { margin:.2rem 0; }
            .big { font-size:1.6rem; font-weight:800; color:var(--gold); }
            .tracks { list-style:none; margin:0; padding:0; display:grid; gap:.7rem; }
            .tracks li { display:grid; grid-template-columns:auto 1fr auto; align-items:center; gap:.3rem .7rem; }
            .meter { grid-column:2 / 4; height:.8rem; border-radius:.4rem; background:#2c3446; overflow:hidden;
              border:1px solid var(--wood-dark); }
            .meter span { display:block; height:100%; background:linear-gradient(90deg, #b07f1a, var(--gold)); }
            .medals { display:flex; flex-wrap:wrap; gap:.6rem; list-style:none; padding:0; margin:.4rem 0; }
            .medals li { display:flex; align-items:center; gap:.5rem; background:var(--panel); border:2px solid var(--line);
              border-radius:6px; padding:.5rem .8rem; }
            .medals .rank { width:2rem; height:2rem; border-radius:50%; display:inline-flex; align-items:center;
              justify-content:center; background:#2c3446; font-weight:800; }
            .medals .podium-1 { background:var(--gold); color:#1a1300; } .medals .podium-2 { background:var(--silver); color:#1a1d24; }
            .medals .podium-3 { background:var(--bronze); color:#1f1206; }
            .player-card { display:flex; align-items:center; gap:.7rem; }
            .player-card .avatar { width:2.6rem; height:2.6rem; font-size:1.3rem; }
            .filters-label { margin:.6rem 0 0; font-weight:700; }
            @media (max-width: 700px) {
              nav ul { display:grid; grid-template-columns:repeat(4, minmax(0, 1fr)); gap:.25rem; }
              nav a { flex-direction:column; justify-content:center; gap:.15rem; padding:.35rem .1rem; font-size:.78rem;
                text-align:center; }
              nav .lq-ico { width:24px; height:24px; }
              .brand { font-size:1.2rem; } }
            @media (max-width: 600px) { h1 { font-size:1.55rem; } .hero { padding:1rem; } body { font-size:1rem; }
              .podium .who { font-size:.85rem; } }
            """;

    private PageLayout() {
    }

    /** Page sans métadonnées (pages historiques : statut, wiki, compte, confidentialité). */
    public static String render(String siteTitle, String pageTitle, boolean online, String bodyHtml) {
        return render(new Site(siteTitle, ""), PageMeta.none(), pageTitle, online, bodyHtml);
    }

    public static String render(String siteTitle, String pageTitle, ServerStatus status, String bodyHtml) {
        return render(new Site(siteTitle, ""), PageMeta.none(), pageTitle, status, bodyHtml);
    }

    /** {@code online = true} : page sans bandeau d'état du jeu ; {@code false} : état inconnu. */
    public static String render(Site site, PageMeta meta, String pageTitle, boolean online, String bodyHtml) {
        return render(site, meta, pageTitle, new ServerStatus(online ? ServerStatus.Kind.ONLINE : ServerStatus.Kind.UNKNOWN,
                null), bodyHtml);
    }

    /** Bandeau selon l'état du serveur de jeu (#421) : rouge uniquement pour un arrêt annoncé. */
    public static String render(Site site, PageMeta meta, String pageTitle, ServerStatus status, String bodyHtml) {
        String banner = status.bannerHtml();
        String fullTitle = pageTitle.equals(site.title()) ? site.title() : pageTitle + " — " + site.title();
        StringBuilder head = new StringBuilder();
        if (meta.noindex()) {
            head.append("<meta name=\"robots\" content=\"noindex, nofollow\">\n");
        }
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
                <a class="brand" href="/">%s%s</a>
                <nav aria-label="Menu principal"><ul>%s</ul></nav>
                </div></header>
                <main id="contenu">
                <h1>%s</h1>
                %s
                %s
                </main>
                <footer><div class="bar">
                <ul><li><a href="/status">Statut du serveur</a></li><li><a href="/progression">Progresser</a></li><li><a href="/wiki">Wiki</a></li><li><a href="/infos">Règles et FAQ</a></li><li><a href="/confidentialite">Confidentialité</a></li></ul>
                <p>%s est un serveur Minecraft communautaire. Site non officiel, sans lien avec Mojang ou Microsoft.
                Icônes pixel art originales du projet.</p>
                <p class="legal" lang="en">NOT AN OFFICIAL MINECRAFT SERVICE. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT.</p>
                </div></footer>
                </body>
                </html>
                """.formatted(Html.escape(fullTitle), head, STYLE, PortalIcons.icon("gem", 32), Html.escape(site.title()), menu(meta.section()),
                Html.escape(pageTitle), banner, bodyHtml, Html.escape(site.title()));
    }

    private static String menu(String section) {
        StringBuilder items = new StringBuilder();
        for (String[] entry : MENU) {
            items.append("<li><a href=\"").append(entry[0]).append('"');
            if (entry[0].equals(section)) {
                items.append(" aria-current=\"page\"");
            }
            items.append('>').append(PortalIcons.icon(entry[2], 16)).append(entry[1]).append("</a></li>");
        }
        return items.toString();
    }
}
