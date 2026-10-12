package com.lodygames.rpgquest.panel.web;

import com.lodygames.rpgquest.panel.agent.AgentActionRow;
import com.lodygames.rpgquest.panel.ai.AiProvider;
import com.lodygames.rpgquest.panel.ai.BuildingCorrectionLoop;
import com.lodygames.rpgquest.panel.building.BriefLevels;
import com.lodygames.rpgquest.panel.building.BuildingPlanDraft;
import com.lodygames.rpgquest.panel.building.BuildingPlanDraftStore.Revision;
import com.lodygames.rpgquest.panel.building.BuildingPlanPreview;
import com.lodygames.rpgquest.panel.building.BuildingPlanVerdict;
import com.lodygames.rpgquest.panel.building.BuildingPlanWorkbench;
import com.lodygames.rpgquest.panel.building.BuildingPlanWorkbench.CheckState;
import com.lodygames.rpgquest.panel.building.BuildingPlanWorkbench.Status;
import com.lodygames.rpgquest.panel.building.BuildingProject;
import com.lodygames.rpgquest.panel.building.BuildingSiteView;
import com.lodygames.rpgquest.panel.building.BuildingVoxels;
import com.lodygames.rpgquest.panel.building.IsometricView;
import com.lodygames.rpgquest.panel.building.PlanVerdictText;
import com.lodygames.rpgquest.panel.http.Http;
import com.lodygames.rpgquest.panel.security.Session;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * L'<strong>Atelier de création</strong> (issues #265, #267) : concevoir un bâtiment sans emplacement.
 *
 * <h2>Le parcours, en quatre étapes</h2>
 *
 * <p>Décrire → Proposer (l'IA) → Vérifier → Enregistrer dans la bibliothèque. À chaque instant, la
 * page ne met en avant qu'<strong>une</strong> action : celle qui fait avancer le projet. Le reste —
 * plan brut, coupes techniques, versions, réponse du modèle — est replié dans « Détails techniques »,
 * avec un bouton « Copier le diagnostic » pour l'assistance.</p>
 *
 * <h2>Ce que la page ne fait jamais</h2>
 *
 * <p>Elle ne pose aucun bloc, et n'appelle l'IA que sur un clic explicite, annoncé comme facturé et
 * protégé contre le double clic (#256). Vérifier, prévisualiser, reprendre, rafraîchir : gratuit.</p>
 *
 * <p>Vocabulaire (#265) : bâtiment, entrée, étage, fenêtre, toit, terrain, vérifier, enregistrer.
 * Jamais « brief », « audit », « schematic », « YAML » ni un code d'erreur dans le parcours principal.</p>
 */
public final class WorkshopPages {

    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    public static final String BASE = "/buildings/workshop";

    private WorkshopPages() {
    }

    // ================================================================================
    //  Liste des projets
    // ================================================================================

    /**
     * Un emplacement dont on peut importer la description ou les plans (migration #267).
     *
     * @param brief       description du bâtiment saisie autrefois sur l'emplacement, ou vide
     * @param draftCount  brouillons de plan encore rattachés à cet emplacement seulement
     */
    public record Importable(String siteId, String siteName, String brief, int draftCount) {
    }

    /** Une ligne de la liste : le projet et où il en est, en une phrase. */
    public record ProjectRow(BuildingProject project, String state, int drafts) {
    }

    public static String index(Session session, List<ProjectRow> projects, List<Importable> importable,
                               String notice, String error) {
        StringBuilder sb = new StringBuilder();
        sb.append(Ui.pageHeader("box", "Atelier de création",
                "Décrivez un bâtiment en français : l'IA en propose un plan, vous le vérifiez, puis vous "
                        + "l'enregistrez dans la bibliothèque. Aucun emplacement n'est nécessaire pour "
                        + "créer : on choisit où le poser plus tard.",
                Ui.primaryLink(BASE + "/new", "plus", "Nouveau projet")));
        banners(sb, notice, error);

        if (projects.isEmpty()) {
            sb.append(Ui.empty("Aucun projet pour le moment. Cliquez sur « Nouveau projet » pour "
                    + "décrire votre premier bâtiment."));
        } else {
            sb.append("<div class=\"ws-grid\">");
            for (ProjectRow row : projects) {
                BuildingProject p = row.project();
                sb.append("<a class=\"card ws-card\" href=\"").append(projectUrl(p.id(), ""))
                        .append("\"><div class=\"card-body\"><div class=\"ws-card-title\">")
                        .append(Http.esc(p.name())).append("</div><div class=\"ws-card-state\">")
                        .append(Http.esc(row.state())).append("</div><div class=\"muted small\">")
                        .append(row.drafts()).append(" version(s) de plan · modifié le ")
                        .append(Http.esc(WHEN.format(p.updatedAt()))).append("</div></div></a>");
            }
            sb.append("</div>");
        }

        if (!importable.isEmpty()) {
            sb.append(detail("history", "Reprendre un travail commencé sur un emplacement"));
            sb.append("<p class=\"field-help\">Avant l'Atelier, la description d'un bâtiment et ses plans "
                    + "étaient rangés dans la fiche d'un emplacement. Les reprendre crée un projet ici, "
                    + "avec la description copiée et les plans rattachés. <strong>L'emplacement n'est pas "
                    + "modifié</strong> et rien n'est supprimé.</p><ul class=\"tight\">");
            for (Importable imp : importable) {
                sb.append("<li><form method=\"post\" action=\"").append(BASE).append("/import\" class=\"inline\">")
                        .append(hidden("_csrf", session.csrfToken())).append(hidden("site", imp.siteId()))
                        .append("<strong>").append(MiniText.html(imp.siteName())).append("</strong> ")
                        .append(Ui.id(imp.siteId())).append(" — ")
                        .append(imp.brief().isBlank() ? "sans description"
                                : Http.esc(excerpt(imp.brief(), 140)))
                        .append(imp.draftCount() > 0 ? " · " + imp.draftCount() + " plan(s)" : "")
                        .append(" <button class=\"btn btn-sm btn-outline-primary\" type=\"submit\">")
                        .append(Icons.icon("open")).append("Reprendre dans l'Atelier</button></form></li>");
            }
            sb.append("</ul>");
        }
        return sb.toString();
    }

    // ================================================================================
    //  Nouveau projet
    // ================================================================================

    public static String newForm(Session session, Map<String, String> prefill, String error) {
        StringBuilder sb = new StringBuilder();
        sb.append(Ui.pageHeader("plus", "Nouveau projet", "Décrivez le bâtiment que vous voulez. Vous "
                + "pourrez modifier ces informations à tout moment.",
                "<a class=\"btn btn-outline-secondary\" href=\"" + BASE + "\">" + Icons.icon("back")
                        + "Retour à l'Atelier</a>"));
        banners(sb, null, error);
        sb.append("<form method=\"post\" action=\"").append(BASE).append("/new\" class=\"actform\">");
        sb.append(hidden("_csrf", session.csrfToken()));
        sb.append(projectFields(prefill));
        sb.append("<div class=\"btnrow\"><button class=\"btn btn-primary\" type=\"submit\">")
                .append(Icons.icon("save")).append("Créer le projet</button></div>");
        sb.append("<p class=\"field-help\">Créer un projet n'appelle pas l'IA et ne coûte rien.</p></form>");
        return sb.toString();
    }

    /** Les champs d'un projet, partagés par la création et la modification. */
    private static String projectFields(Map<String, String> v) {
        StringBuilder sb = new StringBuilder();
        String name = v.getOrDefault("name", "");
        String brief = v.getOrDefault("brief", "");
        sb.append("<div class=\"field\"><label for=\"ws-name\">Nom du bâtiment</label>")
                .append("<input id=\"ws-name\" type=\"text\" name=\"name\" required value=\"")
                .append(Http.esc(name)).append("\" placeholder=\"Bibliothèque du Hub\"></div>");
        sb.append("<div class=\"field\"><label for=\"ws-brief\">Décrivez le bâtiment souhaité</label>")
                .append("<textarea id=\"ws-brief\" name=\"brief\" rows=\"8\" data-charcount=\"")
                .append(BuildingProject.MAX_BRIEF).append("\" placeholder=\"Une bibliothèque médiévale "
                        + "sur deux étages, en pierre claire et bois sombre, avec une grande salle de "
                        + "lecture, un comptoir, un escalier intérieur, des fenêtres et un toit en pente.\">")
                .append(Http.esc(brief)).append("</textarea>")
                .append("<p class=\"field-help charcount\" data-charcount-for=\"ws-brief\" "
                        + "data-charcount-max=\"").append(BuildingProject.MAX_BRIEF)
                .append("\"><span class=\"cc-n\">").append(brief.length()).append("</span> / ")
                .append(BuildingProject.MAX_BRIEF).append(" caractères</p>")
                .append("<p class=\"field-help\">Matériaux, nombre d'étages, pièces, style, ce qui doit "
                        + "absolument y être. Plus c'est précis, meilleur est le plan.</p></div>");
        String front = BuildingProject.normaliseFront(v.get("front"));
        sb.append("<div class=\"field\"><label for=\"ws-front\">Côté de l'entrée</label>")
                .append("<select id=\"ws-front\" name=\"front\">");
        for (String[] o : new String[][] {{"SOUTH", "Sud"}, {"NORTH", "Nord"}, {"EAST", "Est"}, {"WEST", "Ouest"}}) {
            sb.append("<option value=\"").append(o[0]).append("\"").append(o[0].equals(front) ? " selected" : "")
                    .append(">").append(o[1]).append("</option>");
        }
        sb.append("</select><p class=\"field-help\">Une simple référence : à la pose, le bâtiment est "
                + "tourné pour que son entrée suive l'orientation de l'emplacement.</p></div>");
        sb.append("<details class=\"npc-registry\"><summary class=\"muted\">Taille et nom technique "
                + "(facultatif)</summary>");
        sb.append("<div class=\"field\"><label for=\"ws-w\">Largeur et profondeur maximales (blocs)</label>")
                .append("<input id=\"ws-w\" type=\"number\" name=\"max_width\" min=\"5\" max=\"64\" value=\"")
                .append(Http.esc(v.getOrDefault("max_width", "19"))).append("\"></div>");
        sb.append("<div class=\"field\"><label for=\"ws-h\">Hauteur maximale, toit compris (blocs)</label>")
                .append("<input id=\"ws-h\" type=\"number\" name=\"max_height\" min=\"4\" max=\"64\" value=\"")
                .append(Http.esc(v.getOrDefault("max_height", "14"))).append("\"></div>");
        sb.append("<div class=\"field\"><label for=\"ws-id\">Nom technique dans la bibliothèque</label>")
                .append("<input id=\"ws-id\" type=\"text\" name=\"building_id\" spellcheck=\"false\" value=\"")
                .append(Http.esc(v.getOrDefault("building_id", ""))).append("\" placeholder=\"")
                .append(Http.esc(BuildingProject.suggestBuildingId(name.isBlank() ? "mon_batiment" : name)))
                .append("\"><p class=\"field-help\">Laissé vide, il est déduit du nom. Minuscules, chiffres "
                        + "et « _ ». Deux bâtiments ne peuvent pas porter le même.</p></div>");
        sb.append("</details>");
        return sb.toString();
    }

    // ================================================================================
    //  Page d'un projet
    // ================================================================================

    /**
     * Tout ce que la page d'un projet affiche, déjà relevé.
     *
     * @param idTaken l'identifiant de bibliothèque du projet est déjà pris par un bâtiment existant
     */
    public record ProjectView(Session session, String agentId, BuildingProject project,
                              List<AiProvider> providers, boolean canWrite, List<BuildingPlanDraft> drafts,
                              BuildingPlanDraft draft, CheckState audit, CheckState preview,
                              CheckState generate, List<Revision> revisions,
                              List<AgentActionRow> recoverable, String requestToken, String notice,
                              String error, String conflictText, boolean idTaken, int isoView, int isoCut) {
    }

    /** Les étapes du parcours, dans l'ordre. */
    enum Step { DESCRIBE, PROPOSE, CHECK, SAVE, DONE }

    public static String projectUrl(String projectId, String draftId) {
        return BASE + "/project?id=" + Http.esc(projectId)
                + (draftId == null || draftId.isBlank() ? "" : "&amp;draft=" + Http.esc(draftId));
    }

    public static String project(ProjectView v) {
        BuildingProject p = v.project();
        StringBuilder sb = new StringBuilder();
        sb.append(Ui.pageHeader("box", p.name(), "Projet de l'Atelier — rien n'est posé dans le monde depuis "
                + "cette page.", "<a class=\"btn btn-outline-secondary\" href=\"" + BASE + "\">"
                + Icons.icon("back") + "Atelier</a>"));
        banners(sb, v.notice(), v.error());

        Step step = step(v);
        sb.append(stepper(step));
        sb.append("<div class=\"card ws-main\"><div class=\"card-body\">");
        sb.append(mainCard(v, step));
        sb.append("</div></div>");
        sb.append(previewCard(v, step));

        // ---- Le bâtiment souhaité : ouvert tant qu'il n'est pas décrit ---------------------------
        sb.append("<details class=\"npc-registry\"").append(step == Step.DESCRIBE ? " open" : "")
                .append("><summary>").append(Icons.icon("edit")).append("Le bâtiment souhaité</summary>");
        sb.append("<form method=\"post\" action=\"").append(BASE).append("/project\" class=\"actform\">");
        sb.append(hidden("_csrf", v.session().csrfToken())).append(hidden("_action", "project_save"))
                .append(hidden("id", p.id())).append(hidden("project_version", String.valueOf(p.version())));
        if (v.draft() != null) {
            sb.append(hidden("draft", v.draft().id()));
        }
        sb.append(projectFields(Map.of("name", p.name(), "brief", p.brief(), "front", p.front(),
                "max_width", String.valueOf(p.maxWidth()), "max_height", String.valueOf(p.maxHeight()),
                "building_id", p.buildingId())));
        sb.append("<div class=\"btnrow\"><button class=\"btn btn-sm btn-outline-primary\" type=\"submit\">")
                .append(Icons.icon("save")).append("Enregistrer les modifications</button></div>");
        if (!p.sourceSiteId().isEmpty()) {
            sb.append("<p class=\"field-help\">Repris de l'emplacement ").append(Ui.id(p.sourceSiteId()))
                    .append(" : sa fiche n'a pas été modifiée.</p>");
        }
        sb.append("</form></details>");

        if (v.drafts().size() > 1) {
            sb.append(draftList(v));
        }
        sb.append(technicalDetails(v));
        return sb.toString();
    }

    /** Où en est le projet : c'est ce qui décide de l'action mise en avant. */
    static Step step(ProjectView v) {
        if (!v.project().hasBrief()) {
            return Step.DESCRIBE;
        }
        BuildingPlanDraft d = v.draft();
        if (d == null || !d.hasPlan()) {
            return Step.PROPOSE;
        }
        if (v.generate().current() && v.generate().status() == Status.DONE
                && v.generate().row().map(r -> "SUCCESS".equals(r.resultStatus())).orElse(false)) {
            return Step.DONE;
        }
        BuildingPlanVerdict verdict = currentVerdict(v);
        return verdict.available() && verdict.accepted() ? Step.SAVE : Step.CHECK;
    }

    private static String stepper(Step current) {
        String[][] steps = {{"DESCRIBE", "Décrire"}, {"PROPOSE", "Proposer un plan"},
            {"CHECK", "Vérifier"}, {"SAVE", "Enregistrer"}};
        StringBuilder sb = new StringBuilder("<ol class=\"ws-steps\" aria-label=\"Étapes du projet\">");
        int at = current.ordinal();
        for (int i = 0; i < steps.length; i++) {
            String cls = i < at ? "done" : i == at ? "current" : "todo";
            sb.append("<li class=\"").append(cls).append("\"").append(i == at ? " aria-current=\"step\"" : "")
                    .append("><span class=\"ws-step-n\">").append(i < at ? "✓" : String.valueOf(i + 1))
                    .append("</span>").append(steps[i][1]).append("</li>");
        }
        return sb.append("</ol>").toString();
    }

    /** Le cœur de la page : l'état, en une phrase, et l'action qui fait avancer. */
    private static String mainCard(ProjectView v, Step step) {
        BuildingProject p = v.project();
        StringBuilder sb = new StringBuilder();
        switch (step) {
            case DESCRIBE -> sb.append("<h2 class=\"h5\">Décrivez d'abord le bâtiment</h2><p>Ouvrez « Le "
                    + "bâtiment souhaité » ci-dessous et racontez ce que vous voulez : matériaux, étages, "
                    + "pièces, style.</p>");
            case PROPOSE -> {
                sb.append("<h2 class=\"h5\">Prêt à demander un plan</h2><p>L'IA lit votre description et "
                        + "propose un plan de construction. Vous le vérifierez avant d'enregistrer quoi que "
                        + "ce soit.</p>");
                sb.append(proposeForm(v, "Créer un plan avec l'IA"));
            }
            case CHECK -> sb.append(checkCard(v));
            case SAVE -> sb.append(saveCard(v));
            case DONE -> {
                sb.append("<h2 class=\"h5\">").append(Icons.icon("check"))
                        .append("Enregistré dans la bibliothèque</h2><p>« ").append(Http.esc(p.name()))
                        .append(" » peut maintenant être posé sur un ou plusieurs emplacements, sans nouvel "
                                + "appel à l'IA.</p><div class=\"btnrow\">")
                        .append("<a class=\"btn btn-primary\" href=\"/buildings/library\">")
                        .append(Icons.icon("box")).append("Voir dans la bibliothèque</a>")
                        .append("<a class=\"btn btn-outline-primary\" href=\"/buildings/sites?place=")
                        .append(Http.esc(p.buildingId())).append("\">")
                        .append(Icons.icon("world")).append("Choisir un emplacement</a></div>");
            }
        }
        return sb.toString();
    }

    /**
     * L'aperçu du bâtiment en 3D (issue #273) : la grille que le serveur construira, dessinée en SVG.
     *
     * <p>Orientation et coupe passent par un formulaire GET — aucun script (CSP), une URL partageable,
     * aucune action envoyée au serveur pour tourner autour du bâtiment.</p>
     */
    static String previewCard(ProjectView v, Step step) {
        BuildingPlanDraft d = v.draft();
        if (d == null || !d.hasPlan() || step == Step.DESCRIBE) {
            return "";
        }
        StringBuilder sb = new StringBuilder("<section class=\"card ws-preview\"><div class=\"card-body\">");
        sb.append("<h2 class=\"h5\">").append(Icons.icon("box")).append("Aperçu du bâtiment</h2>");
        CheckState preview = v.preview();
        if (preview.status() == Status.NONE) {
            sb.append("<p class=\"muted\">Le serveur dessine le bâtiment tel qu'il le construirait. Gratuit, "
                    + "rien n'est posé.</p>").append(gestureForm(v, "preview", "Afficher l'aperçu", false));
            return sb.append("</div></section>").toString();
        }
        if (preview.status() == Status.PENDING) {
            sb.append("<p class=\"muted\">Aperçu en cours de calcul…</p><a class=\"btn btn-sm btn-outline-secondary\" "
                    + "href=\"").append(projectUrl(v.project().id(), d.id())).append("\">")
                    .append(Icons.icon("refresh")).append("Rafraîchir</a>");
            return sb.append("</div></section>").toString();
        }
        BuildingVoxels voxels = BuildingVoxels.from(preview.details());
        if (!voxels.present()) {
            sb.append(Ui.banner("info", Http.esc(voxels.omittedReason())));
            return sb.append("</div></section>").toString();
        }
        if (preview.stale()) {
            sb.append(Ui.banner("warning", "Cet aperçu montre la version " + preview.version()
                    + " du plan, pas la version actuelle. " + gestureForm(v, "preview", "Mettre à jour l'aperçu", false)));
        }
        String front = BuildingPlanPreview.from(preview.details()).front();
        int view = v.isoView() >= 0 && v.isoView() < IsometricView.views() ? v.isoView()
                : IsometricView.defaultView(front);
        int top = voxels.sizeY() - 1;
        int cut = v.isoCut() >= 0 && v.isoCut() < top ? v.isoCut() : top;
        String title = v.project().name() + ", vu " + IsometricView.viewLabel(view)
                + (cut < top ? ", coupé au niveau " + cut : "");
        sb.append("<figure class=\"ws-iso\">").append(IsometricView.svg(voxels, view, cut, title))
                .append("<figcaption class=\"muted\">Vu ").append(Http.esc(IsometricView.viewLabel(view)))
                .append(cut < top ? ", tout ce qui dépasse le niveau " + cut + " est retiré" : "").append(". ")
                .append(Http.esc(IsometricView.frontHint(front, view))).append("</figcaption></figure>");

        sb.append("<form method=\"get\" action=\"").append(BASE).append("/project\" class=\"ws-iso-controls\">")
                .append(hidden("id", v.project().id())).append(hidden("draft", d.id()));
        sb.append("<label>Tourner <select name=\"view\">");
        for (int i = 0; i < IsometricView.views(); i++) {
            sb.append("<option value=\"").append(i).append('"').append(i == view ? " selected" : "").append('>')
                    .append(Http.esc(IsometricView.viewLabel(i))).append("</option>");
        }
        sb.append("</select></label><label>Voir l'intérieur <select name=\"cut\">")
                .append("<option value=\"-1\"").append(cut == top ? " selected" : "")
                .append(">Bâtiment entier</option>");
        for (int y = 0; y < top; y++) {
            sb.append("<option value=\"").append(y).append('"').append(y == cut ? " selected" : "")
                    .append(">Couper au-dessus du niveau ").append(y).append("</option>");
        }
        sb.append("</select></label><button class=\"btn btn-sm btn-outline-primary\" type=\"submit\">Afficher"
                + "</button></form>");

        sb.append("<ul class=\"ws-legend\">");
        for (IsometricView.Swatch swatch : IsometricView.legend(voxels, 10)) {
            sb.append("<li><svg class=\"ws-swatch\" viewBox=\"0 0 10 10\" aria-hidden=\"true\"><rect width=\"10\" "
                    + "height=\"10\" fill=\"").append(swatch.color()).append("\"/></svg>")
                    .append(Http.esc(swatch.label())).append("</li>");
        }
        sb.append("</ul><p class=\"field-help\">Vue simplifiée : une couleur par matériau, pas les textures du jeu ; "
                + "portes, vitres, escaliers et mobilier sont dessinés comme des cubes, les lumières comme de "
                + "petits cubes jaunes. Chaque cube est un vrai bloc du bâtiment que le serveur construirait.</p>");
        return sb.append("</div></section>").toString();
    }

    /** Étape « Vérifier » : pas encore vérifié, vérification en cours, ou à corriger. */
    private static String checkCard(ProjectView v) {
        StringBuilder sb = new StringBuilder();
        CheckState audit = v.audit();
        if (!audit.current()) {
            sb.append("<h2 class=\"h5\">Un plan est prêt : vérifions-le</h2><p>Le serveur construit le "
                    + "bâtiment « en mémoire » et contrôle l'entrée, les fenêtres, la lumière, le toit et les "
                    + "étages. Rien n'est posé et c'est gratuit.</p>");
            sb.append(gestureForm(v, "audit", "Vérifier le bâtiment", true));
            return sb.toString();
        }
        if (audit.status() == Status.PENDING) {
            sb.append("<h2 class=\"h5\">Vérification en cours…</h2><p>Le serveur répond en général en "
                    + "quelques secondes.</p><a class=\"btn btn-outline-secondary\" href=\"")
                    .append(projectUrl(v.project().id(), v.draft().id())).append("\">")
                    .append(Icons.icon("refresh")).append("Rafraîchir</a>");
            return sb.toString();
        }
        BuildingPlanVerdict verdict = currentVerdict(v);
        Optional<String> divergence = BriefLevels.divergence(v.project().brief(), verdict.metrics());
        sb.append("<h2 class=\"h5\">").append(Icons.icon("warning"))
                .append(Http.esc(PlanVerdictText.headline(verdict, divergence.isPresent()))).append("</h2>");
        List<PlanVerdictText.Problem> problems = new ArrayList<>(PlanVerdictText.blocking(verdict));
        if (!verdict.available()) {
            problems.add(PlanVerdictText.of(""));
        }
        sb.append("<ul class=\"ws-problems\">");
        for (PlanVerdictText.Problem pr : problems) {
            sb.append("<li><strong>").append(Http.esc(pr.text())).append("</strong> <span class=\"muted\">")
                    .append(Http.esc(pr.hint())).append("</span></li>");
        }
        divergence.ifPresent(d -> sb.append("<li><strong>").append(Http.esc(d)).append("</strong></li>"));
        sb.append("</ul>");
        sb.append(correctionForm(v, correctionReason(v)));
        return sb.toString();
    }

    /** Étape « Enregistrer » : le plan est vérifié. */
    private static String saveCard(ProjectView v) {
        StringBuilder sb = new StringBuilder();
        BuildingPlanVerdict verdict = currentVerdict(v);
        Optional<String> divergence = BriefLevels.divergence(v.project().brief(), verdict.metrics());
        sb.append("<h2 class=\"h5\">").append(Icons.icon(divergence.isPresent() ? "warning" : "check"))
                .append(Http.esc(PlanVerdictText.headline(verdict, divergence.isPresent()))).append("</h2>");
        if (verdict.blocks() > 0) {
            sb.append("<p class=\"muted\">").append(verdict.sizeX()).append(" × ").append(verdict.sizeZ())
                    .append(" blocs au sol, ").append(verdict.sizeY()).append(" de haut.</p>");
        }
        divergence.ifPresent(d -> sb.append(Ui.banner("warning", Http.esc(d))));
        if (v.idTaken()) {
            sb.append(Ui.banner("warning", "<strong>Un bâtiment de la bibliothèque porte déjà le nom "
                    + "technique « " + Http.esc(v.project().buildingId()) + " ».</strong> Changez-le dans « Le "
                    + "bâtiment souhaité » (par exemple « " + Http.esc(v.project().buildingId())
                    + "_v2 ») : enregistrer remplacerait l'autre bâtiment, ce qui n'est jamais fait sans "
                    + "votre accord."));
        }
        if (!v.canWrite()) {
            sb.append(Ui.banner("info", "Votre rôle ne permet pas d'enregistrer dans la bibliothèque."));
        } else if (!v.idTaken()) {
            sb.append(gestureForm(v, "generate", "Enregistrer dans la bibliothèque", true));
            sb.append("<p class=\"field-help\">Le bâtiment rejoint la bibliothèque. Aucun bloc n'est posé "
                    + "dans le monde : vous choisirez ensuite un emplacement.</p>");
        }
        if (divergence.isPresent() || !verdict.warnings().isEmpty()) {
            sb.append("<details class=\"npc-registry\"><summary class=\"muted\">Améliorer avec l'IA"
                    + "</summary>").append(correctionForm(v, correctionReason(v))).append("</details>");
        }
        return sb.toString();
    }

    /** Le motif de correction proposé : ce que le serveur a dit de la version courante, en clair. */
    static String correctionReason(ProjectView v) {
        BuildingPlanVerdict verdict = currentVerdict(v);
        List<String> lines = new ArrayList<>();
        if (verdict.available() && !verdict.accepted()) {
            lines.add(verdict.message());
        }
        if (verdict.available()) {
            BriefLevels.divergence(v.project().brief(), verdict.metrics()).ifPresent(lines::add);
            lines.addAll(verdict.warnings());
        }
        if (!verdict.available() && v.audit().current() && v.audit().status() == Status.DONE) {
            v.audit().row().map(AgentActionRow::resultMessage).filter(m -> m != null && !m.isBlank())
                    .ifPresent(lines::add);
        }
        return String.join("\n", lines).trim();
    }

    private static BuildingPlanVerdict currentVerdict(ProjectView v) {
        CheckState audit = v.audit();
        return audit.current() && audit.status() == Status.DONE
                ? BuildingPlanVerdict.from(audit.details()) : BuildingPlanVerdict.none();
    }

    // ---- Formulaires ----------------------------------------------------------------------

    /** Demande payante d'un nouveau plan : jeton à usage unique, coût annoncé AVANT le bouton. */
    private static String proposeForm(ProjectView v, String label) {
        if (v.providers().isEmpty()) {
            return noProvider();
        }
        StringBuilder sb = new StringBuilder("<form method=\"post\" action=\"" + BASE + "/project\" class=\"actform\">");
        sb.append(hidden("_csrf", v.session().csrfToken())).append(hidden("_action", "propose"))
                .append(hidden("id", v.project().id())).append(hidden("request_token", v.requestToken()));
        sb.append(providerSelect(v, ""));
        sb.append("<p class=\"field-help\">").append(Icons.icon("warning")).append("Cette demande est "
                + "<strong>facturée</strong> par le fournisseur d'IA. Un seul plan par clic ; recharger la "
                + "page ne relance rien.</p>");
        sb.append("<div class=\"btnrow\"><button class=\"btn btn-primary\" type=\"submit\">")
                .append(Icons.icon("wrench")).append(Http.esc(label)).append("</button></div></form>");
        return sb.toString();
    }

    /** « Améliorer avec l'IA » : payant, motif prérempli en clair, réponse = nouvelle version. */
    private static String correctionForm(ProjectView v, String reason) {
        if (v.providers().isEmpty()) {
            return noProvider();
        }
        BuildingPlanDraft d = v.draft();
        StringBuilder sb = new StringBuilder("<form method=\"post\" action=\"" + BASE + "/project\" class=\"actform\">");
        sb.append(hidden("_csrf", v.session().csrfToken())).append(hidden("_action", "correct"))
                .append(hidden("id", v.project().id())).append(hidden("draft", d.id()))
                .append(hidden("version", String.valueOf(d.version())))
                .append(hidden("request_token", v.requestToken()));
        sb.append(providerSelect(v, d.providerId()));
        sb.append("<div class=\"field\"><label for=\"ws-reason\">Ce qu'il faut changer</label>")
                .append("<textarea id=\"ws-reason\" name=\"reason\" rows=\"4\">").append(Http.esc(reason))
                .append("</textarea><p class=\"field-help\">Prérempli avec ce que la vérification a "
                        + "trouvé ; ajoutez ce que vous voulez en plus.</p></div>");
        sb.append("<p class=\"field-help\">").append(Icons.icon("warning")).append("Appel <strong>facturé"
                + "</strong>. La réponse devient une nouvelle version du plan ; la version actuelle reste "
                + "disponible.</p>");
        sb.append("<div class=\"btnrow\"><button class=\"btn btn-primary\" type=\"submit\">")
                .append(Icons.icon("wrench")).append("Améliorer avec l'IA</button></div></form>");
        return sb.toString();
    }

    /** Un geste gratuit sur la version courante (vérifier, aperçu, enregistrer). */
    private static String gestureForm(ProjectView v, String gesture, String label, boolean primary) {
        BuildingPlanDraft d = v.draft();
        StringBuilder sb = new StringBuilder("<form method=\"post\" action=\"" + BASE + "/project\" class=\"inline\">");
        sb.append(hidden("_csrf", v.session().csrfToken())).append(hidden("_action", gesture))
                .append(hidden("id", v.project().id())).append(hidden("draft", d.id()))
                .append(hidden("version", String.valueOf(d.version())))
                .append(hidden("plan", d.plan()));
        sb.append("<button class=\"btn ").append(primary ? "btn-primary" : "btn-outline-secondary")
                .append("\" type=\"submit\">").append(Http.esc(label)).append("</button></form>");
        return sb.toString();
    }

    private static String providerSelect(ProjectView v, String chosen) {
        if (v.providers().size() == 1) {
            return hidden("provider", v.providers().get(0).id());
        }
        StringBuilder sb = new StringBuilder("<div class=\"field\"><label for=\"ws-provider\">Fournisseur d'IA"
                + "</label><select id=\"ws-provider\" name=\"provider\">");
        for (AiProvider provider : v.providers()) {
            sb.append("<option value=\"").append(Http.esc(provider.id())).append('"')
                    .append(provider.id().equals(chosen) ? " selected" : "").append('>')
                    .append(Http.esc(provider.label())).append("</option>");
        }
        return sb.append("</select></div>").toString();
    }

    private static String noProvider() {
        return Ui.banner("info", "Aucun fournisseur d'IA n'est configuré. Un administrateur peut en ajouter "
                + "un dans <a href=\"/ai/providers\">Fournisseurs d'IA</a>.");
    }

    // ---- Versions --------------------------------------------------------------------------

    private static String draftList(ProjectView v) {
        StringBuilder sb = new StringBuilder("<details class=\"npc-registry\"><summary>")
                .append(Icons.icon("history")).append("Versions du plan (").append(v.drafts().size())
                .append(")</summary><ul class=\"tight\">");
        for (BuildingPlanDraft d : v.drafts()) {
            boolean open = v.draft() != null && v.draft().id().equals(d.id());
            sb.append("<li>").append(open ? "<strong>" : "").append(Http.esc(d.origin().label()))
                    .append(" — ").append(Http.esc(WHEN.format(d.updatedAt()))).append(open ? "</strong>" : "");
            if (open) {
                sb.append(" <span class=\"faint\">(affichée)</span>");
            } else {
                sb.append(" <a class=\"btn btn-sm btn-outline-primary\" href=\"")
                        .append(projectUrl(v.project().id(), d.id())).append("\">Reprendre cette version</a>");
            }
            sb.append("</li>");
        }
        return sb.append("</ul></details>").toString();
    }

    // ---- Détails techniques ----------------------------------------------------------------

    /**
     * Ce qui sert à l'assistance et aux administrateurs, jamais au parcours principal : plan brut et
     * modifiable, résultat brut de la vérification, coupes techniques, versions, réponse du modèle,
     * reprise d'un plan depuis une action — et « Copier le diagnostic ».
     */
    private static String technicalDetails(ProjectView v) {
        StringBuilder sb = new StringBuilder("<details class=\"npc-registry ws-tech\"><summary class=\"muted\">")
                .append(Icons.icon("wrench")).append("Détails techniques</summary>");
        sb.append("<p class=\"field-help\">Pour l'assistance : à copier tel quel dans un message.</p>");
        sb.append("<div class=\"btnrow\"><button class=\"btn btn-sm btn-outline-secondary\" type=\"button\" "
                + "data-copy=\"").append(Http.esc(diagnostic(v))).append("\">").append(Icons.icon("copy"))
                .append("Copier le diagnostic</button></div>");
        BuildingPlanDraft d = v.draft();
        if (d != null) {
            sb.append("<dl class=\"npc-dl\">");
            row(sb, "Projet", Ui.id(v.project().id()));
            row(sb, "Version du plan", Ui.id(d.id()) + " v" + d.version() + " — " + Http.esc(d.origin().label())
                    + (d.sourceActionId().isEmpty() ? "" : " (action <a href=\"/actions/"
                            + Http.esc(d.sourceActionId()) + "\">" + Http.esc(shortId(d.sourceActionId()))
                            + "</a>)"));
            if (!d.model().isEmpty()) {
                row(sb, "Modèle", Http.esc(d.providerLabel()) + " <code class=\"tid\">" + Http.esc(d.model())
                        + "</code>" + (d.usage().isEmpty() ? "" : " — " + Http.esc(d.usage())));
            }
            v.audit().row().ifPresent(r -> row(sb, "Dernière vérification", "<a href=\"/actions/"
                    + Http.esc(r.id()) + "\">" + Http.esc(shortId(r.id())) + "</a> — "
                    + Http.esc(nz(r.resultStatus())) + " " + Http.esc(nz(r.resultMessage()))
                    + (v.audit().stale() ? " <span class=\"faint\">(version " + v.audit().version()
                            + ", antérieure)</span>" : "")));
            BuildingPlanVerdict verdict = currentVerdict(v);
            if (verdict.available()) {
                if (!verdict.codes().isEmpty()) {
                    row(sb, "Codes", "<code class=\"tid\">" + Http.esc(String.join(", ", verdict.codes())) + "</code>");
                }
                String metrics = BuildingCorrectionLoop.formatMetrics(verdict.metrics());
                if (!metrics.isBlank()) {
                    row(sb, "Mesures", "<pre class=\"rpgq-code\">" + Http.esc(metrics) + "</pre>");
                }
            }
            sb.append("</dl>");
            sb.append(planEditor(v));
            sb.append(asciiSections(v));
            if (v.revisions().size() > 1) {
                sb.append("<details class=\"npc-registry\"><summary class=\"muted\">Anciennes versions du texte"
                        + "</summary>");
                for (Revision r : v.revisions()) {
                    if (r.version() != d.version()) {
                        sb.append("<p class=\"faint\">Version ").append(r.version()).append(" — ")
                                .append(Http.esc(WHEN.format(r.createdAt()))).append("</p><pre class=\"rpgq-code\">")
                                .append(Http.esc(r.plan())).append("</pre>");
                    }
                }
                sb.append("</details>");
            }
            if (!d.rawResponse().isBlank()) {
                sb.append("<details class=\"npc-registry\"><summary class=\"muted\">Réponse brute du modèle"
                        + "</summary><pre class=\"rpgq-code\">").append(Http.esc(d.rawResponse()))
                        .append("</pre></details>");
            }
            sb.append(archiveForm(v));
        }
        sb.append(recovery(v));
        return sb.append("</details>").toString();
    }

    /** Le plan brut, modifiable à la main — pour ceux qui savent, jamais exigé. */
    private static String planEditor(ProjectView v) {
        BuildingPlanDraft d = v.draft();
        StringBuilder sb = new StringBuilder("<details class=\"npc-registry\"><summary class=\"muted\">Plan "
                + "brut (modification manuelle)</summary>");
        if (v.conflictText() != null) {
            sb.append(Ui.banner("warn", "<strong>Vos modifications n'ont pas été enregistrées</strong> : le "
                    + "plan a changé entre-temps (version " + d.version() + "). Votre texte est conservé "
                    + "ci-dessous."));
            sb.append("<textarea rows=\"8\" readonly aria-label=\"Votre texte non enregistré\">")
                    .append(Http.esc(v.conflictText())).append("</textarea>");
        }
        sb.append("<form method=\"post\" action=\"").append(BASE).append("/project\" class=\"actform\">")
                .append(hidden("_csrf", v.session().csrfToken())).append(hidden("id", v.project().id()))
                .append(hidden("draft", d.id())).append(hidden("version", String.valueOf(d.version())));
        sb.append("<textarea id=\"ws-plan\" name=\"plan\" rows=\"18\" spellcheck=\"false\" "
                + "aria-label=\"Plan brut\">").append(Http.esc(d.plan())).append("</textarea>");
        sb.append("<p class=\"field-help\">Une modification est enregistrée comme une nouvelle version avant "
                + "d'être vérifiée. Aucun de ces boutons n'appelle l'IA.</p><div class=\"btnrow\">")
                .append("<button class=\"btn btn-sm btn-outline-secondary\" type=\"submit\" name=\"_action\" "
                        + "value=\"save\">Enregistrer le texte</button>")
                .append("<button class=\"btn btn-sm btn-outline-secondary\" type=\"submit\" name=\"_action\" "
                        + "value=\"audit\">Vérifier ce texte</button>")
                .append("<button class=\"btn btn-sm btn-outline-secondary\" type=\"submit\" name=\"_action\" "
                        + "value=\"preview\">Calculer l'aperçu et les coupes</button></div></form></details>");
        return sb.toString();
    }

    /** Les coupes en caractères : un outil de diagnostic, présenté comme tel (#265, #273). */
    private static String asciiSections(ProjectView v) {
        CheckState preview = v.preview();
        if (preview.status() == Status.NONE) {
            return "";
        }
        StringBuilder sb = new StringBuilder("<details class=\"npc-registry\"><summary class=\"muted\">Coupes "
                + "techniques en caractères (diagnostic, pas un aperçu)</summary>");
        if (preview.stale()) {
            sb.append("<p class=\"faint\">Calculées sur la version ").append(preview.version())
                    .append(" du plan, antérieure.</p>");
        }
        if (preview.status() == Status.PENDING) {
            return sb.append("<p class=\"faint\">Calcul en cours — rafraîchir dans quelques secondes.</p></details>")
                    .toString();
        }
        BuildingPlanPreview drawn = BuildingPlanPreview.from(preview.details());
        if (!drawn.hasSlices()) {
            return sb.append("<p class=\"faint\">Aucune coupe rendue.</p></details>").toString();
        }
        sb.append("<dl class=\"npc-dl\">");
        for (var entry : BuildingPlanPreview.LEGEND) {
            row(sb, entry.getKey(), Http.esc(entry.getValue()));
        }
        sb.append("</dl>");
        for (BuildingPlanPreview.Slice slice : drawn.elevations()) {
            sb.append("<p class=\"faint\">").append(Http.esc(slice.label())).append("</p><pre class=\"rpgq-code\">")
                    .append(Http.esc(slice.text())).append("</pre>");
        }
        for (BuildingPlanPreview.Slice slice : drawn.plans()) {
            sb.append("<p class=\"faint\">").append(Http.esc(slice.label())).append("</p><pre class=\"rpgq-code\">")
                    .append(Http.esc(slice.text())).append("</pre>");
        }
        return sb.append("</details>").toString();
    }

    private static String archiveForm(ProjectView v) {
        return "<form method=\"post\" action=\"" + BASE + "/project\" class=\"inline\">"
                + hidden("_csrf", v.session().csrfToken()) + hidden("_action", "archive")
                + hidden("id", v.project().id()) + hidden("draft", v.draft().id())
                + "<button class=\"btn btn-sm btn-outline-secondary\" type=\"submit\">"
                + Icons.icon("trash") + "Retirer cette version de la liste</button></form>";
    }

    /** Reprendre un plan déjà vérifié ou enregistré, depuis une action — sans appel à l'IA. */
    private static String recovery(ProjectView v) {
        StringBuilder sb = new StringBuilder("<details class=\"npc-registry\"><summary class=\"muted\">Reprendre "
                + "un plan depuis l'historique des actions (sans appel à l'IA)</summary>");
        for (AgentActionRow row : v.recoverable()) {
            String building = BuildingPlanDraft.buildingIdOf(BuildingPlanWorkbench.planOf(row).orElse(""));
            sb.append("<form method=\"post\" action=\"").append(BASE).append("/project\" class=\"inline\">")
                    .append(hidden("_csrf", v.session().csrfToken())).append(hidden("_action", "recover"))
                    .append(hidden("id", v.project().id())).append(hidden("action_id", row.id()))
                    .append("<p>").append(building.isEmpty() ? "plan" : Ui.id(building)).append(" · ")
                    .append(Http.esc(WHEN.format(row.createdAt()))).append(" · <a href=\"/actions/")
                    .append(Http.esc(row.id())).append("\">").append(Http.esc(shortId(row.id())))
                    .append("</a> <button class=\"btn btn-sm btn-outline-primary\" type=\"submit\">Reprendre ce "
                            + "plan</button></p></form>");
        }
        sb.append("<form method=\"post\" action=\"").append(BASE).append("/project\" class=\"actform\">")
                .append(hidden("_csrf", v.session().csrfToken())).append(hidden("_action", "recover"))
                .append(hidden("id", v.project().id()))
                .append("<div class=\"field\"><label for=\"ws-action\">Identifiant d'action</label>")
                .append("<input id=\"ws-action\" type=\"text\" name=\"action_id\" spellcheck=\"false\"></div>")
                .append("<div class=\"btnrow\"><button class=\"btn btn-sm btn-outline-secondary\" type=\"submit\">"
                        + "Reprendre</button></div></form>");
        return sb.append("</details>").toString();
    }

    /** Le diagnostic à copier : identifiants, état, vérification, plan — jamais de clé ni de secret. */
    static String diagnostic(ProjectView v) {
        StringBuilder sb = new StringBuilder("Diagnostic Atelier PlugAdmin\n");
        BuildingProject p = v.project();
        sb.append("Projet : ").append(p.name()).append(" (").append(p.id()).append(")\n")
                .append("Nom technique : ").append(p.buildingId()).append(" ; entrée : ").append(p.front())
                .append(" ; max ").append(p.maxWidth()).append("×").append(p.maxHeight()).append('\n');
        BuildingPlanDraft d = v.draft();
        if (d == null) {
            return sb.append("Aucune version de plan.\n").toString();
        }
        sb.append("Version de plan : ").append(d.id()).append(" v").append(d.version()).append(" — ")
                .append(d.origin().label()).append('\n');
        if (!d.model().isEmpty()) {
            sb.append("Modèle : ").append(d.providerLabel()).append(" ").append(d.model()).append('\n');
        }
        v.audit().row().ifPresent(r -> sb.append("Vérification : action ").append(r.id()).append(" — ")
                .append(nz(r.resultStatus())).append(" — ").append(nz(r.resultMessage()))
                .append(v.audit().stale() ? " (version antérieure " + v.audit().version() + ")" : "").append('\n'));
        BuildingPlanVerdict verdict = currentVerdict(v);
        if (verdict.available()) {
            sb.append("Codes : ").append(String.join(", ", verdict.codes())).append('\n');
            verdict.warnings().forEach(w -> sb.append("Avertissement : ").append(w).append('\n'));
            String metrics = BuildingCorrectionLoop.formatMetrics(verdict.metrics());
            if (!metrics.isBlank()) {
                sb.append("Mesures :\n").append(metrics.trim()).append('\n');
            }
        }
        return sb.append("Plan :\n").append(d.plan().trim()).append('\n').toString();
    }

    // ================================================================================
    //  Fiche d'action : le retour au projet
    // ================================================================================

    /**
     * Sur la fiche d'une action qui porte un plan : « Retour à ce brouillon » s'il appartient à un
     * projet, sinon le reprendre dans un projet existant — sans appel à l'IA.
     */
    public static String actionContext(Session session, AgentActionRow row, Optional<BuildingPlanDraft> owner,
                                       List<BuildingProject> projects) {
        if (BuildingPlanWorkbench.planOf(row).isEmpty()) {
            return "";
        }
        if (owner.isPresent() && !owner.get().projectId().isEmpty()) {
            BuildingPlanDraft d = owner.get();
            return Ui.banner("info", "Ce plan appartient à un projet de l'Atelier. <a class=\"btn btn-sm "
                    + "btn-primary\" href=\"" + projectUrl(d.projectId(), d.id()) + "\">" + Icons.icon("back")
                    + "Retour à ce brouillon</a>");
        }
        if (owner.isPresent()) {
            return Ui.banner("info", "Ce plan appartient à un travail commencé sur l'emplacement "
                    + Ui.id(owner.get().siteId()) + ". <a class=\"btn btn-sm btn-primary\" href=\"" + BASE
                    + "\">" + Icons.icon("back") + "Le reprendre dans l'Atelier</a>");
        }
        StringBuilder sb = new StringBuilder("<div class=\"banner info\">").append(Icons.icon("info"))
                .append("<div>Cette action contient un plan de bâtiment complet. Le reprendre dans un projet "
                        + "de l'Atelier <strong>n'appelle pas l'IA</strong>.");
        if (projects.isEmpty()) {
            sb.append(" <a href=\"").append(BASE).append("/new\">Créez d'abord un projet</a>, puis revenez ici.");
        } else {
            sb.append("<form method=\"post\" action=\"").append(BASE).append("/project\" class=\"inline\">")
                    .append(hidden("_csrf", session.csrfToken())).append(hidden("_action", "recover"))
                    .append(hidden("action_id", row.id()))
                    .append(" <label for=\"ac-project\">Projet</label> <select id=\"ac-project\" name=\"id\">");
            for (BuildingProject p : projects) {
                sb.append("<option value=\"").append(Http.esc(p.id())).append("\">").append(Http.esc(p.name()))
                        .append("</option>");
            }
            sb.append("</select> <button class=\"btn btn-sm btn-primary\" type=\"submit\">")
                    .append(Icons.icon("open")).append("Reprendre ce plan</button></form>");
        }
        return sb.append("</div></div>").toString();
    }

    // ---- Outils ------------------------------------------------------------------------------

    private static void banners(StringBuilder sb, String notice, String error) {
        if (notice != null && !notice.isBlank()) {
            sb.append(Ui.banner("ok", Http.esc(notice)));
        }
        if (error != null && !error.isBlank()) {
            sb.append(Ui.banner("err", Http.esc(error)));
        }
    }

    private static String excerpt(String text, int max) {
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= max ? flat : flat.substring(0, max - 1) + "…";
    }

    private static String shortId(String id) {
        return id == null || id.length() <= 8 ? String.valueOf(id) : id.substring(0, 8);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String detail(String icon, String title) {
        return "<p class=\"npc-section\">" + Icons.icon(icon) + Http.esc(title) + "</p>";
    }

    private static void row(StringBuilder sb, String key, String valueHtml) {
        sb.append("<div class=\"npc-dl-row\"><dt>").append(Http.esc(key)).append("</dt><dd>")
                .append(valueHtml).append("</dd></div>");
    }

    static String hidden(String name, String value) {
        return "<input type=\"hidden\" name=\"" + Http.esc(name) + "\" value=\"" + Http.esc(value) + "\">";
    }
}
