package com.lodygames.rpgquest.panel.web;

import java.util.List;

/**
 * Gabarit HTML unique du Control Panel : coquille sobre, responsive (desktop riche + mobile), CSS
 * en ligne (aucun asset externe, cohérent avec la CSP). Un outil d'administration, pas une
 * interface Minecraft décorative.
 */
public final class Layout {

    /** Un élément de navigation. {@code active} = page courante ; {@code enabled=false} = « à venir ». */
    public record NavItem(String label, String href, boolean enabled, boolean active) {
    }

    public static List<NavItem> nav(String activeHref) {
        return List.of(
                item("Dashboard", "/dashboard", true, activeHref),
                item("Agents", "/agents", true, activeHref),
                item("Joueurs", "/players", true, activeHref),
                item("PNJ", "/npc", false, activeHref),
                item("Quêtes", "/quests", true, activeHref),
                item("Stories", "/stories", true, activeHref),
                item("Diagnostics", "/diagnostics", false, activeHref),
                item("Admin", "/admin", false, activeHref),
                item("Développement", "/dev", false, activeHref));
    }

    private static NavItem item(String label, String href, boolean enabled, String activeHref) {
        return new NavItem(label, href, enabled, href.equals(activeHref));
    }

    public static String page(String title, String username, String activeHref, String content) {
        StringBuilder nav = new StringBuilder();
        for (NavItem it : nav(activeHref)) {
            if (it.enabled()) {
                nav.append("<a class=\"navlink").append(it.active() ? " active" : "").append("\" href=\"")
                        .append(it.href()).append("\">").append(it.label()).append("</a>");
            } else {
                nav.append("<span class=\"navlink disabled\" title=\"Module à venir\">")
                        .append(it.label()).append(" <em>à venir</em></span>");
            }
        }
        String userBox = username == null ? ""
                : "<div class=\"userbox\"><span>" + username + "</span>"
                + "<form method=\"post\" action=\"/logout\" style=\"display:inline\">%CSRF%"
                + "<button class=\"linkbtn\">Se déconnecter</button></form></div>";

        return """
                <!doctype html><html lang="fr"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>%TITLE% · RPGQuest Control Panel</title>
                <style>%CSS%</style></head><body>
                <header class="topbar">
                  <div class="brand">RPGQuest <strong>Control Panel</strong></div>
                  %USERBOX%
                </header>
                <div class="shell">
                  <nav class="side">%NAV%</nav>
                  <main class="main">%CONTENT%</main>
                </div>
                </body></html>
                """
                .replace("%TITLE%", title)
                .replace("%CSS%", CSS)
                .replace("%USERBOX%", userBox)
                .replace("%NAV%", nav.toString())
                .replace("%CONTENT%", content);
    }

    public static String bare(String title, String content) {
        return """
                <!doctype html><html lang="fr"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>%TITLE% · RPGQuest Control Panel</title>
                <style>%CSS%</style></head><body class="centered">
                <main class="card login">%CONTENT%</main></body></html>
                """
                .replace("%TITLE%", title)
                .replace("%CSS%", CSS)
                .replace("%CONTENT%", content);
    }

