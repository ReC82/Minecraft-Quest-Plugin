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
 * recherchable de toutes les actions agent et (#881) des opérations faites par le panel seul, en un flux
 * unique, plus une vue détail ({@code /actions/<id>}). Design
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
    /** L'onglet « Opérations du panel » de l'Historique (#881). */
    public static final String PANEL_OPERATIONS_URL = "/actions?status=" + PANEL_SLUG + "&domain=all";

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

    /**
     * @param operations opérations du panel seul (#881) : mêlées aux actions agent dans « Tous » (flux
     *                   unique trié par date), seules sous l'onglet « Opérations du panel »
     */
    public static String list(List<AgentActionRow> all, List<Refusal> refusals, List<PanelOperation> operations,
                              Query q, Instant now, PlayerNames names) {
        if (q.refused()) {
            return refusedList(refusals, operations.size(), q, now);
        }
        if (q.panel()) {
            return panelList(operations, refusals.size(), q, now);
        }
        Set<ActionView.Domain> present = new LinkedHashSet<>();
        List<Line> filtered = new ArrayList<>();
        for (AgentActionRow a : all) {
            present.add(ActionView.Domain.of(a.type()));
            if (ActionView.targetsPlayer(a.type())) {
                present.add(ActionView.Domain.PLAYERS);
            }
            if (matches(a, q, names)) {
                filtered.add(Line.of(a, q, names));
            }
        }
        int opsShown = 0;
        int opsHidden = 0;
        for (PanelOperation o : operations) {
            present.add(ActionView.Domain.BUILDINGS);
            if (!matchesSearch(o, q)) {
                continue;
            }
            if (matchesFilters(o, q)) {
                filtered.add(Line.of(o));
                opsShown++;
            } else {
                opsHidden++;
            }
        }
        // Un seul flux : actions agent et opérations du panel, de la plus récente à la plus ancienne.
        filtered.sort(java.util.Comparator.comparing(Line::at).reversed());
        int total = filtered.size();
        int pages = Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE);
        int page = Math.min(q.page(), pages);
        int from = (page - 1) * PAGE_SIZE;
        List<Line> pageRows = from >= total ? List.of() : filtered.subList(from, Math.min(total, from + PAGE_SIZE));

        StringBuilder sb = new StringBuilder(header());
        sb.append(searchAndFilters(q, present, countNote(total, opsShown, opsHidden, q), refusals.size(),
                operations.size()));
        if (pageRows.isEmpty()) {
            sb.append(Ui.empty("history", total == 0
                    ? "Aucune action ne correspond à ces critères."
                    : "Aucune action sur cette page."));
        } else {
            sb.append(lines(pageRows, now));
        }
        sb.append(pagination(q, page, pages));
        return sb.toString();
    }

    private static String header() {
        return Ui.pageHeader("history", "Historique des actions",
                "Actions envoyées aux agents RPGQuest et opérations faites dans PlugAdmin seul (Atelier) : "
                        + "statut, cible, résultat, acteur.", "");
    }

    /**
     * #881 — le compte de « Tous », qui dit combien d'opérations du panel il contient et, si un filtre de
     * statut ou de domaine en écarte, combien et où les voir : aucun filtre ne les masque en silence.
     */
    private static String countNote(int total, int opsShown, int opsHidden, Query q) {
        if (opsShown == 0 && opsHidden == 0) {
            return total + (total > 1 ? " actions" : " action");
        }
        StringBuilder sb = new StringBuilder().append(total).append(total > 1 ? " entrées" : " entrée");
        if (opsShown > 0) {
            sb.append(" · dont ").append(opsShown).append(opsShown > 1 ? " opérations" : " opération")
                    .append(" du panel");
        }
        if (opsHidden > 0) {
            sb.append(" · ").append(opsHidden).append(opsHidden > 1 ? " opérations du panel écartées"
                            : " opération du panel écartée").append(" par ce filtre (<a href=\"")
                    .append(Http.esc(q.href("status", PANEL_SLUG).replace("&domain=" + enc(q.domainSlug()),
                            "&domain=all"))).append("\">les voir</a>)");
        }
        return sb.toString();
    }

    /** Une opération du panel réussie compte comme un succès, refusée comme un échec ; jamais « En cours ». */
    private static boolean matchesFilters(PanelOperation o, Query q) {
        if (q.group() == ActionView.Group.PENDING
                || q.group() == ActionView.Group.SUCCESS && !o.ok()
                || q.group() == ActionView.Group.FAILED && o.ok()) {
            return false;
        }
        return q.domain() == null || q.domain() == ActionView.Domain.BUILDINGS;
    }

    private static boolean matchesSearch(PanelOperation o, Query q) {
        return q.search() == null || q.search().isBlank()
                || o.searchText().contains(q.search().toLowerCase(Locale.ROOT));
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
        List<Line> rows = refusals.stream().filter(r -> needle.isBlank() || (r.type() + " "
                + ActionView.humanLabel(r.type()) + " " + r.target() + " " + r.reason() + " " + r.actor())
                .toLowerCase(Locale.ROOT).contains(needle)).limit(200).map(Line::of).toList();
        StringBuilder sb = new StringBuilder(header());
        sb.append(searchAndFilters(q, Set.of(), rows.size() + " refus", refusals.size(), operationCount));
        sb.append(Ui.banner("info", "Demandes <strong>refusées par PlugAdmin avant tout envoi</strong> au serveur "
                + "(confirmation manquante, valeur invalide, conflit…) : rien n'a été exécuté en jeu."));
        if (rows.isEmpty()) {
            return sb.append(Ui.empty("history", "Aucun refus ne correspond à ces critères.")).toString();
        }
        return sb.append(lines(rows, now)).toString();
    }

    /** Issue #881 : les opérations du panel seul, refus compris, avec un lien vers le projet s'il existe. */
    private static String panelList(List<PanelOperation> operations, int refusedCount, Query q, Instant now) {
        List<Line> rows = operations.stream().filter(o -> matchesSearch(o, q)).limit(200).map(Line::of).toList();
        StringBuilder sb = new StringBuilder(header());
        sb.append(searchAndFilters(q, Set.of(), rows.size() + (rows.size() > 1 ? " opérations" : " opération"),
                refusedCount, operations.size()));
        sb.append(Ui.banner("info", "Opérations faites <strong>par PlugAdmin seul</strong> sur les projets de "
                + "l'Atelier (création, corbeille, restauration, suppression définitive, refus) : rien n'est "
                + "envoyé au serveur Minecraft. Elles figurent aussi dans « Tous », parmi les actions agent."));
        if (rows.isEmpty()) {
            return sb.append(Ui.empty("history", "Aucune opération ne correspond à ces critères.")).toString();
        }
        return sb.append(lines(rows, now)).toString();
    }

    /** @param countHtml compte déjà formé (HTML sûr) */
    private static String searchAndFilters(Query q, Set<ActionView.Domain> present, String countHtml,
                                           int refusedCount, int operationCount) {
        StringBuilder sb = new StringBuilder();

        // Recherche (GET) — conserve les filtres actifs
        sb.append("<form class=\"actions-search input-group\" method=\"get\" action=\"/actions\" role=\"search\">");
        sb.append("<input type=\"hidden\" name=\"status\" value=\"").append(Http.esc(q.statusSlug())).append("\">");
        sb.append("<input type=\"hidden\" name=\"domain\" value=\"").append(Http.esc(q.domainSlug())).append("\">");
        sb.append("<span class=\"input-group-text\">").append(Icons.icon("search")).append("</span>");
        sb.append("<input type=\"search\" class=\"form-control\" name=\"q\" value=\"").append(Http.esc(q.search()))
                .append("\" placeholder=\"Rechercher une action, joueur, PNJ, quête, projet…\" aria-label=\"Rechercher\">");
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
        sb.append("<p class=\"count-note\">").append(countHtml).append("</p>");
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
     * #881 — une ligne de l'Historique, quelle qu'en soit l'origine (action agent, opération du panel,
     * refus avant envoi) : tous les onglets se rendent avec le même tableau et les mêmes cartes.
     *
     * @param href        lien utile (fiche de l'action, projet), ou vide
     * @param rowLink     la ligne entière ouvre {@code href} (fiche d'action, #266)
     * @param targetHtml  cible (HTML sûr), vide = « — »
     * @param statusHtml  pastille de statut (HTML sûr)
     */
    record Line(Instant at, String label, String code, String origin, String href, String hrefLabel, boolean rowLink,
                String targetHtml, String targetCompactHtml, String statusHtml, String result, String actor) {

        static Line of(AgentActionRow a, Query q, PlayerNames names) {
            ActionView.Target tgt = ActionView.target(a, names);
            return new Line(a.createdAt(), ActionView.humanLabel(a.type()), a.type(),
                    ActionView.Domain.of(a.type()).label(), detailHref(a, q), "Voir le détail", true,
                    tgt.isEmpty() ? "" : tgt.html(), tgt.isEmpty() ? "" : tgt.compactHtml(),
                    ActionView.statusPill(a.status()), ActionView.shortResult(a), nz(a.createdBy()));
        }

        static Line of(PanelOperation o) {
            String project = o.name().isEmpty() ? (o.buildingId().isEmpty() ? "" : Ui.id(o.buildingId()))
                    : Http.esc(o.name()) + (o.buildingId().isEmpty() ? "" : " " + Ui.id(o.buildingId()));
            String gone = o.link().isEmpty() && !o.projectId().isEmpty()
                    ? " <span class=\"muted small\">(n'existe plus)</span>" : "";
            String target = project.isEmpty() && gone.isEmpty() ? "" : project + gone;
            return new Line(o.at(), o.label(), o.action(), ActionView.Domain.BUILDINGS.label() + " · PlugAdmin seul",
                    o.link(), "Ouvrir le projet", false, target, target,
                    o.ok() ? Ui.pill("fait", "success", "✓") : Ui.pill("refusé", "rejected", "✕"),
                    o.ok() ? "" : o.reason(), o.actor());
        }

        static Line of(Refusal r) {
            return new Line(r.at(), r.type().isEmpty() ? "Action refusée" : ActionView.humanLabel(r.type()), r.type(),
                    "Refusée avant envoi", "", "", false, Http.esc(r.target()), Http.esc(r.target()),
                    Ui.pill("refusé", "rejected", "✕"), r.reason(), r.actor());
        }
    }

    /** Tableau sur grand écran, cartes sur téléphone : la même présentation pour tout l'Historique. */
    static String lines(List<Line> rows, Instant now) {
        return desktopTable(rows, now) + mobileCards(rows, now);
    }

    /**
     * Le tableau sur grand écran. Issue #266 : la ligne d'une action ouvre sa fiche — le libellé est un
     * vrai lien (clavier, lecteur d'écran, clic du milieu), la ligne entière est cliquable par
     * {@code panel.js} ({@code data-href}), et « Voir le détail » le dit en toutes lettres. #881 : cinq
     * colonnes sobres (date, opération, cible, résultat, acteur), le domaine sous le libellé.
     */
    private static String desktopTable(List<Line> rows, Instant now) {
        StringBuilder sb = new StringBuilder("<div class=\"table-responsive d-none d-md-block\">"
                + "<table class=\"table align-middle actions-table actions-history\">");
        sb.append("<thead><tr><th>Date</th><th>Opération</th><th>Cible</th><th>Résultat</th><th>Acteur</th>"
                + "</tr></thead><tbody>");
        for (Line l : rows) {
            String href = Http.esc(l.href());
            sb.append("<tr class=\"act-row\"").append(l.rowLink() ? " data-href=\"" + href + "\"" : "").append(">")
                    .append("<td class=\"nowrap\">").append(when(l.at(), now)).append("</td><td>")
                    .append(l.rowLink() ? "<a class=\"act-label\" href=\"" + href + "\">" + Http.esc(l.label()) + "</a>"
                            : "<span class=\"act-label\">" + Http.esc(l.label()) + "</span>")
                    .append(l.code().isEmpty() ? "" : " " + Ui.id(l.code()))
                    .append("<div class=\"act-sub\">").append(Http.esc(l.origin()));
            if (!l.href().isEmpty()) {
                sb.append(" · <a href=\"").append(href).append("\"");
                if (l.rowLink()) {
                    sb.append(" aria-label=\"Voir le détail de l'action ").append(Http.esc(l.label())).append("\"");
                }
                sb.append(">").append(Http.esc(l.hrefLabel())).append("</a>");
            }
            sb.append("</div></td><td>").append(l.targetHtml().isEmpty() ? "<span class=\"muted\">—</span>" : l.targetHtml())
                    .append("</td><td class=\"act-res\">").append(l.statusHtml())
                    .append(l.result().isEmpty() ? "" : " " + Http.esc(l.result()))
                    .append("</td><td class=\"muted nowrap\">").append(Http.esc(l.actor())).append("</td></tr>");
        }
        return sb.append("</tbody></table></div>").toString();
    }

    private static String mobileCards(List<Line> rows, Instant now) {
        StringBuilder sb = new StringBuilder("<div class=\"d-md-none actions-cards\">");
        for (Line l : rows) {
            sb.append(l.rowLink() ? "<a class=\"card actions-card\" href=\"" + Http.esc(l.href()) + "\">"
                            : "<div class=\"card actions-card\">")
                    .append("<div class=\"card-body\">")
                    .append("<div class=\"ac-top\">").append(l.statusHtml())
                    .append("<span class=\"act-sub\">").append(Http.esc(l.origin())).append("</span></div>")
                    .append("<div class=\"ac-title\">").append(Http.esc(l.label())).append("</div>");
            if (!l.targetCompactHtml().isEmpty()) {
                sb.append("<div class=\"ac-tgt\">").append(l.targetCompactHtml()).append("</div>");
            }
            if (!l.result().isEmpty()) {
                sb.append("<div class=\"ac-res muted\">").append(Http.esc(l.result())).append("</div>");
            }
            sb.append("<div class=\"ac-meta muted\">").append(Http.esc(ActionView.relativeTime(l.at(), now)));
            if (!l.actor().isEmpty()) {
                sb.append(" · ").append(Http.esc(l.actor()));
            }
            if (!l.rowLink() && !l.href().isEmpty()) {
                sb.append(" · <a href=\"").append(Http.esc(l.href())).append("\">").append(Http.esc(l.hrefLabel()))
                        .append("</a>");
            }
            sb.append("</div></div>").append(l.rowLink() ? "</a>" : "</div>");
        }
        return sb.append("</div>").toString();
    }

    /** Date homogène pour toutes les lignes : relative, l'instant exact au survol. */
    private static String when(Instant at, Instant now) {
        return "<span title=\"" + Http.esc(at.toString()) + "\">" + Http.esc(ActionView.relativeTime(at, now))
                + "</span>";
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
