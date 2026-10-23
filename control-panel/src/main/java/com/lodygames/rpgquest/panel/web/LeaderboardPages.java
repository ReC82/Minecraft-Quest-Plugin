package com.lodygames.rpgquest.panel.web;

import com.lodygames.rpgquest.panel.agent.LeaderboardForm;
import com.lodygames.rpgquest.panel.http.Http;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Page {@code /leaderboards} (issue #735) : les classements du site public LodyQuests, gérés sans
 * YAML. Une carte par classement (titre, ce qu'il mesure en mots simples, période, statut), des gestes
 * directs (activer, mettre en pause, monter / descendre, mettre en avant, masquer, dupliquer,
 * archiver) et un formulaire guidé en quatre questions : que mesurer, quelles cibles, sur quelle
 * période, comment le présenter.
 *
 * <p>La prévisualisation n'affiche que des comptes <strong>agrégés</strong> (participants, total)
 * renvoyés par le serveur, et un podium <strong>fictif</strong> clairement étiqueté : aucun pseudo
 * réel n'est lu par cette page. Toutes les écritures passent par les actions {@code leaderboard.*},
 * validées par le serveur avant écriture de {@code leaderboards.yml}.</p>
 */
public final class LeaderboardPages {

    static final String PATH = "/leaderboards";

    /** Libellés des mesures, dans l'ordre du formulaire. Une mesure inconnue garde son identifiant. */
    static final Map<String, String[]> METRICS = metrics();
    static final Map<String, String> CATEGORY_LABELS = Map.of("aventure", "Aventure", "combat", "Combat",
            "recolte", "Récolte", "exploration", "Exploration", "communaute", "Communauté", "autres", "Autres");
    static final Map<String, String[]> PERIODS = periods();
    static final Map<String, String> STATUS_LABELS = Map.of("draft", "Brouillon", "enabled", "Actif",
            "paused", "En pause", "archived", "Archivé");
    /** Icônes du sprite partagé avec le portail (#428). */
    static final List<String> ICONS = List.of("trophy", "skull", "spider", "slime", "boss", "sword", "shield",
            "pickaxe", "ore", "gem", "log", "grass", "flower", "bread", "apple", "pie", "fish", "potion", "chest",
            "scroll", "map", "waypoint", "npc", "talk", "xp", "heart", "coin");
    /** Joueurs fictifs du podium d'aperçu — jamais un vrai pseudo. */
    private static final List<String> FICTIONAL = List.of("Demo_Aventurier", "Demo_Exploratrice", "Demo_Forgeron",
            "Demo_Pecheuse", "Demo_Archer");
    private static final Locale FR = Locale.FRANCE;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy 'à' HH:mm", FR);

    /**
     * @param details     dernier {@code leaderboard.catalog.status} réussi ({@code null} = jamais relevé)
     * @param preview     dernier {@code leaderboard.preview} réussi ({@code null} = aucun)
     * @param previewWhen date de cet aperçu (HTML sûr, {@code <time>})
     * @param headerHtml  sélecteur d'agent + barre « Rafraîchir » (HTML sûr)
     * @param canWrite    droit {@code LEADERBOARD_WRITE}
     * @param filters     filtres de la liste : {@code statut}, {@code categorie}, {@code q}
     */
    public record View(String agentId, Map<String, Object> details, Map<String, Object> preview, String previewWhen,
                       String headerHtml, String csrf, boolean canWrite, Map<String, String> filters) {
    }

    private LeaderboardPages() {
    }

    static String header() {
        return Ui.pageHeader("trophy", "Classements du site",
                "Les classements du site public LodyQuests : ce qu'ils mesurent, sur quelle période, dans quel ordre "
                        + "et lesquels sont mis en avant sur l'accueil. Un nouveau classement naît en brouillon : le "
                        + "prévisualiser, puis l'activer. Aucun pseudo réel n'est affiché ici.", "");
    }

    @SuppressWarnings("unchecked")
    public static String render(View view) {
        StringBuilder sb = new StringBuilder(header());
        sb.append(view.headerHtml());
        Map<String, Object> d = view.details();
        if (d == null) {
            return sb.append(Ui.empty("trophy", "État pas encore chargé — cliquer sur « Rafraîchir les classements ».")).toString();
        }
        if (!Boolean.TRUE.equals(d.get("available"))) {
            return sb.append(Ui.banner("warn", "Le plugin installé ne gère pas encore les classements depuis "
                    + "PlugAdmin (version antérieure).")).toString();
        }
        ZoneId zone = zone(str(d.get("timezone")));
        boolean writable = view.canWrite() && Boolean.TRUE.equals(d.get("writable"));
        sb.append(banners(d, view.canWrite()));
        List<Map<String, Object>> boards = maps(d.get("boards"));
        sb.append(summary(boards));
        if (view.preview() != null) {
            sb.append(preview(view.preview(), view.previewWhen(), zone, d));
        }
        sb.append("<div class=\"npc-catbar mt-3\"><span class=\"npc-catbar-t\">Classements (").append(boards.size())
                .append(")</span>");
        if (writable) {
            sb.append("<button class=\"btn btn-sm btn-primary\" type=\"button\" data-bs-toggle=\"collapse\" "
                    + "data-bs-target=\"#lb-new\" aria-expanded=\"false\" aria-controls=\"lb-new\">")
                    .append(Icons.icon("plus")).append("Nouveau classement</button>");
        }
        sb.append("</div>");
        if (writable) {
            sb.append("<div class=\"collapse\" id=\"lb-new\"><div class=\"card card-body npc-formcard\">")
                    .append("<p class=\"npc-fs-h\">Nouveau classement</p>")
                    .append(form(view, d, null, "lb-new", zone)).append("</div></div>");
        }
        sb.append(filters(view.filters()));
        List<Map<String, Object>> shown = boards.stream().filter(b -> matches(b, view.filters())).toList();
        if (shown.isEmpty()) {
            sb.append(Ui.empty("trophy", boards.isEmpty() ? "Aucun classement." : "Aucun classement pour ces filtres."));
        }
        for (int i = 0; i < shown.size(); i++) {
            sb.append(card(view, d, shown.get(i), writable, zone));
        }
        if (writable) {
            sb.append(timezoneForm(view, zone));
        }
        return sb.toString();
    }

    // ---- Bandeaux et synthèse ----------------------------------------------------------------

    private static String banners(Map<String, Object> d, boolean canWrite) {
        StringBuilder sb = new StringBuilder();
        String source = str(d.get("source"));
        if (!Boolean.TRUE.equals(d.get("valid"))) {
            StringBuilder errors = new StringBuilder("<ul class=\"mb-0\">");
            for (Object e : list(d.get("errors"))) {
                errors.append("<li>").append(Http.esc(str(e))).append("</li>");
            }
            sb.append(Ui.banner("err", "<strong>Le serveur refuse les fichiers de classements</strong> : aucun "
                    + "classement ni aucune saison ne s'applique tant qu'ils ne sont pas corrigés." + errors + "</ul>"));
        }
        if (d.get("readOnlyReason") != null) {
            sb.append(Ui.banner("warn", "<strong>Lecture seule.</strong> " + Http.esc(str(d.get("readOnlyReason")))));
        } else if ("bundled".equals(source)) {
            sb.append(Ui.banner("info", "Catalogue <strong>par défaut</strong> du plugin. Le premier changement fait "
                    + "ici le recopie dans <code>leaderboards.yml</code> (séparé des saisons et des données de jeu)"
                    + (canWrite ? "." : " — droit « gérer les classements » nécessaire.")));
        }
        if (!Boolean.TRUE.equals(d.get("webExportEnabled"))) {
            sb.append(Ui.banner("info", "<strong>Le site public n'affiche encore aucun classement réel</strong> : "
                    + "l'export web est désactivé sur ce serveur (décision #55, profils et pseudos). Vos réglages sont "
                    + "prêts et s'appliqueront dès l'ouverture ; l'aperçu public avec des joueurs fictifs est sur "
                    + "<code>/demo/classements/tous</code>."));
        }
        return sb.toString();
    }

    private static String summary(List<Map<String, Object>> boards) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String status : List.of("enabled", "draft", "paused", "archived")) {
            counts.put(status, 0);
        }
        int featured = 0;
        for (Map<String, Object> b : boards) {
            counts.merge(str(b.get("status")), 1, Integer::sum);
            if (Boolean.TRUE.equals(b.get("featured")) && "enabled".equals(str(b.get("status")))) {
                featured++;
            }
        }
        StringBuilder sb = new StringBuilder("<p class=\"d-flex flex-wrap gap-2 mb-2\">");
        counts.forEach((status, count) -> sb.append(badge(status)).append(' ').append(count).append("&nbsp;&nbsp;"));
        sb.append("<span class=\"badge text-bg-warning\">À l'accueil</span> ").append(featured).append("</p>");
        return sb.toString();
    }

    private static String filters(Map<String, String> f) {
        StringBuilder sb = new StringBuilder("<form method=\"get\" action=\"").append(PATH)
                .append("\" class=\"row g-2 align-items-end mb-3\">");
        String agent = f.getOrDefault("agent", "");
        if (!agent.isEmpty()) {
            sb.append("<input type=\"hidden\" name=\"agent\" value=\"").append(Http.esc(agent)).append("\">");
        }
        sb.append("<div class=\"col-6 col-md-3\"><label class=\"form-label\" for=\"lbf-s\">Statut</label>")
                .append("<select class=\"form-select\" id=\"lbf-s\" name=\"statut\"><option value=\"\">Tous</option>");
        for (String status : List.of("enabled", "draft", "paused", "archived")) {
            sb.append(option(status, STATUS_LABELS.get(status), status.equals(f.get("statut"))));
        }
        sb.append("</select></div><div class=\"col-6 col-md-3\"><label class=\"form-label\" for=\"lbf-c\">Catégorie</label>")
                .append("<select class=\"form-select\" id=\"lbf-c\" name=\"categorie\"><option value=\"\">Toutes</option>");
        for (String category : LeaderboardForm.CATEGORIES) {
            sb.append(option(category, CATEGORY_LABELS.get(category), category.equals(f.get("categorie"))));
        }
        sb.append("</select></div><div class=\"col-12 col-md-4\"><label class=\"form-label\" for=\"lbf-q\">Recherche</label>")
                .append("<input class=\"form-control\" id=\"lbf-q\" name=\"q\" maxlength=\"60\" value=\"")
                .append(Http.esc(f.getOrDefault("q", ""))).append("\" placeholder=\"Titre ou identifiant\"></div>")
                .append("<div class=\"col-12 col-md-2\"><button class=\"btn btn-outline-primary w-100\" type=\"submit\">")
                .append(Icons.icon("filter")).append("Filtrer</button></div></form>");
        return sb.toString();
    }

    static boolean matches(Map<String, Object> board, Map<String, String> f) {
        String status = f.getOrDefault("statut", "");
        String category = f.getOrDefault("categorie", "");
        String q = f.getOrDefault("q", "").trim().toLowerCase(FR);
        return (status.isEmpty() || status.equals(str(board.get("status"))))
                && (category.isEmpty() || category.equals(str(board.get("category"))))
                && (q.isEmpty() || str(board.get("name")).toLowerCase(FR).contains(q)
                || str(board.get("id")).contains(q));
    }

    // ---- Carte d'un classement -------------------------------------------------------------

    private static String card(View view, Map<String, Object> d, Map<String, Object> b, boolean writable, ZoneId zone) {
        String id = str(b.get("id"));
        String status = str(b.get("status"));
        String uid = "lb-" + id;
        boolean featured = Boolean.TRUE.equals(b.get("featured"));
        boolean visible = !Boolean.FALSE.equals(b.get("visible"));
        StringBuilder sb = new StringBuilder("<div class=\"card card-body mb-2\" id=\"").append(uid).append("\">");
        sb.append("<div class=\"d-flex align-items-center gap-2 flex-wrap\">")
                .append(PixelIcons.tile(icon(b))).append("<strong>").append(Http.esc(str(b.get("name"))))
                .append("</strong> <code>").append(Http.esc(id)).append("</code> ").append(badge(status));
        if (featured) {
            sb.append(" <span class=\"badge text-bg-warning\">À l'accueil</span>");
        }
        if (!visible) {
            sb.append(" <span class=\"badge text-bg-dark\">Caché du site</span>");
        }
        sb.append(" <span class=\"badge text-bg-light\">").append(Http.esc(CATEGORY_LABELS.getOrDefault(
                str(b.get("category")), str(b.get("category"))))).append("</span></div>");
        if (!str(b.get("description")).isEmpty()) {
            sb.append("<div class=\"muted small\">« ").append(Http.esc(str(b.get("description")))).append(" »</div>");
        }
        sb.append("<div>").append(Http.esc(describe(b, d, zone))).append("</div>");
        sb.append("<div class=\"d-flex flex-wrap gap-2 mt-2\">")
                .append(post(view, "leaderboard.preview", id, Map.of(), "Prévisualiser", "btn secondary sm", "eye"));
        if (writable) {
            sb.append(toggleButton(uid + "-edit", "edit", "Modifier"));
            if ("enabled".equals(status)) {
                sb.append(post(view, "leaderboard.status", id, Map.of("status", "paused"), "Mettre en pause",
                        "btn secondary sm", null));
            } else if (!"archived".equals(status)) {
                sb.append(post(view, "leaderboard.status", id, Map.of("status", "enabled"), "Activer", "btn sm", "check"));
            } else {
                sb.append(post(view, "leaderboard.status", id, Map.of("status", "draft"), "Remettre en brouillon",
                        "btn secondary sm", null));
            }
            sb.append(toggleButton(uid + "-more", "menu", "Plus…"));
        }
        sb.append("</div>");
        if (writable) {
            // Gestes secondaires repliés : la carte reste lisible (prévisualiser, modifier, activer).
            sb.append("<div class=\"collapse mt-2\" id=\"").append(uid).append("-more\"><div class=\"d-flex flex-wrap gap-2\">")
                    .append(post(view, "leaderboard.move", id, Map.of("direction", "up"), "Monter", "btn secondary sm", "up"))
                    .append(post(view, "leaderboard.move", id, Map.of("direction", "down"), "Descendre",
                            "btn secondary sm", "down"))
                    .append(post(view, "leaderboard.flag", id, Map.of("flag", "featured", "value",
                            Boolean.toString(!featured)), featured ? "Retirer de l'accueil" : "Mettre à l'accueil",
                            "btn secondary sm", "bell"))
                    .append(post(view, "leaderboard.flag", id, Map.of("flag", "visible", "value",
                            Boolean.toString(!visible)), visible ? "Cacher du site" : "Montrer sur le site",
                            "btn secondary sm", "eye"))
                    .append(toggleButton(uid + "-dup", "copy", "Dupliquer"));
            if (!"archived".equals(status)) {
                sb.append(toggleButton(uid + "-arc", "box", "Archiver"));
            }
            sb.append("</div></div>");
            sb.append("<div class=\"collapse mt-2\" id=\"").append(uid).append("-dup\">")
                    .append("<form method=\"post\" action=\"/agents/action\" class=\"npc-def-form\">")
                    .append(hidden(view, "leaderboard.duplicate")).append(field("board_id", id))
                    .append("<div class=\"mb-2\"><label class=\"form-label\" for=\"").append(uid)
                    .append("-dup-id\">Identifiant de la copie</label><input class=\"form-control\" id=\"").append(uid)
                    .append("-dup-id\" name=\"new_id\" required maxlength=\"64\" pattern=\"[a-z0-9_]{1,64}\" value=\"")
                    .append(Http.esc((id.length() > 57 ? id.substring(0, 57) : id) + "_copie")).append("\"></div>")
                    .append("<div class=\"mb-2\"><label class=\"form-label\" for=\"").append(uid)
                    .append("-dup-n\">Titre de la copie (facultatif)</label><input class=\"form-control\" id=\"")
                    .append(uid).append("-dup-n\" name=\"new_name\" maxlength=\"120\"></div>")
                    .append("<div class=\"form-text mb-2\">La copie naît en brouillon, hors de l'accueil.</div>")
                    .append("<button class=\"btn sm\" type=\"submit\">Créer la copie</button></form></div>");
            if (!"archived".equals(status)) {
                sb.append("<div class=\"collapse mt-2\" id=\"").append(uid).append("-arc\">")
                        .append("<form method=\"post\" action=\"/agents/action\" class=\"npc-def-form\">")
                        .append(hidden(view, "leaderboard.status")).append(field("board_id", id))
                        .append(field("status", "archived"))
                        .append("<p class=\"mb-2\">Archiver retire ce classement du site. Ses résultats déjà figés "
                                + "restent en base (rien n'est effacé) ; il pourra être remis en brouillon.</p>")
                        .append("<div class=\"form-check mb-2\"><input class=\"form-check-input\" type=\"checkbox\" "
                                + "name=\"confirm\" id=\"").append(uid).append("-arc-c\" required><label "
                                + "class=\"form-check-label\" for=\"").append(uid)
                        .append("-arc-c\">Je confirme l'archivage</label></div>")
                        .append("<button class=\"btn sm danger\" type=\"submit\">Archiver</button></form></div>");
            }
            sb.append("<div class=\"collapse mt-2\" id=\"").append(uid).append("-edit\"><div class=\"card card-body "
                    + "npc-formcard\">").append(form(view, d, b, uid + "-f", zone)).append("</div></div>");
        }
        return sb.append("</div>").toString();
    }

    /** « Monstres hostiles et boss · chaque semaine (fenêtre en cours …) · 10 joueurs. » */
    static String describe(Map<String, Object> b, Map<String, Object> d, ZoneId zone) {
        List<String> parts = new ArrayList<>();
        String metric = str(b.get("metric"));
        String targets = targets(b, d);
        parts.add(metricLabel(metric) + (targets.isEmpty() ? "" : " — " + targets));
        String period = str(b.get("period"));
        String periodText = PERIODS.getOrDefault(period, new String[] {period, ""})[0];
        if ("event".equals(period)) {
            periodText += " « " + eventName(d, str(b.get("event"))) + " »";
        }
        if (b.get("window") instanceof Map<?, ?> window && window.get("start") instanceof String start
                && window.get("end") instanceof String end) {
            periodText += " (du " + date(start, zone) + " au " + date(end, zone) + ")";
        } else if ("custom".equals(period) && b.get("start") != null) {
            periodText += " (du " + date(str(b.get("start")), zone) + " au " + date(str(b.get("end")), zone) + ")";
        }
        parts.add(periodText);
        parts.add("top " + num(b.get("size")));
        return String.join(" · ", parts) + ".";
    }

    /** Cibles du filtre en mots : familles par leur libellé, cibles exactes telles quelles. */
    static String targets(Map<String, Object> b, Map<String, Object> d) {
        if (!(b.get("filter") instanceof Map<?, ?> filter)) {
            return "";
        }
        Map<String, String> familyLabels = familyLabels(d);
        List<String> names = new ArrayList<>();
        for (Map.Entry<?, ?> entry : filter.entrySet()) {
            boolean family = String.valueOf(entry.getKey()).endsWith("-family");
            for (Object value : list(entry.getValue())) {
                names.add(family ? familyLabels.getOrDefault(entry.getKey() + "/" + value, str(value)) : str(value));
            }
        }
        return String.join(", ", names);
    }

    // ---- Aperçu ------------------------------------------------------------------------------

    private static String preview(Map<String, Object> p, String when, ZoneId zone, Map<String, Object> d) {
        StringBuilder sb = new StringBuilder("<div class=\"card card-body mb-3 border-primary\" id=\"lb-preview\">");
        if (!Boolean.TRUE.equals(p.get("available"))) {
            return sb.append(Http.esc(str(p.get("message")))).append("</div>").toString();
        }
        Map<String, Object> b = p.get("board") instanceof Map<?, ?> m ? cast(m) : Map.of();
        sb.append("<p class=\"npc-fs-h mb-1\">").append(Icons.icon("eye")).append("Aperçu — ")
                .append(Http.esc(str(b.get("name")))).append(" <span class=\"muted small\">(").append(when)
                .append(")</span></p>");
        sb.append("<div>").append(Http.esc(describe(b, d, zone))).append("</div>");
        if (p.get("window") instanceof Map<?, ?> window) {
            sb.append("<div class=\"small\">Période en cours : <code>").append(Http.esc(str(window.get("key"))))
                    .append("</code>");
            if (window.get("start") != null) {
                sb.append(" — du ").append(Http.esc(date(str(window.get("start")), zone))).append(" au ")
                        .append(Http.esc(date(str(window.get("end")), zone))).append(" (").append(Http.esc(zone.getId()))
                        .append(", fin exclue)");
            } else {
                sb.append(" — depuis toujours");
            }
            sb.append(".</div>");
        }
        if (p.get("previous") instanceof Map<?, ?> previous) {
            sb.append("<div class=\"small\">Période précédente <code>").append(Http.esc(str(previous.get("key"))))
                    .append("</code> : ").append(Boolean.TRUE.equals(previous.get("archived"))
                            ? "résultats figés (archivés)." : "pas encore figée (elle le sera dans la minute qui suit "
                            + "sa fin, si le classement est actif).").append("</div>");
        }
        sb.append("<div class=\"small\">Mesure suivie depuis : ").append(p.get("trackedSince") == null ? "jamais encore"
                : Http.esc(date(str(p.get("trackedSince")), zone))).append(".</div>");
        if (p.get("participation") instanceof Map<?, ?> part) {
            sb.append("<div class=\"small\"><strong>").append(Http.esc(str(part.get("players"))))
                    .append("</strong> participant(s) sur la période en cours, total <strong>")
                    .append(Http.esc(num(part.get("total")))).append("</strong> (compte agrégé, aucun pseudo).</div>");
        }
        List<Object> warnings = list(p.get("warnings"));
        if (!warnings.isEmpty()) {
            StringBuilder items = new StringBuilder("<ul class=\"mb-0\">");
            warnings.forEach(w -> items.append("<li>").append(Http.esc(str(w))).append("</li>"));
            sb.append(Ui.banner("warn", items + "</ul>"));
        }
        sb.append(fictionalPodium(b));
        return sb.append("</div>").toString();
    }

    /** Podium d'illustration : joueurs et valeurs <strong>inventés</strong>, étiquetés comme tels. */
    static String fictionalPodium(Map<String, Object> b) {
        int size = Math.min(5, Math.max(1, parseInt(num(b.get("size")), 10)));
        StringBuilder sb = new StringBuilder("<p class=\"small mt-2 mb-1\"><span class=\"badge text-bg-secondary\">"
                + "Données fictives</span> Rendu indicatif sur le site (joueurs inventés, valeurs inventées) :</p>"
                + "<ol class=\"mb-0\">");
        for (int i = 0; i < size; i++) {
            sb.append("<li>").append(FICTIONAL.get(i)).append(" — ").append((size - i) * 7).append("</li>");
        }
        return sb.append("</ol>").toString();
    }

    // ---- Formulaire guidé ----------------------------------------------------------------------

    /** Formulaire de création ({@code b == null}) ou de modification. */
    static String form(View view, Map<String, Object> d, Map<String, Object> b, String uid, ZoneId zone) {
        boolean create = b == null;
        Map<String, Object> v = create ? Map.of("category", "combat", "metric", "ENTITY_KILL", "period", "weekly",
                "size", 10, "visible", true) : b;
        StringBuilder sb = new StringBuilder("<form method=\"post\" action=\"/agents/action\" class=\"npc-def-form\">");
        sb.append(hidden(view, "leaderboard.save")).append(field("create", Boolean.toString(create)))
                .append(field("timezone", zone.getId()));
        if (!create) {
            sb.append(field("board_id", str(b.get("id")))).append(field("expected_version", str(b.get("version"))));
        }
        // 1. Que mesurer ?
        sb.append("<fieldset class=\"mb-3\"><legend class=\"form-label fs-6 mb-1\">1. Que mesurer ?</legend>")
                .append("<select class=\"form-select\" name=\"metric\" aria-label=\"Mesure\">");
        for (Map<String, Object> metric : maps(d.get("metrics"))) {
            String id = str(metric.get("id"));
            String since = metric.get("trackedSince") == null ? (Boolean.TRUE.equals(metric.get("dated"))
                    ? " — jamais encore collectée" : "") : " — suivie depuis le " + day(str(metric.get("trackedSince")), zone);
            sb.append(option(id, metricLabel(id) + since, id.equals(str(v.get("metric")))));
        }
        sb.append("</select><div class=\"form-text\">Seules les mesures réellement enregistrées par le serveur sont "
                + "proposées : aucun score n'est inventé. « Quêtes terminées depuis toujours » ne se découpe pas en "
                + "périodes.</div></fieldset>");
        // 2. Quelles cibles ?
        sb.append("<fieldset class=\"mb-3\"><legend class=\"form-label fs-6 mb-1\">2. Quelles cibles ? "
                + "<span class=\"muted small\">(facultatif : vide = tout)</span></legend>");
        Map<String, Object> filter = v.get("filter") instanceof Map<?, ?> m ? cast(m) : Map.of();
        if (d.get("families") instanceof Map<?, ?> families) {
            for (Map.Entry<?, ?> kind : families.entrySet()) {
                String key = str(kind.getKey());
                List<Object> chosen = list(filter.get(key));
                sb.append("<div class=\"small fw-semibold mt-1\">").append(key.equals("entity-family")
                        ? "Familles de monstres (mesure « Monstres et créatures vaincus »)"
                        : "Familles de blocs (mesure « Blocs cassés »)").append("</div><div class=\"d-flex flex-wrap gap-3\">");
                for (Map<String, Object> family : maps(kind.getValue())) {
                    String fid = str(family.get("id"));
                    String inputId = uid + "-fam-" + key + "-" + fid;
                    sb.append("<div class=\"form-check\"><input class=\"form-check-input\" type=\"checkbox\" name=\"fam__")
                            .append(Http.esc(key)).append("__").append(Http.esc(fid)).append("\" id=\"")
                            .append(Http.esc(inputId)).append('"').append(chosen.contains(fid) ? " checked" : "")
                            .append("><label class=\"form-check-label\" for=\"").append(Http.esc(inputId)).append("\">")
                            .append(Http.esc(str(family.get("label")))).append(" <span class=\"muted small\">(")
                            .append(Http.esc(str(family.get("size")))).append(")</span></label></div>");
                }
                sb.append("</div>");
            }
        }
        List<String> exact = new ArrayList<>();
        for (String key : List.of("entity", "block", "item", "quest")) {
            list(filter.get(key)).forEach(value -> exact.add(str(value)));
        }
        sb.append("<label class=\"form-label mt-2\" for=\"").append(uid).append("-tg\">Cibles exactes</label>")
                .append("<input class=\"form-control\" id=\"").append(uid).append("-tg\" name=\"targets\" maxlength=\"2000\" "
                        + "value=\"").append(Http.esc(String.join(", ", exact))).append("\" placeholder=\"ex. ZOMBIE, HUSK\">")
                .append("<div class=\"form-text\">Noms Minecraft séparés par des virgules : un monstre (ZOMBIE), un "
                        + "bloc (DIAMOND_ORE), un poisson (COD) ou un id de quête, selon la mesure. S'ajoutent aux "
                        + "familles cochées.</div></fieldset>");
        // 3. Sur quelle période ?
        String period = str(v.get("period"));
        sb.append("<fieldset class=\"mb-3\"><legend class=\"form-label fs-6 mb-1\">3. Sur quelle période ?</legend>");
        PERIODS.forEach((key, label) -> {
            String id = uid + "-p-" + key;
            sb.append("<div class=\"form-check\"><input class=\"form-check-input\" type=\"radio\" name=\"period\" id=\"")
                    .append(id).append("\" value=\"").append(key).append('"').append(key.equals(period) ? " checked" : "")
                    .append(" required><label class=\"form-check-label\" for=\"").append(id).append("\"><strong>")
                    .append(label[0]).append("</strong> — ").append(label[1]).append("</label></div>");
        });
        List<Map<String, Object>> events = maps(d.get("events"));
        sb.append("<div class=\"row g-2 mt-1\"><div class=\"col-12 col-md-4\"><label class=\"form-label\" for=\"")
                .append(uid).append("-ev\">Événement suivi</label><select class=\"form-select\" id=\"").append(uid)
                .append("-ev\" name=\"event\"><option value=\"\">—</option>");
        for (Map<String, Object> event : events) {
            sb.append(option(str(event.get("id")), str(event.get("name")) + " (" + day(str(event.get("start")), zone)
                    + ")", str(event.get("id")).equals(str(v.get("event")))));
        }
        sb.append("</select></div><div class=\"col-6 col-md-4\"><label class=\"form-label\" for=\"").append(uid)
                .append("-st\">Début</label><input class=\"form-control\" type=\"datetime-local\" id=\"").append(uid)
                .append("-st\" name=\"start_local\" value=\"").append(Http.esc(local(v.get("start"), zone))).append("\">")
                .append("</div><div class=\"col-6 col-md-4\"><label class=\"form-label\" for=\"").append(uid)
                .append("-en\">Fin</label><input class=\"form-control\" type=\"datetime-local\" id=\"").append(uid)
                .append("-en\" name=\"end_local\" value=\"").append(Http.esc(local(v.get("end"), zone))).append("\">")
                .append("</div></div><div class=\"form-text\">Événement : seulement pour « Événement » (les événements "
                        + "se déclarent dans statistics.yml"
                        + (events.isEmpty() ? " — aucun pour l'instant" : "") + "). Début / fin : seulement pour « Dates "
                        + "personnalisées », heure de ").append(Http.esc(zone.getId()))
                .append(" (changement d'heure compris). Les jours, semaines (lundi) et mois commencent à minuit dans "
                        + "ce fuseau.</div></fieldset>");
        // 4. Présentation
        sb.append("<fieldset class=\"mb-2\"><legend class=\"form-label fs-6 mb-1\">4. Présentation sur le site</legend>")
                .append("<div class=\"row g-2\"><div class=\"col-12 col-md-8\"><label class=\"form-label\" for=\"")
                .append(uid).append("-n\">Titre</label><input class=\"form-control\" id=\"").append(uid)
                .append("-n\" name=\"name\" required maxlength=\"120\" value=\"").append(Http.esc(str(v.get("name"))))
                .append("\" placeholder=\"Chasseurs de zombies\"></div>");
        if (create) {
            sb.append("<div class=\"col-12 col-md-4\"><label class=\"form-label\" for=\"").append(uid)
                    .append("-id\">Identifiant (facultatif)</label><input class=\"form-control\" id=\"").append(uid)
                    .append("-id\" name=\"board_id\" maxlength=\"64\" pattern=\"[a-z0-9_]{0,64}\" "
                            + "placeholder=\"tiré du titre\"></div>");
        }
        sb.append("</div><label class=\"form-label mt-2\" for=\"").append(uid).append("-d\">Description (facultatif)</label>")
                .append("<input class=\"form-control\" id=\"").append(uid).append("-d\" name=\"description\" maxlength=\"200\" "
                        + "value=\"").append(Http.esc(str(v.get("description"))))
                .append("\" placeholder=\"Une phrase pour les joueurs\">")
                .append("<div class=\"row g-2 mt-1\"><div class=\"col-6 col-md-4\"><label class=\"form-label\" for=\"")
                .append(uid).append("-c\">Catégorie</label><select class=\"form-select\" id=\"").append(uid)
                .append("-c\" name=\"category\">");
        for (String category : LeaderboardForm.CATEGORIES) {
            sb.append(option(category, CATEGORY_LABELS.get(category), category.equals(str(v.get("category")))));
        }
        sb.append("</select></div><div class=\"col-6 col-md-4\"><label class=\"form-label\" for=\"").append(uid)
                .append("-sz\">Joueurs affichés</label><input class=\"form-control\" type=\"number\" min=\"1\" max=\"50\" "
                        + "id=\"").append(uid).append("-sz\" name=\"size\" required value=\"").append(Http.esc(num(v.get("size"))))
                .append("\"></div></div>");
        String icon = str(v.get("icon"));
        sb.append("<p class=\"form-label mt-2 mb-1\">Icône</p><div class=\"d-flex flex-wrap gap-2\">")
                .append(iconChoice(uid, "", icon.isEmpty(), "Selon la mesure"));
        for (String name : ICONS) {
            sb.append(iconChoice(uid, name, name.equals(icon), name));
        }
        sb.append("</div>");
        sb.append(check(uid + "-vi", "visible", !Boolean.FALSE.equals(v.get("visible")), "Visible sur le site "
                + "(décoché : calculé et figé, mais caché)"))
                .append(check(uid + "-fe", "featured", Boolean.TRUE.equals(v.get("featured")),
                        "Mis en avant sur l'accueil du site (3 au plus, dans l'ordre de la liste)"))
                .append("</fieldset>");
        sb.append("<div class=\"form-text mb-2\">").append(create ? "Le classement naît en <strong>brouillon</strong> : "
                + "rien n'est calculé ni publié avant « Activer »." : "Le statut et la place dans la liste ne changent pas ici.")
                .append(" Le serveur vérifie tout avant d'écrire (cibles, événement, dates).</div>")
                .append("<button class=\"btn sm\" type=\"submit\">").append(Icons.icon("save"))
                .append(create ? "Créer le brouillon" : "Enregistrer").append("</button></form>");
        return sb.toString();
    }

    private static String timezoneForm(View view, ZoneId zone) {
        return "<details class=\"mt-4\"><summary>Réglage avancé : fuseau horaire</summary>"
                + "<form method=\"post\" action=\"/agents/action\" class=\"npc-def-form mt-2\">"
                + hidden(view, "leaderboard.timezone")
                + "<label class=\"form-label\" for=\"lb-tz\">Fuseau des jours, semaines et mois</label>"
                + "<input class=\"form-control\" id=\"lb-tz\" name=\"timezone\" required maxlength=\"64\" value=\""
                + Http.esc(zone.getId()) + "\"><div class=\"form-text mb-2\">Europe/Brussels par défaut : minuit à "
                + "Bruxelles, changement d'heure compris.</div><button class=\"btn sm secondary\" type=\"submit\">"
                + "Changer le fuseau</button></form></details>";
    }

    // ---- Petits rendus -------------------------------------------------------------------------

    private static String post(View view, String type, String id, Map<String, String> extra, String label,
                               String css, String icon) {
        StringBuilder sb = new StringBuilder("<form method=\"post\" action=\"/agents/action\" class=\"d-inline\">")
                .append(hidden(view, type)).append(field("board_id", id));
        extra.forEach((k, v) -> sb.append(field(k, v)));
        return sb.append("<button class=\"").append(css).append("\" type=\"submit\">")
                .append(icon == null ? "" : Icons.icon(icon)).append(Http.esc(label)).append("</button></form>").toString();
    }

    private static String toggleButton(String target, String icon, String label) {
        return "<button class=\"btn secondary sm\" type=\"button\" data-bs-toggle=\"collapse\" data-bs-target=\"#"
                + target + "\" aria-expanded=\"false\" aria-controls=\"" + target + "\">" + Icons.icon(icon)
                + Http.esc(label) + "</button>";
    }

    private static String hidden(View view, String type) {
        return field("_csrf", view.csrf()) + field("agent", view.agentId()) + field("type", type) + field("return", PATH);
    }

    private static String field(String name, String value) {
        return "<input type=\"hidden\" name=\"" + Http.esc(name) + "\" value=\"" + Http.esc(value) + "\">";
    }

    private static String option(String value, String label, boolean selected) {
        return "<option value=\"" + Http.esc(value) + "\"" + (selected ? " selected" : "") + ">" + Http.esc(label)
                + "</option>";
    }

    private static String check(String id, String name, boolean checked, String label) {
        return "<div class=\"form-check mt-2\"><input class=\"form-check-input\" type=\"checkbox\" name=\"" + name
                + "\" id=\"" + id + "\"" + (checked ? " checked" : "") + "><label class=\"form-check-label\" for=\""
                + id + "\">" + Http.esc(label) + "</label></div>";
    }

    private static String iconChoice(String uid, String name, boolean checked, String label) {
        String id = uid + "-ic-" + (name.isEmpty() ? "auto" : name);
        return "<div class=\"form-check form-check-inline me-0\"><input class=\"form-check-input\" type=\"radio\" "
                + "name=\"icon\" id=\"" + id + "\" value=\"" + Http.esc(name) + "\"" + (checked ? " checked" : "")
                + "><label class=\"form-check-label\" for=\"" + id + "\" title=\"" + Http.esc(label) + "\">"
                + (name.isEmpty() ? Http.esc(label) : PixelIcons.tile(name) + "<span class=\"visually-hidden\">"
                + Http.esc(label) + "</span>") + "</label></div>";
    }

    static String badge(String status) {
        String css = switch (status) {
            case "enabled" -> "text-bg-success";
            case "draft" -> "text-bg-info";
            case "paused" -> "text-bg-secondary";
            default -> "text-bg-dark";
        };
        return "<span class=\"badge " + css + "\">" + Http.esc(STATUS_LABELS.getOrDefault(status, status)) + "</span>";
    }

    private static String icon(Map<String, Object> b) {
        String icon = str(b.get("icon"));
        if (!icon.isEmpty()) {
            return icon;
        }
        return METRICS.getOrDefault(str(b.get("metric")), new String[] {"", "trophy"})[1];
    }

    static String metricLabel(String metric) {
        String[] known = METRICS.get(metric);
        return known != null ? known[0] : metric;
    }

    private static String eventName(Map<String, Object> d, String id) {
        return maps(d.get("events")).stream().filter(e -> id.equals(str(e.get("id")))).map(e -> str(e.get("name")))
                .findFirst().orElse(id);
    }

    private static Map<String, String> familyLabels(Map<String, Object> d) {
        Map<String, String> labels = new LinkedHashMap<>();
        if (d.get("families") instanceof Map<?, ?> families) {
            for (Map.Entry<?, ?> kind : families.entrySet()) {
                for (Map<String, Object> family : maps(kind.getValue())) {
                    labels.put(kind.getKey() + "/" + str(family.get("id")), str(family.get("label")));
                }
            }
        }
        return labels;
    }

    static ZoneId zone(String id) {
        try {
            return ZoneId.of(id);
        } catch (RuntimeException e) {
            return ZoneId.of("Europe/Brussels");
        }
    }

    /** « lundi 20 octobre 2026 à 00:00 » dans le fuseau des classements. */
    static String date(String iso, ZoneId zone) {
        try {
            return ZonedDateTime.ofInstant(Instant.parse(iso), zone).format(DAY);
        } catch (RuntimeException e) {
            return iso;
        }
    }

    private static String day(String iso, ZoneId zone) {
        try {
            return ZonedDateTime.ofInstant(Instant.parse(iso), zone).format(DateTimeFormatter.ofPattern("d MMMM yyyy", FR));
        } catch (RuntimeException e) {
            return iso;
        }
    }

    /** Valeur d'un champ {@code datetime-local} dans le fuseau des classements. */
    private static String local(Object iso, ZoneId zone) {
        if (iso == null) {
            return "";
        }
        try {
            return ZonedDateTime.ofInstant(Instant.parse(str(iso)), zone).toLocalDateTime()
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"));
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static Map<String, String[]> metrics() {
        Map<String, String[]> m = new LinkedHashMap<>();
        m.put("ENTITY_KILL", new String[] {"Monstres et créatures vaincus", "skull"});
        m.put("BLOCK_BREAK", new String[] {"Blocs cassés (minage, bûcheronnage, récoltes)", "pickaxe"});
        m.put("FISH_CAUGHT", new String[] {"Prises de pêche", "fish"});
        m.put("QUEST_COMPLETION", new String[] {"Quêtes terminées pendant la période", "scroll"});
        m.put("QUESTS_COMPLETED", new String[] {"Quêtes terminées depuis toujours", "scroll"});
        m.put("STORY_COMPLETED", new String[] {"Histoires terminées", "scroll"});
        m.put("WAYPOINT_DISCOVERED", new String[] {"Waypoints découverts", "waypoint"});
        m.put("CITY_DISCOVERED", new String[] {"Villes découvertes", "map"});
        m.put("WAYSTONE_DISCOVERED", new String[] {"Pierres de voyage découvertes", "waypoint"});
        m.put("RPG_XP_GLOBAL", new String[] {"XP RPG (toutes pistes)", "xp"});
        m.put("RPG_XP_COMBAT", new String[] {"XP RPG — combat", "sword"});
        m.put("RPG_XP_MINING", new String[] {"XP RPG — minage", "pickaxe"});
        m.put("RPG_XP_FARMING", new String[] {"XP RPG — agriculture", "bread"});
        m.put("RPG_XP_FISHING", new String[] {"XP RPG — pêche", "fish"});
        m.put("RPG_XP_EXPLORATION", new String[] {"XP RPG — exploration", "map"});
        return java.util.Collections.unmodifiableMap(m);
    }

    private static Map<String, String[]> periods() {
        Map<String, String[]> m = new LinkedHashMap<>();
        m.put("weekly", new String[] {"Chaque semaine", "du lundi minuit au lundi suivant, puis on recommence"});
        m.put("daily", new String[] {"Chaque jour", "de minuit à minuit"});
        m.put("monthly", new String[] {"Chaque mois", "du 1er au 1er du mois suivant"});
        m.put("lifetime", new String[] {"Depuis toujours", "tout ce que le serveur a enregistré"});
        m.put("season", new String[] {"Saison", "suit la saison en cours de statistics.yml"});
        m.put("event", new String[] {"Événement", "suit les dates d'un événement de statistics.yml"});
        m.put("custom", new String[] {"Dates personnalisées", "début et fin fixés ci-dessous"});
        return java.util.Collections.unmodifiableMap(m);
    }

    private static int parseInt(String raw, int fallback) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    private static List<Object> list(Object raw) {
        return raw instanceof List<?> l ? new ArrayList<>(l) : List.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object raw) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list(raw)) {
            if (item instanceof Map<?, ?> map) {
                out.add((Map<String, Object>) map);
            }
        }
        return out;
    }

    /** Nombre relu du JSON (souvent {@code 10.0}) écrit en entier. */
    static String num(Object value) {
        return value instanceof Number n ? Long.toString(n.longValue()) : str(value);
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
