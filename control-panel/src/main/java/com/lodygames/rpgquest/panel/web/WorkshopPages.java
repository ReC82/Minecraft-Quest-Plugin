package com.lodygames.rpgquest.panel.web;

import com.lodygames.rpgquest.panel.agent.AgentActionRow;
import com.lodygames.rpgquest.panel.ai.AiProvider;
import com.lodygames.rpgquest.panel.ai.BuildingCorrectionLoop;
import com.lodygames.rpgquest.panel.ai.BuildingDesignPipeline;
import com.lodygames.rpgquest.panel.building.BriefLevels;
import com.lodygames.rpgquest.panel.building.BuildingDesignDocStore;
import com.lodygames.rpgquest.panel.building.BuildingDesignRunStore;
import com.lodygames.rpgquest.panel.building.ReferenceImageStore;
import com.lodygames.rpgquest.panel.building.BuildingPlanDraft;
import com.lodygames.rpgquest.panel.building.BuildingPlanDraftStore.Revision;
import com.lodygames.rpgquest.panel.building.BuildingPlanPreview;
import com.lodygames.rpgquest.panel.building.BuildingPlanVerdict;
import com.lodygames.rpgquest.panel.building.BuildingPlanWorkbench;
import com.lodygames.rpgquest.panel.building.BuildingPlanWorkbench.CheckState;
import com.lodygames.rpgquest.panel.building.BuildingPlanWorkbench.Status;
import com.lodygames.rpgquest.panel.building.BuildingProject;
import com.lodygames.rpgquest.panel.building.ProjectUsage;
import com.lodygames.rpgquest.panel.building.WorkshopProjectDeletion;
import com.lodygames.rpgquest.panel.building.BuildingSiteView;
import com.lodygames.rpgquest.panel.building.CatalogState;
import com.lodygames.rpgquest.panel.building.BuildingVoxels;
import com.lodygames.rpgquest.panel.building.IsometricView;
import com.lodygames.rpgquest.panel.building.PlanDiagnosis;
import com.lodygames.rpgquest.panel.building.PlanImportPreview;
import com.lodygames.rpgquest.panel.building.SaveFailure;
import com.lodygames.rpgquest.panel.building.PlanVerdictText;
import com.lodygames.rpgquest.panel.building.PlanVersionRef;
import com.lodygames.rpgquest.panel.building.PlanVersionStatus;
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

    /**
     * Une ligne de la liste : le projet, où il en est en une phrase, et son usage en jeu (#881).
     */
    public record ProjectRow(BuildingProject project, String state, int drafts, ProjectUsage usage) {
    }

    public static String index(Session session, List<ProjectRow> projects, WorkshopProjectQuery query,
                               List<Importable> importable, String notice, String error) {
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
            sb.append(projectToolbar(projects, query));
            List<ProjectRow> shown = query.apply(projects);
            sb.append("<p class=\"count-note\">").append(shown.size()).append(" projet(s)")
                    .append(query.narrowed() ? " sur " + projects.size() : "").append("</p>");
            if (shown.isEmpty()) {
                sb.append(Ui.empty("search", "Aucun projet ne correspond"
                        + (query.text().isEmpty() ? "" : " à « " + query.text() + " »")
                        + (query.filter() == WorkshopProjectQuery.Filter.ALL ? ""
                                : " dans « " + query.filter().label() + " »") + "."));
                sb.append("<p><a class=\"btn secondary sm\" href=\"").append(BASE).append("\">")
                        .append(Icons.icon("back")).append("Effacer la recherche et les filtres</a></p>");
            } else {
                sb.append("<div class=\"ws-grid\">");
                for (ProjectRow row : shown) {
                    sb.append(projectTile(row));
                }
                sb.append("</div>");
            }
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

    /** Recherche, filtres et tri (#881) : un formulaire GET, utilisable sans JavaScript. */
    private static String projectToolbar(List<ProjectRow> projects, WorkshopProjectQuery query) {
        StringBuilder sb = new StringBuilder("<form method=\"get\" action=\"").append(BASE)
                .append("\" class=\"toolbar ws-find\" role=\"search\">");
        sb.append("<div class=\"search\">").append(Icons.icon("search"))
                .append("<input type=\"search\" name=\"q\" maxlength=\"").append(WorkshopProjectQuery.MAX_TEXT)
                .append("\" value=\"").append(Http.esc(query.text()))
                .append("\" placeholder=\"Nom, identifiant ou description\" aria-label=\"Rechercher un projet\"></div>");
        if (query.filter() != WorkshopProjectQuery.Filter.ALL) {
            sb.append(hidden("filtre", query.filter().key()));
        }
        sb.append("<label class=\"ws-sort\">Trier <select name=\"tri\" class=\"form-select form-select-sm\">");
        for (WorkshopProjectQuery.Sort s : WorkshopProjectQuery.Sort.values()) {
            sb.append("<option value=\"").append(s.key()).append("\"").append(s == query.sort() ? " selected" : "")
                    .append(">").append(Http.esc(s.label())).append("</option>");
        }
        sb.append("</select></label><button class=\"btn sm\" type=\"submit\">")
                .append(Icons.icon("search")).append("Rechercher</button>");
        if (query.narrowed()) {
            sb.append("<a class=\"btn secondary sm\" href=\"").append(BASE).append("\">").append("Effacer</a>");
        }
        sb.append("</form><nav class=\"chips ws-filters\" aria-label=\"Filtrer les projets\">");
        for (WorkshopProjectQuery.Filter f : WorkshopProjectQuery.Filter.values()) {
            int n = query.count(projects, f);
            if (n == 0 && f != WorkshopProjectQuery.Filter.ALL && f != query.filter()) {
                continue;
            }
            boolean on = f == query.filter();
            sb.append("<a class=\"chip").append(on ? " on" : "").append("\" href=\"").append(Http.esc(query.url(f)))
                    .append("\"").append(on ? " aria-current=\"true\"" : "").append(">")
                    .append(Http.esc(f.label())).append(" (").append(n).append(")</a>");
        }
        return sb.append("</nav>").toString();
    }

    /** Une tuile : son nom mène au projet ; l'usage en jeu y est toujours lisible (#881). */
    private static String projectTile(ProjectRow row) {
        BuildingProject p = row.project();
        ProjectUsage u = row.usage();
        StringBuilder sb = new StringBuilder("<div class=\"card ws-card\"><div class=\"card-body\">");
        sb.append("<a class=\"ws-card-title\" href=\"").append(projectUrl(p.id(), "")).append("\">")
                .append(Http.esc(p.name())).append("</a>");
        if (!p.buildingId().isEmpty()) {
            sb.append(" ").append(Ui.id(p.buildingId()));
        }
        sb.append("<div class=\"ws-card-state\">").append(Http.esc(row.state())).append("</div>");
        sb.append("<div class=\"ws-card-usage\">").append(usagePill(u));
        if (u.usage() == ProjectUsage.Usage.USED && !u.sites().isEmpty()) {
            sb.append(" <span class=\"small\">").append(u.sites().size()).append(" emplacement(s)</span>");
        }
        sb.append(" ").append(Ui.badge(u.catalog().label())).append("</div>");
        sb.append("<div class=\"muted small\">").append(row.drafts()).append(" version(s) de plan · modifié le ")
                .append(Http.esc(WHEN.format(p.updatedAt()))).append("</div>");
        if (u.usage() == ProjectUsage.Usage.USED) {
            sb.append("<a class=\"small\" href=\"").append(usedWhereUrl(u)).append("\">")
                    .append(Icons.icon("world")).append("Voir où utilisé</a>");
        }
        return sb.append("</div></div>").toString();
    }

    /** Pastille d'usage : glyphe + texte, jamais la couleur seule. */
    static String usagePill(ProjectUsage u) {
        return switch (u.usage()) {
            case USED -> Ui.pill(u.usage().label(), "delivered", "●");
            case UNUSED -> Ui.pill(u.usage().label(), "neutral", "○");
            case UNCERTAIN -> Ui.pill(u.usage().label(), "pending", "?");
        };
    }

    private static String usedWhereUrl(ProjectUsage u) {
        return u.sites().isEmpty() && !u.portals().isEmpty() ? "/portals" : "/buildings/sites";
    }

    // ================================================================================
    //  Supprimer le projet (#881)
    // ================================================================================

    /**
     * La zone « Supprimer le projet » de la fiche : l'usage, ce qui serait effacé et conservé, et le
     * formulaire seulement si la suppression est permise. Le serveur revérifie tout à la soumission.
     */
    public static String deletionCard(Session session, BuildingProject p, ProjectUsage usage,
                                      WorkshopProjectDeletion.Preview preview, boolean canWrite) {
        StringBuilder sb = new StringBuilder("<details class=\"npc-registry ws-delete\" id=\"supprimer\">");
        sb.append("<summary>").append(Icons.icon("trash")).append("Supprimer le projet — ")
                .append(Http.esc(usage.usage().label())).append("</summary>");
        sb.append("<p>").append(usagePill(usage)).append(" ").append(Ui.badge(usage.catalog().label())).append("</p>");
        if (!usage.sites().isEmpty() || !usage.portals().isEmpty()) {
            sb.append("<p>Utilisé par : ");
            List<String> where = new java.util.ArrayList<>();
            usage.sites().forEach(s -> where.add("emplacement " + Ui.id(s)));
            usage.portals().forEach(s -> where.add("portail " + Ui.id(s)));
            sb.append(String.join(", ", where)).append(" — <a href=\"").append(usedWhereUrl(usage)).append("\">")
                    .append("Voir où utilisé</a></p>");
        }
        List<String> blockers = usage.blockers();
        sb.append("<p><strong>").append(blockers.isEmpty() ? "Serait supprimé définitivement"
                        : "Si la suppression était permise, serait supprimé")
                .append("</strong> (panel uniquement) : la fiche du projet, ")
                .append(preview.versions()).append(" version(s) de plan et ").append(preview.revisions())
                .append(" révision(s), ").append(preview.designs()).append(" conception(s) détaillée(s), ")
                .append(preview.runs()).append(" conception(s) automatique(s), ").append(preview.images())
                .append(" image(s) de référence").append(preview.images() > 0 ? " (" + kib(preview.imageBytes()) + ")" : "")
                .append(".</p>");
        sb.append("<p><strong>Conservé</strong> : le bâtiment de la bibliothèque et ses versions archivées, les "
                + "bâtiments posés, les emplacements et leurs sauvegardes de terrain, les portails, l'historique "
                + "des actions de l'agent (un plan y reste importable), le journal IA et le journal d'audit. "
                + "Aucun bloc du monde n'est touché.</p>");
        if (!blockers.isEmpty()) {
            sb.append(Ui.banner("warning", "<strong>Suppression impossible pour l'instant.</strong><ul class=\"tight\">"
                    + blockers.stream().map(b -> "<li>" + Http.esc(b) + "</li>").reduce("", String::concat)
                    + "</ul>" + (usage.catalog().inLibrary()
                            ? "<a href=\"/buildings/library\">Ouvrir la Bibliothèque</a>" : "")));
        } else if (!canWrite) {
            sb.append("<p class=\"muted\">Votre rôle ne permet pas de supprimer un projet.</p>");
        } else {
            sb.append("<form method=\"post\" action=\"").append(BASE).append("/project\" class=\"actform\">")
                    .append(hidden("_csrf", session.csrfToken())).append(hidden("_action", "project_delete"))
                    .append(hidden("id", p.id())).append(hidden("project_version", String.valueOf(p.version())))
                    .append("<label class=\"form-check\"><input class=\"form-check-input\" type=\"checkbox\" "
                            + "name=\"confirm\" value=\"yes\" required> Je supprime définitivement le projet « ")
                    .append(Http.esc(p.name())).append(" » et ses versions de plan. Cette action est "
                            + "irréversible.</label><div class=\"btnrow\"><button class=\"btn sm danger\" "
                            + "type=\"submit\">").append(Icons.icon("trash")).append("Supprimer le projet</button></div></form>");
        }
        return sb.append("</details>").toString();
    }

    private static String kib(long bytes) {
        return bytes < 1024 ? bytes + " o" : (bytes / 1024) + " Kio";
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
        // #568 : une arche de portail — passage traversable aux dimensions choisies, vérifié par le serveur.
        String kind = BuildingProject.normaliseKind(v.get("kind"));
        sb.append("<div class=\"field\"><label for=\"ws-kind\">Type de construction</label>")
                .append("<select id=\"ws-kind\" name=\"kind\">")
                .append("<option value=\"building\"").append(BuildingProject.KIND_PORTAL.equals(kind) ? "" : " selected")
                .append(">Bâtiment</option><option value=\"portal\"")
                .append(BuildingProject.KIND_PORTAL.equals(kind) ? " selected" : "")
                .append(">Arche de portail</option></select>")
                .append("<p class=\"field-help\">Une arche de portail a un passage que l'on traverse d'une face à "
                        + "l'autre ; une fois posée sur l'emplacement d'un portail, PlugAdmin en déduit sa zone "
                        + "d'activation (page Portails, « Lier l'arche posée »). La poser n'active jamais le "
                        + "portail.</p></div>");
        sb.append("<details class=\"npc-registry\"").append(BuildingProject.KIND_PORTAL.equals(kind) ? " open" : "")
                .append("><summary class=\"muted\">Passage de l'arche (arche de portail seulement)</summary>")
                .append("<div class=\"field\"><label for=\"ws-pw\">Largeur du passage (blocs)</label>")
                .append("<input id=\"ws-pw\" type=\"number\" name=\"passage_width\" min=\"1\" max=\"62\" step=\"1\" "
                        + "value=\"").append(Http.esc(v.getOrDefault("passage_width",
                                String.valueOf(BuildingProject.DEFAULT_PASSAGE_WIDTH)))).append("\"></div>")
                .append("<div class=\"field\"><label for=\"ws-ph\">Hauteur du passage (blocs)</label>")
                .append("<input id=\"ws-ph\" type=\"number\" name=\"passage_height\" min=\"2\" max=\"63\" step=\"1\" "
                        + "value=\"").append(Http.esc(v.getOrDefault("passage_height",
                                String.valueOf(BuildingProject.DEFAULT_PASSAGE_HEIGHT)))).append("\"></div>")
                .append("<p class=\"field-help\">Le passage traverse l'arche dans l'axe de l'entrée ; il doit laisser "
                        + "un pilier de chaque côté et un linteau. Le serveur relève le passage réel sur le plan et "
                        + "refuse une arche dont le passage est bouché.</p></details>");
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
        // #305 : trois limites distinctes, pour une emprise rectangulaire (13 × 9 × 7, pas 13 × 13).
        String width = v.getOrDefault("max_width", "19");
        sb.append("<p class=\"field-help\">Les axes sont ceux du plan, vus avec l'entrée devant soi : la "
                + "largeur (X) court le long de la façade d'entrée, la longueur (Z) va de l'entrée vers le fond, "
                + "la hauteur (Y) compte le toit. À la pose, une rotation peut échanger X et Z dans le monde ; "
                + "les dimensions du projet, elles, ne changent pas.</p>");
        sb.append("<div class=\"field\"><label for=\"ws-w\">Largeur maximale — X (blocs)</label>")
                .append("<input id=\"ws-w\" type=\"number\" name=\"max_width\" min=\"5\" max=\"64\" step=\"1\" "
                        + "required value=\"").append(Http.esc(width)).append("\"></div>");
        sb.append("<div class=\"field\"><label for=\"ws-d\">Longueur / profondeur maximale — Z (blocs)</label>")
                .append("<input id=\"ws-d\" type=\"number\" name=\"max_depth\" min=\"5\" max=\"64\" step=\"1\" "
                        + "required value=\"").append(Http.esc(v.getOrDefault("max_depth", width))).append("\"></div>");
        sb.append("<div class=\"field\"><label for=\"ws-h\">Hauteur maximale, toit compris — Y (blocs)</label>")
                .append("<input id=\"ws-h\" type=\"number\" name=\"max_height\" min=\"4\" max=\"64\" step=\"1\" "
                        + "required value=\"").append(Http.esc(v.getOrDefault("max_height", "14"))).append("\"></div>");
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
                              String error, String conflictText, boolean idTaken, int isoView, int isoCut,
                              Optional<BuildingDesignRunStore.Run> designRun,
                              List<ReferenceImageStore.Info> images, CatalogState catalog,
                              boolean ownBuilding, List<BuildingDesignDocStore.Doc> designs,
                              String designConflict, String importQuery,
                              Optional<PlanImportPreview> importPreview, RepairInfo repairInfo,
                              FailureInfo failure, Map<String, CheckState> versionAudits) {

        public ProjectView {
            versionAudits = versionAudits == null ? Map.of() : Map.copyOf(versionAudits);
        }
    }

    /**
     * #747 (23/10/2026) : le dernier appel à l'IA a échoué — refus, réponse vide, format invalide,
     * troncature, panne — et n'a créé aucune version. La page le dit en rouge et propose de le
     * reprendre à l'identique ; repartir de zéro reste un geste séparé et explicite.
     *
     * @param keptDraftId  la version source, restée active ; vide si aucune n'a de plan
     * @param gesture      « correct » (améliorer la version source) ou « propose » (plan neuf)
     * @param legacy       une ancienne réponse inexploitable rangée comme version avant le correctif
     */
    public record FailureInfo(String callId, String when, String label, String detail, String keptDraftId,
                              int keptVersion, String gesture, String mode, String notes, String providerId,
                              boolean legacy) {
    }

    /**
     * #747 : ce qui partira avec « Améliorer avec l'IA » en plus du motif — la mémoire des tentatives,
     * la conformité au brief, le mode suggéré et l'état des images — pour que l'administrateur le voie
     * avant de payer.
     *
     * @param complianceLines une ligne par exigence du brief (✓ réalisée, ✗ manquante, ◌ à apprécier)
     * @param regressions     ce que cette version réintroduit, ou vide
     */
    public record RepairInfo(String memorySummary, List<String> complianceLines, String suggestedMode,
                             String why, String imageNote, String regressions, String best,
                             String stagnation, String bestDraftId, String notes) {

        public RepairInfo {
            complianceLines = List.copyOf(complianceLines == null ? List.of() : complianceLines);
            stagnation = stagnation == null ? "" : stagnation;
            bestDraftId = bestDraftId == null ? "" : bestDraftId;
            notes = notes == null ? "" : notes;
        }

        public RepairInfo(String memorySummary, List<String> complianceLines, String suggestedMode, String why,
                          String imageNote, String regressions, String best) {
            this(memorySummary, complianceLines, suggestedMode, why, imageNote, regressions, best, "", "", "");
        }

        /** Stagnation sur une autre version que la meilleure : l'automatique n'est plus proposé. */
        public boolean stopped() {
            return !stagnation.isEmpty();
        }
    }

    /** Les étapes du parcours, dans l'ordre. */
    enum Step { DESCRIBE, PROPOSE, CHECK, SAVE, DONE }

    public static String projectUrl(String projectId, String draftId) {
        return BASE + "/project?id=" + Http.esc(projectId)
                + (draftId == null || draftId.isBlank() ? "" : "&amp;draft=" + Http.esc(draftId));
    }

    /**
     * La version montrée quand l'URL n'en désigne aucune (#655).
     *
     * <p>D'ordinaire la plus récente : reprendre un projet, c'est reprendre là où on en était. Mais
     * une conception automatique terminée qui a <strong>retenu</strong> une version — la dernière
     * conforme — l'emporte, tant qu'aucune version n'a été créée après elle. Le 20/10/2026, la
     * session « Exigeant » de la Boulangerie a obtenu un plan conforme, puis dix corrections d'une
     * critique ratée ont empilé des versions refusées : la page rouvrait sur la dernière, « un point
     * est à corriger », et le plan conforme restait introuvable.</p>
     */
    public static BuildingPlanDraft defaultDraft(List<BuildingPlanDraft> drafts,
                                                 Optional<BuildingDesignRunStore.Run> run) {
        if (drafts == null || drafts.isEmpty()) {
            return null;
        }
        BuildingPlanDraft latest = drafts.get(0);
        if (run == null || run.isEmpty() || run.get().running() || run.get().finalDraftId().isBlank()) {
            return latest;
        }
        BuildingDesignRunStore.Run r = run.get();
        boolean newerWork = drafts.stream().anyMatch(d -> d.createdAt() != null && r.updatedAt() != null
                && d.createdAt().isAfter(r.updatedAt()));
        if (newerWork) {
            return latest;
        }
        return drafts.stream().filter(d -> d.id().equals(r.finalDraftId()) && !d.archived()).findFirst()
                .orElse(latest);
    }

    public static String project(ProjectView v) {
        BuildingProject p = v.project();
        StringBuilder sb = new StringBuilder();
        // #739 : le journal IA complet du projet, en texte — un clic, aucun appel payant.
        sb.append(Ui.pageHeader("box", p.name(), "Projet de l'Atelier — rien n'est posé dans le monde depuis "
                + "cette page.", "<a class=\"btn btn-outline-secondary\" href=\"" + BASE + "/journal?id="
                + Http.esc(java.net.URLEncoder.encode(p.id(), java.nio.charset.StandardCharsets.UTF_8))
                + "\" title=\"Fichier texte : chaque échange réel avec l'IA et chaque vérification du serveur, "
                + "horodatés, secrets masqués\">" + Icons.icon("export") + "Télécharger le journal IA</a> "
                + "<a class=\"btn btn-outline-secondary\" href=\"" + BASE + "\">"
                + Icons.icon("back") + "Atelier</a>"));
        banners(sb, v.notice(), v.error());
        sb.append(failureCard(v));
        Optional<String> orientation = com.lodygames.rpgquest.panel.building.BriefOrientation.conflict(p);
        orientation.ifPresent(c -> sb.append(Ui.banner("warning", "<strong>Façade d'entrée contradictoire : "
                + "les gestes payants sont suspendus.</strong> " + Http.esc(c))));
        if (v.draft() != null && v.draft().origin() == BuildingPlanDraft.Origin.ENGINE_REPAIR) {
            sb.append(Ui.banner("info", "<strong>Version réparée automatiquement par le moteur du serveur, "
                    + "sans appel à l'IA.</strong> La version d'origine reste disponible.<br>"
                    + Http.esc(v.draft().extractionNote()).replace("\n", "<br>")));
        }

        Step step = step(v);
        sb.append(stepper(step));
        sb.append("<div class=\"card ws-main\"><div class=\"card-body\">");
        sb.append(mainCard(v, step));
        sb.append("</div></div>");
        sb.append(conceptionCard(v, step));
        sb.append(restartCard(v, step));
        sb.append(designCard(v, step));
        sb.append(previewCard(v, step));

        // ---- Le bâtiment souhaité : ouvert tant qu'il n'est pas décrit ---------------------------
        sb.append("<details class=\"npc-registry\"")
                .append(step == Step.DESCRIBE || orientation.isPresent() ? " open" : "")
                .append("><summary>").append(Icons.icon("edit")).append("Le bâtiment souhaité</summary>");
        sb.append("<form method=\"post\" action=\"").append(BASE).append("/project\" class=\"actform\">");
        sb.append(hidden("_csrf", v.session().csrfToken())).append(hidden("_action", "project_save"))
                .append(hidden("id", p.id())).append(hidden("project_version", String.valueOf(p.version())));
        if (v.draft() != null) {
            sb.append(hidden("draft", v.draft().id()));
        }
        sb.append(projectFields(Map.of("name", p.name(), "brief", p.brief(), "front", p.front(),
                "max_width", String.valueOf(p.maxWidth()), "max_depth", String.valueOf(p.maxDepth()),
                "max_height", String.valueOf(p.maxHeight()), "building_id", p.buildingId(), "kind", p.kind(),
                "passage_width", String.valueOf(p.portal() ? p.passageWidth() : BuildingProject.DEFAULT_PASSAGE_WIDTH),
                "passage_height", String.valueOf(p.portal() ? p.passageHeight()
                        : BuildingProject.DEFAULT_PASSAGE_HEIGHT))));
        sb.append("<div class=\"btnrow\"><button class=\"btn btn-sm btn-outline-primary\" type=\"submit\">")
                .append(Icons.icon("save")).append("Enregistrer les modifications</button></div>");
        if (!p.sourceSiteId().isEmpty()) {
            sb.append("<p class=\"field-help\">Repris de l'emplacement ").append(Ui.id(p.sourceSiteId()))
                    .append(" : sa fiche n'a pas été modifiée.</p>");
        }
        sb.append("</form></details>");
        sb.append(imagesCard(v));

        // #308 : l'historique de CE projet, puis — à part, replié — l'import d'un plan venu d'ailleurs.
        sb.append(draftList(v));
        sb.append(importCard(v));
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
                if (v.failure() != null && v.failure().legacy()) {
                    // La reprise est dans la carte d'échec : « Créer un plan » perdrait le contexte.
                    sb.append("<h2 class=\"h5\">Cette version ne contient pas de plan</h2><p>Voir ci-dessus : "
                            + "la réponse de l'IA était inexploitable. Reprenez depuis la dernière version "
                            + "exploitable.</p>");
                    break;
                }
                sb.append("<h2 class=\"h5\">Prêt à demander un plan</h2><p>L'IA lit votre description et "
                        + "propose un plan de construction. Vous le vérifierez avant d'enregistrer quoi que "
                        + "ce soit.</p>");
                sb.append(proposeForm(v, "Créer un plan avec l'IA"));
            }
            case CHECK -> sb.append(checkCard(v));
            case SAVE -> sb.append(saveCard(v));
            case DONE -> sb.append(doneCard(v));
        }
        return sb.toString();
    }

    /**
     * La conception détaillée (#289) : l'étape entre la description et le plan technique. Consultable,
     * modifiable à la main (chaque enregistrement est une version), ou rédigée par l'IA en un appel.
     * Tout plan demandé ensuite — proposition, correction, critique, conception automatique — la suit.
     */
    static String conceptionCard(ProjectView v, Step step) {
        if (step == Step.DESCRIBE) {
            return "";
        }
        Optional<BuildingDesignDocStore.Doc> current = v.designs().isEmpty() ? Optional.empty()
                : Optional.of(v.designs().get(0));
        StringBuilder sb = new StringBuilder("<details class=\"npc-registry\" id=\"conception\"")
                .append(v.designConflict() != null || current.isEmpty() && step == Step.PROPOSE ? " open" : "")
                .append("><summary>").append(Icons.icon("edit")).append("Conception détaillée")
                .append(current.map(d -> " — version " + d.version()).orElse(" — pas encore rédigée"))
                .append("</summary>");
        sb.append("<p class=\"field-help\">Avant le plan technique : les pièces, la palette, les accès, le "
                + "mobilier pièce par pièce, la décoration, ce que le moteur ne sait pas poser, et la "
                + "correspondance avec votre description. Chaque plan demandé ensuite à l'IA la suit ; votre "
                + "description reste jointe à chaque demande.</p>");
        current.ifPresent(d -> sb.append("<p class=\"muted\">Version ").append(d.version()).append(" — ")
                .append(Http.esc(d.origin().label()))
                .append(d.createdBy() == null || d.createdBy().isBlank() ? "" : " par " + Http.esc(d.createdBy()))
                .append(d.model() == null || d.model().isBlank() ? "" : " (" + Http.esc(d.model()) + ")")
                .append(", ").append(v.designs().size()).append(" version(s) conservée(s).</p>"));
        current.ifPresent(d -> sb.append(designStructure(v, d.text())));
        if (v.designConflict() != null) {
            sb.append(Ui.banner("warning", "Votre texte n'a pas été enregistré : il est réaffiché ci-dessous."));
        }
        String text = v.designConflict() != null ? v.designConflict() : current.map(BuildingDesignDocStore.Doc::text).orElse("");
        if (v.canWrite()) {
            sb.append("<form method=\"post\" action=\"").append(BASE).append("/project\" class=\"actform\">")
                    .append(hidden("_csrf", v.session().csrfToken())).append(hidden("_action", "design_save"))
                    .append(hidden("id", v.project().id()))
                    .append(hidden("design_version", String.valueOf(current.map(BuildingDesignDocStore.Doc::version).orElse(0))));
            if (v.draft() != null) {
                sb.append(hidden("draft", v.draft().id()));
            }
            sb.append("<textarea name=\"design\" rows=\"16\" maxlength=\"").append(BuildingDesignDocStore.MAX_TEXT)
                    .append("\" aria-label=\"Conception détaillée\">").append(Http.esc(text)).append("</textarea>")
                    .append("<div class=\"btnrow\"><button class=\"btn btn-sm btn-outline-primary\" type=\"submit\">")
                    .append(Icons.icon("save")).append("Enregistrer la conception</button></div></form>");
        } else if (!text.isEmpty()) {
            sb.append("<pre class=\"brief-text\">").append(Http.esc(text)).append("</pre>");
        }
        if (v.canWrite() && !v.providers().isEmpty()) {
            sb.append("<form method=\"post\" action=\"").append(BASE).append("/project\" class=\"actform\">")
                    .append(hidden("_csrf", v.session().csrfToken())).append(hidden("_action", "design_write"))
                    .append(hidden("id", v.project().id())).append(hidden("request_token", v.requestToken()));
            if (v.draft() != null) {
                sb.append(hidden("draft", v.draft().id()));
            }
            sb.append(providerSelect(v, "")).append("<div class=\"btnrow\"><button class=\"btn btn-sm btn-outline-primary\" "
                    + "type=\"submit\">").append(Icons.icon("wrench"))
                    .append(current.isEmpty() ? "Rédiger la conception avec l'IA" : "Réécrire la conception avec l'IA")
                    .append("</button></div><p class=\"field-help\">Un appel facturé, d'après votre description et "
                            + "vos images. La version actuelle reste conservée.</p></form>");
        }
        return sb.append("</details>").toString();
    }

    /**
     * #747 : ce que le panel LIT dans la conception — volumes, éléments — et ses lacunes face à la
     * description. C'est exactement ce qui partira avec le plan (« CONTRAT DE VOLUMES »).
     */
    private static String designStructure(ProjectView v, String design) {
        var structure = com.lodygames.rpgquest.panel.ai.ArchitecturalDesign.parse(design);
        var p = v.project();
        StringBuilder sb = new StringBuilder("<div class=\"ws-design-structure\"><p><strong>Structure lue par le panel"
                + "</strong> — transmise au plan comme contrat de volumes :</p><ul>");
        if (!structure.structured()) {
            sb.append("<li>✗ Aucune ligne « VOLUME … » lisible : le plan n'aura pas de structure à suivre. Réécrivez la "
                    + "conception avec l'IA, ou ajoutez une ligne par volume (VOLUME nom | rôle: … | x A..B | z A..B | "
                    + "y A..B | toit: … | relié à: …).</li>");
        } else {
            for (var f : structure.check(p.brief(), p.maxWidth(), p.maxDepth(), p.maxHeight())) {
                sb.append("<li>").append(Http.esc(f.line())).append("</li>");
            }
        }
        return sb.append("</ul></div>").toString();
    }

    /**
     * Le bâtiment enregistré — ou qui ne l'est plus (#290). L'état vient de la bibliothèque réelle
     * ({@link CatalogState}) : après un retrait, plus de « posez-le » mensonger ; la reprise de la
     * conception reste toujours possible, sans effacer quoi que ce soit.
     */
    static String doneCard(ProjectView v) {
        BuildingProject p = v.project();
        StringBuilder sb = new StringBuilder();
        if (v.catalog().gone()) {
            sb.append("<h2 class=\"h5\">").append(Icons.icon("warning")).append(Http.esc(v.catalog().label()))
                    .append("</h2><p>« ").append(Http.esc(p.name())).append(" » (<code class=\"tid\">")
                    .append(Http.esc(p.buildingId())).append("</code>) n'est plus dans la bibliothèque du "
                            + "serveur")
                    .append(v.catalog().state() == CatalogState.State.RETIRED
                            ? " : il a été retiré, ses fichiers sont archivés"
                            : v.catalog().state() == CatalogState.State.PURGED
                                    ? " : il a été supprimé avec toutes ses versions enregistrées" : "")
                    .append(". Il ne peut pas être posé. La description, les versions et les images du projet "
                            + "sont conservées.</p>");
            if (v.canWrite()) {
                sb.append("<div class=\"btnrow\">").append(gestureForm(v, "generate",
                        "Réenregistrer dans la bibliothèque", true)).append("</div>");
            }
        } else {
            // #747 : une version enregistrée malgré ses défauts le reste, jamais « conforme ».
            boolean withDefects = "GENERATED_WITH_WARNINGS".equals(String.valueOf(v.generate().details().get("code")));
            sb.append("<h2 class=\"h5\">").append(Icons.icon(withDefects ? "warning" : "check"))
                    .append(withDefects ? "Enregistré avec avertissements" : "Enregistré dans la bibliothèque")
                    .append(v.catalog().version() > 0 ? " — version " + v.catalog().version() : "")
                    .append("</h2><p>« ").append(Http.esc(p.name()))
                    .append(" » peut maintenant être posé sur un ou plusieurs emplacements, sans nouvel "
                            + "appel à l'IA.</p><div class=\"btnrow\">")
                    .append("<a class=\"btn btn-primary\" href=\"/buildings/library\">")
                    .append(Icons.icon("box")).append("Voir dans la bibliothèque</a>")
                    .append("<a class=\"btn btn-outline-primary\" href=\"/buildings/sites?place=")
                    .append(Http.esc(p.buildingId())).append("\">")
                    .append(Icons.icon("world")).append("Choisir un emplacement</a></div>");
            if (withDefects) {
                sb.append(Ui.banner("warning", "<strong>Version non conforme, enregistrée à votre demande</strong> "
                        + "avec ses défauts connus : " + Http.esc(String.valueOf(v.generate().details()
                        .getOrDefault("message", ""))) + " Examinez-la en jeu, puis retouchez-la."));
            }
        }
        sb.append(resumeForm(v, "Reprendre la conception"));
        sb.append("<p class=\"field-help\">Reprendre crée une nouvelle version à partir de celle-ci, sans "
                + "appel à l'IA : vous pourrez la modifier, l'améliorer avec l'IA, puis la publier. Le bâtiment "
                + "de la bibliothèque et ses exemplaires déjà posés ne changent pas tant que vous ne les "
                + "remplacez pas explicitement.</p>");
        return sb.toString();
    }

    /** Une nouvelle version à partir de celle affichée (#290) : gratuite, rien n'est remplacé. */
    static String resumeForm(ProjectView v, String label) {
        if (v.draft() == null || !v.draft().hasPlan()) {
            return "";
        }
        return "<form method=\"post\" action=\"" + BASE + "/project\" class=\"inline\">"
                + hidden("_csrf", v.session().csrfToken()) + hidden("_action", "resume")
                + hidden("id", v.project().id()) + hidden("draft", v.draft().id())
                + "<button class=\"btn btn-outline-primary\" type=\"submit\">" + Icons.icon("edit")
                + Http.esc(label) + "</button></form>";
    }

    /**
     * Publier une nouvelle version du bâtiment que CE projet a produit (#290) : remplacement
     * explicite, coché, et contrôlé — la version vue est envoyée, le serveur refuse si elle a
     * changé. Les exemplaires posés ne bougent pas.
     */
    static String republishForm(ProjectView v) {
        BuildingPlanDraft d = v.draft();
        int version = v.catalog().version();
        StringBuilder sb = new StringBuilder("<form method=\"post\" action=\"" + BASE + "/project\" class=\"actform\">");
        sb.append(hidden("_csrf", v.session().csrfToken())).append(hidden("_action", "generate"))
                .append(hidden("id", v.project().id())).append(hidden("draft", d.id()))
                .append(hidden("version", String.valueOf(d.version()))).append(hidden("plan", d.plan()))
                .append(hidden("republish", "yes"))
                .append(hidden("expected_version", String.valueOf(version)));
        sb.append("<div class=\"field\"><label><input type=\"checkbox\" name=\"republish_confirm\" value=\"yes\"> "
                + "Je publie cette version à la place de la version ").append(version > 0 ? version : "actuelle")
                .append(" dans la bibliothèque. La version remplacée est archivée sur le serveur ; les "
                        + "exemplaires déjà posés ne changent pas tant que je ne les remplace pas.</label></div>");
        sb.append("<div class=\"btnrow\"><button class=\"btn btn-primary\" type=\"submit\">")
                .append(Icons.icon("save")).append("Publier une nouvelle version</button></div></form>");
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

        // #289 : TOUS les matériaux de la grille, avec leur nombre de blocs. La légende était bornée
        // à dix : un lutrin ou trois barrières disparaissaient de la liste, comme s'ils manquaient.
        List<IsometricView.Swatch> legend = IsometricView.legend(voxels, Integer.MAX_VALUE);
        sb.append("<ul class=\"ws-legend\">");
        for (IsometricView.Swatch swatch : legend) {
            sb.append("<li><svg class=\"ws-swatch\" viewBox=\"0 0 10 10\" aria-hidden=\"true\"><rect width=\"10\" "
                    + "height=\"10\" fill=\"").append(swatch.color()).append("\"/></svg>")
                    .append(Http.esc(swatch.label())).append(" <span class=\"muted\">×").append(swatch.count())
                    .append("</span></li>");
        }
        sb.append("</ul>");
        List<String> absent = materialsAbsentFromGrid(v.draft().plan(), legend);
        if (!absent.isEmpty()) {
            sb.append("<p class=\"field-help\">Cités par le plan mais absents de la grille finale : ")
                    .append(Http.esc(String.join(", ", absent))).append(". Une instruction postérieure les "
                            + "recouvre ou les vide (« clear », mobilier, escalier) : vérifiez l'ordre des "
                            + "instructions si vous les attendiez.</p>");
        }
        sb.append("<p class=\"field-help\">Vue simplifiée : une couleur par matériau, pas les textures du jeu ; "
                + "l'eau, le verre et la glace sont translucides. Escaliers, dalles, portes, trappes, chaînes, "
                + "barrières, murets, vitres, lanternes et torches ont leur forme et leur orientation ; le mobilier "
                + "et les décorations (coffres, lits, fleurs, panneaux suspendus…) restent des cubes. Chaque forme "
                + "est un vrai bloc du bâtiment que le serveur construirait.</p>");
        return sb.append("</div></section>").toString();
    }

    /** Les matériaux que le plan écrit (« block: », « fill: ») et que la grille ne contient pas. */
    static List<String> materialsAbsentFromGrid(String plan, List<IsometricView.Swatch> legend) {
        java.util.Set<String> inGrid = new java.util.HashSet<>();
        for (IsometricView.Swatch s : legend) {
            inGrid.add(s.label());
        }
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        var m = java.util.regex.Pattern.compile("(?m)^\\s*(?:block|fill):\\s*(?:minecraft:)?([a-z0-9_]+)")
                .matcher(plan == null ? "" : plan);
        while (m.find()) {
            String label = m.group(1).replace('_', ' ');
            if (!inGrid.contains(label)) {
                out.add(label);
            }
        }
        return List.copyOf(out);
    }

    /**
     * « Recommencer depuis ma description » (issue #283) : une nouvelle conception indépendante, qui
     * n'hérite ni du plan ni des erreurs de la version affichée. Replié : c'est un geste payant, à
     * côté du parcours principal, jamais à sa place.
     */
    static String restartCard(ProjectView v, Step step) {
        if ((step != Step.CHECK && step != Step.SAVE) || v.providers().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("<details class=\"npc-registry ws-restart\"><summary>")
                .append(Icons.icon("refresh")).append("Recommencer depuis ma description</summary>");
        sb.append("<p>L'IA repart de votre description").append(v.images().isEmpty() ? ""
                : " et de vos " + v.images().size() + " image(s) de référence")
                .append(", sans reprendre ce plan ni ses erreurs. Le résultat devient une nouvelle version ; "
                        + "les versions actuelles restent disponibles.</p>");
        sb.append("<form method=\"post\" action=\"").append(BASE).append("/project\" class=\"actform\">");
        sb.append(hidden("_csrf", v.session().csrfToken())).append(hidden("_action", "restart"))
                .append(hidden("id", v.project().id())).append(hidden("request_token", v.requestToken()));
        if (v.draft() != null) {
            sb.append(hidden("draft", v.draft().id()));
        }
        sb.append(providerSelect(v, v.draft() == null ? "" : v.draft().providerId()));
        sb.append("<div class=\"field\"><label><input type=\"checkbox\" name=\"confirm\" value=\"yes\"> "
                + "Je confirme : <strong>un appel facturé</strong> par le fournisseur d'IA.</label></div>");
        sb.append("<div class=\"btnrow\"><button class=\"btn btn-outline-primary\" type=\"submit\">")
                .append(Icons.icon("refresh")).append("Recommencer depuis ma description</button></div></form>");
        return sb.append("</details>").toString();
    }

    /**
     * Les images de référence du projet (issue #283) : vignettes, retrait, ajout.
     *
     * <p>Envoi en {@code multipart/form-data} vers {@code /buildings/workshop/image}, sans script.
     * Le texte dit ce que l'image fait — une référence de style, transmise en pixels — et ce
     * qu'elle ne fait pas.</p>
     */
    static String imagesCard(ProjectView v) {
        StringBuilder sb = new StringBuilder("<details class=\"npc-registry ws-images\"")
                .append(v.images().isEmpty() ? "" : " open").append("><summary>").append(Icons.icon("box"))
                .append("Images de référence <span class=\"muted\">(facultatif, ").append(v.images().size())
                .append(" / ").append(ReferenceImageStore.MAX_IMAGES).append(")</span></summary>");
        sb.append("<p>Montrez à l'IA le style recherché : silhouette, toiture, matériaux, ambiance. Les images "
                + "lui sont <strong>réellement transmises</strong> (leurs pixels) à chaque demande de ce projet ; "
                + "elles l'inspirent, elles ne sont pas recopiées bloc pour bloc. Le bâtiment reste aux "
                + "dimensions du projet, en blocs Minecraft vanilla. Chaque image ajoute des jetons à chaque "
                + "appel. Si le modèle choisi n'accepte pas les images, l'appel échoue et le dit.</p>");
        if (!v.images().isEmpty()) {
            sb.append("<ul class=\"ws-thumbs\">");
            for (ReferenceImageStore.Info img : v.images()) {
                String src = BASE + "/image?project=" + Http.esc(v.project().id()) + "&amp;id=" + Http.esc(img.id());
                sb.append("<li><img src=\"").append(src).append("\" alt=\"").append(Http.esc(img.name()))
                        .append("\" style=\"max-width:220px;max-height:160px;border-radius:6px\"><br><span class=\"muted\">")
                        .append(Http.esc(img.name())).append(" — ").append(img.size() / 1024).append(" Kio</span>");
                sb.append("<form method=\"post\" action=\"").append(BASE).append("/project\" class=\"actform\">")
                        .append(hidden("_csrf", v.session().csrfToken())).append(hidden("_action", "image_remove"))
                        .append(hidden("id", v.project().id())).append(hidden("image", img.id()));
                if (v.draft() != null) {
                    sb.append(hidden("draft", v.draft().id()));
                }
                sb.append("<button class=\"btn btn-sm btn-outline-danger\" type=\"submit\">Retirer</button></form></li>");
            }
            sb.append("</ul>");
        }
        if (v.images().size() < ReferenceImageStore.MAX_IMAGES) {
            // Le projet voyage aussi dans l'URL : un envoi trop lourd, refusé avant toute lecture du
            // formulaire (par PlugAdmin ou par le proxy nginx), revient quand même sur ce projet.
            String target = BASE + "/image?id=" + Http.esc(v.project().id())
                    + (v.draft() == null ? "" : "&amp;draft=" + Http.esc(v.draft().id()));
            sb.append("<form method=\"post\" action=\"").append(target).append("\" enctype=\"multipart/form-data\" "
                    + "class=\"actform\">").append(hidden("_csrf", v.session().csrfToken()))
                    .append(hidden("id", v.project().id()));
            if (v.draft() != null) {
                sb.append(hidden("draft", v.draft().id()));
            }
            sb.append("<div class=\"field\"><label for=\"ws-image\">Ajouter une image</label>"
                    + "<input id=\"ws-image\" type=\"file\" name=\"image\" accept=\"image/png,image/jpeg,image/webp,image/gif\">"
                    + "<p class=\"muted small\">Formats acceptés : PNG, JPEG, WebP ou GIF. "
                    + ReferenceImageStore.MAX_BYTES / (1024 * 1024) + " Mio au plus par image, "
                    + ReferenceImageStore.MAX_IMAGES + " images par projet. Une image ou une planche "
                    + "ChatGPT ordinaire passe ; si elle est plus lourde, enregistrez-la en JPEG ou réduisez-la "
                    + "(1600 pixels de large suffisent).</p></div><div class=\"btnrow\"><button class=\"btn btn-sm btn-outline-primary\" type=\"submit\">"
                    + "Ajouter l'image</button></div></form>");
        }
        return sb.append("</details>").toString();
    }

    /**
     * « Conception automatique » (issue #282) : l'enveloppe annoncée avant le consentement, puis le
     * journal des passes — chacune avec ses jetons — pendant et après la session.
     */
    static String designCard(ProjectView v, Step step) {
        if (step == Step.DESCRIBE || step == Step.DONE) {
            return "";
        }
        StringBuilder sb = new StringBuilder("<section class=\"card ws-design\"><div class=\"card-body\">");
        sb.append("<h2 class=\"h5\">").append(Icons.icon("wrench")).append("Conception automatique</h2>");
        Optional<BuildingDesignRunStore.Run> latest = v.designRun();
        latest.ifPresent(run -> sb.append(designRun(v, run)));
        if (latest.map(BuildingDesignRunStore.Run::running).orElse(false)) {
            return sb.append("</div></section>").toString();
        }
        if (v.providers().isEmpty()) {
            return sb.append(noProvider()).append("</div></section>").toString();
        }
        sb.append("<p>L'IA conçoit, le serveur vérifie et répare ce qu'il sait réparer, l'IA corrige avec "
                + "le diagnostic exact, puis relit le plan en architecte. Vous ne touchez à rien ; chaque "
                + "passe devient une version que vous pouvez consulter.</p>");
        String model = v.drafts().stream().map(BuildingPlanDraft::model).filter(m -> m != null && !m.isBlank())
                .findFirst().orElse("");
        int[] seen = v.drafts().stream().map(d -> usage(d.usage())).filter(u -> u[0] > 0).findFirst()
                .orElse(new int[] {0, 0});
        sb.append("<form method=\"post\" action=\"").append(BASE).append("/project\" class=\"actform\">");
        sb.append(hidden("_csrf", v.session().csrfToken())).append(hidden("_action", "design"))
                .append(hidden("id", v.project().id())).append(hidden("request_token", v.requestToken()));
        if (v.draft() != null) {
            sb.append(hidden("draft", v.draft().id()));
        }
        sb.append(providerSelect(v, v.draft() == null ? "" : v.draft().providerId()));
        sb.append("<fieldset class=\"field\"><legend>Niveau d'exigence</legend>");
        for (BuildingDesignPipeline.Quality q : BuildingDesignPipeline.Quality.values()) {
            BuildingDesignPipeline.Envelope e = BuildingDesignPipeline.envelope(q.calls(), model, seen[0], seen[1],
                    v.project().brief().length(), v.images().size());
            sb.append("<label class=\"ws-choice\"><input type=\"radio\" name=\"quality\" value=\"")
                    .append(q.name()).append('"').append(q == BuildingDesignPipeline.Quality.SOIGNE ? " checked" : "")
                    .append("> <strong>").append(Http.esc(q.label())).append("</strong> — ")
                    .append(Http.esc(q.description())).append("<br><span class=\"muted\">Enveloppe : ")
                    .append(Http.esc(e.label())).append(".</span></label>");
        }
        sb.append("</fieldset>");
        if (v.drafts().stream().anyMatch(BuildingPlanDraft::hasPlan)) {
            sb.append("<div class=\"field\"><label><input type=\"checkbox\" name=\"fresh\" value=\"yes\"> "
                    + "Repartir de zéro depuis ma description, sans reprendre les versions existantes (elles "
                    + "restent consultables). La conception détaillée en vigueur est <strong>conservée</strong>."
                    + "</label></div>");
        }
        if (v.designs() != null && !v.designs().isEmpty()) {
            sb.append("<div class=\"field\"><label><input type=\"checkbox\" name=\"rewrite_design\" value=\"yes\"> "
                    + "Réécrire aussi la conception détaillée : l'IA en rédige une nouvelle version, qui "
                    + "remplace la version " + v.designs().get(0).version() + " pour cette session (elle reste "
                    + "consultable). Décoché, la conception actuelle — y compris vos corrections à la main — "
                    + "est transmise telle quelle.</label></div>");
        }
        sb.append("<div class=\"field\"><label><input type=\"checkbox\" name=\"consent\" value=\"yes\"> "
                + "J'accepte cette enveloppe : au plus le nombre d'appels annoncé, <strong>facturés</strong> "
                + "par le fournisseur. La session s'arrête d'elle-même dès qu'un plan est conforme (et "
                + "embelli selon le niveau), si le serveur ne répond plus, ou si l'IA tourne en rond."
                + "</label></div>");
        sb.append("<div class=\"btnrow\"><button class=\"btn btn-primary\" type=\"submit\">")
                .append(Icons.icon("wrench")).append("Lancer la conception automatique</button></div></form>");
        return sb.append("</div></section>").toString();
    }

    private static String designRun(ProjectView v, BuildingDesignRunStore.Run run) {
        StringBuilder sb = new StringBuilder();
        if (run.running()) {
            // Pas de script (CSP) : la page se recharge seule tant que la session tourne.
            sb.append("<meta http-equiv=\"refresh\" content=\"5\">");
        }
        sb.append("<p><strong>").append(Http.esc(run.status().label())).append("</strong> — ")
                .append(run.spentCalls()).append(" appel(s) à l'IA sur ").append(run.maxCalls())
                .append(" consentis, ").append(String.format(java.util.Locale.FRANCE, "%,d", run.inputTokens()))
                .append(" jetons d'entrée et ")
                .append(String.format(java.util.Locale.FRANCE, "%,d", run.outputTokens()))
                .append(" de sortie.");
        if (!run.outcome().isBlank()) {
            sb.append(" ").append(Http.esc(run.outcome()));
        }
        sb.append("</p>");
        if (!run.finalDraftId().isBlank()) {
            sb.append("<p><a class=\"btn btn-sm btn-primary\" href=\"")
                    .append(projectUrl(v.project().id(), run.finalDraftId())).append("\">")
                    .append(Icons.icon("check")).append("Voir le plan retenu</a></p>");
        }
        sb.append("<ol class=\"ws-passes\">");
        for (BuildingDesignRunStore.Pass pass : run.passes()) {
            sb.append("<li><strong>").append(Http.esc(passLabel(pass.kind()))).append("</strong>");
            if (!pass.draftId().isBlank()) {
                sb.append(" — <a href=\"").append(projectUrl(v.project().id(), pass.draftId()))
                        .append("\">version ").append(shortId(pass.draftId())).append("</a>");
            }
            if (pass.tokensIn() > 0 || pass.tokensOut() > 0) {
                sb.append(" <span class=\"muted\">(").append(Http.esc(pass.model())).append(", ")
                        .append(pass.tokensIn()).append(" / ").append(pass.tokensOut()).append(" jetons)</span>");
            }
            sb.append(" : ").append(Http.esc(pass.verdict())).append("</li>");
        }
        sb.append("</ol>");
        if (run.running()) {
            sb.append("<a class=\"btn btn-sm btn-outline-secondary\" href=\"")
                    .append(projectUrl(v.project().id(), "")).append("\">").append(Icons.icon("refresh"))
                    .append("Rafraîchir</a>");
        }
        return sb.toString();
    }

    private static String passLabel(String kind) {
        return switch (kind) {
            case "CONCEPTION" -> "Conception par l'IA (payant)";
            case "CONCEPTION NEUVE" -> "Nouvelle conception par l'IA, en évitant les écueils (payant)";
            case "CORRECTION" -> "Correction par l'IA avec le diagnostic du serveur (payant)";
            case "CRITIQUE" -> "Critique architecturale par l'IA (payant)";
            case "MOTEUR" -> "Réparation par le moteur (gratuit)";
            case "VÉRIFICATION" -> "Vérification par le serveur (gratuit)";
            default -> "Note";
        };
    }

    private static int[] usage(String raw) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+) entrée / (\\d+) sortie")
                .matcher(raw == null ? "" : raw);
        return m.find() ? new int[] {Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))}
                : new int[] {0, 0};
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
                    + "quelques secondes. La page affiche le résultat d'elle-même dès qu'il arrive, "
                    + "réussi ou non.</p><a class=\"btn btn-outline-secondary\" href=\"")
                    .append(projectUrl(v.project().id(), v.draft().id())).append("\">")
                    .append(Icons.icon("refresh")).append("Rafraîchir</a>");
            // #655 : suivi jusqu'à l'état terminal, sans clic — même mécanisme que l'import de mobs (#214).
            audit.row().ifPresent(row -> sb.append("<span hidden data-pa-track-action=\"")
                    .append(Http.esc(row.id())).append("\" data-pa-track-reload=\"terminal\"></span>"));
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
        sb.append(acceptDefectsForm(v));
        sb.append(correctionForm(v, correctionReason(v)));
        return sb.toString();
    }

    /**
     * #747 : enregistrer une version refusée pour des défauts CONTOURNABLES (lumière, support
     * décoratif, accessibilité…), après lecture de chacun et de son effet en jeu. Le serveur revérifie
     * que ce sont exactement les défauts du plan ; la version reste « avec avertissements », jamais
     * « conforme ». Rien n'est proposé pour une impossibilité (plan illisible, bloc inconnu, hors
     * volume, grille vide, lit ou porte incomplets).
     */
    static String acceptDefectsForm(ProjectView v) {
        java.util.Map<String, String> defects = com.lodygames.rpgquest.panel.building.AcceptableDefects.of(v.audit());
        if (defects.isEmpty() || !v.canWrite() || (v.idTaken() && !v.ownBuilding())) {
            return "";
        }
        BuildingPlanDraft d = v.draft();
        boolean republish = v.idTaken() && v.ownBuilding();
        StringBuilder sb = new StringBuilder("<details class=\"npc-registry\" id=\"ws-accept-defects\"><summary>")
                .append(Icons.icon("warning")).append("Enregistrer malgré les avertissements</summary>");
        sb.append("<p>Le serveur a construit cette version en entier ; ses défauts ne l'empêchent pas d'exister. "
                + "Vous pouvez l'enregistrer telle quelle pour l'examiner en jeu et la retoucher, sans appel à "
                + "l'IA. Elle restera marquée « avec avertissements », jamais « conforme ».</p><ul class=\"ws-problems\">");
        defects.forEach((code, effect) -> sb.append("<li><strong>").append(Http.esc(PlanVerdictText.of(code).text()))
                .append("</strong> <span class=\"muted\">(").append(Http.esc(code)).append(") — en jeu : ")
                .append(Http.esc(effect)).append("</span></li>"));
        sb.append("</ul><form method=\"post\" action=\"").append(BASE).append("/project\" class=\"actform\">")
                .append(hidden("_csrf", v.session().csrfToken())).append(hidden("_action", "generate"))
                .append(hidden("id", v.project().id())).append(hidden("draft", d.id()))
                .append(hidden("version", String.valueOf(d.version()))).append(hidden("plan", d.plan()))
                .append(hidden("accept_defects", com.lodygames.rpgquest.panel.building.AcceptableDefects.param(defects)));
        if (republish) {
            sb.append(hidden("republish", "yes")).append(hidden("republish_confirm", "yes"))
                    .append(hidden("expected_version", String.valueOf(v.catalog().version())));
        }
        sb.append("<div class=\"field\"><label><input type=\"checkbox\" name=\"accept_confirm\" value=\"yes\"> "
                + "J'ai lu ces défauts et leurs effets ; j'enregistre cette version exacte (")
                .append(Http.esc(d.id().substring(0, Math.min(8, d.id().length())))).append(", révision ")
                .append(d.version()).append(") avec ses avertissements")
                .append(republish ? ", à la place de la version " + v.catalog().version() + " de la bibliothèque "
                        + "(archivée ; les exemplaires posés ne changent pas)" : "")
                .append(".</label></div><div class=\"btnrow\"><button class=\"btn btn-outline-secondary\" "
                        + "type=\"submit\">").append(Icons.icon("save"))
                .append("Enregistrer avec avertissements</button></div></form>");
        sb.append("<p class=\"field-help\">Aucun bloc n'est posé : la pose reste le geste habituel depuis la "
                + "bibliothèque, avec ses contrôles d'emplacement et sa sauvegarde de restauration.</p></details>");
        return sb.toString();
    }

    /** Étape « Enregistrer » : le plan est vérifié. */
    private static String saveCard(ProjectView v) {
        StringBuilder sb = new StringBuilder();
        Optional<SaveFailure> failed = SaveFailure.of(v.generate());
        if (failed.isPresent()) {
            sb.append(saveFailure(v, failed.get()));
        } else if (v.generate().current() && v.generate().status() == Status.PENDING) {
            sb.append(Ui.banner("info", "<strong>Enregistrement en cours</strong> : le serveur écrit le "
                    + "schematic et la définition. Rechargez la page dans quelques secondes."));
        }
        BuildingPlanVerdict verdict = currentVerdict(v);
        Optional<String> divergence = BriefLevels.divergence(v.project().brief(), verdict.metrics());
        sb.append("<h2 class=\"h5\">").append(Icons.icon(divergence.isPresent() ? "warning" : "check"))
                .append(Http.esc(PlanVerdictText.headline(verdict, divergence.isPresent()))).append("</h2>");
        if (verdict.blocks() > 0) {
            // #305 : dimensions produites ET limites du projet, axe par axe, sans ambiguïté.
            BuildingProject p = v.project();
            sb.append("<p class=\"muted\">Produit : ").append(Http.esc(BuildingProject.limitsLabel(
                    verdict.sizeX(), verdict.sizeZ(), verdict.sizeY()))).append(" — limites du projet : X ≤ ")
                    .append(p.maxWidth()).append(", Z ≤ ").append(p.maxDepth()).append(", Y ≤ ")
                    .append(p.maxHeight()).append(".</p>");
        }
        divergence.ifPresent(d -> sb.append(Ui.banner("warning", Http.esc(d))));
        if (v.idTaken() && v.ownBuilding()) {
            sb.append(Ui.banner("info", "<strong>Ce projet a déjà publié « " + Http.esc(v.project().buildingId())
                    + " » dans la bibliothèque" + (v.catalog().version() > 0 ? " (version "
                    + v.catalog().version() + ")" : "") + ".</strong> Cette version peut le remplacer."));
        } else if (v.idTaken()) {
            sb.append(Ui.banner("warning", "<strong>Un bâtiment de la bibliothèque porte déjà le nom "
                    + "technique « " + Http.esc(v.project().buildingId()) + " ».</strong> Changez-le dans « Le "
                    + "bâtiment souhaité » (par exemple « " + Http.esc(v.project().buildingId())
                    + "_v2 ») : enregistrer remplacerait l'autre bâtiment, ce qui n'est jamais fait sans "
                    + "votre accord."));
        }
        if (!v.canWrite()) {
            sb.append(Ui.banner("info", "Votre rôle ne permet pas d'enregistrer dans la bibliothèque."));
        } else if (v.idTaken() && v.ownBuilding()) {
            sb.append(republishForm(v));
        } else if (!v.idTaken()) {
            sb.append(gestureForm(v, "generate", SaveFailure.of(v.generate())
                    .map(f -> f.kind() == SaveFailure.Kind.WRITE ? "Réessayer l'enregistrement"
                            : "Enregistrer dans la bibliothèque")
                    .orElse("Enregistrer dans la bibliothèque"), true));
            sb.append("<p class=\"field-help\">Le bâtiment rejoint la bibliothèque. Aucun bloc n'est posé "
                    + "dans le monde : vous choisirez ensuite un emplacement.</p>");
        }
        // #747 (24/10) : une version acceptée qui ne réalise pas toute la description se reprend aussi — les
        // écarts mesurés (✗ manquant, ⚠ ajout non demandé) partent alors comme motif.
        boolean gaps = v.repairInfo() != null && v.repairInfo().complianceLines().stream()
                .anyMatch(l -> l.startsWith("✗") || l.startsWith("⚠"));
        if (divergence.isPresent() || !verdict.warnings().isEmpty() || gaps) {
            sb.append("<details class=\"npc-registry\"").append(gaps ? " open" : "").append("><summary class=\"muted\">")
                    .append(gaps ? "Améliorer avec l'IA — la description n'est pas entièrement réalisée" : "Améliorer avec l'IA")
                    .append("</summary>").append(correctionForm(v, correctionReason(v))).append("</details>");
        }
        return sb.toString();
    }

    /**
     * L'échec du dernier enregistrement, en tête de carte (#292) : le motif exact, et l'action
     * utile. Plus de bandeau vert seul pendant que l'enregistrement a échoué.
     */
    static String saveFailure(ProjectView v, SaveFailure f) {
        StringBuilder sb = new StringBuilder();
        String what = switch (f.kind()) {
            case CONTENT -> "Le plan doit être corrigé : le serveur l'a refusé au moment de l'écrire. "
                    + "« Améliorer avec l'IA » ci-dessous envoie ce motif exact, sans commentaire à "
                    + "écrire.";
            case CONFLICT -> "VERSION_CONFLICT".equals(f.code())
                    ? "Le bâtiment a été republié entre-temps : rechargez la bibliothèque (« Rafraîchir »), "
                            + "relisez cette page, puis publiez de nouveau si c'est toujours votre intention."
                    : "PLACED_VERSION_PROTECTED".equals(f.code())
                    ? "Ce bâtiment est posé en jeu et sa version posée doit rester intacte (#644). "
                            + "Changez le nom technique dans « Le bâtiment souhaité » pour l'enregistrer "
                            + "comme un nouveau bâtiment ; rien n'est demandé à l'IA."
                    : "Un bâtiment de la bibliothèque porte déjà cet identifiant. Changez le nom "
                            + "technique dans « Le bâtiment souhaité », puis enregistrez de nouveau.";
            case WRITE -> "Échec d'écriture côté serveur (disque, WorldEdit ou bibliothèque) : le plan "
                    + "avait passé ses contrôles. Réessayez ; si l'échec persiste, vérifiez WorldEdit "
                    + "et les journaux du serveur. Rien n'est demandé à l'IA.";
        };
        sb.append(Ui.banner("err", "<strong>L'enregistrement a échoué (" + Http.esc(f.code())
                + ").</strong> Aucune définition n'a été créée.<br><span class=\"ws-failure\">"
                + Http.esc(f.message()).replace("\n", "<br>") + "</span><br>" + what));
        if (f.kind() == SaveFailure.Kind.CONTENT && v.canWrite()) {
            sb.append("<details class=\"npc-registry\" open><summary>Corriger avec l'IA</summary>")
                    .append(correctionForm(v, correctionReason(v))).append("</details>");
        }
        return sb.toString();
    }

    /** Le motif de correction proposé : ce que le serveur a dit de la version courante, en clair. */
    static String correctionReason(ProjectView v) {
        return diagnosis(v.project(), v.audit(), v.generate());
    }

    /** Voir {@link PlanDiagnosis#of} (issue #282). */
    public static String diagnosis(BuildingProject project, CheckState audit) {
        return PlanDiagnosis.of(project, audit);
    }

    /** Voir {@link PlanDiagnosis#of(BuildingProject, CheckState, CheckState)} (#292). */
    public static String diagnosis(BuildingProject project, CheckState audit, CheckState generate) {
        return PlanDiagnosis.of(project, audit, generate);
    }

    private static BuildingPlanVerdict currentVerdict(ProjectView v) {
        return verdictOf(v.audit());
    }

    /** Le verdict de la version courante, avec le message de l'action en secours. */
    static BuildingPlanVerdict verdictOf(CheckState audit) {
        return PlanDiagnosis.verdict(audit);
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

    /**
     * La carte d'un appel échoué (#747) : ce qui s'est passé, ce qui n'a pas changé, et deux gestes
     * distincts — reprendre à l'identique, ou repartir de zéro en le cochant explicitement. Aucun
     * des deux ne part sans clic.
     */
    static String failureCard(ProjectView v) {
        FailureInfo f = v.failure();
        if (f == null) {
            return "";
        }
        String kept = f.keptDraftId().isEmpty() ? "" : "V-" + f.keptDraftId().substring(0,
                Math.min(8, f.keptDraftId().length()));
        StringBuilder sb = new StringBuilder("<div class=\"card ws-failure-card\"><div class=\"card-body\">");
        sb.append("<h2 class=\"h5\">").append(Icons.icon("warning")).append(f.legacy()
                ? "Réponse inexploitable de l'IA" : "Le dernier appel à l'IA a échoué").append("</h2>");
        sb.append(Ui.banner("err", "<strong>" + Http.esc(f.label()) + "</strong>"
                + (f.detail().isEmpty() ? "" : " — " + Http.esc(f.detail()))
                + (f.when().isEmpty() ? "" : " <span class=\"muted\">(" + Http.esc(f.when()) + ")</span>")));
        sb.append("<p>").append(f.legacy() ? "Cette réponse a été rangée comme version avant le correctif du "
                + "23/10/2026 ; elle ne contient aucun plan. " : "Aucune version n'a été créée. ")
                .append(kept.isEmpty() ? "Aucune version antérieure n'a de plan."
                        : "La dernière version exploitable reste <strong>" + Http.esc(kept) + "</strong>, avec son "
                        + "diagnostic, la mémoire des tentatives et vos images.")
                .append(" La réponse brute est conservée dans le journal IA.</p>");
        if (!kept.isEmpty() && (v.draft() == null || !v.draft().id().equals(f.keptDraftId()))) {
            sb.append("<p><a href=\"").append(projectUrl(v.project().id(), f.keptDraftId())).append("\">Ouvrir ")
                    .append(Http.esc(kept)).append("</a></p>");
        }
        if (!v.canWrite() || v.providers().isEmpty()) {
            return sb.append("</div></div>").toString();
        }
        boolean improve = "correct".equals(f.gesture()) && !f.keptDraftId().isEmpty();
        sb.append("<form method=\"post\" action=\"").append(BASE).append("/project\" class=\"actform\">")
                .append(hidden("_csrf", v.session().csrfToken()))
                .append(hidden("_action", improve ? "correct" : "propose"))
                .append(hidden("id", v.project().id())).append(hidden("request_token", v.requestToken()))
                .append(hidden("retry_of", f.callId()));
        if (!f.keptDraftId().isEmpty()) {
            sb.append(hidden("draft", f.keptDraftId()));
        }
        if (improve) {
            sb.append(hidden("version", String.valueOf(f.keptVersion())))
                    .append(hidden("mode", f.mode().isEmpty() ? "auto" : f.mode()));
        } else if ("propose_lessons".equals(f.gesture())) {
            sb.append(hidden("lessons", "yes"));
        }
        sb.append(providerSelect(v, f.providerId()));
        sb.append("<p class=\"field-help\">").append(improve
                ? "Même version source (" + Http.esc(kept) + "), même nature de reprise (" + Http.esc(modeLabel(f.mode()))
                        + "), mêmes consignes ; le diagnostic, la mémoire des tentatives et les images sont relus "
                        + "et le journal dira s'ils sont identiques à ceux de l'appel échoué."
                : "Même description, même conception et mêmes images que l'appel échoué.").append("</p>");
        if (improve) {
            sb.append("<div class=\"field\"><label for=\"ws-retry-notes\">Vos consignes (reprises telles quelles)"
                    + "</label><textarea id=\"ws-retry-notes\" name=\"notes\" rows=\"3\">")
                    .append(Http.esc(f.notes())).append("</textarea></div>");
        }
        sb.append("<p class=\"field-help\">").append(Icons.icon("warning")).append("Un appel <strong>facturé"
                + "</strong>, sur ce clic seulement : rien n'est relancé automatiquement.</p>");
        sb.append("<div class=\"btnrow\"><button class=\"btn btn-primary\" type=\"submit\">")
                .append(Icons.icon("refresh")).append("Réessayer avec le même contexte</button></div></form>");
        // Repartir de zéro : séparé, replié, et confirmé par une case — jamais le geste par défaut.
        sb.append("<details class=\"npc-registry\"><summary class=\"muted\">Repartir de zéro depuis ma "
                + "description</summary><p>Un plan neuf, <strong>sans</strong> la version source ni son "
                + "diagnostic. Les versions existantes restent consultables.</p>")
                .append("<form method=\"post\" action=\"").append(BASE).append("/project\" class=\"actform\">")
                .append(hidden("_csrf", v.session().csrfToken())).append(hidden("_action", "restart"))
                .append(hidden("id", v.project().id())).append(hidden("request_token", v.requestToken()));
        if (!f.keptDraftId().isEmpty()) {
            sb.append(hidden("draft", f.keptDraftId()));
        }
        sb.append(providerSelect(v, f.providerId()));
        sb.append("<div class=\"field\"><label><input type=\"checkbox\" name=\"confirm\" value=\"yes\"> Je "
                + "confirme : repartir de zéro, <strong>un appel facturé</strong>.</label></div>")
                .append("<div class=\"btnrow\"><button class=\"btn btn-outline-secondary\" type=\"submit\">")
                .append("Repartir de zéro</button></div></form></details>");
        return sb.append("</div></div>").toString();
    }

    private static String modeLabel(String mode) {
        return switch (mode == null ? "" : mode) {
            case "repair" -> "réparation technique";
            case "improve" -> "amélioration architecturale";
            default -> "automatique";
        };
    }

    /** « Améliorer avec l'IA » : payant, motif prérempli en clair, réponse = nouvelle version. */
    private static String correctionForm(ProjectView v, String reason) {
        if (v.providers().isEmpty()) {
            return noProvider();
        }
        BuildingPlanDraft d = v.draft();
        StringBuilder sb = new StringBuilder(stagnationChoices(v));
        sb.append("<form method=\"post\" action=\"" + BASE + "/project\" class=\"actform\">");
        sb.append(hidden("_csrf", v.session().csrfToken())).append(hidden("_action", "correct"))
                .append(hidden("id", v.project().id())).append(hidden("draft", d.id()))
                .append(hidden("version", String.valueOf(d.version())))
                .append(hidden("request_token", v.requestToken()));
        sb.append(providerSelect(v, d.providerId()));
        // #282 : le diagnostic du serveur part DE LUI-MÊME avec la demande. Il est montré en
        // lecture seule ; le champ libre ne sert qu'aux consignes en plus, et il est facultatif.
        if (!reason.isBlank()) {
            sb.append("<details class=\"npc-registry\"><summary class=\"muted\">Ce que l'IA recevra "
                    + "automatiquement (diagnostic du serveur)</summary><pre class=\"ws-diagnosis\">")
                    .append(Http.esc(reason)).append("</pre></details>");
        }
        sb.append(repairInfo(v.repairInfo()));
        sb.append("<div class=\"field\"><label for=\"ws-reason\">Vos consignes en plus "
                + "<span class=\"muted\">(facultatif)</span></label>")
                .append("<textarea id=\"ws-reason\" name=\"notes\" rows=\"3\"></textarea>")
                .append("<p class=\"field-help\">").append(reason.isBlank()
                        ? "Dites ce que vous voulez changer : le serveur n'a rien relevé sur cette version."
                        : "Le diagnostic du serveur est déjà joint. Ajoutez seulement ce que vous voulez "
                                + "en plus (style, pièces, matériaux…).")
                .append("</p></div>");
        sb.append("<p class=\"field-help\">").append(Icons.icon("warning")).append("Appel <strong>facturé"
                + "</strong>. La réponse devient une nouvelle version du plan ; la version actuelle reste "
                + "disponible.</p>");
        sb.append("<div class=\"btnrow\"><button class=\"btn btn-primary\" type=\"submit\">")
                .append(Icons.icon("wrench")).append("Améliorer avec l'IA</button></div></form>");
        return sb.toString();
    }

    /**
     * #747 (23/10/2026) : après une stagnation, les stratégies proposées à la place d'une nouvelle
     * variante du même défaut — reprendre depuis la meilleure version, ou une nouvelle conception qui
     * garde les enseignements. Rien ne part sans clic.
     */
    static String stagnationChoices(ProjectView v) {
        RepairInfo info = v.repairInfo();
        if (info == null || !info.stopped()) {
            return "";
        }
        String best = "V-" + info.bestDraftId().substring(0, Math.min(8, info.bestDraftId().length()));
        StringBuilder sb = new StringBuilder(Ui.banner("warning", "<strong>" + Http.esc(info.stagnation())
                + "</strong> Une nouvelle correction automatique de cette version ne sera pas envoyée : "
                + "choisissez une stratégie."));
        sb.append("<ul class=\"ws-strategies\"><li><a class=\"btn btn-sm btn-outline-primary\" href=\"")
                .append(projectUrl(v.project().id(), info.bestDraftId())).append("\">Reprendre depuis ")
                .append(Http.esc(best)).append("</a> <span class=\"muted\">la version la plus proche du but ; "
                        + "« Améliorer avec l'IA » y repart de son diagnostic et de la mémoire.</span></li>");
        sb.append("<li><form method=\"post\" action=\"").append(BASE).append("/project\" class=\"inline\">")
                .append(hidden("_csrf", v.session().csrfToken())).append(hidden("_action", "propose"))
                .append(hidden("id", v.project().id())).append(hidden("request_token", v.requestToken()))
                .append(hidden("draft", v.draft().id())).append(hidden("lessons", "yes"))
                .append(providerSelect(v, v.draft().providerId()))
                .append("<button class=\"btn btn-sm btn-outline-primary\" type=\"submit\">Nouvelle conception avec "
                        + "les enseignements</button></form> <span class=\"muted\">un appel facturé : la "
                        + "description, les images et ce que les versions précédentes ont appris (fautes "
                        + "récurrentes, identifiants inexistants, éléments demandés), sans recopier d'ancien "
                        + "plan.</span></li>");
        sb.append("<li class=\"muted\">Ou continuer sur cette version : choisissez ci-dessous une nature de "
                + "reprise explicite.</li></ul>");
        return sb.toString();
    }

    /**
     * #747 : la mémoire des tentatives, la conformité au brief, les images et le choix du mode, dans
     * le formulaire d'amélioration. Le mode « automatique » suit la suggestion affichée.
     */
    private static String repairInfo(RepairInfo info) {
        if (info == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder("<details class=\"npc-registry\"><summary class=\"muted\">"
                + "Mémoire des tentatives et conformité à la description (envoyées avec la demande)</summary>");
        sb.append("<p class=\"field-help\">").append(Http.esc(info.memorySummary())).append("</p>");
        if (!info.regressions().isEmpty()) {
            sb.append("<p class=\"field-help\">").append(Icons.icon("warning")).append(Http.esc(info.regressions()))
                    .append("</p>");
        }
        if (!info.best().isEmpty()) {
            sb.append("<p class=\"field-help\">").append(Http.esc(info.best())).append("</p>");
        }
        if (info.complianceLines().isEmpty()) {
            sb.append("<p class=\"field-help\">Aucune exigence reconnue dans la description.</p>");
        } else {
            sb.append("<ul class=\"ws-compliance\">");
            for (String line : info.complianceLines()) {
                sb.append("<li>").append(Http.esc(line)).append("</li>");
            }
            sb.append("</ul><p class=\"field-help\">✓ et ✗ sont vérifiés sur les blocs du plan et du résultat "
                    + "compilé ; ◐ est dans le plan mais NON VÉRIFIÉ dans le résultat (version non construite) ; "
                    + "◌ ne se prouve par aucun bloc et reste à juger à l'aperçu.</p>");
        }
        if (!info.notes().isEmpty()) {
            sb.append("<p class=\"field-help\">").append(Http.esc(info.notes())).append("</p>");
        }
        sb.append("<p class=\"field-help\">").append(Http.esc(info.imageNote())).append("</p></details>");
        sb.append("<div class=\"field\"><label for=\"ws-mode\">Nature de la reprise</label>"
                + "<select id=\"ws-mode\" name=\"mode\">");
        if (!info.stopped()) {
            sb.append("<option value=\"auto\" selected>Automatique — ").append(Http.esc(info.suggestedMode()))
                    .append("</option>");
        }
        sb.append("<option value=\"repair\">Réparation technique : corriger le motif, tout garder</option>"
                        + "<option value=\"improve\"" + (info.stopped() ? " selected" : "") + ">Amélioration "
                        + "architecturale : revoir les volumes en cause, ajouter ce qui manque à la description"
                        + "</option></select>")
                .append("<p class=\"field-help\">").append(Http.esc(info.why())).append("</p></div>");
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
        return providerChoice(v, chosen) + trialFields();
    }

    /**
     * #747 : un essai isolé avec un autre modèle (et un autre plafond de sortie, utile aux modèles qui
     * raisonnent), sans toucher aux réglages enregistrés du fournisseur. Replié : vide, c'est le modèle
     * configuré qui répond.
     */
    static String trialFields() {
        return "<details class=\"npc-registry\"><summary class=\"muted\">Essai avec un autre modèle (facultatif)</summary>"
                + "<div class=\"field\"><label>Modèle pour cet appel seulement <input type=\"text\" name=\"trial_model\" "
                + "maxlength=\"64\" placeholder=\"vide = modèle configuré\" pattern=\"[A-Za-z0-9][A-Za-z0-9._:\\-]{0,63}\">"
                + "</label></div><div class=\"field\"><label>Plafond de jetons de sortie pour cet appel <input type=\"number\" "
                + "name=\"trial_max_tokens\" min=\"256\" max=\"32000\" placeholder=\"vide = réglage enregistré\"></label></div>"
                + "<p class=\"field-help\">Les réglages du fournisseur ne changent pas. Le modèle doit exister chez ce "
                + "fournisseur, et lire les images si le projet en a. Le journal IA note le modèle de l'essai et la "
                + "consommation réelle.</p></details>";
    }

    private static String providerChoice(ProjectView v, String chosen) {
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

    /**
     * L'historique de ce projet : ses seules versions (#308). Un projet neuf le dit, plutôt que
     * d'afficher des plans d'ailleurs.
     *
     * <p>#828 : chaque version porte son identifiant court copiable, son lien direct et le statut
     * réel de sa dernière vérification ; la version affichée est mise en évidence, et une recherche
     * par identifiant y mène sans ouvrir les versions une à une. Consulter ne génère, n'enregistre et
     * ne pose rien.</p>
     */
    private static String draftList(ProjectView v) {
        StringBuilder sb = new StringBuilder("<details class=\"npc-registry\" id=\"ws-history\"><summary>")
                .append(Icons.icon("history")).append("Historique de ce projet — versions du plan (")
                .append(v.drafts().size()).append(")");
        if (v.draft() != null) {
            sb.append(" — affichée : ").append(Http.esc(PlanVersionRef.label(v.draft().id())));
        }
        sb.append("</summary>");
        if (v.drafts().isEmpty()) {
            return sb.append("<p class=\"faint\">Aucune version de plan pour ce projet : il est neuf. Les "
                    + "plans d'autres projets ne s'affichent jamais ici.</p></details>").toString();
        }
        sb.append(versionSearch(v));
        sb.append("<ul class=\"ws-versions\">");
        for (BuildingPlanDraft d : v.drafts()) {
            sb.append(versionItem(v, d));
        }
        return sb.append("</ul></details>").toString();
    }

    /** Aller directement à une version par son identifiant court ou complet (#828) — une simple navigation. */
    private static String versionSearch(ProjectView v) {
        return "<form method=\"get\" action=\"" + BASE + "/project\" class=\"ws-version-search\" role=\"search\">"
                + "<input type=\"hidden\" name=\"id\" value=\"" + Http.esc(v.project().id()) + "\">"
                + "<label for=\"ws-version-q\">Aller à une version</label>"
                + "<input id=\"ws-version-q\" name=\"draft\" type=\"search\" placeholder=\"V-8776ad09\" "
                + "autocomplete=\"off\" spellcheck=\"false\" aria-describedby=\"ws-version-help\">"
                + "<button class=\"btn btn-sm btn-outline-secondary\" type=\"submit\">Afficher</button>"
                + "<p id=\"ws-version-help\" class=\"field-help\">Identifiant court (V-8776ad09) ou complet. "
                + "Afficher une version ne lance aucune IA, n'enregistre rien et ne pose rien en jeu.</p></form>";
    }

    private static String versionItem(ProjectView v, BuildingPlanDraft d) {
        boolean open = v.draft() != null && v.draft().id().equals(d.id());
        String label = PlanVersionRef.label(d.id());
        PlanVersionStatus st = PlanVersionStatus.of(v.versionAudits().get(d.id()));
        String link = projectUrl(v.project().id(), d.id());
        StringBuilder sb = new StringBuilder("<li class=\"ws-version").append(open ? " ws-version--open" : "")
                .append("\" id=\"ws-v-").append(Http.esc(shortId(d.id()))).append("\"")
                .append(open ? " aria-current=\"true\"" : "").append(">");
        sb.append("<div class=\"ws-version-head\">").append(Ui.id(label))
                .append(" <span class=\"muted\">").append(Http.esc(WHEN.format(d.updatedAt()))).append(" — ")
                .append(Http.esc(d.origin().label())).append("</span> ")
                .append(Ui.pill(st.kind().label(), st.kind().pill(), st.kind().glyph()));
        if (open) {
            sb.append(" <strong class=\"ws-version-shown\">Version affichée</strong>");
        }
        sb.append("</div><p class=\"ws-version-why\">");
        if (!st.codes().isEmpty()) {
            sb.append("<code class=\"tid\">").append(Http.esc(String.join(", ", st.codes()))).append("</code> ");
        }
        sb.append(Http.esc(st.explanation()));
        if (st.stale()) {
            sb.append(" <span class=\"faint\">(vérifiée sur le texte n°").append(st.auditVersion())
                    .append(", antérieur à l'actuel n°").append(d.version()).append(")</span>");
        }
        sb.append("</p><div class=\"btnrow\">");
        if (!open) {
            sb.append("<a class=\"btn btn-sm btn-outline-primary\" href=\"").append(link)
                    .append("\" aria-label=\"Reprendre la version ").append(Http.esc(label)).append("\">")
                    .append("Reprendre ").append(Http.esc(label)).append("</a>");
        }
        sb.append("<button class=\"btn btn-sm btn-outline-secondary\" type=\"button\" data-copy-url data-copy=\"")
                .append(link).append("\" aria-label=\"Copier le lien direct vers ").append(Http.esc(label))
                .append("\">").append(Icons.icon("copy")).append("Copier le lien</button></div></li>");
        return sb.toString();
    }

    // ---- Détails techniques ----------------------------------------------------------------

    /**
     * Ce qui sert à l'assistance et aux administrateurs, jamais au parcours principal : plan brut et
     * modifiable, résultat brut de la vérification, coupes techniques, versions, réponse du modèle,
     * et « Copier le diagnostic ».
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

    // ---- Import d'un plan d'ailleurs (#308) ----------------------------------------------------

    /**
     * Importer un plan d'un autre projet ou de l'historique global des actions : une démarche
     * distincte de l'historique du projet, repliée par défaut, avec recherche et provenance. Rien
     * n'est copié sans l'examen préalable, et la copie ne touche jamais la source.
     */
    private static String importCard(ProjectView v) {
        boolean open = v.importPreview().isPresent() || !v.importQuery().isEmpty();
        StringBuilder sb = new StringBuilder("<details class=\"npc-registry\" id=\"ws-import\"")
                .append(open ? " open" : "").append("><summary class=\"muted\">").append(Icons.icon("open"))
                .append("Importer un plan d'un autre projet ou de l'historique global</summary>")
                .append("<p class=\"field-help\">Ces plans <strong>n'appartiennent pas</strong> à ce projet. "
                        + "Importer en fait une <strong>copie</strong>, nouvelle version de ce projet : la source "
                        + "n'est ni déplacée ni modifiée, le brief et la conception de ce projet restent les "
                        + "siens. Aucun appel à l'IA, aucune facturation.</p>");
        v.importPreview().ifPresent(p -> sb.append(importPreview(v, p)));
        sb.append("<form method=\"get\" action=\"").append(BASE).append("/project#ws-import\" class=\"actform\">")
                .append(hidden("id", v.project().id()))
                .append("<div class=\"field\"><label for=\"ws-iq\">Rechercher (nom technique, nom ou identifiant "
                        + "d'action)</label><input id=\"ws-iq\" type=\"search\" name=\"iq\" value=\"")
                .append(Http.esc(v.importQuery())).append("\" spellcheck=\"false\"></div>")
                .append("<div class=\"btnrow\"><button class=\"btn btn-sm btn-outline-secondary\" type=\"submit\">")
                .append(Icons.icon("search")).append("Rechercher</button></div></form>");
        if (v.recoverable().isEmpty()) {
            sb.append("<p class=\"faint\">").append(v.importQuery().isEmpty()
                    ? "Aucun plan orphelin dans l'historique global des actions."
                    : "Aucun plan orphelin ne correspond à cette recherche.").append("</p>");
        } else {
            sb.append("<p class=\"faint\">Plans de l'historique global, rattachés à aucun projet :</p>"
                    + "<ul class=\"tight\">");
            for (AgentActionRow row : v.recoverable()) {
                String building = BuildingPlanDraft.buildingIdOf(BuildingPlanWorkbench.planOf(row).orElse(""));
                sb.append("<li>").append(building.isEmpty() ? "plan" : Ui.id(building)).append(" · ")
                        .append(Http.esc(WHEN.format(row.createdAt()))).append(" · action <a href=\"/actions/")
                        .append(Http.esc(row.id())).append("\">").append(Http.esc(shortId(row.id())))
                        .append("</a> <a class=\"btn btn-sm btn-outline-primary\" href=\"").append(importUrl(v, row.id()))
                        .append("\">Examiner avant import</a></li>");
            }
            sb.append("</ul>");
        }
        sb.append("<form method=\"get\" action=\"").append(BASE).append("/project#ws-import\" class=\"actform\">")
                .append(hidden("id", v.project().id()))
                .append("<div class=\"field\"><label for=\"ws-action\">Ou identifiant d'action (plan d'un autre "
                        + "projet, ancien brouillon sans projet…)</label>")
                .append("<input id=\"ws-action\" type=\"text\" name=\"import\" spellcheck=\"false\"></div>")
                .append("<div class=\"btnrow\"><button class=\"btn btn-sm btn-outline-secondary\" type=\"submit\">"
                        + "Examiner</button></div></form>");
        return sb.append("</details>").toString();
    }

    /** L'examen avant import : provenance, contenu, incompatibilités — puis la confirmation. */
    private static String importPreview(ProjectView v, PlanImportPreview p) {
        StringBuilder sb = new StringBuilder("<div class=\"card\"><div class=\"card-body\">")
                .append(detail("info", "Examen avant import")).append("<dl class=\"npc-dl\">");
        row(sb, "Provenance", Http.esc(p.provenance()));
        row(sb, "Action source", "<a href=\"/actions/" + Http.esc(p.actionId()) + "\">" + Http.esc(p.actionId())
                + "</a> — " + Http.esc(p.actionType()) + " — " + Http.esc(WHEN.format(p.createdAt())));
        row(sb, "Nom technique", p.buildingId().isEmpty() ? "<span class=\"faint\">absent</span>" : Ui.id(p.buildingId()));
        row(sb, "Nom", p.name().isEmpty() ? "<span class=\"faint\">absent</span>" : Http.esc(p.name()));
        row(sb, "Dimensions", Http.esc(p.dimensions()));
        row(sb, "Entrée", p.front().isEmpty() ? "<span class=\"faint\">non précisée</span>" : Http.esc(p.front()));
        row(sb, "Limites de ce projet", Http.esc(v.project().limitsLabel() + ", entrée " + v.project().front()));
        sb.append("</dl>");
        if (p.conflicts().isEmpty()) {
            sb.append("<p class=\"faint\">Aucune incompatibilité avec les limites de ce projet.</p>");
        } else {
            sb.append("<p><strong>Incompatibilités avec ce projet</strong> — l'import reste possible, la "
                    + "vérification les signalera :</p><ul class=\"tight\">");
            p.conflicts().forEach(c -> sb.append("<li>").append(Http.esc(c)).append("</li>"));
            sb.append("</ul>");
        }
        if (p.ownedHere()) {
            return sb.append("<p>Ce plan est déjà une version de ce projet : <a href=\"")
                    .append(projectUrl(v.project().id(), p.sourceDraftId()))
                    .append("\">l'ouvrir</a>.</p></div></div>").toString();
        }
        if (!p.importedDraftId().isEmpty()) {
            return sb.append("<p>Ce plan a déjà été importé dans ce projet : <a href=\"")
                    .append(projectUrl(v.project().id(), p.importedDraftId()))
                    .append("\">ouvrir la copie</a>.</p></div></div>").toString();
        }
        sb.append("<form method=\"post\" action=\"").append(BASE).append("/project\" class=\"inline\">")
                .append(hidden("_csrf", v.session().csrfToken())).append(hidden("_action", "recover"))
                .append(hidden("id", v.project().id())).append(hidden("action_id", p.actionId()))
                .append(hidden("confirm", "1"))
                .append("<div class=\"btnrow\"><button class=\"btn btn-sm btn-primary\" type=\"submit\">")
                .append(Icons.icon("open")).append("Importer comme nouvelle version de ce projet</button> ")
                .append("<a class=\"btn btn-sm btn-outline-secondary\" href=\"").append(projectUrl(v.project().id(), ""))
                .append("\">Annuler</a></div></form>");
        return sb.append("</div></div>").toString();
    }

    private static String importUrl(ProjectView v, String actionId) {
        return BASE + "/project?id=" + Http.esc(v.project().id()) + "&amp;import=" + Http.esc(actionId)
                + "#ws-import";
    }

    /** Le diagnostic à copier : identifiants, état, vérification, plan — jamais de clé ni de secret. */
    static String diagnostic(ProjectView v) {
        StringBuilder sb = new StringBuilder("Diagnostic Atelier PlugAdmin\n");
        BuildingProject p = v.project();
        sb.append("Projet : ").append(p.name()).append(" (").append(p.id()).append(")\n")
                .append("Nom technique : ").append(p.buildingId()).append(" ; entrée : ").append(p.front())
                .append(" ; max X×Z×Y ").append(p.maxWidth()).append("×").append(p.maxDepth()).append("×")
                .append(p.maxHeight()).append('\n');
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
                .append("<div>Cette action contient un plan de bâtiment complet. L'importer dans un projet "
                        + "de l'Atelier en fait une copie, après examen, et <strong>n'appelle pas l'IA</strong>.");
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
                    .append(Icons.icon("open")).append("Examiner l'import dans ce projet</button></form>");
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
