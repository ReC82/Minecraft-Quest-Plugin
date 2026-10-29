package com.lodygames.rpgquest.panel.web;

import com.lodygames.rpgquest.panel.security.Session;
import com.lodygames.rpgquest.panel.building.BuildingPlacementView;
import com.lodygames.rpgquest.panel.building.BuildingProject;
import com.lodygames.rpgquest.panel.building.BuildingSiteDirectory;
import com.lodygames.rpgquest.panel.building.BuildingSiteView;
import com.lodygames.rpgquest.panel.building.ProjectUsage;
import com.lodygames.rpgquest.panel.building.WorkshopProjectDeletion;
import com.lodygames.rpgquest.panel.http.Http;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * La corbeille de l'Atelier (issue #881, suite du 29/10) : désencombrer la liste sans perte
 * irréversible.
 *
 * <p>Deux gestes séparés. Sur la fiche, « Mettre à la corbeille » retire le projet de la liste
 * principale sans rien effacer ; la corbeille ({@code /buildings/workshop/trash}) permet de le
 * <strong>restaurer</strong> tel quel, ou de le <strong>supprimer définitivement</strong> après une
 * confirmation forte (identifiant à recopier). Les mêmes refus que #881 s'appliquent aux deux gestes
 * qui retirent : bâtiment posé, cité par un portail, publié dans la bibliothèque, usage incertain,
 * geste en cours. <strong>Pur</strong> : reçoit tout déjà calculé, le serveur revérifie à la
 * soumission.</p>
 *
 * <p>Nom du projet, nom de l'emplacement et bâtiment réellement posé sont trois choses distinctes
 * (retour propriétaire : le projet « Bibliothèque du Hub » vient de l'emplacement du même nom, qui
 * porte aujourd'hui {@code la_grande_librairie}) : l'{@link Origin origine} l'écrit en toutes lettres.</p>
 */
public final class WorkshopTrashPages {

    public static final String URL = WorkshopPages.BASE + "/trash";

    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    private WorkshopTrashPages() {
    }

    /**
     * L'emplacement dont le projet a été importé, et ce qu'il porte d'après le dernier relevé.
     *
     * @param siteId     identifiant de l'emplacement d'origine, vide si le projet n'en vient pas
     * @param siteName   son nom affiché, vide s'il n'est plus dans le relevé
     * @param occupiedBy bâtiment posé dessus, vide si libre ou inconnu
     * @param surveyed   un relevé des emplacements a été lu
     */
    public record Origin(String siteId, String siteName, String occupiedBy, int occupiedVersion, boolean surveyed) {

        public static Origin of(BuildingProject p, BuildingSiteDirectory sites) {
            String siteId = p.sourceSiteId();
            if (siteId.isEmpty()) {
                return new Origin("", "", "", 0, sites.available());
            }
            String name = sites.sites().stream().filter(s -> s.id().equals(siteId)).map(BuildingSiteView::name)
                    .findFirst().orElse("");
            BuildingPlacementView placed = sites.placements().stream().filter(pl -> pl.siteId().equals(siteId))
                    .findFirst().orElse(null);
            return new Origin(siteId, name, placed == null ? "" : placed.buildingId(),
                    placed == null ? 0 : placed.buildingVersion(), sites.available());
        }

        public boolean present() {
            return !siteId.isEmpty();
        }
    }

    /** Une ligne de la corbeille. */
    public record TrashRow(BuildingProject project, int drafts, ProjectUsage usage, Origin origin,
                           WorkshopProjectDeletion.Preview preview) {
    }

    // ================================================================================
    //  Fiche : mettre à la corbeille
    // ================================================================================

    /**
     * La zone « Mettre à la corbeille » de la fiche : l'usage, l'origine, ce qui est conservé, et le
     * formulaire seulement si le retrait est permis.
     */
    public static String trashCard(Session session, BuildingProject p, ProjectUsage usage, Origin origin,
                                   boolean canWrite) {
        StringBuilder sb = new StringBuilder("<details class=\"npc-registry ws-delete\" id=\"supprimer\">");
        sb.append("<summary>").append(Icons.icon("trash")).append("Mettre à la corbeille — ")
                .append(Http.esc(usage.usage().label())).append("</summary>");
        sb.append(usageLines(usage, origin));
        sb.append("<p><strong>Rien n'est effacé</strong> : le projet quitte la liste de l'Atelier et rejoint la "
                + "<a href=\"").append(URL).append("\">corbeille</a>, avec ses versions de plan, sa conception et ses "
                + "images. Il peut en être <strong>restauré</strong> tel quel. La suppression définitive est un "
                + "second geste, séparé, depuis la corbeille. Bibliothèque, bâtiments posés, emplacements et "
                + "portails ne sont jamais touchés.</p>");
        List<String> blockers = usage.blockers();
        if (!blockers.isEmpty()) {
            sb.append(blockersBanner("Mise à la corbeille impossible pour l'instant.", usage));
        } else if (!canWrite) {
            sb.append("<p class=\"muted\">Votre rôle ne permet pas de retirer un projet.</p>");
        } else {
            sb.append("<form method=\"post\" action=\"").append(WorkshopPages.BASE).append("/project\" class=\"actform\">")
                    .append(WorkshopPages.hidden("_csrf", session.csrfToken()))
                    .append(WorkshopPages.hidden("_action", "project_trash"))
                    .append(WorkshopPages.hidden("id", p.id()))
                    .append(WorkshopPages.hidden("project_version", String.valueOf(p.version())))
                    .append("<div class=\"btnrow\"><button class=\"btn sm secondary\" type=\"submit\">")
                    .append(Icons.icon("trash")).append("Mettre « ").append(Http.esc(p.name()))
                    .append(" » à la corbeille</button></div></form>");
        }
        return sb.append("</details>").toString();
    }

    // ================================================================================
    //  La corbeille
    // ================================================================================

    public static String page(Session session, List<TrashRow> rows, boolean canWrite, String notice, String error) {
        StringBuilder sb = new StringBuilder();
        sb.append(Ui.pageHeader("trash", "Corbeille de l'Atelier",
                "Les projets retirés de la liste. Rien n'y est effacé tant que vous ne le demandez pas : "
                        + "restaurez-les, ou supprimez-les définitivement un par un.",
                "<a class=\"btn secondary\" href=\"" + WorkshopPages.BASE + "\">" + Icons.icon("back")
                        + "Retour à l'Atelier</a>"));
        if (notice != null && !notice.isBlank()) {
            sb.append(Ui.banner("ok", Http.esc(notice)));
        }
        if (error != null && !error.isBlank()) {
            sb.append(Ui.banner("err", Http.esc(error)));
        }
        sb.append("<p class=\"field-help\">Chaque geste (mise à la corbeille, restauration, suppression "
                + "définitive, refus) est noté dans l'<a href=\"/actions?status=panel&amp;domain=all\">Historique des "
                + "actions</a>, onglet « Opérations du panel ».</p>");
        if (rows.isEmpty()) {
            return sb.append(Ui.empty("trash", "La corbeille est vide.")).toString();
        }
        sb.append("<p class=\"count-note\">").append(rows.size()).append(" projet(s) dans la corbeille</p>");
        for (TrashRow row : rows) {
            sb.append(trashItem(session, row, canWrite));
        }
        return sb.toString();
    }

    private static String trashItem(Session session, TrashRow row, boolean canWrite) {
        BuildingProject p = row.project();
        WorkshopProjectDeletion.Preview pv = row.preview();
        StringBuilder sb = new StringBuilder("<div class=\"card ws-trash\" id=\"projet-")
                .append(Http.esc(p.id())).append("\"><div class=\"card-body\">");
        sb.append("<p class=\"ws-card-title\"><strong>").append(Http.esc(p.name())).append("</strong> ")
                .append(Ui.id(p.buildingId())).append("</p>");
        sb.append("<p class=\"muted small\">Mis à la corbeille le ").append(Http.esc(WHEN.format(p.updatedAt())))
                .append(" par ").append(Http.esc(p.updatedBy() == null ? "?" : p.updatedBy()))
                .append(" · ").append(row.drafts()).append(" version(s) de plan</p>");
        sb.append(usageLines(row.usage(), row.origin()));
        if (!canWrite) {
            return sb.append("<p class=\"muted\">Votre rôle ne permet pas de restaurer ni de supprimer un projet.</p>")
                    .append("</div></div>").toString();
        }
        sb.append("<form method=\"post\" action=\"").append(URL).append("\" class=\"actform inline\">")
                .append(fields(session, p, "project_restore"))
                .append("<button class=\"btn sm\" type=\"submit\">").append(Icons.icon("back"))
                .append("Restaurer dans l'Atelier</button></form>");

        sb.append("<details class=\"ws-purge\"><summary>Supprimer définitivement…</summary>");
        sb.append("<p><strong>Serait effacé sans retour</strong> (panel uniquement) : la fiche du projet, ")
                .append(pv.versions()).append(" version(s) de plan et ").append(pv.revisions())
                .append(" révision(s), ").append(pv.designs()).append(" conception(s) détaillée(s), ")
                .append(pv.runs()).append(" conception(s) automatique(s), ").append(pv.images())
                .append(" image(s) de référence").append(pv.images() > 0 ? " (" + kib(pv.imageBytes()) + ")" : "")
                .append(".</p>");
        sb.append("<p><strong>Conservé</strong> : le bâtiment de la bibliothèque et ses versions archivées, les "
                + "bâtiments posés, les emplacements et leurs sauvegardes de terrain, les portails, l'historique "
                + "des actions de l'agent (un plan y reste importable), le journal IA et le journal d'audit. "
                + "Aucun bloc du monde n'est touché.</p>");
        if (!row.usage().deletable()) {
            sb.append(blockersBanner("Suppression définitive impossible pour l'instant.", row.usage()));
        } else {
            sb.append("<form method=\"post\" action=\"").append(URL).append("\" class=\"actform\">")
                    .append(fields(session, p, "project_delete"))
                    .append("<label class=\"form-label\">Pour confirmer, recopiez l'identifiant <code>")
                    .append(Http.esc(p.buildingId())).append("</code><input class=\"form-control\" name=\"confirm_id\" "
                            + "autocomplete=\"off\" required></label>")
                    .append("<label class=\"form-check\"><input class=\"form-check-input\" type=\"checkbox\" "
                            + "name=\"confirm\" value=\"yes\" required> Je supprime définitivement le projet « ")
                    .append(Http.esc(p.name())).append(" » et ses versions de plan. Cette action est "
                            + "irréversible : aucune restauration ne sera possible.</label>")
                    .append("<div class=\"btnrow\"><button class=\"btn sm danger\" type=\"submit\">")
                    .append(Icons.icon("trash")).append("Supprimer définitivement</button></div></form>");
        }
        return sb.append("</details></div></div>").toString();
    }

    private static String fields(Session session, BuildingProject p, String action) {
        return WorkshopPages.hidden("_csrf", session.csrfToken()) + WorkshopPages.hidden("_action", action)
                + WorkshopPages.hidden("id", p.id())
                + WorkshopPages.hidden("project_version", String.valueOf(p.version()));
    }

    /** L'usage en jeu, où, et l'emplacement d'origine — jamais confondus. */
    static String usageLines(ProjectUsage usage, Origin origin) {
        StringBuilder sb = new StringBuilder("<p>").append(WorkshopPages.usagePill(usage)).append(" ")
                .append(Ui.badge(usage.catalog().label())).append("</p>");
        if (!usage.sites().isEmpty() || !usage.portals().isEmpty()) {
            List<String> where = new ArrayList<>();
            usage.sites().forEach(s -> where.add("emplacement " + Ui.id(s)));
            usage.portals().forEach(s -> where.add("portail " + Ui.id(s)));
            sb.append("<p>Bâtiment posé ou cité : ").append(String.join(", ", where)).append(" — <a href=\"")
                    .append(usage.sites().isEmpty() ? "/portals" : "/buildings/sites").append("\">Voir où utilisé</a></p>");
        }
        if (origin.present()) {
            sb.append("<p class=\"ws-origin\">").append(Icons.icon("world")).append("Importé de l'emplacement ")
                    .append(origin.siteName().isEmpty() ? "" : "« " + MiniText.html(origin.siteName()) + " » ")
                    .append(Ui.id(origin.siteId())).append(" : ");
            if (!origin.surveyed()) {
                sb.append("contenu actuel inconnu (aucun relevé des emplacements).");
            } else if (origin.occupiedBy().isEmpty()) {
                sb.append(origin.siteName().isEmpty() ? "absent du dernier relevé." : "libre au dernier relevé.");
            } else {
                sb.append("porte aujourd'hui le bâtiment ").append(Ui.id(origin.occupiedBy()))
                        .append(origin.occupiedVersion() > 0 ? " v" + origin.occupiedVersion() : "");
            }
            sb.append(" Le nom de l'emplacement, celui du projet et le bâtiment posé sont distincts : retirer ce "
                    + "projet ne touche pas l'emplacement.</p>");
        }
        return sb.toString();
    }

    private static String blockersBanner(String title, ProjectUsage usage) {
        return Ui.banner("warning", "<strong>" + Http.esc(title) + "</strong><ul class=\"tight\">"
                + usage.blockers().stream().map(b -> "<li>" + Http.esc(b) + "</li>").reduce("", String::concat)
                + "</ul>" + (usage.catalog().inLibrary() ? "<a href=\"/buildings/library\">Ouvrir la Bibliothèque</a>" : ""));
    }

    private static String kib(long bytes) {
        return bytes < 1024 ? bytes + " o" : (bytes / 1024) + " Kio";
    }
}
