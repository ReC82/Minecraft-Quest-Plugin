package com.lodygames.rpgquest.panel.web;

import com.lodygames.rpgquest.panel.http.Http;
import java.util.List;

/**
 * Gabarit HTML unique du Control Panel. Refonte graphique #92 : thème clair, design system par
 * tokens CSS, shell moderne (topbar + sidebar groupée + icônes SVG locales + tiroir mobile
 * sans JS). CSS et sprite d'icônes en ligne (aucun asset externe, cohérent avec la CSP
 * {@code default-src 'self'}). Un outil d'administration professionnel, pas une interface
 * Minecraft décorative.
 */
public final class Layout {

    /** Un lien de navigation. {@code icon} = nom d'icône {@link Icons} ; {@code enabled=false} = « à venir ». */
    public record NavItem(String label, String href, String icon, boolean enabled) {
    }

    /** Un groupe de navigation dans la sidebar. */
    public record NavGroup(String title, List<NavItem> items) {
    }

    public static List<NavGroup> nav() {
        return List.of(
                new NavGroup("Vue d'ensemble", List.of(
                        new NavItem("Dashboard", "/dashboard", "dashboard", true),
                        new NavItem("Agents", "/agents", "agents", true))),
                new NavGroup("RPGQuest", List.of(
                        new NavItem("Joueurs", "/players", "players", true),
                        new NavItem("PNJ", "/npcs", "npc", true),
                        new NavItem("Quêtes", "/quests", "quests", true),
                        new NavItem("Stories", "/stories", "stories", true),
                        new NavItem("Dialogues", "/dialogues", "dialogues", true))),
                new NavGroup("Ressources", List.of(
                        new NavItem("Documentation", "/docs", "docs", true),
                        new NavItem("Diagnostics", "/diagnostics", "diagnostics", false))),
                new NavGroup("Administration", List.of(
                        new NavItem("Admin", "/admin", "admin", false),
                        new NavItem("Développement", "/dev", "dev", false))));
    }

    /** Contexte du shell : cible/env, état serveur synthétique, utilisateur. Champs {@code null} = masqués. */
    public record Shell(String envLabel, String serverState, String username) {
        public static Shell of(String envLabel, String serverState, String username) {
            return new Shell(envLabel, serverState, username);
        }
    }

    public static String page(String title, String username, String activeHref, String content) {
        return page(title, activeHref, content, new Shell(null, null, username));
    }

    public static String page(String title, String activeHref, String content, Shell shell) {
        StringBuilder side = new StringBuilder();
        for (NavGroup g : nav()) {
            side.append("<div class=\"nav-group\"><p class=\"nav-group-t\">").append(Http.esc(g.title())).append("</p>");
            for (NavItem it : g.items()) {
                boolean active = it.href().equals(activeHref)
                        || (activeHref != null && activeHref.startsWith(it.href() + "/"));
                if (it.enabled()) {
                    side.append("<a class=\"navlink").append(active ? " active" : "").append("\" href=\"")
                            .append(it.href()).append("\">").append(Icons.icon(it.icon(), "nav-ic"))
                            .append("<span>").append(Http.esc(it.label())).append("</span></a>");
                } else {
                    side.append("<span class=\"navlink disabled\">").append(Icons.icon(it.icon(), "nav-ic"))
                            .append("<span>").append(Http.esc(it.label())).append("</span>")
                            .append("<em class=\"nav-soon\">bientôt</em></span>");
                }
            }
            side.append("</div>");
        }

        String envChip = shell.envLabel() == null ? ""
                : "<span class=\"env-chip\">" + Http.esc(shell.envLabel()) + "</span>";
        String statusChip = shell.serverState() == null ? "" : serverChip(shell.serverState());
        String userBox = shell.username() == null ? ""
                : "<div class=\"userbox\"><span class=\"userbox-n\">" + Http.esc(shell.username()) + "</span>"
                + "<form method=\"post\" action=\"/logout\">%CSRF%"
                + "<button class=\"iconbtn\" type=\"submit\" aria-label=\"Se déconnecter\" title=\"Se déconnecter\">"
                + Icons.icon("open") + "</button></form></div>";

        return """
                <!doctype html><html lang="fr"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>%TITLE% · PlugAdmin</title>
                <style>%CSS%</style></head><body>
                %SPRITE%
                <input type="checkbox" id="nav-toggle" class="nav-toggle" hidden>
                <header class="topbar">
                  <label for="nav-toggle" class="burger" aria-label="Menu">%BURGER%</label>
                  <div class="brand"><span class="brand-mark">PA</span>
                    <span class="brand-t">Plug<strong>Admin</strong></span>%ENV%</div>
                  <div class="topbar-r">%STATUS%%USERBOX%</div>
                </header>
                <div class="shell">
                  <label for="nav-toggle" class="scrim" aria-hidden="true"></label>
                  <nav class="side">%NAV%</nav>
                  <main class="main">%CONTENT%</main>
                </div>
                </body></html>
                """
                .replace("%TITLE%", Http.esc(title))
                .replace("%CSS%", CSS)
                .replace("%SPRITE%", Icons.sprite())
                .replace("%BURGER%", Icons.icon("menu"))
                .replace("%ENV%", envChip)
                .replace("%STATUS%", statusChip)
                .replace("%USERBOX%", userBox)
                .replace("%NAV%", side.toString())
                .replace("%CONTENT%", content);
    }

