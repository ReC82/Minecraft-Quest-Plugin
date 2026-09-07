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
            :root{--bg:#0f1115;--panel:#171a21;--panel2:#1e222b;--line:#2a2f3a;--txt:#e6e8ec;
            --muted:#9aa3b2;--ok:#3fb950;--warn:#d29922;--err:#f85149;--accent:#4c8dff}
            *{box-sizing:border-box}
            body{margin:0;background:var(--bg);color:var(--txt);font:14px/1.5 system-ui,Segoe UI,Roboto,sans-serif}
            a{color:var(--accent);text-decoration:none}
            .topbar{display:flex;align-items:center;justify-content:space-between;padding:10px 16px;
            background:var(--panel);border-bottom:1px solid var(--line)}
            .brand strong{color:var(--accent)}
            .userbox{display:flex;gap:10px;align-items:center;color:var(--muted)}
            .linkbtn{background:none;border:1px solid var(--line);color:var(--muted);padding:4px 10px;
            border-radius:6px;cursor:pointer}
            .linkbtn:hover{color:var(--txt);border-color:var(--muted)}
            .shell{display:flex;min-height:calc(100vh - 49px)}
            .side{width:210px;background:var(--panel);border-right:1px solid var(--line);padding:12px;display:flex;
            flex-direction:column;gap:2px}
            .navlink{display:block;padding:8px 10px;border-radius:6px;color:var(--txt)}
            .navlink:hover{background:var(--panel2)}
            .navlink.active{background:var(--panel2);border-left:3px solid var(--accent)}
            .navlink.disabled{color:#5b6370;cursor:not-allowed}
            .navlink.disabled em{font-style:normal;font-size:11px;color:#4b5563;border:1px solid var(--line);
            padding:0 5px;border-radius:10px;margin-left:4px}
            .main{flex:1;padding:20px;max-width:1100px}
            h1{font-size:20px;margin:0 0 4px} h2{font-size:15px;margin:22px 0 10px;color:var(--muted)}
            .sub{color:var(--muted);margin:0 0 18px}
            .cards{display:grid;grid-template-columns:repeat(auto-fill,minmax(220px,1fr));gap:12px}
            .card{background:var(--panel);border:1px solid var(--line);border-radius:10px;padding:14px}
            .card .k{color:var(--muted);font-size:12px;text-transform:uppercase;letter-spacing:.04em}
            .card .v{font-size:20px;margin-top:4px}
            .pill{display:inline-block;padding:2px 9px;border-radius:999px;font-size:12px;font-weight:600}
            .pill.ok{background:rgba(63,185,80,.15);color:var(--ok)}
            .pill.err{background:rgba(248,81,73,.15);color:var(--err)}
            .pill.warn{background:rgba(210,153,34,.15);color:var(--warn)}
            .banner{border-radius:10px;padding:12px 14px;margin:0 0 16px;border:1px solid}
            .banner.err{background:rgba(248,81,73,.08);border-color:var(--err);color:#ffb4ae}
            table{width:100%;border-collapse:collapse;margin-top:8px}
            td,th{text-align:left;padding:7px 8px;border-bottom:1px solid var(--line)}
            th{color:var(--muted);font-weight:600;font-size:12px}
            .centered{display:flex;align-items:center;justify-content:center;min-height:100vh;padding:16px}
            .login{width:340px}
            label{display:block;color:var(--muted);font-size:12px;margin:12px 0 4px}
            input[type=text],input[type=password]{width:100%;padding:9px 10px;background:var(--panel2);
            border:1px solid var(--line);border-radius:8px;color:var(--txt)}
            .btn{margin-top:16px;width:100%;padding:10px;background:var(--accent);border:0;border-radius:8px;
            color:#fff;font-weight:600;cursor:pointer}
            .formerr{color:var(--err);margin-top:12px;font-size:13px}
            .muted{color:var(--muted)}
            .actions-panel{margin-top:6px}
            .poll-status{font-size:12px;margin-top:6px;color:var(--muted)}
            [hidden]{display:none}
            select,input[type=number],input[list]{width:100%;max-width:420px;padding:9px 10px;background:var(--panel2);
            border:1px solid var(--line);border-radius:8px;color:var(--txt)}
            .actform{background:var(--panel);border:1px solid var(--line);border-radius:10px;padding:12px 14px;margin:10px 0}
            .actform .btn{width:auto;max-width:none;padding:8px 16px}
            .btn.danger{background:var(--err)}
            label.inline{display:inline-flex;align-items:center;gap:6px;margin:10px 0 0;color:var(--txt);font-size:13px}
            label.inline.confirm{color:var(--warn)}
            .resline{margin:8px 0;font-size:13px}
            .agentpicker{display:flex;gap:8px;align-items:center;margin:8px 0}
            .agentpicker label{margin:0}
            details{margin:10px 0;border:1px solid var(--line);border-radius:8px;padding:8px 12px}
            summary{cursor:pointer}
            ol,ul{margin:6px 0;padding-left:20px}
            @media(max-width:720px){.shell{flex-direction:column}.side{width:auto;flex-direction:row;flex-wrap:wrap}
            .main{padding:14px}.login{width:100%}}
            """;
}
