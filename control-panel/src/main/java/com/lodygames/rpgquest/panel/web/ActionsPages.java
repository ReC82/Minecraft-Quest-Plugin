package com.lodygames.rpgquest.panel.web;

import com.lodygames.rpgquest.panel.agent.AgentActionRow;
import com.lodygames.rpgquest.panel.http.Http;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Page <strong>Historique des actions</strong> ({@code /actions}, issue #93) : liste filtrable et
 * recherchable de toutes les actions agent, plus une vue détail ({@code /actions/<id>}). Design
 * system PlugAdmin + Bootstrap. <strong>Pur</strong> : reçoit la liste déjà chargée depuis
 * {@link com.lodygames.rpgquest.panel.agent.AgentStore} (source de vérité {@code agent_action}),
 * ne conserve aucun état.
 */
public final class ActionsPages {

    public static final int PAGE_SIZE = 25;

    private ActionsPages() {
    }

    /**
     * @param group   filtre de groupe de statut, ou {@code null} = tous
     * @param domain  filtre de domaine, ou {@code null} = tous
     * @param search  texte de recherche (peut être vide)
     * @param page    page 1-indexée
     */
    public record Query(ActionView.Group group, ActionView.Domain domain, String search, int page) {

        public static Query parse(Map<String, String> q) {
            ActionView.Group g = ActionView.Group.fromSlug(q.getOrDefault("status", "")).orElse(null);
            ActionView.Domain d = ActionView.Domain.fromSlug(q.getOrDefault("domain", "")).orElse(null);
            String s = q.getOrDefault("q", "").trim();
            int p = 1;
            try {
                p = Math.max(1, Integer.parseInt(q.getOrDefault("page", "1").trim()));
            } catch (NumberFormatException ignored) {
                // page 1
            }
            return new Query(g, d, s, p);
        }

        String statusSlug() {
            return group == null ? "all" : group.slug();
        }

        String domainSlug() {
            return domain == null ? "all" : domain.slug();
        }

        /** URL vers cette liste en remplaçant un paramètre. */
        String href(String key, String value) {
            String st = "status".equals(key) ? value : statusSlug();
            String dm = "domain".equals(key) ? value : domainSlug();
            String se = "q".equals(key) ? value : search;
            String pg = "page".equals(key) ? value : "1";
            StringBuilder sb = new StringBuilder("/actions?status=").append(enc(st)).append("&domain=").append(enc(dm));
            if (se != null && !se.isBlank()) {
                sb.append("&q=").append(enc(se));
            }
            if (pg != null && !"1".equals(pg)) {
                sb.append("&page=").append(enc(pg));
            }
            return sb.toString();
        }
    }

    // ---- liste -----------------------------------------------------------------------

    public static String list(List<AgentActionRow> all, Query q, Instant now) {
        List<AgentActionRow> filtered = new ArrayList<>();
        Set<ActionView.Domain> present = new LinkedHashSet<>();
        for (AgentActionRow a : all) {
            present.add(ActionView.Domain.of(a.type()));
            if (matches(a, q)) {
                filtered.add(a);
            }
        }
        int total = filtered.size();
        int pages = Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE);
        int page = Math.min(q.page(), pages);
        int from = (page - 1) * PAGE_SIZE;
        List<AgentActionRow> pageRows = from >= total ? List.of()
                : filtered.subList(from, Math.min(total, from + PAGE_SIZE));

        StringBuilder sb = new StringBuilder();
        sb.append(Ui.pageHeader("history", "Historique des actions",
                "Toutes les actions envoyées aux agents RPGQuest : statut, cible, résultat.", ""));

        sb.append(searchAndFilters(q, present, total));

        if (pageRows.isEmpty()) {
            sb.append(Ui.empty("history", total == 0
                    ? "Aucune action ne correspond à ces critères."
                    : "Aucune action sur cette page."));
        } else {
            sb.append(desktopTable(pageRows, now));
            sb.append(mobileCards(pageRows, now));
        }
        sb.append(pagination(q, page, pages));
        return sb.toString();
    }

    private static boolean matches(AgentActionRow a, Query q) {
        if (q.group() != null && ActionView.Group.of(a.status()) != q.group()) {
            return false;
        }
        if (q.domain() != null && ActionView.Domain.of(a.type()) != q.domain()) {
            return false;
        }
        if (q.search() == null || q.search().isBlank()) {
            return true;
        }
        String needle = q.search().toLowerCase(Locale.ROOT);
        StringBuilder hay = new StringBuilder();
        hay.append(a.type()).append(' ')
                .append(ActionView.humanLabel(a.type())).append(' ')
                .append(ActionView.Domain.of(a.type()).label()).append(' ')
                .append(ActionView.target(a)).append(' ')
                .append(nz(a.resultMessage())).append(' ')
                .append(nz(a.resultValue())).append(' ')
                .append(nz(a.createdBy())).append(' ')
                .append(a.status().name()).append(' ')
                .append(ActionView.statusLabel(a.status())).append(' ');
        a.params().forEach((k, v) -> hay.append(k).append('=').append(v).append(' '));
        return hay.toString().toLowerCase(Locale.ROOT).contains(needle);
    }

    private static String searchAndFilters(Query q, Set<ActionView.Domain> present, int total) {
        StringBuilder sb = new StringBuilder();

        // Recherche (GET) — conserve les filtres actifs
        sb.append("<form class=\"actions-search input-group\" method=\"get\" action=\"/actions\" role=\"search\">");
        sb.append("<input type=\"hidden\" name=\"status\" value=\"").append(Http.esc(q.statusSlug())).append("\">");
        sb.append("<input type=\"hidden\" name=\"domain\" value=\"").append(Http.esc(q.domainSlug())).append("\">");
        sb.append("<span class=\"input-group-text\">").append(Icons.icon("search")).append("</span>");
        sb.append("<input type=\"search\" class=\"form-control\" name=\"q\" value=\"").append(Http.esc(q.search()))
                .append("\" placeholder=\"Rechercher une action, joueur, PNJ, quête…\" aria-label=\"Rechercher\">");
        sb.append("<button class=\"btn btn-primary\" type=\"submit\">Rechercher</button>");
        if (!q.search().isBlank()) {
            sb.append("<a class=\"btn btn-outline-secondary\" href=\"").append(q.href("q", "")).append("\">")
                    .append(Icons.icon("close")).append("</a>");
        }
        sb.append("</form>");

        // Statut — nav-pills Bootstrap
        sb.append("<ul class=\"nav nav-pills actions-status\" role=\"tablist\">");
        sb.append(statusPill(q, null, "Tous"));
        sb.append(statusPill(q, ActionView.Group.SUCCESS, "Succès"));
        sb.append(statusPill(q, ActionView.Group.PENDING, "En cours"));
        sb.append(statusPill(q, ActionView.Group.FAILED, "Échec"));
        sb.append("</ul>");

        // Domaine — puces (seulement ceux réellement présents, + « Tous »)
        sb.append("<div class=\"actions-domains\">");
        sb.append(domainChip(q, null, "Tous", "filter"));
        for (ActionView.Domain d : ActionView.Domain.values()) {
            if (present.contains(d)) {
                sb.append(domainChip(q, d, d.label(), d.icon()));
            }
        }
        sb.append("</div>");

        sb.append("<p class=\"count-note\">").append(total).append(total > 1 ? " actions" : " action").append("</p>");
        return sb.toString();
    }

    private static String statusPill(Query q, ActionView.Group g, String label) {
        boolean on = q.group() == g;
        return "<li class=\"nav-item\"><a class=\"nav-link" + (on ? " active" : "")
                + "\"" + (on ? " aria-current=\"page\"" : "") + " href=\""
                + q.href("status", g == null ? "all" : g.slug()) + "\">" + Http.esc(label) + "</a></li>";
    }

    private static String domainChip(Query q, ActionView.Domain d, String label, String icon) {
        boolean on = q.domain() == d;
        return "<a class=\"pa-chip" + (on ? " on" : "") + "\" href=\""
                + q.href("domain", d == null ? "all" : d.slug()) + "\">"
                + Icons.icon(icon) + "<span>" + Http.esc(label) + "</span></a>";
    }

    private static String desktopTable(List<AgentActionRow> rows, Instant now) {
        StringBuilder sb = new StringBuilder(
                "<div class=\"table-responsive d-none d-md-block\"><table class=\"table table-hover align-middle actions-table\">");
        sb.append("<thead><tr><th>Date</th><th>Domaine</th><th>Action</th><th>Cible</th>"
                + "<th>Statut</th><th>Résultat</th><th>Acteur</th></tr></thead><tbody>");
        for (AgentActionRow a : rows) {
            String tgt = ActionView.target(a);
            String res = ActionView.shortResult(a);
            sb.append("<tr>")
                    .append("<td class=\"nowrap\"><span title=\"").append(Http.esc(a.createdAt().toString()))
                    .append("\">").append(Http.esc(ActionView.relativeTime(a.createdAt(), now))).append("</span></td>")
                    .append("<td>").append(ActionView.domainBadge(ActionView.Domain.of(a.type()))).append("</td>")
                    .append("<td><span class=\"act-label\">").append(Http.esc(ActionView.humanLabel(a.type())))
                    .append("</span> ").append(Ui.id(a.type())).append("</td>")
                    .append("<td>").append(tgt.isEmpty() ? "<span class=\"muted\">—</span>" : Http.esc(tgt)).append("</td>")
                    .append("<td>").append(ActionView.statusBadge(a.status())).append("</td>")
                    .append("<td class=\"act-res\">").append(res.isEmpty() ? "<span class=\"muted\">—</span>" : Http.esc(res)).append("</td>")
                    .append("<td class=\"muted nowrap\">").append(Http.esc(nz(a.createdBy()))).append("</td>")
                    .append("</tr>");
        }
        return sb.append("</tbody></table></div>").toString();
    }

    private static String mobileCards(List<AgentActionRow> rows, Instant now) {
        StringBuilder sb = new StringBuilder("<div class=\"d-md-none actions-cards\">");
        for (AgentActionRow a : rows) {
            String tgt = ActionView.target(a);
            String res = ActionView.shortResult(a);
            sb.append("<a class=\"card actions-card\" href=\"/actions/").append(Http.esc(a.id())).append("\">")
                    .append("<div class=\"card-body\">")
                    .append("<div class=\"ac-top\">").append(ActionView.domainBadge(ActionView.Domain.of(a.type())))
                    .append(ActionView.statusBadge(a.status())).append("</div>")
                    .append("<div class=\"ac-title\">").append(Http.esc(ActionView.humanLabel(a.type()))).append("</div>");
            if (!tgt.isEmpty()) {
                sb.append("<div class=\"ac-tgt\">").append(Http.esc(tgt)).append("</div>");
            }
            if (!res.isEmpty()) {
                sb.append("<div class=\"ac-res muted\">").append(Http.esc(res)).append("</div>");
            }
            sb.append("<div class=\"ac-meta muted\">").append(Http.esc(ActionView.relativeTime(a.createdAt(), now)));
            if (!nz(a.createdBy()).isEmpty()) {
                sb.append(" · ").append(Http.esc(a.createdBy()));
            }
            sb.append("</div></div></a>");
        }
        return sb.append("</div>").toString();
    }

    private static String pagination(Query q, int page, int pages) {
        if (pages <= 1) {
            return "";
        }
        StringBuilder sb = new StringBuilder("<nav class=\"actions-pager\" aria-label=\"Pagination\"><ul class=\"pagination\">");
        sb.append("<li class=\"page-item").append(page <= 1 ? " disabled" : "").append("\">")
                .append("<a class=\"page-link\" href=\"").append(page <= 1 ? "#" : q.href("page", String.valueOf(page - 1)))
                .append("\" aria-label=\"Précédent\">").append(Icons.icon("back")).append("</a></li>");
        sb.append("<li class=\"page-item active\"><span class=\"page-link\">Page ").append(page).append(" / ").append(pages)
                .append("</span></li>");
        sb.append("<li class=\"page-item").append(page >= pages ? " disabled" : "").append("\">")
                .append("<a class=\"page-link\" href=\"").append(page >= pages ? "#" : q.href("page", String.valueOf(page + 1)))
                .append("\" aria-label=\"Suivant\">").append(Icons.icon("chevron")).append("</a></li>");
        return sb.append("</ul></nav>").toString();
    }

    // ---- détail ---------------------------------------------------------------------

    public static String detail(AgentActionRow a, Instant now) {
        StringBuilder sb = new StringBuilder();
        sb.append(Ui.pageHeader(ActionView.Domain.of(a.type()).icon(), ActionView.humanLabel(a.type()),
                ActionView.Domain.of(a.type()).label() + " · " + ActionView.relativeTime(a.createdAt(), now),
                "<a class=\"btn btn-outline-secondary\" href=\"/actions\">" + Icons.icon("back") + "Retour</a>"));

        sb.append("<div class=\"form-section\"><p class=\"fs-h\">").append(Icons.icon("info")).append("Résumé</p>");
        sb.append("<dl class=\"detail-dl\">");
        row(sb, "Statut", ActionView.statusBadge(a.status()));
        row(sb, "Type technique", Ui.id(a.type()));
        row(sb, "Cible", esc(ActionView.target(a), "—"));
        row(sb, "Acteur", esc(a.createdBy(), "—"));
        row(sb, "Créée", esc(a.createdAt().toString(), "—"));
        row(sb, "Transmise", a.deliveredAt() == null ? "<span class=\"muted\">—</span>" : Http.esc(a.deliveredAt().toString()));
        row(sb, "Terminée", a.completedAt() == null ? "<span class=\"muted\">—</span>" : Http.esc(a.completedAt().toString()));
        row(sb, "Livraisons", String.valueOf(a.deliverCount()));
        row(sb, "Identifiant", Ui.id(a.id()));
        sb.append("</dl></div>");

        if (!a.params().isEmpty()) {
            sb.append("<div class=\"form-section\"><p class=\"fs-h\">").append(Icons.icon("filter")).append("Paramètres</p>");
            sb.append("<dl class=\"detail-dl\">");
            a.params().forEach((k, v) -> row(sb, k, "value".equals(k) ? "<span class=\"muted\">&lt;défini&gt;</span>" : Http.esc(v)));
            sb.append("</dl></div>");
        }

        String value = nz(a.resultValue());
        String message = nz(a.resultMessage());
        if (!value.isEmpty() || !message.isEmpty()) {
            sb.append("<div class=\"form-section\"><p class=\"fs-h\">").append(Icons.icon("check")).append("Résultat</p>");
            sb.append("<dl class=\"detail-dl\">");
            if (!value.isEmpty()) {
                row(sb, "Valeur", Http.esc(value));
            }
            if (!message.isEmpty()) {
                row(sb, "Message", Http.esc(MiniText.prettifyTokens(message)));
            }
            sb.append("</dl></div>");
        }

        String json = nz(a.resultJson());
        if (!json.isEmpty()) {
            sb.append("<div class=\"form-section\"><p class=\"fs-h\">").append(Icons.icon("docs"))
                    .append("Corps brut du résultat</p>");
            sb.append("<div class=\"codeblock\"><pre>").append(Http.esc(json)).append("</pre></div>");
            sb.append("<p class=\"field-help\">Diagnostic — sans secret (voir protocole agent #51).</p></div>");
        }
        return sb.toString();
    }

    private static void row(StringBuilder sb, String k, String vHtml) {
        sb.append("<div class=\"dl-row\"><dt>").append(Http.esc(k)).append("</dt><dd>").append(vHtml).append("</dd></div>");
    }

    // ---- helpers ------------------------------------------------------------------------

    static Optional<String> idFromPath(String path) {
        String prefix = "/actions/";
        if (path == null || !path.startsWith(prefix) || path.length() <= prefix.length()) {
            return Optional.empty();
        }
        String id = path.substring(prefix.length());
        if (id.endsWith("/")) {
            id = id.substring(0, id.length() - 1);
        }
        return id.matches("[0-9a-fA-F-]{8,36}") ? Optional.of(id) : Optional.empty();
    }

    private static String esc(String v, String fallback) {
        return v == null || v.isBlank() ? "<span class=\"muted\">" + Http.esc(fallback) + "</span>" : Http.esc(v);
    }

    private static String nz(String s) {
        return s == null ? "" : s.trim();
    }

    private static String enc(String v) {
        return java.net.URLEncoder.encode(v == null ? "" : v, java.nio.charset.StandardCharsets.UTF_8);
    }
}
