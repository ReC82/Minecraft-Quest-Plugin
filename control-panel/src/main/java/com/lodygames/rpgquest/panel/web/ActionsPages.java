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
    /** Issue #169 : filtre des demandes refusées par le panel avant tout envoi à l'agent. */
    static final String REFUSED_SLUG = "refused";
    /** Issue #881 : filtre des opérations faites par le panel seul (Atelier), sans action agent. */
    static final String PANEL_SLUG = "panel";

    /**
     * Une opération faite <strong>par le panel seul</strong> sur un projet de l'Atelier (création,
     * corbeille, restauration, suppression définitive, refus…), lue dans le journal d'audit : rien
     * n'est envoyé au serveur Minecraft, il n'y a donc pas de fiche d'action agent.
     *
     * @param link fiche ou corbeille du projet s'il existe encore, vide s'il a été supprimé
     */
    public record PanelOperation(Instant at, String actor, String action, boolean ok, String projectId,
                                 String buildingId, String name, String reason, String link) {

        /** Le projet tel qu'il est aujourd'hui : où l'ouvrir et son nom, ou absent s'il n'existe plus. */
        public record ProjectRef(String link, String name) {
        }

        /**
         * {@code target} d'audit : {@code project=… building=… name=<le reste>} — les entrées antérieures
         * à #881 n'ont que {@code project=…} : le nom vient alors du projet s'il existe encore.
         */
        public static PanelOperation of(Instant at, String actor, String action, String result, String auditTarget,
                                        String details,
                                        java.util.function.Function<String, Optional<ProjectRef>> current) {
            String t = nz(auditTarget);
            String name = "";
            int n = t.indexOf(" name=");
            if (n >= 0) {
                name = t.substring(n + 6);
                t = t.substring(0, n);
            }
            String project = "";
            String building = "";
            for (String part : t.split(" ")) {
                if (part.startsWith("project=")) {
                    project = part.substring(8);
                } else if (part.startsWith("building=")) {
                    building = part.substring(9);
                }
            }
            Optional<ProjectRef> now = project.isEmpty() ? Optional.empty() : current.apply(project);
            if (name.isEmpty()) {
                name = now.map(ProjectRef::name).orElse("");
            }
            return new PanelOperation(at, nz(actor), nz(action), "OK".equals(result), project, building, name,
                    nz(details), now.map(ProjectRef::link).orElse(""));
        }

        public String label() {
            return switch (action) {
                case "building.project.create" -> "Projet créé";
                case "building.project.import" -> "Projet repris d'un emplacement";
                case "building.project.save" -> "Projet modifié";
                case "building.project.image.add" -> "Image de référence ajoutée";
                case "building.project.image.remove" -> "Image de référence retirée";
                case "building.project.trash" -> "Mis à la corbeille";
                case "building.project.restore" -> "Restauré depuis la corbeille";
                case "building.project.delete" -> "Supprimé définitivement";
                default -> action;
            };
        }

        String searchText() {
            return (label() + " " + action + " " + name + " " + buildingId + " " + projectId + " " + reason + " "
                    + actor).toLowerCase(Locale.ROOT);
        }
    }

    /**
     * Une demande refusée par le panel <strong>avant</strong> la file (confirmation manquante,
     * paramètre invalide, conflit…) : lue dans le journal d'audit existant, jamais présentée comme une
     * action agent ni comme un échec d'exécution.
     */
    public record Refusal(Instant at, String actor, String type, String target, String reason) {

        /** {@code target} d'audit : {@code agent=… type=… player=…} → type et cible lisibles. */
        public static Refusal of(Instant at, String actor, String auditTarget, String reason) {
            String type = "";
            StringBuilder target = new StringBuilder();
            for (String part : nz(auditTarget).split(" ")) {
                if (part.startsWith("type=")) {
                    type = part.substring(5);
                } else if (!part.startsWith("agent=") && !part.isBlank()) {
                    target.append(target.isEmpty() ? "" : " · ").append(part);
                }
            }
            return new Refusal(at, nz(actor), type, target.toString(), nz(reason));
        }
    }

    private ActionsPages() {
    }

    /**
     * @param group   filtre de groupe de statut, ou {@code null} = tous
     * @param domain  filtre de domaine, ou {@code null} = tous
     * @param search  texte de recherche (peut être vide)
     * @param page    page 1-indexée
     */
    public record Query(ActionView.Group group, ActionView.Domain domain, String search, int page, boolean refused,
                        boolean panel) {

        public Query(ActionView.Group group, ActionView.Domain domain, String search, int page) {
            this(group, domain, search, page, false, false);
        }

        public Query(ActionView.Group group, ActionView.Domain domain, String search, int page, boolean refused) {
            this(group, domain, search, page, refused, false);
        }

        public static Query parse(Map<String, String> q) {
            boolean refused = REFUSED_SLUG.equals(q.getOrDefault("status", ""));
            boolean panel = PANEL_SLUG.equals(q.getOrDefault("status", ""));
            ActionView.Group g = ActionView.Group.fromSlug(q.getOrDefault("status", "")).orElse(null);
            ActionView.Domain d = ActionView.Domain.fromSlug(q.getOrDefault("domain", "")).orElse(null);
            String s = q.getOrDefault("q", "").trim();
            int p = 1;
            try {
                p = Math.max(1, Integer.parseInt(q.getOrDefault("page", "1").trim()));
            } catch (NumberFormatException ignored) {
                // page 1
            }
            return new Query(g, d, s, p, refused, panel);
        }

        String statusSlug() {
            return refused ? REFUSED_SLUG : panel ? PANEL_SLUG : group == null ? "all" : group.slug();
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

        /** Cette liste-ci, page comprise : là où « Retour » doit ramener depuis une fiche (#266). */
        String selfHref() {
            return href("page", String.valueOf(page));
        }
    }

    /**
     * Issue #266 — le lien vers la fiche d'une action, qui emporte la liste d'origine (filtres,
     * recherche, page) pour que « Retour » y ramène au lieu de tout réinitialiser.
     */
    static String detailHref(AgentActionRow a, Query q) {
        return "/actions/" + enc(a.id()) + (q == null ? "" : "?back=" + enc(q.selfHref()));
    }

    /** Un retour accepté seulement s'il reste dans l'historique : jamais une URL fournie au hasard. */
    public static String safeBack(String raw) {
        if (raw == null || !raw.startsWith("/actions?") || raw.contains("//") || raw.contains("\\")
                || raw.length() > 300 || !raw.matches("[A-Za-z0-9/?&=%._+:-]*")) {
            return "/actions";
        }
        return raw;
    }

    // ---- liste -----------------------------------------------------------------------

    public static String list(List<AgentActionRow> all, Query q, Instant now) {
        return list(all, List.of(), q, now);
    }

    public static String list(List<AgentActionRow> all, List<Refusal> refusals, Query q, Instant now) {
        return list(all, refusals, q, now, PlayerNames.EMPTY);
    }

    /** @param names pseudos connus ({@code player.catalog}) : la cible joueur s'affiche par son pseudo */
    public static String list(List<AgentActionRow> all, List<Refusal> refusals, Query q, Instant now,
                              PlayerNames names) {
        return list(all, refusals, List.of(), q, now, names);
    }

    /** @param operations opérations du panel seul (#881), affichées sous leur propre onglet */
    public static String list(List<AgentActionRow> all, List<Refusal> refusals, List<PanelOperation> operations,
                              Query q, Instant now, PlayerNames names) {
        if (q.refused()) {
            return refusedList(refusals, operations.size(), q, now);
        }
        if (q.panel()) {
            return panelList(operations, refusals.size(), q, now);
        }
        List<AgentActionRow> filtered = new ArrayList<>();
        Set<ActionView.Domain> present = new LinkedHashSet<>();
        for (AgentActionRow a : all) {
            present.add(ActionView.Domain.of(a.type()));
            if (ActionView.targetsPlayer(a.type())) {
                present.add(ActionView.Domain.PLAYERS);
            }
            if (matches(a, q, names)) {
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

        sb.append(searchAndFilters(q, present, total, refusals.size(), operations.size()));

        if (pageRows.isEmpty()) {
            sb.append(Ui.empty("history", total == 0
                    ? "Aucune action ne correspond à ces critères."
                    : "Aucune action sur cette page."));
        } else {
            sb.append(desktopTable(pageRows, now, q, names));
            sb.append(mobileCards(pageRows, now, q, names));
        }
        sb.append(pagination(q, page, pages));
        return sb.toString();
    }

    private static boolean matches(AgentActionRow a, Query q, PlayerNames names) {
        if (q.group() != null && ActionView.Group.of(a.status()) != q.group()) {
            return false;
        }
        if (q.domain() != null && !q.domain().includes(a.type())) {
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
                .append(ActionView.target(a, names).searchText()).append(' ')
                .append(nz(a.resultMessage())).append(' ')
                .append(nz(a.resultValue())).append(' ')
                .append(nz(a.createdBy())).append(' ')
                .append(a.status().name()).append(' ')
                .append(ActionView.statusLabel(a.status())).append(' ');
        a.params().forEach((k, v) -> hay.append(k).append('=').append(v).append(' '));
        return hay.toString().toLowerCase(Locale.ROOT).contains(needle);
    }

    /** Issue #169 : les refus avant envoi, distincts des actions exécutées (aucune fiche agent). */
    private static String refusedList(List<Refusal> refusals, int operationCount, Query q, Instant now) {
        String needle = q.search() == null ? "" : q.search().toLowerCase(Locale.ROOT);
        List<Refusal> rows = refusals.stream().filter(r -> needle.isBlank() || (r.type() + " "
                + ActionView.humanLabel(r.type()) + " " + r.target() + " " + r.reason() + " " + r.actor())
                .toLowerCase(Locale.ROOT).contains(needle)).limit(200).toList();
        StringBuilder sb = new StringBuilder();
        sb.append(Ui.pageHeader("history", "Historique des actions",
                "Toutes les actions envoyées aux agents RPGQuest : statut, cible, résultat.", ""));
        sb.append(searchAndFilters(q, Set.of(), rows.size(), refusals.size(), operationCount));
        sb.append(Ui.banner("info", "Demandes <strong>refusées par PlugAdmin avant tout envoi</strong> au serveur "
                + "(confirmation manquante, valeur invalide, conflit…) : rien n'a été exécuté en jeu."));
        if (rows.isEmpty()) {
            return sb.append(Ui.empty("history", "Aucun refus ne correspond à ces critères.")).toString();
        }
        sb.append("<div class=\"table-responsive\"><table class=\"table align-middle actions-table actions-refused\">")
                .append("<thead><tr><th>Date</th><th>Action demandée</th><th>Cible</th><th>Motif du refus</th>"
                        + "<th>Acteur</th></tr></thead><tbody>");
        for (Refusal r : rows) {
            sb.append("<tr><td class=\"nowrap\"><span title=\"").append(Http.esc(r.at().toString())).append("\">")
                    .append(Http.esc(ActionView.relativeTime(r.at(), now))).append("</span></td><td>")
                    .append(r.type().isEmpty() ? "<span class=\"muted\">—</span>"
                            : Http.esc(ActionView.humanLabel(r.type())) + " " + Ui.id(r.type()))
                    .append("</td><td>").append(r.target().isEmpty() ? "<span class=\"muted\">—</span>"
                            : Http.esc(r.target()))
                    .append("</td><td>").append(Ui.pill("refusé", "warn", "")).append(' ').append(Http.esc(r.reason()))
                    .append("</td><td class=\"muted nowrap\">").append(Http.esc(r.actor())).append("</td></tr>");
        }
        return sb.append("</tbody></table></div>").toString();
    }

    /** Issue #881 : les opérations du panel seul, refus compris, avec un lien vers le projet s'il existe. */
    private static String panelList(List<PanelOperation> operations, int refusedCount, Query q, Instant now) {
        String needle = q.search() == null ? "" : q.search().toLowerCase(Locale.ROOT);
        List<PanelOperation> rows = operations.stream()
                .filter(o -> needle.isBlank() || o.searchText().contains(needle)).limit(200).toList();
        StringBuilder sb = new StringBuilder();
        sb.append(Ui.pageHeader("history", "Historique des actions",
                "Toutes les actions envoyées aux agents RPGQuest : statut, cible, résultat.", ""));
        sb.append(searchAndFilters(q, Set.of(), rows.size(), refusedCount, operations.size()));
        sb.append(Ui.banner("info", "Opérations faites <strong>par PlugAdmin seul</strong> sur les projets de "
                + "l'Atelier (création, corbeille, restauration, suppression définitive, refus) : rien n'est "
                + "envoyé au serveur Minecraft."));
        if (rows.isEmpty()) {
            return sb.append(Ui.empty("history", "Aucune opération ne correspond à ces critères.")).toString();
        }
        sb.append("<div class=\"table-responsive\"><table class=\"table align-middle actions-table actions-panel\">")
                .append("<thead><tr><th>Date</th><th>Opération</th><th>Projet</th><th>Résultat</th>"
                        + "<th>Acteur</th></tr></thead><tbody>");
        for (PanelOperation o : rows) {
            String project = o.name().isEmpty() ? (o.buildingId().isEmpty() ? "" : Ui.id(o.buildingId()))
                    : Http.esc(o.name()) + (o.buildingId().isEmpty() ? "" : " " + Ui.id(o.buildingId()));
            sb.append("<tr><td class=\"nowrap\"><span title=\"").append(Http.esc(o.at().toString())).append("\">")
                    .append(Http.esc(ActionView.relativeTime(o.at(), now))).append("</span></td><td>")
                    .append(Http.esc(o.label())).append(" ").append(Ui.id(o.action())).append("</td><td>")
                    .append(project.isEmpty() ? "<span class=\"muted\">—</span>" : project);
            if (!o.link().isEmpty()) {
                sb.append(" <a class=\"small\" href=\"").append(Http.esc(o.link())).append("\">Ouvrir</a>");
            } else if (!o.projectId().isEmpty()) {
                sb.append(" <span class=\"muted small\">(n'existe plus)</span>");
            }
            sb.append("</td><td>").append(o.ok() ? Ui.pill("fait", "delivered", "✓") : Ui.pill("refusé", "warn", ""))
                    .append(o.ok() || o.reason().isEmpty() ? "" : " " + Http.esc(o.reason()))
                    .append("</td><td class=\"muted nowrap\">").append(Http.esc(o.actor())).append("</td></tr>");
        }
        return sb.append("</tbody></table></div>").toString();
    }

    private static String searchAndFilters(Query q, Set<ActionView.Domain> present, int total, int refusedCount,
                                           int operationCount) {
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
        boolean on = q.refused();
        sb.append("<li class=\"nav-item\"><a class=\"nav-link").append(on ? " active" : "").append("\"")
                .append(on ? " aria-current=\"page\"" : "").append(" href=\"").append(q.href("status", REFUSED_SLUG))
                .append("\">Refusées avant envoi (").append(refusedCount).append(")</a></li>");
        boolean ops = q.panel();
        sb.append("<li class=\"nav-item\"><a class=\"nav-link").append(ops ? " active" : "").append("\"")
                .append(ops ? " aria-current=\"page\"" : "").append(" href=\"").append(q.href("status", PANEL_SLUG))
                .append("\">Opérations du panel (").append(operationCount).append(")</a></li>");
        sb.append("</ul>");

        // Domaine — puces (seulement ceux réellement présents, + « Tous »), sauf pour les refus.

        if (!q.refused() && !q.panel()) {
            sb.append("<div class=\"actions-domains\">");
            sb.append(domainChip(q, null, "Tous", "filter"));
            for (ActionView.Domain d : ActionView.Domain.values()) {
                if (present.contains(d)) {
                    sb.append(domainChip(q, d, d.label(), d.icon()));
                }
            }
            sb.append("</div>");
        }
        sb.append("<p class=\"count-note\">").append(total).append(q.panel() ? (total > 1 ? " opérations" : " opération")
                : q.refused() ? " refus"
                : total > 1 ? " actions" : " action").append("</p>");
        return sb.toString();
    }

    private static String statusPill(Query q, ActionView.Group g, String label) {
        boolean on = !q.refused() && !q.panel() && q.group() == g;
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

    /**
     * Le tableau des actions sur grand écran. Issue #266 : chaque ligne ouvre sa fiche — le libellé
     * est un vrai lien (clavier, lecteur d'écran, clic du milieu), la ligne entière est cliquable
     * par {@code panel.js} ({@code data-href}), et « Voir le détail » le dit en toutes lettres.
     * Avant, seules les cartes mobiles et les notifications menaient au détail.
     */
    private static String desktopTable(List<AgentActionRow> rows, Instant now, Query q, PlayerNames names) {
        StringBuilder sb = new StringBuilder(
                "<div class=\"table-responsive d-none d-md-block\"><table class=\"table table-hover align-middle actions-table\">");
        sb.append("<thead><tr><th>Date</th><th>Domaine</th><th>Action</th><th>Cible</th>"
                + "<th>Statut</th><th>Résultat</th><th>Acteur</th><th><span class=\"visually-hidden\">"
                + "Détail</span></th></tr></thead><tbody>");
        for (AgentActionRow a : rows) {
            ActionView.Target tgt = ActionView.target(a, names);
            String res = ActionView.shortResult(a);
            String href = detailHref(a, q);
            sb.append("<tr class=\"act-row\" data-href=\"").append(Http.esc(href)).append("\">")
                    .append("<td class=\"nowrap\"><span title=\"").append(Http.esc(a.createdAt().toString()))
                    .append("\">").append(Http.esc(ActionView.relativeTime(a.createdAt(), now))).append("</span></td>")
                    .append("<td>").append(ActionView.domainBadge(ActionView.Domain.of(a.type()))).append("</td>")
                    .append("<td><a class=\"act-label\" href=\"").append(Http.esc(href)).append("\">")
                    .append(Http.esc(ActionView.humanLabel(a.type())))
                    .append("</a> ").append(Ui.id(a.type())).append("</td>")
                    .append("<td>").append(tgt.isEmpty() ? "<span class=\"muted\">—</span>" : tgt.html()).append("</td>")
                    .append("<td>").append(ActionView.statusBadge(a.status())).append("</td>")
                    .append("<td class=\"act-res\">").append(res.isEmpty() ? "<span class=\"muted\">—</span>" : Http.esc(res)).append("</td>")
                    .append("<td class=\"muted nowrap\">").append(Http.esc(nz(a.createdBy()))).append("</td>")
                    .append("<td class=\"nowrap\"><a class=\"btn btn-sm btn-outline-secondary\" href=\"")
                    .append(Http.esc(href)).append("\" aria-label=\"Voir le détail de l'action ")
                    .append(Http.esc(ActionView.humanLabel(a.type()))).append("\">Voir le détail</a></td>")
                    .append("</tr>");
        }
        return sb.append("</tbody></table></div>").toString();
    }

    private static String mobileCards(List<AgentActionRow> rows, Instant now, Query q, PlayerNames names) {
        StringBuilder sb = new StringBuilder("<div class=\"d-md-none actions-cards\">");
        for (AgentActionRow a : rows) {
            ActionView.Target tgt = ActionView.target(a, names);
            String res = ActionView.shortResult(a);
            sb.append("<a class=\"card actions-card\" href=\"").append(Http.esc(detailHref(a, q))).append("\">")
                    .append("<div class=\"card-body\">")
                    .append("<div class=\"ac-top\">").append(ActionView.domainBadge(ActionView.Domain.of(a.type())))
                    .append(ActionView.statusBadge(a.status())).append("</div>")
                    .append("<div class=\"ac-title\">").append(Http.esc(ActionView.humanLabel(a.type()))).append("</div>");
            if (!tgt.isEmpty()) {
                sb.append("<div class=\"ac-tgt\">").append(tgt.compactHtml()).append("</div>");
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
        return detail(a, now, "");
    }

    /**
     * Variante avec un bandeau de contexte placé sous l'en-tête (issue #256) : pour une action qui
     * transporte un plan de bâtiment, le chemin du retour vers le brouillon concerné. Sans lui, le
     * seul « Retour » de la fiche menait à l'historique, et le parcours de génération était perdu.
     *
     * @param contextHtml HTML déjà sûr, ou vide
     */
    public static String detail(AgentActionRow a, Instant now, String contextHtml) {
        return detail(a, now, contextHtml, "/actions");
    }

    /** @param backHref la liste d'où l'on vient (#266), déjà passée par {@link #safeBack} */
    public static String detail(AgentActionRow a, Instant now, String contextHtml, String backHref) {
        return detail(a, now, contextHtml, backHref, PlayerNames.EMPTY);
    }

    public static String detail(AgentActionRow a, Instant now, String contextHtml, String backHref,
                                PlayerNames names) {
        StringBuilder sb = new StringBuilder();
        sb.append(Ui.pageHeader(ActionView.Domain.of(a.type()).icon(), ActionView.humanLabel(a.type()),
                ActionView.Domain.of(a.type()).label() + " · " + ActionView.relativeTime(a.createdAt(), now),
                "<a class=\"btn btn-outline-secondary\" href=\"" + Http.esc(safeBack(backHref)) + "\">"
                        + Icons.icon("back") + "Retour à l'historique</a>"));
        if (contextHtml != null && !contextHtml.isBlank()) {
            sb.append(contextHtml);
        }

        sb.append("<div class=\"form-section\"><p class=\"fs-h\">").append(Icons.icon("info")).append("Résumé</p>");
        sb.append("<dl class=\"detail-dl\">");
        row(sb, "Statut", ActionView.statusBadge(a.status()));
        row(sb, "Type technique", Ui.id(a.type()));
        ActionView.Target tgt = ActionView.target(a, names);
        row(sb, "Cible", tgt.isEmpty() ? "<span class=\"muted\">—</span>" : tgt.html());
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
            // #583 : copie du texte brut exact (textContent du <pre>, aucun balisage), côté client
            // uniquement — aucune requête, aucune réexécution. Repli : sélection pour copie manuelle.
            sb.append("<div class=\"copy-bar\"><button class=\"btn secondary sm\" type=\"button\" "
                    + "data-copy-from=\"action-result-raw\" data-copy-status=\"action-result-raw-status\">")
                    .append(Icons.icon("copy")).append("Copier le résultat brut</button>")
                    .append("<span class=\"copy-status\" id=\"action-result-raw-status\" role=\"status\" "
                            + "aria-live=\"polite\"></span></div>");
            sb.append("<div class=\"codeblock\"><pre id=\"action-result-raw\" tabindex=\"0\" "
                    + "aria-label=\"Corps brut du résultat\">").append(Http.esc(json)).append("</pre></div>");
            sb.append("<p class=\"field-help\">Diagnostic — sans secret (voir protocole agent #51).</p></div>");
        } else {
            sb.append("<div class=\"form-section\"><p class=\"fs-h\">").append(Icons.icon("docs"))
                    .append("Corps brut du résultat</p><p class=\"muted\">Aucun corps brut : l'agent n'a pas "
                            + "(encore) renvoyé de résultat pour cette action, rien à copier.</p></div>");
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