    private static String serverChip(String state) {
        String s = state == null ? "" : state.trim().toUpperCase(java.util.Locale.ROOT);
        String kind = switch (s) {
            case "ONLINE" -> "ok";
            case "OFFLINE" -> "err";
            case "STALE" -> "warn";
            default -> "neutral";
        };
        String ic = "ONLINE".equals(s) ? "online" : "OFFLINE".equals(s) ? "offline" : "server";
        return "<span class=\"srv-chip srv-" + kind + "\">" + Icons.icon(ic) + "<span>" + Http.esc(s) + "</span></span>";
    }

    public static String bare(String title, String content) {
        return """
                <!doctype html><html lang="fr"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>%TITLE% · PlugAdmin</title>
                <style>%CSS%</style></head><body class="centered">
                %SPRITE%
                <main class="card login">%CONTENT%</main></body></html>
                """
                .replace("%TITLE%", Http.esc(title))
                .replace("%CSS%", CSS)
                .replace("%SPRITE%", Icons.sprite())
                .replace("%CONTENT%", content);
    }

    // ================================================================================
    //  Design system — tokens + composants (issue #92)
    // ================================================================================

    private static final String CSS = """
            :root{
              --bg:#f4f6fb; --surface:#ffffff; --surface-2:#f7f9fc; --surface-3:#eef2f8;
              --sidebar:#1d2534; --sidebar-2:#232c3e; --sidebar-txt:#c4ccdb; --sidebar-muted:#8b97ac;
              --txt:#1f2733; --txt-2:#5b6676; --txt-faint:#8a94a4;
              --line:#e2e7f0; --line-2:#cfd7e4;
              --primary:#2f6df6; --primary-d:#255ad6; --primary-soft:#e8f0ff;
              --ok:#1f9d57; --ok-soft:#e5f6ec; --warn:#c07d12; --warn-soft:#fdf1dd;
              --err:#d93b3b; --err-soft:#fdeaea; --info:#1b7fa8; --info-soft:#e2f3f9;
              --radius:12px; --radius-s:8px; --radius-l:16px;
              --shadow:0 1px 2px rgba(20,30,50,.06),0 4px 14px rgba(20,30,50,.06);
              --shadow-s:0 1px 2px rgba(20,30,50,.08);
              --sp:16px; --content-max:1180px;
              --font:'Inter',system-ui,-apple-system,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;
              --mono:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;
            }
            *{box-sizing:border-box}
            html{-webkit-text-size-adjust:100%}
            body{margin:0;background:var(--bg);color:var(--txt);font:14.5px/1.6 var(--font);
              -webkit-font-smoothing:antialiased}
            a{color:var(--primary);text-decoration:none} a:hover{text-decoration:underline}
            code,kbd,samp{font-family:var(--mono)}
            :focus-visible{outline:2px solid var(--primary);outline-offset:2px;border-radius:4px}
            .ic{width:18px;height:18px;flex:none;vertical-align:-.16em}
            /* ---- topbar ---- */
            .topbar{position:sticky;top:0;z-index:30;display:flex;align-items:center;gap:12px;
              height:56px;padding:0 18px;background:var(--surface);border-bottom:1px solid var(--line)}
            .brand{display:flex;align-items:center;gap:9px;font-size:15px}
            .brand-mark{display:inline-flex;align-items:center;justify-content:center;width:28px;height:28px;
              border-radius:8px;background:var(--primary);color:#fff;font-weight:800;font-size:12px;letter-spacing:.02em}
            .brand-t strong{color:var(--primary)}
            .env-chip{margin-left:6px;padding:2px 9px;border-radius:999px;background:var(--primary-soft);
              color:var(--primary-d);font-size:11px;font-weight:700;letter-spacing:.04em}
            .topbar-r{margin-left:auto;display:flex;align-items:center;gap:12px}
            .srv-chip{display:inline-flex;align-items:center;gap:6px;padding:4px 10px;border-radius:999px;
              font-size:12px;font-weight:700;border:1px solid transparent}
            .srv-chip .ic{width:15px;height:15px}
            .srv-ok{background:var(--ok-soft);color:var(--ok);border-color:#bfe6cd}
            .srv-err{background:var(--err-soft);color:var(--err);border-color:#f4c4c4}
            .srv-warn{background:var(--warn-soft);color:var(--warn);border-color:#eed9b0}
            .srv-neutral{background:var(--surface-3);color:var(--txt-2);border-color:var(--line)}
            .userbox{display:flex;align-items:center;gap:8px}
            .userbox-n{font-size:13px;color:var(--txt-2);font-weight:600}
            .userbox form{display:inline;margin:0}
            .iconbtn{display:inline-flex;align-items:center;justify-content:center;width:32px;height:32px;
              border:1px solid var(--line);background:var(--surface);border-radius:8px;color:var(--txt-2);cursor:pointer}
            .iconbtn:hover{color:var(--txt);border-color:var(--line-2);background:var(--surface-2)}
            .burger{display:none;align-items:center;justify-content:center;width:34px;height:34px;
              border:1px solid var(--line);border-radius:8px;color:var(--txt-2);cursor:pointer}
            .nav-toggle{position:absolute}
            /* ---- shell ---- */
            .shell{display:flex;min-height:calc(100vh - 56px)}
            .side{width:236px;flex-shrink:0;background:var(--sidebar);padding:14px 12px;
              display:flex;flex-direction:column;gap:14px;overflow-y:auto}
            .nav-group-t{margin:0 0 4px;padding:0 10px;font-size:10.5px;font-weight:800;letter-spacing:.09em;
              text-transform:uppercase;color:var(--sidebar-muted)}
            .navlink{display:flex;align-items:center;gap:10px;padding:8px 10px;border-radius:9px;
              color:var(--sidebar-txt);font-size:13.5px;font-weight:500;line-height:1.2}
            .navlink:hover{background:var(--sidebar-2);color:#fff;text-decoration:none}
            .navlink.active{background:var(--primary);color:#fff;font-weight:650;box-shadow:0 2px 8px rgba(47,109,246,.35)}
            .navlink .nav-ic{width:17px;height:17px;flex:none;opacity:.9}
            .navlink.active .nav-ic{opacity:1}
            .navlink.disabled{color:var(--sidebar-muted);cursor:default}
            .navlink.disabled:hover{background:none;color:var(--sidebar-muted)}
            .nav-soon{margin-left:auto;font-size:9.5px;font-weight:700;letter-spacing:.05em;text-transform:uppercase;
              color:var(--sidebar-muted);border:1px solid #3a4459;border-radius:999px;padding:0 6px}
            .scrim{display:none}
            .main{flex:1;min-width:0;padding:26px 30px;max-width:calc(var(--content-max) + 60px);width:100%}
            /* ---- page header standard ---- */
            .pagehead{display:flex;flex-wrap:wrap;align-items:flex-start;gap:12px 18px;margin:0 0 20px}
            .pagehead .ph-l{min-width:0;flex:1 1 auto}
            .pagehead .ph-title{display:flex;align-items:center;gap:10px}
            .pagehead .ph-title .ic{width:22px;height:22px;color:var(--primary)}
            .pagehead h1{font-size:22px;font-weight:750;margin:0;letter-spacing:-.01em}
            .pagehead .sub{margin:5px 0 0;color:var(--txt-2);max-width:78ch;font-size:13.5px}
            .pagehead .ph-actions{display:flex;flex-wrap:wrap;gap:8px;align-items:center}
            h1{font-size:21px;margin:0 0 4px;letter-spacing:-.01em}
            h2{font-size:12px;font-weight:750;text-transform:uppercase;letter-spacing:.07em;
              color:var(--txt-faint);margin:28px 0 12px}
            h3{font-size:14.5px;margin:16px 0 8px;font-weight:650}
            .sub{color:var(--txt-2);margin:0 0 16px;max-width:74ch}
            .muted{color:var(--txt-2)} .faint{color:var(--txt-faint)}
            .section-title{display:flex;align-items:center;gap:8px;margin:26px 0 12px;font-size:12px;
              font-weight:750;text-transform:uppercase;letter-spacing:.07em;color:var(--txt-faint)}
            .section-title .ic{width:15px;height:15px}
            /* ---- toolbar : recherche + filtres ---- */
            .toolbar{display:flex;flex-wrap:wrap;gap:10px;align-items:center;margin:0 0 16px}
            .search{position:relative;flex:1 1 280px;max-width:460px}
            .search .ic{position:absolute;left:12px;top:50%;transform:translateY(-50%);color:var(--txt-faint);
              width:17px;height:17px;pointer-events:none}
            .search input{width:100%;padding:9px 12px 9px 36px;background:var(--surface);border:1px solid var(--line-2);
              border-radius:10px;color:var(--txt);font:inherit}
            .search input:focus{outline:2px solid rgba(47,109,246,.35);border-color:var(--primary)}
            .chips{display:flex;flex-wrap:wrap;gap:6px}
            .chip{display:inline-flex;align-items:center;gap:5px;padding:6px 12px;border-radius:999px;
              background:var(--surface);border:1px solid var(--line-2);color:var(--txt-2);font-size:12.5px;
              font-weight:600;cursor:pointer;user-select:none}
            .chip:hover{border-color:var(--primary);color:var(--primary)}
            .chip.on{background:var(--primary);border-color:var(--primary);color:#fff}
            .chip .ic{width:13px;height:13px}
            .count-note{font-size:12.5px;color:var(--txt-faint);margin:0 0 12px}
            /* ---- cartes / surfaces ---- */
            .cards{display:grid;grid-template-columns:repeat(auto-fill,minmax(220px,1fr));gap:14px;margin:8px 0 4px}
            .card{background:var(--surface);border:1px solid var(--line);border-radius:var(--radius);
              padding:15px 16px;box-shadow:var(--shadow-s)}
            .card .k{color:var(--txt-faint);font-size:10.5px;font-weight:700;text-transform:uppercase;letter-spacing:.06em}
            .card .v{font-size:20px;margin-top:4px;font-weight:700}
            /* stat card */
            .stat{display:flex;gap:12px;align-items:flex-start;background:var(--surface);border:1px solid var(--line);
              border-radius:var(--radius);padding:14px 16px;box-shadow:var(--shadow-s)}
            .stat-ic{display:inline-flex;align-items:center;justify-content:center;width:38px;height:38px;flex:none;
              border-radius:10px;background:var(--primary-soft);color:var(--primary-d)}
            .stat-ic .ic{width:19px;height:19px}
            .stat.ok .stat-ic{background:var(--ok-soft);color:var(--ok)}
            .stat.warn .stat-ic{background:var(--warn-soft);color:var(--warn)}
            .stat.err .stat-ic{background:var(--err-soft);color:var(--err)}
            .stat-b{min-width:0}
            .stat-v{font-size:19px;font-weight:750;line-height:1.15}
            .stat-k{font-size:12px;color:var(--txt-2);margin-top:1px}
            .stat-l{font-size:11.5px;margin-top:3px}
            .panelbox{background:var(--surface);border:1px solid var(--line);border-radius:var(--radius);
              padding:16px 18px;box-shadow:var(--shadow-s);margin:0 0 16px}
            /* hero serveur (dashboard) */
            .hero{display:flex;flex-wrap:wrap;gap:16px 24px;align-items:center;background:var(--surface);
              border:1px solid var(--line);border-radius:var(--radius-l);padding:18px 22px;box-shadow:var(--shadow);margin:0 0 18px}
            .hero-main{display:flex;align-items:center;gap:14px}
            .hero-ic{display:inline-flex;align-items:center;justify-content:center;width:46px;height:46px;flex:none;
              border-radius:12px;background:var(--surface-3);color:var(--txt-2)}
            .hero-ic .ic{width:24px;height:24px}
            .hero.ok .hero-ic{background:var(--ok-soft);color:var(--ok)}
            .hero.err .hero-ic{background:var(--err-soft);color:var(--err)}
            .hero-name{font-size:17px;font-weight:750}
            .hero-state{font-size:12.5px;color:var(--txt-2);margin-top:2px}
            .hero-facts{display:flex;flex-wrap:wrap;gap:6px 22px;margin-left:auto}
            .hero-fact .hf-k{font-size:10.5px;text-transform:uppercase;letter-spacing:.05em;color:var(--txt-faint)}
            .hero-fact .hf-v{font-size:14px;font-weight:650}
            /* ---- carte entité (quête / story / PNJ / dialogue) ---- */
            .entity-card{background:var(--surface);border:1px solid var(--line);border-radius:var(--radius);
              padding:15px 17px;margin:12px 0;box-shadow:var(--shadow-s)}
            .entity-card:hover{border-color:var(--line-2)}
            .entity-head{display:flex;flex-wrap:wrap;align-items:baseline;gap:8px 12px;justify-content:space-between}
            .entity-name{font-size:15.5px;font-weight:700;margin:0;color:var(--txt);line-height:1.3}
            .entity-meta{display:flex;flex-wrap:wrap;gap:6px;align-items:center}
            .meta-line{margin:8px 0 0;font-size:13px;color:var(--txt-2)}
            .meta-k{display:inline-block;min-width:92px;color:var(--txt-faint);font-size:10.5px;font-weight:700;
              text-transform:uppercase;letter-spacing:.05em}
            .obj-list,.step-list,.reward-list{margin:8px 0 0;padding:0;list-style:none;display:flex;
              flex-direction:column;gap:6px}
            .obj-list li,.step-list li,.reward-list li{display:flex;flex-wrap:wrap;align-items:center;
              gap:8px;font-size:13.5px}
            .obj-list li,.reward-list li{padding-left:16px;position:relative}
            .obj-list li::before{content:"";position:absolute;left:2px;top:8px;width:6px;height:6px;border-radius:50%;
              background:var(--primary)}
            .reward-list li::before{content:"";position:absolute;left:2px;top:7px;width:7px;height:7px;
              background:var(--warn);transform:rotate(45deg)}
            .step-n{display:inline-flex;align-items:center;justify-content:center;width:22px;height:22px;
              flex-shrink:0;border-radius:50%;background:var(--primary-soft);border:1px solid #cfe0ff;
              font-size:11px;font-weight:700;color:var(--primary-d)}
            .obj-text{color:var(--txt);overflow-wrap:anywhere}
            .act-type{color:var(--txt)}
            .reward-list .obj-text{font-weight:600}
            /* ---- identifiant technique en second plan ---- */
            .tid{font-size:11.5px;color:var(--txt-faint);background:var(--surface-3);border:1px solid var(--line);
              border-radius:6px;padding:1px 6px;white-space:nowrap;font-family:var(--mono)}
            .tid--wrap{white-space:normal;word-break:break-word}
            .tid[data-copy]{cursor:pointer;transition:color .1s,border-color .1s}
            .tid[data-copy]:hover,.tid[data-copy]:focus-visible{color:var(--txt-2);border-color:var(--line-2);outline:none}
            .tid.copied{color:var(--ok);border-color:var(--ok);background:var(--ok-soft)}
            .badge{display:inline-flex;align-items:center;gap:4px;font-size:11px;color:var(--txt-2);
              background:var(--surface-3);border:1px solid var(--line);border-radius:999px;padding:1px 9px;white-space:nowrap}
            .badge .ic{width:12px;height:12px}
            /* ---- pastilles de statut (glyphe + texte, jamais couleur seule) ---- */
            .pill{display:inline-flex;align-items:center;gap:5px;padding:2px 10px;border-radius:999px;
              font-size:11.5px;font-weight:700;border:1px solid transparent;white-space:nowrap;vertical-align:middle}
            .pill-g{font-weight:800;font-size:11px;line-height:1}
            .pill--success,.pill.ok{background:var(--ok-soft);color:var(--ok);border-color:#bfe6cd}
            .pill--delivered{background:var(--info-soft);color:var(--info);border-color:#bfe1ec}
            .pill--pending,.pill.warn{background:var(--warn-soft);color:var(--warn);border-color:#eed9b0}
            .pill--failed,.pill.err{background:var(--err-soft);color:var(--err);border-color:#f4c4c4}
            .pill--rejected{background:var(--err-soft);color:var(--err);border-color:#f4c4c4;border-style:dashed}
            .pill--expired{background:var(--surface-3);color:var(--txt-faint);border-color:var(--line)}
            .pill--neutral{background:var(--surface-3);color:var(--txt-2);border-color:var(--line)}
            /* ---- bannières / callouts ---- */
            .banner{border-radius:var(--radius-s);padding:11px 14px;margin:0 0 16px;border:1px solid;font-size:13.5px;
              display:flex;gap:9px;align-items:flex-start}
            .banner .ic{width:17px;height:17px;flex:none;margin-top:1px}
            .banner.err{background:var(--err-soft);border-color:#f0bcbc;color:#a52020}
            .banner.ok{background:var(--ok-soft);border-color:#bfe6cd;color:#166b3d}
            .banner.info{background:var(--info-soft);border-color:#bfe1ec;color:#12566f}
            .banner.warn{background:var(--warn-soft);border-color:#eed9b0;color:#8a5a0c}
            /* ---- tableaux ---- */
            .table-wrap{overflow-x:auto;border:1px solid var(--line);border-radius:var(--radius);
              margin-top:8px;background:var(--surface);box-shadow:var(--shadow-s)}
            table{width:100%;border-collapse:collapse}
            .table-wrap table{margin:0}
            td,th{text-align:left;padding:9px 12px;border-bottom:1px solid var(--line);vertical-align:top;font-size:13px}
            tbody tr:last-child td{border-bottom:0}
            tbody tr:hover{background:var(--surface-2)}
            th{color:var(--txt-faint);font-weight:750;font-size:10.5px;text-transform:uppercase;letter-spacing:.04em;
              background:var(--surface-2)}
            /* ---- état vide ---- */
            .empty{color:var(--txt-2);font-size:13.5px;background:var(--surface);border:1px dashed var(--line-2);
              border-radius:var(--radius);padding:22px 18px;margin:10px 0;text-align:center}
            .empty .ic{width:22px;height:22px;color:var(--txt-faint);display:block;margin:0 auto 6px}
            /* ---- formulaires ---- */
            label{display:block;color:var(--txt-2);font-size:12.5px;font-weight:600;margin:14px 0 5px}
            .field-help{font-size:11.5px;color:var(--txt-faint);margin:3px 0 0;font-weight:400}
            input[type=text],input[type=password],input[type=number],input[type=search],input[list],select,textarea{
              width:100%;max-width:460px;padding:9px 11px;background:var(--surface);border:1px solid var(--line-2);
              border-radius:9px;color:var(--txt);font:inherit}
            textarea{min-height:80px;resize:vertical}
            input:focus,select:focus,textarea:focus{outline:2px solid rgba(47,109,246,.35);border-color:var(--primary)}
            input:disabled,select:disabled,textarea:disabled{background:var(--surface-3);color:var(--txt-faint);cursor:not-allowed}
            .form-grid{display:grid;grid-template-columns:1fr 1fr;gap:0 22px}
            .form-grid .full{grid-column:1/-1}
            .form-section{background:var(--surface);border:1px solid var(--line);border-radius:var(--radius);
              padding:14px 18px 18px;margin:12px 0;box-shadow:var(--shadow-s)}
            .form-section>.fs-h{display:flex;align-items:center;gap:8px;margin:0 0 4px;font-weight:700;font-size:14px}
            .form-section>.fs-h .ic{width:16px;height:16px;color:var(--primary)}
            .form-section>.fs-d{font-size:12.5px;color:var(--txt-2);margin:0 0 6px}
            .btn{margin-top:14px;padding:9px 16px;background:var(--primary);border:1px solid var(--primary);
              border-radius:9px;color:#fff;font-weight:650;font:inherit;font-weight:650;cursor:pointer;
              display:inline-flex;align-items:center;gap:7px}
            .btn .ic{width:15px;height:15px}
            .btn:hover{background:var(--primary-d);text-decoration:none}
            .btn.secondary{background:var(--surface);color:var(--txt);border-color:var(--line-2)}
            .btn.secondary:hover{background:var(--surface-2)}
            .btn.danger{background:var(--err);border-color:var(--err)}
            .btn.danger:hover{filter:brightness(.94)}
            .btn.sm{padding:6px 11px;font-size:12.5px;margin-top:0}
            .btn:disabled{opacity:.5;cursor:not-allowed}
            .btnrow{display:flex;flex-wrap:wrap;gap:8px;align-items:center;margin-top:14px}
            .formerr{color:var(--err);margin-top:12px;font-size:13px;font-weight:600}
            .actform{background:var(--surface);border:1px solid var(--line);border-radius:var(--radius);
              padding:13px 16px;margin:10px 0;box-shadow:var(--shadow-s)}
            .actform.read{border-left:3px solid var(--line-2)}
            .actform .btn{width:auto}
            .actform.read .btn{margin-top:0;background:var(--surface);color:var(--txt);border-color:var(--line-2)}
            .actform.read .btn:hover{background:var(--surface-2)}
            label.inline{display:inline-flex;align-items:center;gap:7px;margin:10px 0 0;color:var(--txt);
              font-size:13px;font-weight:500}
            label.inline input{width:auto;max-width:none}
            label.inline.confirm{color:var(--warn);font-weight:600}
            .resline{margin:8px 0;font-size:13px}
            .preview{background:var(--surface-2);border:1px solid var(--line);border-radius:var(--radius-s);
              padding:10px 14px;margin:8px 0}
            .preview .meta-line{margin:4px 0}
            .coord-row{display:flex;flex-wrap:wrap;gap:10px;margin-top:8px}
            .coord-row label.coord{display:flex;flex-direction:column;gap:3px;margin:0;font-size:12px;
              color:var(--txt-2);flex:1 1 80px}
            .coord-row label.coord input{width:100%}
            /* ---- diff / aperçu code ---- */
            .codeblock{position:relative;margin:12px 0}
            .codeblock pre{margin:0;background:#0f1729;border:1px solid #1e2b45;border-radius:10px;
              padding:14px 16px;overflow-x:auto;color:#dbe4f3;font-size:12.5px;line-height:1.55;white-space:pre}
            .codeblock .doc-copy,.doc-cmd .doc-copy{position:absolute;top:8px;right:8px}
            .diff pre{background:#0f1729}
            .diff .di-add{color:#7ee0a1} .diff .di-del{color:#ff9d9d} .diff .di-ctx{color:#9fb0cc}
            /* ---- éditeur : liste d'items (objectifs / quêtes de story) ---- */
            .rowlist{display:flex;flex-direction:column;gap:10px;margin:8px 0}
            .rowitem{background:var(--surface-2);border:1px solid var(--line);border-radius:10px;padding:12px 14px}
            .rowitem-h{display:flex;align-items:center;gap:8px;font-weight:650;font-size:13.5px}
            .rowitem-h .grip{color:var(--txt-faint)}
            .rowitem-actions{margin-left:auto;display:flex;gap:4px}
            .rowitem-actions .iconbtn{width:28px;height:28px}
            .rowitem-actions .iconbtn .ic{width:15px;height:15px}
            /* ---- graphe de dialogue (#82) ---- */
            .dlg-summary{margin:4px 0 2px}
            .dlg-diag{margin:10px 0 2px;border-left:3px solid var(--line-2);padding:2px 0 2px 10px}
            .dlg-diag-h{margin:0 0 4px;font-size:11px;text-transform:uppercase;letter-spacing:.05em;color:var(--txt-faint)}
            .dlg-graph-wrap>summary{font-weight:650;color:var(--txt)}
            .dlg-graph{display:flex;flex-direction:column;gap:10px;margin-top:8px}
            .dlg-node{border:1px solid var(--line);border-left:3px solid var(--line-2);border-radius:8px;
              padding:9px 12px;background:var(--surface-2)}
            .dlg-node.start{border-left-color:var(--primary)}
            .dlg-node.unreachable{border-left-color:var(--err);opacity:.9}
            .dlg-node-head{display:flex;align-items:center;gap:6px;flex-wrap:wrap}
            .dlg-speaker{margin:7px 0 2px;font-weight:650;font-size:13px}
            .dlg-text{margin:0 0 6px;font-size:13px;color:var(--txt-2);overflow-wrap:anywhere}
            .dlg-choices{margin:4px 0 0;padding-left:20px}
            .dlg-choices li{margin:5px 0;font-size:12.5px;overflow-wrap:anywhere}
            .dlg-arrow{color:var(--primary);font-weight:650;white-space:nowrap}
            .dlg-choice-text{color:var(--txt)}
            .dlg-fx{display:inline-block;font-size:11px;color:var(--info);background:var(--info-soft);
              border:1px solid #bfe1ec;border-radius:6px;padding:0 6px}
            .dlg-cond{display:inline-block;font-size:11px;color:var(--warn);background:var(--warn-soft);
              border:1px solid #eed9b0;border-radius:6px;padding:0 6px}
            .dlg-adv{font-size:11px}
            .dlg-node-edit{display:flex;flex-wrap:wrap;gap:8px;margin-top:8px}
            .dlg-edit{margin:0;flex:1 1 240px;background:var(--surface-3);border-color:var(--line)}
            .dlg-edit>summary{font-size:12px;color:var(--primary)}
            .dlg-edit .actform{background:transparent;border:0;padding:6px 0 0;margin:4px 0 0;box-shadow:none}
            .dlg-edit-choice{display:block;margin-top:4px}
            .dlg-add-node{margin-top:10px}
            .agentpicker{display:flex;gap:8px;align-items:center;margin:8px 0}
            .agentpicker label{margin:0}
            .actions-panel{margin-top:6px}
            .poll-status{font-size:12px;margin-top:6px;color:var(--txt-2)}
            [hidden]{display:none}
            .danger-zone{border:1px solid #f0c5c5;border-left:3px solid var(--err);border-radius:var(--radius);
              padding:6px 16px 16px;margin:16px 0;background:var(--err-soft)}
            .danger-zone>.dz-title{display:flex;align-items:center;gap:8px;margin:10px 0 2px;font-size:11.5px;
              text-transform:uppercase;letter-spacing:.05em;color:#a52020;font-weight:800}
            .danger-zone details{border-color:#f0c5c5}
            details{margin:10px 0;border:1px solid var(--line);border-radius:10px;padding:10px 14px;background:var(--surface)}
            summary{cursor:pointer;color:var(--txt-2);font-weight:600}
            details[open] summary{margin-bottom:8px}
            ol,ul{margin:6px 0;padding-left:20px}
            /* ---- centre de documentation (#49) ---- */
            .doc-search{display:flex;gap:8px;margin:14px 0 22px}
            .doc-search input[type=search]{flex:1;max-width:660px;padding:12px 16px;font-size:15px;
              background:var(--surface);border:1px solid var(--line-2);border-radius:12px;color:var(--txt);
              box-shadow:var(--shadow-s)}
            .doc-search input[type=search]:focus{outline:2px solid rgba(47,109,246,.35);border-color:var(--primary)}
            .doc-search .btn{margin:0;white-space:nowrap}
            .doc-shortcuts{display:flex;flex-wrap:wrap;gap:9px;margin:6px 0 4px}
            .doc-chip{display:inline-flex;align-items:center;gap:7px;padding:9px 14px;border-radius:12px;
              background:var(--surface);border:1px solid var(--line);color:var(--txt);font-size:13px;box-shadow:var(--shadow-s)}
            .doc-chip .ic{width:15px;height:15px;color:var(--primary)}
            .doc-chip:hover{border-color:var(--primary);text-decoration:none;box-shadow:var(--shadow)}
            .doc-cats{display:grid;grid-template-columns:repeat(auto-fill,minmax(250px,1fr));gap:14px;margin-top:8px}
            .doc-cat{background:var(--surface);border:1px solid var(--line);border-radius:var(--radius);
              padding:14px 16px;box-shadow:var(--shadow-s)}
            .doc-cat h3{margin:0 0 8px;font-size:12px;text-transform:uppercase;letter-spacing:.05em;color:var(--txt-faint);
              display:flex;align-items:center;gap:7px}
            .doc-cat h3 .ic{width:15px;height:15px;color:var(--primary)}
            .doc-cat ul{margin:0;padding-left:2px;list-style:none} .doc-cat li{margin:5px 0;font-size:13.5px}
            .doc-cat li a{display:flex;align-items:center;gap:6px}
            .doc-cat li a::before{content:"";width:5px;height:5px;border-radius:50%;background:var(--line-2);flex:none}
            .doc-hits{display:flex;flex-direction:column;gap:10px;margin-top:6px}
            .doc-hit{display:block;background:var(--surface);border:1px solid var(--line);border-left:3px solid var(--primary);
              border-radius:10px;padding:12px 16px;box-shadow:var(--shadow-s)}
            .doc-hit:hover{box-shadow:var(--shadow);text-decoration:none}
            .doc-hit-cat{display:block;font-size:10.5px;text-transform:uppercase;letter-spacing:.05em;color:var(--txt-faint)}
            .doc-hit-title{display:block;font-weight:700;color:var(--txt);margin:2px 0 3px}
            .doc-hit-snip{display:block;font-size:12.5px;color:var(--txt-2)}
            .doc-crumbs{font-size:12.5px;color:var(--txt-2);margin:2px 0 14px}
            .doc-crumbs span{color:var(--txt-faint);margin:0 5px}
            .doc-layout{display:grid;grid-template-columns:220px 1fr;gap:26px;align-items:start}
            .doc-toc{position:sticky;top:74px;font-size:13px;border-left:2px solid var(--line);padding-left:14px}
            .doc-toc-h{margin:0 0 5px;font-size:10.5px;text-transform:uppercase;letter-spacing:.05em;color:var(--txt-faint);font-weight:750}
            .doc-toc ul{list-style:none;margin:0 0 14px;padding:0} .doc-toc li{margin:5px 0}
            .doc-toc li.lvl3{padding-left:12px;font-size:12.5px}
            .doc-tags .badge{margin:2px 4px 2px 0}
            .doc-body{min-width:0;max-width:800px;background:var(--surface);border:1px solid var(--line);
              border-radius:var(--radius-l);padding:26px 34px;box-shadow:var(--shadow-s)}
            .doc-body h1{font-size:24px;margin:0 0 14px;font-weight:750}
            .doc-body h2{font-size:16px;margin:28px 0 10px;color:var(--txt);text-transform:none;letter-spacing:0;
              border-bottom:1px solid var(--line);padding-bottom:6px;font-weight:700}
            .doc-body h3{font-size:14.5px;margin:20px 0 7px;color:var(--txt-2)}
            .doc-body p{margin:10px 0} .doc-body li{margin:5px 0}
            .doc-body code{background:var(--surface-3);border:1px solid var(--line);border-radius:5px;padding:1px 5px;
              font-size:12.5px;color:#243b53;overflow-wrap:anywhere}
            .doc-body a{overflow-wrap:anywhere}
            .doc-cmd{position:relative;margin:14px 0}
            .doc-cmd pre{margin:0;background:#0f1729;border:1px solid #1e2b45;border-radius:10px;
              padding:14px 16px;overflow-x:auto}
            .doc-cmd pre code{background:none;border:0;padding:0;font-size:12.5px;color:#dbe4f3;white-space:pre}
            .doc-copy{font:inherit;font-size:11px;font-weight:600;cursor:pointer;background:#1e2b45;color:#aab8d4;
              border:1px solid #2c3d5e;border-radius:6px;padding:3px 9px;display:inline-flex;align-items:center;gap:5px}
            .doc-copy .ic{width:12px;height:12px}
            .doc-copy:hover{color:#fff;border-color:#3a4e74}
            .doc-copy.copied{color:#7ee0a1;border-color:#7ee0a1}
            .doc-tablewrap{overflow-x:auto;border:1px solid var(--line);border-radius:10px;margin:14px 0}
            .doc-tablewrap table{width:100%;border-collapse:collapse;margin:0}
            .doc-tablewrap th,.doc-tablewrap td{border-bottom:1px solid var(--line);padding:8px 12px;
              text-align:left;font-size:13px;vertical-align:top}
            .doc-tablewrap th{background:var(--surface-2);color:var(--txt-faint);font-size:10.5px;text-transform:uppercase;letter-spacing:.04em}
            .doc-callout{border:1px solid var(--line);border-left:3px solid var(--info);border-radius:10px;
              padding:10px 16px;margin:14px 0;background:var(--info-soft)}
            .doc-callout.doc-warn{border-left-color:var(--warn);background:var(--warn-soft)}
            .doc-callout .doc-callout-h{display:block;font-size:10.5px;font-weight:800;text-transform:uppercase;
              letter-spacing:.05em;color:var(--info);margin-bottom:3px}
            .doc-callout.doc-warn .doc-callout-h{color:var(--warn)}
            .doc-callout p{margin:2px 0}
            .doc-source{font-size:11.5px;margin-top:26px;border-top:1px solid var(--line);padding-top:10px;color:var(--txt-faint)}
            .doc-cm-link{display:inline-flex;align-items:center;gap:6px;margin:0 0 14px;font-size:12.5px;
              background:var(--surface);border:1px solid var(--line);border-radius:999px;padding:5px 12px;box-shadow:var(--shadow-s)}
            .doc-cm-link .ic{width:14px;height:14px}
            /* ---- connexion ---- */
            .centered{display:flex;align-items:center;justify-content:center;min-height:100vh;padding:16px;
              background:linear-gradient(160deg,#eef3ff,#f4f6fb 55%)}
            .login{width:360px;max-width:100%;box-shadow:var(--shadow);border-radius:var(--radius-l);padding:26px}
            .login h1{font-size:19px;margin-bottom:4px}
            .login .btn{width:100%;justify-content:center}
            .card.login{background:var(--surface);border:1px solid var(--line)}
            /* ---- responsive ---- */
            @media(max-width:1000px){.main{padding:20px}.doc-layout{grid-template-columns:180px 1fr;gap:18px}}
            @media(max-width:820px){
              .burger{display:inline-flex}
              .side{position:fixed;left:0;top:56px;bottom:0;z-index:40;transform:translateX(-100%);
                transition:transform .18s ease;box-shadow:0 0 40px rgba(0,0,0,.35)}
              .nav-toggle:checked ~ .shell .side{transform:translateX(0)}
              .nav-toggle:checked ~ .shell .scrim{display:block;position:fixed;inset:56px 0 0;z-index:35;
                background:rgba(12,18,30,.45)}
              .main{padding:16px}
              .hero-facts{margin-left:0}
              .doc-layout{grid-template-columns:1fr}
              .doc-toc{position:static;border-left:0;border-top:1px solid var(--line);padding:12px 0 0;margin-top:18px;order:2}
              .doc-body{order:1;max-width:100%;padding:18px 18px}
              .form-grid{grid-template-columns:1fr}
              input[type=text],input[type=password],input[type=number],input[type=search],input[list],select,textarea{max-width:100%}
              .meta-k{min-width:0;display:block;margin-bottom:2px}
              .doc-search{flex-direction:column} .doc-search .btn{width:100%}
              .pagehead .ph-actions{width:100%}
            }
            """;
}
