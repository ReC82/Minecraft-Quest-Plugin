package com.lodygames.rpgquest.panel.web;

import com.lodygames.rpgquest.panel.agent.AgentLiveness;
import com.lodygames.rpgquest.panel.diag.DiagnosticEntry;
import com.lodygames.rpgquest.panel.diag.DiagnosticsReport;
import com.lodygames.rpgquest.panel.diag.Domain;
import com.lodygames.rpgquest.panel.diag.Severity;
import com.lodygames.rpgquest.panel.http.Http;
import com.lodygames.rpgquest.panel.security.Session;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Page <strong>/diagnostics</strong> (issue #38) : « Que dois-je corriger maintenant ? ». Rendu
 * chaîne, sans état. Priorité de lecture : <em>humain → conséquence → action → documentation →
 * technique</em>. Les données viennent d'un {@link DiagnosticsReport} déjà agrégé et trié par
 * {@code DiagnosticsService} ; ici, uniquement de la présentation.
 */
public final class DiagnosticsPages {

    /** Seuil de regroupement : au-delà, les diagnostics identiques d'un domaine sont pliés (§12). */
    private static final int GROUP_THRESHOLD = 4;

    private DiagnosticsPages() {
    }

    public static String render(Session session, String agentId, DiagnosticsReport report, Instant now) {
        StringBuilder sb = new StringBuilder();
        sb.append(Ui.pageHeader("diagnostics", "Diagnostics",
                "État de cohérence de RPGQuest et problèmes à corriger.",
                refreshButton(session, agentId)));

        // ---- Fraîcheur ----
        String fresh = freshnessLabel(report.oldestSnapshot(), now);
        if (!fresh.isBlank()) {
            sb.append("<p class=\"diag-fresh muted\">").append(Icons.icon("clock")).append(Http.esc(fresh)).append("</p>");
        }

        if (!report.anyDataLoaded()) {
            sb.append(Ui.banner("info", "<strong>Aucun relevé disponible.</strong> Cliquez sur "
                    + "« Actualiser les diagnostics » pour que l'agent renvoie l'état courant du serveur "
                    + "et de son contenu."));
            return sb.toString();
        }

        // ---- Cartes synthétiques ----
        sb.append("<div class=\"diag-summary cards\">");
        sb.append(Ui.statCard("error", String.valueOf(report.errors()), plural(report.errors(), "Erreur", "Erreurs"),
                report.errors() > 0 ? "err" : "", null));
        sb.append(Ui.statCard("warning", String.valueOf(report.warnings()),
                plural(report.warnings(), "Avertissement", "Avertissements"),
                report.warnings() > 0 ? "warn" : "", null));
        sb.append(Ui.statCard("info", String.valueOf(report.infos()),
                plural(report.infos(), "Information", "Informations"), "", null));
        sb.append(Ui.statCard("diagnostics", String.valueOf(report.total()), "Total", "", null));
        sb.append("</div>");

        // ---- État vide positif (§26) ----
        if (report.isClean()) {
            sb.append("<div class=\"diag-clean\">").append(Icons.icon("check"))
                    .append("<div><p class=\"diag-clean-t\">Aucun problème détecté</p>")
                    .append("<p class=\"muted\">RPGQuest ne présente actuellement aucune incohérence connue")
                    .append(report.infos() > 0 ? " (quelques informations à surveiller ci-dessous)." : ".")
                    .append("</p></div></div>");
            if (report.infos() == 0) {
                return sb.toString();
            }
        }

        // ---- Filtres (gravité + domaine + recherche, combinables) ----
        sb.append(filters(report));
        sb.append("<p class=\"count-note\" data-count-note data-noun=\"diagnostic\">")
                .append(report.entries().size()).append(" diagnostic(s)</p>");

        // ---- Liste ----
        sb.append("<div class=\"diag-list\" id=\"diag-list\">");
        for (Map.Entry<String, List<DiagnosticEntry>> grp : grouped(report.entries()).entrySet()) {
            List<DiagnosticEntry> items = grp.getValue();
            if (items.size() >= GROUP_THRESHOLD) {
                sb.append(groupCard(items));
            } else {
                for (DiagnosticEntry e : items) {
                    sb.append(card(e, false));
                }
            }
        }
        sb.append("</div>");
        return sb.toString();
    }

    // ================================================================================
    //  Fragments
    // ================================================================================

    private static String refreshButton(Session session, String agentId) {
        return "<form method=\"post\" action=\"/diagnostics/refresh\" class=\"d-inline\">"
                + "<input type=\"hidden\" name=\"_csrf\" value=\"" + Http.esc(session.csrfToken()) + "\">"
                + "<input type=\"hidden\" name=\"agent\" value=\"" + Http.esc(agentId == null ? "" : agentId) + "\">"
                + "<button class=\"btn btn-sm btn-outline-primary\" type=\"submit\">"
                + Icons.icon("refresh") + "Actualiser les diagnostics</button></form>";
    }

    private static String filters(DiagnosticsReport report) {
        StringBuilder sb = new StringBuilder("<div class=\"diag-controls\">");
        // recherche
        sb.append("<div class=\"input-group diag-search\"><span class=\"input-group-text\">")
                .append(Icons.icon("search")).append("</span>")
                .append("<input type=\"search\" class=\"form-control\" data-filter-input=\"diag\" "
                        + "placeholder=\"Rechercher un problème, une ressource, un code…\" "
                        + "aria-label=\"Rechercher un diagnostic\"></div>");
        // gravité
        sb.append("<div class=\"diag-chips\" data-filter-chips=\"diag\" role=\"group\" aria-label=\"Filtrer par gravité\">");
        sb.append(chip("", "Toutes gravités", true));
        if (report.errors() > 0) {
            sb.append(chip("err", "Erreurs", false));
        }
        if (report.warnings() > 0) {
            sb.append(chip("warn", "Avertissements", false));
        }
        if (report.infos() > 0) {
            sb.append(chip("info", "Infos", false));
        }
        sb.append("</div>");
        // domaine
        if (report.domainsPresent().size() > 1) {
            sb.append("<div class=\"diag-chips\" data-filter-chips=\"diag\" role=\"group\" aria-label=\"Filtrer par domaine\">");
            sb.append(chip("", "Tous domaines", true));
            for (Domain d : Domain.values()) {
                if (report.domainsPresent().contains(d)) {
                    sb.append(chip(d.filterKey(), d.label(), false));
                }
            }
            sb.append("</div>");
        }
        return sb.append("</div>").toString();
    }

    private static String chip(String value, String label, boolean on) {
        return "<button type=\"button\" class=\"pa-chip" + (on ? " on" : "") + "\" data-filter-chip=\""
                + Http.esc(value) + "\">" + Http.esc(label) + "</button>";
    }

    /** Regroupe par (code + domaine) en conservant l'ordre de tri déjà appliqué. */
    private static Map<String, List<DiagnosticEntry>> grouped(List<DiagnosticEntry> entries) {
        Map<String, List<DiagnosticEntry>> map = new LinkedHashMap<>();
        for (DiagnosticEntry e : entries) {
            map.computeIfAbsent(e.severity().name() + "|" + e.domain().name() + "|" + e.code(),
                    k -> new ArrayList<>()).add(e);
        }
        return map;
    }

    private static String groupCard(List<DiagnosticEntry> items) {
        DiagnosticEntry first = items.get(0);
        String catAttr = first.severity().filterKey() + " " + first.domain().filterKey();
        String text = first.searchBlob();
        StringBuilder sb = new StringBuilder("<div class=\"diag-group\" data-filter-item=\"diag\" data-filter-cat=\"")
                .append(Http.esc(catAttr)).append("\" data-filter-text=\"").append(Http.esc(text)).append("\">");
        sb.append("<div class=\"diag-group-h\">").append(sevIcon(first.severity()))
                .append("<span class=\"diag-group-t\">").append(Http.esc(items.size() + " " + groupNoun(first)))
                .append("</span><span class=\"diag-dom\">").append(Http.esc(first.domain().label())).append("</span></div>");
        sb.append("<p class=\"diag-what\">").append(Http.esc(first.title()))
                .append(" — <code class=\"tid\">").append(Http.esc(first.code())).append("</code></p>");
        sb.append("<details class=\"diag-group-more\"><summary>Voir les ").append(items.size()).append("</summary>");
        for (DiagnosticEntry e : items) {
            sb.append(card(e, true));
        }
        sb.append("</details></div>");
        return sb.toString();
    }

    private static String groupNoun(DiagnosticEntry e) {
        return switch (e.domain()) {
            case PNJ -> "PNJ concernés";
            case DIALOGUES -> "dialogues concernés";
            case QUESTS -> "quêtes concernées";
            case STORIES -> "stories concernées";
            case WORLDS -> "mondes concernés";
            default -> "ressources concernées";
        };
    }

    private static String card(DiagnosticEntry e, boolean nested) {
        String catAttr = e.severity().filterKey() + " " + e.domain().filterKey();
        StringBuilder sb = new StringBuilder("<div class=\"alert ").append(e.severity().alertClass())
                .append(" pa-diag diag-card").append(nested ? " diag-nested" : "")
                .append("\" data-filter-item=\"diag\" data-filter-cat=\"").append(Http.esc(catAttr))
                .append("\" data-filter-text=\"").append(Http.esc(e.searchBlob())).append("\">");

        // en-tête : icône + titre humain + domaine · ressource
        sb.append("<div class=\"pa-diag-h\">").append(sevIcon(e.severity()))
                .append("<span>").append(Http.esc(e.title())).append("</span></div>");
        sb.append("<p class=\"diag-loc\"><span class=\"diag-dom\">").append(Http.esc(e.domain().label())).append("</span>");
        if (!e.resourceLabel().isBlank()) {
            sb.append(" · <span class=\"diag-res\">").append(Http.esc(e.resourceLabel())).append("</span>");
        }
        sb.append("</p>");

        sb.append("<p class=\"pa-diag-what\">").append(Http.esc(e.message())).append("</p>");
        if (!e.consequence().isBlank()) {
            sb.append("<p class=\"pa-diag-why\"><strong>Conséquence :</strong> ")
                    .append(Http.esc(e.consequence())).append("</p>");
        }
        if (!e.recommendedAction().isBlank()) {
            sb.append("<p class=\"pa-diag-do\"><strong>À faire :</strong> ")
                    .append(Http.esc(e.recommendedAction())).append("</p>");
        }

        // boutons : Ouvrir · Corriger maintenant · Comment corriger ?
        sb.append("<div class=\"pa-diag-btns\">");
        if (!e.resourceHref().isBlank()) {
            sb.append("<a class=\"btn btn-sm btn-outline-primary\" href=\"").append(Http.esc(e.resourceHref()))
                    .append("\">").append(Icons.icon("open")).append("Ouvrir</a>");
        }
        if (e.quickAction() != null && !e.quickAction().href().isBlank()) {
            sb.append("<a class=\"btn btn-sm btn-primary\" href=\"").append(Http.esc(e.quickAction().href()))
                    .append("\">").append(Icons.icon("wrench")).append(Http.esc(e.quickAction().label())).append("</a>");
        }
        if (!e.docHref().isBlank()) {
            sb.append("<a class=\"btn btn-sm btn-outline-secondary\" href=\"").append(Http.esc(e.docHref()))
                    .append("\">").append(Icons.icon("help")).append("Comment corriger ?</a>");
        }
        sb.append("</div>");

        // technique en dernier
        if (!e.code().isBlank()) {
            sb.append("<p class=\"pa-diag-code\">Code technique : <code class=\"tid\">")
                    .append(Http.esc(e.code())).append("</code>");
            if (!e.technicalDetail().isBlank()) {
                sb.append(" <span class=\"muted\">· ").append(Http.esc(shorten(e.technicalDetail(), 160)))
                        .append("</span>");
            }
            sb.append("</p>");
        }
        return sb.append("</div>").toString();
    }

    private static String sevIcon(Severity s) {
        return Icons.icon(s.icon());
    }

    static String freshnessLabel(Instant snapshot, Instant now) {
        if (snapshot == null) {
            return "";
        }
        return "Dernière vérification : " + AgentLiveness.ageHuman(snapshot, now);
    }

    private static String plural(int n, String one, String many) {
        return n <= 1 ? one : many;
    }

    private static String shorten(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max - 1).strip() + "…";
    }
}