    private static final String CSS = """
            :root{--bg:#0f1115;--panel:#171a21;--panel2:#1e222b;--panel3:#232833;--line:#2a2f3a;
            --line2:#363c49;--txt:#e6e8ec;--muted:#9aa3b2;--faint:#6b7380;
            --ok:#3fb950;--warn:#d29922;--err:#f85149;--info:#4bd6d6;--accent:#4c8dff;
            --radius:10px}
            *{box-sizing:border-box}
            body{margin:0;background:var(--bg);color:var(--txt);
            font:14px/1.55 system-ui,-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif}
            a{color:var(--accent);text-decoration:none} a:hover{text-decoration:underline}
            code,kbd,samp{font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace}
            /* ---- coquille ---- */
            .topbar{display:flex;align-items:center;justify-content:space-between;gap:10px;
            padding:10px 16px;background:var(--panel);border-bottom:1px solid var(--line)}
            .brand{font-size:14px} .brand strong{color:var(--accent)}
            .userbox{display:flex;gap:10px;align-items:center;color:var(--muted);font-size:13px}
            .linkbtn{background:none;border:1px solid var(--line);color:var(--muted);padding:5px 10px;
            border-radius:7px;cursor:pointer;font:inherit}
            .linkbtn:hover{color:var(--txt);border-color:var(--muted)}
            .shell{display:flex;min-height:calc(100vh - 49px)}
            .side{width:212px;flex-shrink:0;background:var(--panel);border-right:1px solid var(--line);
            padding:12px;display:flex;flex-direction:column;gap:2px}
            .navlink{display:block;padding:8px 11px;border-radius:7px;color:var(--txt);font-size:13.5px;
            border-left:3px solid transparent;white-space:nowrap}
            .navlink:hover{background:var(--panel2);text-decoration:none}
            .navlink.active{background:var(--panel2);border-left-color:var(--accent);font-weight:600}
            .navlink.disabled{color:#5b6370;cursor:not-allowed}
            .navlink.disabled em{font-style:normal;font-size:10.5px;color:#4b5563;border:1px solid var(--line);
            padding:0 5px;border-radius:10px;margin-left:4px}
            .main{flex:1;min-width:0;padding:22px 26px;max-width:1120px;width:100%}
            /* ---- titres ---- */
            h1{font-size:21px;margin:0 0 4px;letter-spacing:-.01em}
            h2{font-size:13px;font-weight:600;text-transform:uppercase;letter-spacing:.06em;
            color:var(--muted);margin:26px 0 10px;padding-bottom:6px;border-bottom:1px solid var(--line)}
            h3{font-size:14.5px;margin:16px 0 8px}
            .sub{color:var(--muted);margin:0 0 16px;max-width:70ch}
            .muted{color:var(--muted)} .faint{color:var(--faint)}
            /* ---- cartes indicateurs (dashboard) ---- */
            .cards{display:grid;grid-template-columns:repeat(auto-fill,minmax(210px,1fr));gap:12px;margin:10px 0}
            .card{background:var(--panel);border:1px solid var(--line);border-radius:var(--radius);padding:14px}
            .card .k{color:var(--muted);font-size:11px;text-transform:uppercase;letter-spacing:.05em}
            .card .v{font-size:19px;margin-top:4px}
            /* ---- carte entité (quête / story) ---- */
            .entity-card{background:var(--panel);border:1px solid var(--line);border-radius:var(--radius);
            padding:14px 16px;margin:10px 0}
            .entity-head{display:flex;flex-wrap:wrap;align-items:baseline;gap:8px 12px;justify-content:space-between}
            .entity-name{font-size:15.5px;font-weight:650;margin:0;color:var(--txt);line-height:1.3}
            .entity-meta{display:flex;flex-wrap:wrap;gap:6px;align-items:center}
            .meta-line{margin:8px 0 0;font-size:13px;color:var(--muted)}
            .meta-k{display:inline-block;min-width:88px;color:var(--faint);font-size:11px;
            text-transform:uppercase;letter-spacing:.05em}
            .obj-list,.step-list,.reward-list{margin:8px 0 0;padding:0;list-style:none;display:flex;
            flex-direction:column;gap:6px}
            .obj-list li,.step-list li,.reward-list li{display:flex;flex-wrap:wrap;align-items:center;
            gap:8px;font-size:13.5px}
            .obj-list li,.reward-list li{padding-left:14px;position:relative}
            .obj-list li::before{content:"▹";position:absolute;left:0;color:var(--faint)}
            .reward-list li::before{content:"◆";position:absolute;left:0;color:var(--faint);font-size:9px;top:5px}
            .step-n{display:inline-flex;align-items:center;justify-content:center;width:20px;height:20px;
            flex-shrink:0;border-radius:50%;background:var(--panel3);border:1px solid var(--line2);
            font-size:11px;color:var(--muted)}
            .obj-text{color:var(--txt);overflow-wrap:anywhere}
            .act-type{color:var(--txt)}
            .reward-list .obj-text{font-weight:500}
            /* ---- identifiant technique en second plan ---- */
            .tid{font-size:11.5px;color:var(--faint);background:var(--panel2);border:1px solid var(--line);
            border-radius:5px;padding:1px 6px;white-space:nowrap;font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace}
            .tid--wrap{white-space:normal;word-break:break-word}
            .tid[data-copy]{cursor:pointer;transition:color .1s,border-color .1s}
            .tid[data-copy]:hover,.tid[data-copy]:focus-visible{color:var(--muted);border-color:var(--muted);outline:none}
            .tid[data-copy]::after{content:" ⧉";opacity:.45;font-size:10px}
            .tid.copied{color:var(--ok);border-color:var(--ok)}
            .tid.copied::after{content:" ✓"}
            .badge{display:inline-block;font-size:11px;color:var(--muted);background:var(--panel2);
            border:1px solid var(--line);border-radius:999px;padding:1px 9px;white-space:nowrap}
            /* ---- pastilles de statut (texte + glyphe, jamais couleur seule) ---- */
            .pill{display:inline-flex;align-items:center;gap:5px;padding:2px 10px;border-radius:999px;
            font-size:11.5px;font-weight:650;letter-spacing:.02em;border:1px solid transparent;white-space:nowrap;
            text-transform:none;vertical-align:middle}
            .badge,.tid,code{text-transform:none}
            .pill-g{font-weight:700;font-size:11px;line-height:1}
            .pill--success{background:rgba(63,185,80,.14);color:var(--ok);border-color:rgba(63,185,80,.4)}
            .pill--delivered{background:rgba(75,214,214,.13);color:var(--info);border-color:rgba(75,214,214,.4)}
            .pill--pending{background:rgba(210,153,34,.13);color:var(--warn);border-color:rgba(210,153,34,.45)}
            .pill--failed{background:rgba(248,81,73,.14);color:var(--err);border-color:rgba(248,81,73,.45)}
            .pill--rejected{background:rgba(248,81,73,.10);color:#ff9d97;border-color:rgba(248,81,73,.4);
            border-style:dashed}
            .pill--expired{background:var(--panel3);color:var(--faint);border-color:var(--line2)}
            .pill--neutral{background:var(--panel3);color:var(--muted);border-color:var(--line2)}
            .pill.ok{background:rgba(63,185,80,.14);color:var(--ok);border-color:rgba(63,185,80,.4)}
            .pill.warn{background:rgba(210,153,34,.13);color:var(--warn);border-color:rgba(210,153,34,.45)}
            .pill.err{background:rgba(248,81,73,.14);color:var(--err);border-color:rgba(248,81,73,.45)}
            /* ---- bannières ---- */
            .banner{border-radius:var(--radius);padding:11px 14px;margin:0 0 16px;border:1px solid;font-size:13.5px}
            .banner.err{background:rgba(248,81,73,.08);border-color:var(--err);color:#ffb4ae}
            .banner.ok{background:rgba(63,185,80,.09);border-color:rgba(63,185,80,.5);color:#a8e6b6}
            .banner.info{background:rgba(76,141,255,.08);border-color:rgba(76,141,255,.5);color:#bcd3ff}
            /* ---- tableaux ---- */
            .table-wrap{overflow-x:auto;border:1px solid var(--line);border-radius:var(--radius);margin-top:8px}
            table{width:100%;border-collapse:collapse}
            .table-wrap table{margin:0}
            td,th{text-align:left;padding:8px 10px;border-bottom:1px solid var(--line);vertical-align:top}
            tbody tr:last-child td{border-bottom:0}
            tbody tr:hover{background:var(--panel2)}
            th{color:var(--muted);font-weight:600;font-size:11px;text-transform:uppercase;letter-spacing:.04em;
            background:var(--panel);position:sticky;top:0}
            /* ---- état vide ---- */
            .empty{color:var(--muted);font-size:13px;font-style:italic;background:var(--panel);
            border:1px dashed var(--line2);border-radius:var(--radius);padding:12px 14px;margin:8px 0}
            /* ---- formulaires / actions ---- */
            label{display:block;color:var(--muted);font-size:12px;margin:12px 0 4px}
            input[type=text],input[type=password],input[type=number],input[list],select{width:100%;max-width:440px;
            padding:8px 10px;background:var(--panel2);border:1px solid var(--line);border-radius:8px;
            color:var(--txt);font:inherit}
            input:focus,select:focus{outline:2px solid rgba(76,141,255,.5);outline-offset:0;border-color:var(--accent)}
            .btn{margin-top:14px;padding:9px 16px;background:var(--accent);border:1px solid var(--accent);
            border-radius:8px;color:#fff;font-weight:600;font:inherit;font-weight:600;cursor:pointer}
            .btn:hover{filter:brightness(1.08)}
            .btn.secondary{background:transparent;color:var(--txt);border-color:var(--line2)}
            .btn.secondary:hover{background:var(--panel2);filter:none}
            .btn.danger{background:var(--err);border-color:var(--err)}
            .formerr{color:var(--err);margin-top:12px;font-size:13px}
            .actform{background:var(--panel);border:1px solid var(--line);border-radius:var(--radius);
            padding:12px 14px;margin:10px 0}
            .actform.read{border-left:3px solid var(--line2)}
            .actform .btn{width:auto}
            .actform.read .btn{margin-top:0;background:transparent;color:var(--txt);border-color:var(--line2)}
            .actform.read .btn:hover{background:var(--panel2);filter:none}
            label.inline{display:inline-flex;align-items:center;gap:6px;margin:10px 0 0;color:var(--txt);font-size:13px}
            label.inline.confirm{color:var(--warn)}
            .resline{margin:8px 0;font-size:13px}
            .agentpicker{display:flex;gap:8px;align-items:center;margin:8px 0}
            .agentpicker label{margin:0}
            .actions-panel{margin-top:6px}
            .poll-status{font-size:12px;margin-top:6px;color:var(--muted)}
            [hidden]{display:none}
            /* ---- zone sensible : actions qui modifient réellement l'état ---- */
            .danger-zone{border:1px solid rgba(248,81,73,.4);border-left:3px solid var(--err);
            border-radius:var(--radius);padding:6px 14px 14px;margin:16px 0;background:rgba(248,81,73,.04)}
            .danger-zone>.dz-title{display:flex;align-items:center;gap:8px;margin:10px 0 2px;
            font-size:12px;text-transform:uppercase;letter-spacing:.05em;color:#ff9d97;font-weight:700}
            .danger-zone details{border-color:rgba(248,81,73,.35)}
            details{margin:10px 0;border:1px solid var(--line);border-radius:8px;padding:8px 12px;background:var(--panel)}
            summary{cursor:pointer;color:var(--muted)}
            details[open] summary{margin-bottom:6px}
            ol,ul{margin:6px 0;padding-left:20px}
            /* ---- connexion ---- */
            .centered{display:flex;align-items:center;justify-content:center;min-height:100vh;padding:16px}
            .login{width:340px;max-width:100%}
            .login .btn{width:100%}
            /* ---- responsive ---- */
            @media(max-width:860px){.main{padding:16px}}
            @media(max-width:720px){
              .shell{flex-direction:column}
              .side{width:auto;flex-direction:row;flex-wrap:nowrap;overflow-x:auto;gap:4px;padding:8px 10px;
              border-right:0;border-bottom:1px solid var(--line)}
              .navlink{border-left:0;border-bottom:3px solid transparent;padding:7px 10px}
              .navlink.active{border-left:0;border-bottom-color:var(--accent)}
              .main{padding:14px}
              .entity-head{gap:6px}
              input[type=text],input[type=password],input[type=number],input[list],select{max-width:100%}
              .meta-k{min-width:0;display:block;margin-bottom:2px}
            }
            """;
}
