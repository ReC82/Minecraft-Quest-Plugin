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
import com.lodygames.rpgquest.panel.building.BuildingSiteView;
import com.lodygames.rpgquest.panel.http.Http;
import com.lodygames.rpgquest.panel.security.Session;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Génération d'un bâtiment par IA depuis la fiche d'un emplacement (issues #94 et #256).
 *
 * <h2>Le plan est un brouillon de travail, pas un écran</h2>
 *
 * <p>Jusqu'au #256, le plan proposé par l'IA n'existait que dans le HTML de la réponse : un clic
 * sur « Auditer » quittait la page, et le plan — déjà payé — n'était plus accessible depuis le
 * parcours. Désormais chaque proposition devient un <strong>brouillon</strong> enregistré dans la
 * base du panel, rattaché à son emplacement, et cette page est l'endroit où on le reprend : son
 * adresse ({@code ?site=…&draft=…}) suffit à le retrouver après un audit, une navigation, une
 * fermeture du navigateur, une reconnexion ou un redémarrage du panel.</p>
 *
 * <h2>Deux gestes payants, et seulement deux</h2>
 *
 * <p>« Demander un plan » et « Demander une correction » appellent le modèle. Tous les autres
 * boutons — reprendre, enregistrer, auditer, prévisualiser, générer, récupérer — n'appellent
 * <strong>jamais</strong> l'IA. Les deux formulaires payants portent un jeton à usage unique : un
 * double clic, un rechargement ou un « renvoyer le formulaire » mène au plan déjà obtenu.</p>
 *
 * <h2>Le plan est montré brut, et c'est un choix</h2>
 *
 * <p>Le panel n'embarque aucune bibliothèque YAML et ne cherche pas à juger le plan. Le serveur
 * est le validateur autoritaire : lui seul connaît les bornes du moteur et le vocabulaire des
 * instructions. Afficher ici un « plan valide » que le serveur refuserait ensuite serait pire que
 * de ne rien dire.</p>
 */
public final class BuildingPlanPages {

    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    private BuildingPlanPages() {
    }

    /**
     * Tout ce que l'écran affiche, déjà relevé.
     *
     * @param draft          le brouillon ouvert, ou {@code null}
     * @param audit          dernier audit du brouillon ouvert
     * @param preview        dernier aperçu du brouillon ouvert
     * @param generate       dernière génération du brouillon ouvert
     * @param draftSummaries pour chaque brouillon de la liste, une courte phrase d'état
     * @param recoverable    actions portant un plan qu'aucun brouillon ne contient encore
     * @param requestToken   jeton à usage unique des formulaires payants de cet affichage
     * @param conflictText   texte soumis mais refusé pour cause de version périmée — rendu tel
     *                       quel pour que rien ne soit perdu
     */
    public record View(Session session, String agentId, BuildingSiteView site,
                       List<AiProvider> providers, boolean canWrite,
                       List<BuildingPlanDraft> drafts, Map<String, String> draftSummaries,
                       BuildingPlanDraft draft, CheckState audit, CheckState preview,
                       CheckState generate, List<Revision> revisions,
                       List<AgentActionRow> recoverable, String requestToken, String notice,
                       String error, String conflictText) {
    }

    /** L'adresse qui rouvre exactement ce brouillon. */
    public static String draftUrl(String siteId, String draftId) {
        return "/buildings/plan?site=" + Http.esc(siteId) + "&amp;draft=" + Http.esc(draftId);
    }

    /** L'écran complet. */
    public static String render(View v) {
        StringBuilder sb = new StringBuilder();
        BuildingSiteView site = v.site();
        sb.append(Ui.pageHeader("box", "Générer un bâtiment avec l'IA",
                "Le brief de l'emplacement est envoyé à l'IA, qui répond par un plan. Chaque plan "
                        + "est conservé comme brouillon : vous pouvez le reprendre, l'auditer, le "
                        + "prévisualiser et le générer quand vous voulez.",
                ""));
        sb.append("<p><a class=\"btn btn-sm btn-outline-secondary\" href=\"/buildings/sites?agent=")
                .append(Http.esc(v.agentId())).append("&amp;focus=").append(Http.esc(site.id()))
                .append("\">").append(Icons.icon("open")).append("Retour à l'emplacement</a></p>");

        if (v.notice() != null && !v.notice().isBlank()) {
            sb.append(Ui.banner("ok", Http.esc(v.notice())));
        }
        if (v.error() != null && !v.error().isBlank()) {
            sb.append(Ui.banner("err", Http.esc(v.error())));
        }

        // ---- L'emplacement, et ce que l'IA en saura -----------------------------------------
        sb.append(detail("box", "Emplacement visé"));
        sb.append("<dl class=\"npc-dl\">");
        row(sb, "Emplacement", MiniText.html(site.name()) + " " + Ui.id(site.id()));
        row(sb, "Monde et position",
                "<code class=\"tid\">" + Http.esc(site.world()) + "</code> "
                        + Http.esc(site.positionLabel()));
        row(sb, "Façade", Http.esc(site.facingLabel())
                + "<div class=\"faint\">Transmise à l'IA : le plan déclarera cette façade, pour "
                + "que le bâtiment ne soit pas posé de travers.</div>");
        row(sb, "État", Http.esc(site.statusLabel()));
        sb.append("</dl>");

        if (!site.hasAiBrief()) {
            sb.append("<div class=\"alert alert-warning npc-alert\">").append(Icons.icon("warning"))
                    .append("<div><strong>Aucun brief architectural sur cet emplacement.</strong> "
                            + "Il n'y a rien à générer : l'IA n'aurait que le nom et l'orientation. "
                            + "Rédiger le brief depuis la fiche de l'emplacement, section "
                            + "« Modifier la fiche ».</div></div>");
        } else {
            sb.append("<details class=\"npc-registry\"").append(v.draft() == null ? " open" : "")
                    .append("><summary class=\"muted\">Brief envoyé à l'IA</summary>")
                    .append("<p class=\"brief-text\">").append(Http.esc(site.aiBrief()))
                    .append("</p></details>");
        }

        sb.append(draftList(v));
        if (v.draft() != null) {
            sb.append(draftPanel(v));
        }
        sb.append(recoveryCard(v));
        if (site.hasAiBrief()) {
            sb.append(proposeCard(v));
        }
        return sb.toString();
    }

    // ---- Brouillons ---------------------------------------------------------------------------

    private static String draftList(View v) {
        StringBuilder sb = new StringBuilder();
        sb.append(detail("history", "Plans de cet emplacement"));
        if (v.drafts().isEmpty()) {
            sb.append("<p class=\"faint\">Aucun plan conservé pour cet emplacement. Un plan "
                    + "demandé à l'IA, ou récupéré depuis une action existante, apparaîtra ici et "
                    + "pourra être repris à tout moment.</p>");
            return sb.toString();
        }
        sb.append("<ul class=\"tight\">");
        for (BuildingPlanDraft d : v.drafts()) {
            boolean open = v.draft() != null && v.draft().id().equals(d.id());
            sb.append("<li>");
            sb.append(open ? "<strong>" : "")
                    .append(d.buildingId().isEmpty() ? "plan sans identifiant"
                            : "<code class=\"tid\">" + Http.esc(d.buildingId()) + "</code>")
                    .append(" — version ").append(d.version()).append(" · ")
                    .append(Http.esc(d.origin().label())).append(" · ")
                    .append(Http.esc(WHEN.format(d.updatedAt())))
                    .append(open ? "</strong>" : "");
            String summary = v.draftSummaries().getOrDefault(d.id(), "");
            if (!summary.isEmpty()) {
                sb.append(" <span class=\"faint\">").append(Http.esc(summary)).append("</span>");
            }
            if (open) {
                sb.append(" <span class=\"faint\">(ouvert ci-dessous)</span>");
            } else {
                sb.append(" <a class=\"btn btn-sm btn-outline-primary\" href=\"")
                        .append(draftUrl(d.siteId(), d.id())).append("#brouillon\">")
                        .append(Icons.icon("open")).append("Reprendre ce plan</a>");
            }
            sb.append("</li>");
        }
        sb.append("</ul>");
        return sb.toString();
    }

    private static String draftPanel(View v) {
        BuildingPlanDraft d = v.draft();
        StringBuilder sb = new StringBuilder();
        sb.append("<div id=\"brouillon\"></div>");
        sb.append(detail("wrench", "Plan en cours — version " + d.version()));
        sb.append("<dl class=\"npc-dl\">");
        row(sb, "Brouillon", Ui.id(d.id()) + " <span class=\"faint\">adresse stable : "
                + "<a href=\"" + draftUrl(d.siteId(), d.id()) + "#brouillon\">lien direct</a></span>");
        row(sb, "Origine", Http.esc(d.origin().label())
                + (d.sourceActionId().isEmpty() ? ""
                        : " — action <a href=\"/actions/" + Http.esc(d.sourceActionId()) + "\">"
                                + Http.esc(d.sourceActionId()) + "</a>, <strong>sans appel à "
                                + "l'IA</strong>")
                + (d.parentId().isEmpty() ? ""
                        : " — corrige le brouillon <a href=\"" + draftUrl(d.siteId(), d.parentId())
                                + "#brouillon\">" + Http.esc(shortId(d.parentId())) + "</a>"));
        if (!d.providerLabel().isEmpty()) {
            row(sb, "Fournisseur", Http.esc(d.providerLabel())
                    + (d.model().isEmpty() ? "" : " <code class=\"tid\">" + Http.esc(d.model())
                            + "</code>"));
        }
        if (!d.usage().isEmpty()) {
            row(sb, "Consommation", Http.esc(d.usage()));
        }
        row(sb, "Créé", Http.esc(WHEN.format(d.createdAt())) + " par " + Http.esc(d.createdBy()));
        row(sb, "Modifié", Http.esc(WHEN.format(d.updatedAt())) + " par " + Http.esc(d.updatedBy()));
        row(sb, "Étape", stage(v));
        sb.append("</dl>");

        if (!d.extractionNote().isEmpty()) {
            sb.append("<p class=\"form-text\">").append(Icons.icon("info"))
                    .append(Http.esc(d.extractionNote())).append("</p>");
        }
        if (!d.hasPlan()) {
            sb.append(Ui.banner("warn", "<strong>Aucun plan exploitable dans la réponse du "
                    + "modèle.</strong> La réponse brute est conservée plus bas : vous pouvez en "
                    + "recopier le plan dans le champ, ou demander une correction."));
        }

        sb.append(auditSection(v));
        sb.append(previewSection(v));
        sb.append(generationSection(v));
        sb.append(planForm(v));
        sb.append(correctionCard(v));
        sb.append(revisionsCard(v));
        sb.append(rawResponse(d));
        sb.append(archiveForm(v));
        return sb.toString();
    }

    /** Où en est le brouillon, en une phrase : c'est ce qui dit quoi faire ensuite. */
    private static String stage(View v) {
        CheckState generate = v.generate();
        if (generate.current() && generate.status() == Status.DONE
                && generate.row().map(r -> "SUCCESS".equals(r.resultStatus())).orElse(false)) {
            return "Généré — le bâtiment est dans la bibliothèque. Aucun bloc n'a été posé.";
        }
        CheckState audit = v.audit();
        if (!audit.current()) {
            return audit.stale()
                    ? "Modifié depuis le dernier audit — à <strong>auditer</strong> de nouveau."
                    : "Pas encore audité — prochaine étape : <strong>Auditer</strong>.";
        }
        if (audit.status() == Status.PENDING) {
            return "Audit en cours sur le serveur.";
        }
        BuildingPlanVerdict verdict = BuildingPlanVerdict.from(audit.details());
        if (!verdict.available()) {
            return "Audit terminé sans verdict lisible ("
                    + Http.esc(audit.row().map(AgentActionRow::resultStatus).orElse("?")) + ").";
        }
        if (!verdict.accepted()) {
            return "Refusé par l'audit — <strong>corriger</strong> le plan ou demander une "
                    + "correction.";
        }
        boolean divergent = BriefLevels.divergence(v.site().aiBrief(), verdict.metrics()).isPresent();
        return divergent
                ? "Conforme au contrat, mais <strong>en écart avec le brief</strong> — voir "
                        + "l'audit ci-dessous."
                : "Conforme — <strong>prévisualiser</strong> puis <strong>générer</strong>.";
    }

    private static String auditSection(View v) {
        CheckState audit = v.audit();
        StringBuilder sb = new StringBuilder();
        sb.append(detail("search", "Audit du plan"));
        if (audit.status() == Status.NONE) {
            sb.append("<p class=\"faint\">Pas encore audité. Le bouton « Auditer » ci-dessous "
                    + "construit le plan en mémoire sur le serveur et mesure le résultat ; il "
                    + "n'écrit rien.</p>");
            return sb.toString();
        }
        sb.append(staleNote(audit, v.draft(), "Ce verdict"));
        if (audit.status() == Status.PENDING) {
            sb.append(pendingNote(v, audit, "L'audit"));
            return sb.toString();
        }
        BuildingPlanVerdict verdict = BuildingPlanVerdict.from(audit.details());
        if (!verdict.available()) {
            sb.append(Ui.banner("warn", "L'audit s'est terminé sans verdict lisible ("
                    + Http.esc(audit.row().map(AgentActionRow::resultStatus).orElse("?")) + ") : "
                    + Http.esc(audit.row().map(AgentActionRow::resultMessage).orElse(""))));
            return sb.toString();
        }
        Optional<String> divergence = BriefLevels.divergence(v.site().aiBrief(), verdict.metrics());
        sb.append(verdictCard(verdict, divergence, audit.row().map(AgentActionRow::id).orElse("")));
        return sb.toString();
    }

    private static String previewSection(View v) {
        CheckState preview = v.preview();
        if (preview.status() == Status.NONE) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(detail("box", "Aperçu 2D"));
        sb.append(staleNote(preview, v.draft(), "Cet aperçu"));
        if (preview.status() == Status.PENDING) {
            sb.append(pendingNote(v, preview, "L'aperçu"));
            return sb.toString();
        }
        BuildingPlanPreview drawn = BuildingPlanPreview.from(preview.details());
        if (!drawn.available() || !drawn.hasSlices()) {
            sb.append(Ui.banner("warn", "Le serveur n'a pas rendu de coupes ("
                    + Http.esc(preview.row().map(AgentActionRow::resultStatus).orElse("?")) + ") : "
                    + Http.esc(preview.row().map(AgentActionRow::resultMessage).orElse(""))));
            return sb.toString();
        }
        sb.append(previewCard(drawn));
        return sb.toString();
    }

    private static String generationSection(View v) {
        CheckState generate = v.generate();
        if (generate.status() == Status.NONE) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(detail("save", "Génération"));
        sb.append(staleNote(generate, v.draft(), "Cette génération"));
        if (generate.status() == Status.PENDING) {
            sb.append(pendingNote(v, generate, "La génération"));
            return sb.toString();
        }
        AgentActionRow row = generate.row().orElseThrow();
        boolean ok = "SUCCESS".equals(row.resultStatus());
        sb.append(Ui.banner(ok ? "ok" : "err", (ok ? "<strong>Bâtiment généré.</strong> "
                : "<strong>Génération refusée.</strong> ")
                + Http.esc(row.resultMessage() == null ? "" : row.resultMessage())
                + (ok ? " <a href=\"/buildings/library\">Voir la bibliothèque</a>. La pose reste "
                        + "une opération distincte, depuis la fiche de l'emplacement." : "")
                + " <span class=\"faint\">Action <a href=\"/actions/" + Http.esc(row.id()) + "\">"
                + Http.esc(shortId(row.id())) + "</a></span>"));
        return sb.toString();
    }

    /**
     * Le plan et les quatre gestes qui n'appellent pas l'IA, dans un seul formulaire.
     *
     * <p>Un seul formulaire, parce que les quatre gestes portent sur le texte du champ : une
     * modification non enregistrée est d'abord sauvegardée (nouvelle version), puis envoyée. Ainsi
     * on audite toujours ce qu'on a sous les yeux, et ce qu'on a audité est toujours conservé.</p>
     */
    private static String planForm(View v) {
        BuildingPlanDraft d = v.draft();
        StringBuilder sb = new StringBuilder();
        sb.append(detail("edit", "Plan (modifiable)"));
        if (v.conflictText() != null) {
            sb.append(Ui.banner("warn", "<strong>Vos modifications n'ont pas été enregistrées</strong> "
                    + ": le brouillon a changé entre-temps (il est maintenant en version "
                    + d.version() + "). Votre texte est conservé ci-dessous ; le champ du plan "
                    + "montre la version courante."));
            sb.append("<div class=\"field\"><label for=\"bp-conflict\">Votre texte non enregistré"
                    + "</label><textarea id=\"bp-conflict\" rows=\"10\" spellcheck=\"false\" "
                    + "readonly>").append(Http.esc(v.conflictText())).append("</textarea></div>");
        }
        sb.append("<form method=\"post\" action=\"/buildings/plan\" class=\"actform\">");
        sb.append(hidden("_csrf", v.session().csrfToken()));
        sb.append(hidden("site", d.siteId()));
        sb.append(hidden("draft", d.id()));
        sb.append(hidden("version", String.valueOf(d.version())));
        sb.append("<div class=\"field\"><label for=\"bp-plan\">Plan — version ").append(d.version())
                .append("</label>");
        sb.append("<textarea id=\"bp-plan\" name=\"plan\" rows=\"22\" data-charcount=\"64000\" "
                + "spellcheck=\"false\">").append(Http.esc(d.plan())).append("</textarea>");
        sb.append(charCount("bp-plan", d.plan().length(), 64000));
        sb.append("<p class=\"field-help\">Une modification du champ est enregistrée comme une "
                + "<strong>nouvelle version</strong> avant d'être auditée, prévisualisée ou "
                + "générée ; les versions précédentes restent consultables plus bas. "
                + "<strong>Aucun de ces boutons n'appelle l'IA.</strong></p></div>");

        sb.append("<div class=\"btnrow\">");
        sb.append("<button class=\"btn btn-sm btn-outline-secondary\" type=\"submit\" "
                + "name=\"_action\" value=\"save\">").append(Icons.icon("save"))
                .append("Enregistrer</button>");
        sb.append("<button class=\"btn btn-sm ")
                .append(v.audit().current() ? "btn-outline-secondary" : "btn-primary")
                .append("\" type=\"submit\" name=\"_action\" value=\"audit\">")
                .append(Icons.icon("search")).append("Auditer</button>");
        sb.append("<button class=\"btn btn-sm btn-outline-secondary\" type=\"submit\" "
                + "name=\"_action\" value=\"preview\">").append(Icons.icon("box"))
                .append("Prévisualiser</button>");
        sb.append("</div>");
        sb.append("<p class=\"field-help\">« Auditer » et « Prévisualiser » construisent le plan "
                + "<strong>en mémoire</strong> sur le serveur : aucun fichier écrit, bibliothèque "
                + "intacte. Le résultat s'affiche sur cette page, rattaché à cette version.</p>");

        BuildingPlanVerdict verdict = v.audit().current() && v.audit().status() == Status.DONE
                ? BuildingPlanVerdict.from(v.audit().details()) : BuildingPlanVerdict.none();
        if (verdict.exists() && !verdict.buildingId().isEmpty()) {
            sb.append(Ui.banner("warn", "<strong>L'identifiant <code class=\"tid\">"
                    + Http.esc(verdict.buildingId()) + "</code> existe déjà dans la "
                    + "bibliothèque.</strong> Générer avec « Remplacer » écraserait ce bâtiment. "
                    + "Pour le conserver, changez la ligne <code>id:</code> du plan (par exemple "
                    + "<code>" + Http.esc(verdict.buildingId()) + "_v2</code>), puis auditez."));
        }
        if (v.canWrite()) {
            sb.append("<label class=\"inline confirm\"><input type=\"checkbox\" name=\"replace\" "
                    + "value=\"true\"> Remplacer un bâtiment existant portant le même identifiant"
                    + "</label>");
            sb.append("<p class=\"field-help\">Décoché, la génération est refusée si l'identifiant "
                    + "est déjà pris — un bâtiment existant ne disparaît pas par accident.</p>");
            boolean ready = verdict.available() && verdict.accepted();
            sb.append("<div class=\"btnrow\"><button class=\"btn btn-sm ")
                    .append(ready ? "btn-primary" : "btn-outline-primary")
                    .append("\" type=\"submit\" name=\"_action\" value=\"generate\">")
                    .append(Icons.icon("save")).append("Générer le bâtiment</button></div>");
            sb.append("<p class=\"field-help\">Écrit un fichier <code>.schem</code> et une "
                    + "définition sur le serveur, et ajoute le bâtiment à la bibliothèque. "
                    + "<strong>Aucun bloc n'est posé dans le monde</strong> : la pose reste une "
                    + "opération distincte, depuis la fiche de l'emplacement."
                    + (ready ? "" : " Le serveur refait l'audit avant d'écrire : un plan refusé "
                            + "n'écrit rien.") + "</p>");
        } else {
            sb.append("<p class=\"field-help\">").append(Icons.icon("warning"))
                    .append("Droit d'écriture des bâtiments requis pour lancer la génération.</p>");
        }
        sb.append("</form>");
        return sb.toString();
    }

    /**
     * La demande de correction : le seul geste payant qui porte sur un brouillon existant.
     *
     * <p>Le motif est prérempli avec ce que le serveur a réellement dit — refus, avertissements,
     * écart avec le brief — et reste modifiable. Le résultat devient un <strong>nouveau</strong>
     * brouillon : celui-ci n'est jamais écrasé par une réponse du modèle.</p>
     */
    private static String correctionCard(View v) {
        BuildingPlanDraft d = v.draft();
        if (!v.site().hasAiBrief()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        String reason = correctionReason(v);
        boolean needed = !reason.isEmpty();
        sb.append("<details class=\"npc-registry\"").append(needed ? " open" : "")
                .append("><summary class=\"muted\">").append(Icons.icon("wrench"))
                .append("Demander une correction à l'IA — appel facturé</summary>");
        if (v.providers().isEmpty()) {
            sb.append(noProvider());
            return sb.append("</details>").toString();
        }
        sb.append("<form method=\"post\" action=\"/buildings/plan\" class=\"actform\">");
        sb.append(hidden("_csrf", v.session().csrfToken()));
        sb.append(hidden("_action", "correct"));
        sb.append(hidden("site", d.siteId()));
        sb.append(hidden("draft", d.id()));
        sb.append(hidden("version", String.valueOf(d.version())));
        sb.append(hidden("request_token", v.requestToken()));
        sb.append(providerSelect(v, d.providerId()));
        sb.append("<div class=\"field\"><label for=\"bp-reason\">Ce qu'il faut corriger</label>");
        sb.append("<textarea id=\"bp-reason\" name=\"reason\" rows=\"6\">")
                .append(Http.esc(reason)).append("</textarea>");
        sb.append("<p class=\"field-help\">Prérempli avec le dernier verdict du serveur sur cette "
                + "version. Le modèle reçoit ce motif, les mesures de la grille et le plan de la "
                + "version " + d.version() + ".</p></div>");
        sb.append(hidden("max_width", String.valueOf(d.maxWidth())));
        sb.append(hidden("max_height", String.valueOf(d.maxHeight())));
        sb.append("<p class=\"field-help\">").append(Icons.icon("warning"))
                .append("Un <strong>appel facturé</strong>. La réponse devient un nouveau "
                        + "brouillon ; celui-ci reste intact.</p>");
        sb.append("<div class=\"btnrow\"><button class=\"btn btn-sm btn-outline-primary\" "
                + "type=\"submit\">").append(Icons.icon("wrench"))
                .append("Demander une correction</button></div></form>");
        return sb.append("</details>").toString();
    }

    /** Le motif de correction à proposer : ce que le serveur a dit de la version courante. */
    static String correctionReason(View v) {
        CheckState audit = v.audit();
        if (!audit.current() || audit.status() != Status.DONE) {
            return "";
        }
        BuildingPlanVerdict verdict = BuildingPlanVerdict.from(audit.details());
        if (!verdict.available()) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        if (!verdict.accepted()) {
            lines.add(verdict.message());
        }
        BriefLevels.divergence(v.site().aiBrief(), verdict.metrics()).ifPresent(lines::add);
        lines.addAll(verdict.warnings());
        return String.join("\n", lines).trim();
    }

    private static String revisionsCard(View v) {
        if (v.revisions().size() <= 1) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("<details class=\"npc-registry\"><summary class=\"muted\">")
                .append(Icons.icon("history")).append("Versions précédentes (")
                .append(v.revisions().size() - 1).append(")</summary>");
        for (Revision r : v.revisions()) {
            if (r.version() == v.draft().version()) {
                continue;
            }
            sb.append("<details class=\"npc-registry\"><summary class=\"muted\">Version ")
                    .append(r.version()).append(" — ").append(Http.esc(WHEN.format(r.createdAt())))
                    .append(" par ").append(Http.esc(r.createdBy())).append("</summary>")
                    .append("<pre class=\"rpgq-code\">").append(Http.esc(r.plan()))
                    .append("</pre></details>");
        }
        return sb.append("</details>").toString();
    }

    private static String archiveForm(View v) {
        BuildingPlanDraft d = v.draft();
        return "<form method=\"post\" action=\"/buildings/plan\" class=\"actform\">"
                + hidden("_csrf", v.session().csrfToken()) + hidden("_action", "archive")
                + hidden("site", d.siteId()) + hidden("draft", d.id())
                + "<div class=\"btnrow\"><button class=\"btn btn-sm btn-outline-secondary\" "
                + "type=\"submit\">" + Icons.icon("trash") + "Retirer ce plan de la liste</button>"
                + "</div><p class=\"field-help\">Le brouillon est archivé, pas effacé : ses "
                + "versions et ses audits restent dans la base du panel.</p></form>";
    }

    // ---- Récupération -------------------------------------------------------------------------

    /**
     * Reprendre un plan déjà envoyé au serveur, <strong>sans appeler l'IA</strong>.
     *
     * <p>Un audit, un aperçu ou une génération transporte le plan complet dans ses paramètres :
     * c'est exactement le plan payé. Le récupérer coûte une lecture en base.</p>
     */
    private static String recoveryCard(View v) {
        StringBuilder sb = new StringBuilder();
        sb.append("<details class=\"npc-registry\"").append(v.drafts().isEmpty()
                        && !v.recoverable().isEmpty() ? " open" : "")
                .append("><summary class=\"muted\">").append(Icons.icon("history"))
                .append("Récupérer un plan déjà audité, prévisualisé ou généré — sans appel à l'IA"
                        + "</summary>");
        if (v.recoverable().isEmpty()) {
            sb.append("<p class=\"faint\">Aucune action récente ne porte un plan qui ne soit pas "
                    + "déjà dans un brouillon.</p>");
        } else {
            sb.append("<ul class=\"tight\">");
            for (AgentActionRow row : v.recoverable()) {
                String plan = BuildingPlanWorkbench.planOf(row).orElse("");
                String building = BuildingPlanDraft.buildingIdOf(plan);
                sb.append("<li><form method=\"post\" action=\"/buildings/plan\" class=\"inline\">")
                        .append(hidden("_csrf", v.session().csrfToken()))
                        .append(hidden("_action", "recover"))
                        .append(hidden("site", v.site().id()))
                        .append(hidden("action_id", row.id()))
                        .append("<code class=\"tid\">").append(Http.esc(row.type())).append("</code> ")
                        .append(building.isEmpty() ? "" : "plan <code class=\"tid\">"
                                + Http.esc(building) + "</code> ")
                        .append("· ").append(Http.esc(WHEN.format(row.createdAt()))).append(" · ")
                        .append(Http.esc(row.resultStatus() == null ? row.status().name()
                                : row.resultStatus()))
                        .append(" · <a href=\"/actions/").append(Http.esc(row.id())).append("\">")
                        .append(Http.esc(shortId(row.id()))).append("</a> ")
                        .append("<button class=\"btn btn-sm btn-outline-primary\" type=\"submit\">")
                        .append(Icons.icon("open")).append("Reprendre ce plan</button></form></li>");
            }
            sb.append("</ul>");
        }
        sb.append("<form method=\"post\" action=\"/buildings/plan\" class=\"actform\">");
        sb.append(hidden("_csrf", v.session().csrfToken()));
        sb.append(hidden("_action", "recover"));
        sb.append(hidden("site", v.site().id()));
        sb.append("<div class=\"field\"><label for=\"bp-action\">Identifiant d'action</label>");
        sb.append("<input id=\"bp-action\" type=\"text\" name=\"action_id\" "
                + "placeholder=\"7a1ab38d-1b11-4e9d-901a-3e2749ca90ff\" spellcheck=\"false\">");
        sb.append("<p class=\"field-help\">Celui d'un audit, d'un aperçu ou d'une génération de "
                + "plan, tel qu'affiché dans l'historique des actions. Le plan est rattaché à cet "
                + "emplacement ; son verdict est repris tel quel.</p></div>");
        sb.append("<div class=\"btnrow\"><button class=\"btn btn-sm btn-outline-secondary\" "
                + "type=\"submit\">").append(Icons.icon("open"))
                .append("Récupérer ce plan</button></div></form>");
        return sb.append("</details>").toString();
    }

    // ---- Nouvelle demande ---------------------------------------------------------------------

    private static String proposeCard(View v) {
        StringBuilder sb = new StringBuilder();
        boolean first = v.drafts().isEmpty();
        sb.append("<details class=\"npc-registry\"").append(first ? " open" : "")
                .append("><summary class=\"muted\">").append(Icons.icon("wrench"))
                .append(first ? "Demander un plan à l'IA — appel facturé"
                        : "Demander un nouveau plan à l'IA — appel facturé")
                .append("</summary>");
        if (v.providers().isEmpty()) {
            sb.append(noProvider());
            return sb.append("</details>").toString();
        }
        sb.append("<form method=\"post\" action=\"/buildings/plan\" class=\"actform\">");
        sb.append(hidden("_csrf", v.session().csrfToken()));
        sb.append(hidden("site", v.site().id()));
        sb.append(hidden("_action", "propose"));
        sb.append(hidden("request_token", v.requestToken()));
        sb.append(providerSelect(v, ""));
        sb.append("<div class=\"field\"><label for=\"bp-width\">Emprise maximale (blocs)</label>");
        sb.append("<input id=\"bp-width\" type=\"number\" name=\"max_width\" min=\"5\" "
                + "max=\"64\" value=\"19\">");
        sb.append("<p class=\"field-help\">Largeur et profondeur maximales. Le Hub est dense : "
                + "une emprise trop grande produit un bâtiment qui ne tiendra pas.</p></div>");
        sb.append("<div class=\"field\"><label for=\"bp-height\">Hauteur maximale (blocs)</label>");
        sb.append("<input id=\"bp-height\" type=\"number\" name=\"max_height\" min=\"4\" "
                + "max=\"64\" value=\"14\">");
        sb.append("<p class=\"field-help\">Toit compris.</p></div>");
        // Le budget de sortie du fournisseur borne la taille du plan, et un document coupé en deux
        // n'est pas un « plan partiel » : il est inutilisable. Mieux vaut le dire ici que laisser
        // découvrir un échec d'extraction après l'appel.
        sb.append("<p class=\"field-help\">").append(Icons.icon("info"))
                .append("Le plan est borné par la limite de <strong>jetons de sortie</strong> du "
                        + "fournisseur : une instruction coûte environ 30 jetons, donc 4 000 "
                        + "jetons autorisent à peu près 80 instructions. Si les plans reviennent "
                        + "coupés ou trop sobres, augmenter cette limite dans "
                        + "<a href=\"/ai/providers\">Fournisseurs d'IA</a>.</p>");
        // Un appel au modèle coûte de l'argent. L'écran le dit avant le bouton, et jamais après :
        // personne ne doit découvrir une dépense.
        sb.append("<p class=\"field-help\">").append(Icons.icon("warning"))
                .append("Chaque demande est un <strong>appel facturé</strong> au fournisseur. "
                        + (first ? "" : "Les plans ci-dessus sont conservés : pour reprendre l'un "
                                + "d'eux, utilisez « Reprendre ce plan », qui n'appelle pas l'IA. ")
                        + "Le plan obtenu est enregistré comme brouillon.</p>");
        sb.append("<div class=\"btnrow\"><button class=\"btn btn-sm btn-primary\" "
                + "type=\"submit\">").append(Icons.icon("wrench"))
                .append(first ? "Demander un plan" : "Demander un nouveau plan")
                .append("</button></div></form>");
        return sb.append("</details>").toString();
    }

    private static String providerSelect(View v, String chosen) {
        StringBuilder sb = new StringBuilder();
        String id = "bp-provider-" + (chosen.isEmpty() ? "new" : "fix");
        sb.append("<div class=\"field\"><label for=\"").append(id).append("\">Fournisseur</label>");
        sb.append("<select id=\"").append(id).append("\" name=\"provider\">");
        for (AiProvider provider : v.providers()) {
            sb.append("<option value=\"").append(Http.esc(provider.id())).append('"')
                    .append(provider.id().equals(chosen) ? " selected" : "").append('>')
                    .append(Http.esc(provider.label())).append("</option>");
        }
        return sb.append("</select></div>").toString();
    }

    private static String noProvider() {
        return "<div class=\"alert alert-info npc-alert\">" + Icons.icon("info")
                + "<div>Aucun fournisseur d'IA utilisable : il faut une clé API enregistrée et le "
                + "fournisseur activé. Voir <a href=\"/ai/providers\">Fournisseurs d'IA</a>."
                + "</div></div>";
    }

    // ---- Fiche d'action -----------------------------------------------------------------------

    /**
     * Le lien de retour au parcours, sur la fiche d'une action qui porte un plan.
     *
     * <p>C'est là que le parcours se perdait : la fiche n'offrait qu'un « Retour » vers
     * l'historique. Elle mène désormais au brouillon concerné — ou, si l'action n'appartient à aucun
     * brouillon, propose de le reprendre sur l'emplacement choisi, sans appel à l'IA.</p>
     *
     * @param owner le brouillon qui contient déjà ce plan, s'il existe
     * @param sites emplacements dotés d'un brief, pour la récupération
     */
    public static String actionContext(Session session, AgentActionRow row,
                                       Optional<BuildingPlanDraft> owner,
                                       List<BuildingSiteView> sites) {
        if (BuildingPlanWorkbench.planOf(row).isEmpty()) {
            return "";
        }
        if (owner.isPresent()) {
            BuildingPlanDraft d = owner.get();
            return Ui.banner("info", "Ce plan appartient à un brouillon de l'emplacement "
                    + Ui.id(d.siteId()) + ". <a class=\"btn btn-sm btn-primary\" href=\""
                    + draftUrl(d.siteId(), d.id()) + "#brouillon\">" + Icons.icon("back")
                    + "Revenir au plan</a>");
        }
        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"banner info\">").append(Icons.icon("info")).append("<div>")
                .append("Cette action transporte un plan de bâtiment complet. Le reprendre comme "
                        + "brouillon <strong>n'appelle pas l'IA</strong>.");
        if (sites.isEmpty()) {
            sb.append(" Aucun emplacement doté d'un brief dans le dernier relevé : "
                    + "<a href=\"/buildings/sites\">rafraîchir les emplacements</a>.");
        } else {
            sb.append("<form method=\"post\" action=\"/buildings/plan\" class=\"inline\">")
                    .append(hidden("_csrf", session.csrfToken()))
                    .append(hidden("_action", "recover"))
                    .append(hidden("action_id", row.id()))
                    .append(" <label for=\"ac-site\">Emplacement</label> ")
                    .append("<select id=\"ac-site\" name=\"site\">");
            for (BuildingSiteView site : sites) {
                sb.append("<option value=\"").append(Http.esc(site.id())).append("\">")
                        .append(Http.esc(site.name())).append(" (").append(Http.esc(site.id()))
                        .append(")</option>");
            }
            sb.append("</select> <button class=\"btn btn-sm btn-primary\" type=\"submit\">")
                    .append(Icons.icon("open")).append("Reprendre ce plan</button></form>");
        }
        return sb.append("</div></div>").toString();
    }

    // ---- Cartes de résultat -------------------------------------------------------------------

    private static String staleNote(CheckState state, BuildingPlanDraft draft, String subject) {
        if (!state.stale()) {
            return "";
        }
        return Ui.banner("warn", subject + " porte sur la <strong>version " + state.version()
                + "</strong>, pas sur la version " + draft.version() + " affichée : il ne dit "
                + "rien du plan actuel. Relancez-le sur cette version.");
    }

    private static String pendingNote(View v, CheckState state, String subject) {
        String id = state.row().map(AgentActionRow::id).orElse("");
        return Ui.banner("info", subject + " est en attente de la réponse du serveur "
                + "(action <a href=\"/actions/" + Http.esc(id) + "\">" + Http.esc(shortId(id))
                + "</a>). <a class=\"btn btn-sm btn-outline-secondary\" href=\""
                + draftUrl(v.draft().siteId(), v.draft().id()) + "#brouillon\">"
                + Icons.icon("refresh") + "Rafraîchir</a>");
    }

    /** Les coupes d'un aperçu, avec leur légende. */
    private static String previewCard(BuildingPlanPreview preview) {
        StringBuilder sb = new StringBuilder();
        sb.append("<dl class=\"npc-dl\">");
        row(sb, "Dimensions mesurées", preview.sizeX() + " × " + preview.sizeZ() + " × "
                + preview.sizeY() + " <span class=\"faint\">(x × z × y)</span> — "
                + preview.blocks() + " blocs de matière");
        row(sb, "Orientation de l'entrée", "<code class=\"tid\">" + Http.esc(preview.front())
                + "</code> <span class=\"faint\">la façade « avant » de toutes les coupes</span>");
        row(sb, "Ancre", Http.esc(preview.anchorLabel()));
        if (!preview.codes().isEmpty()) {
            row(sb, "Défauts bloquants", "<code class=\"tid\">"
                    + Http.esc(String.join(", ", preview.codes())) + "</code>");
        }
        if (!preview.warnings().isEmpty()) {
            row(sb, "Avertissements de l'audit", list(preview.warnings()));
        }
        sb.append("</dl>");

        sb.append("<details class=\"npc-registry\" open><summary class=\"muted\">Légende"
                + "</summary><dl class=\"npc-dl\">");
        for (var entry : BuildingPlanPreview.LEGEND) {
            row(sb, entry.getKey(), Http.esc(entry.getValue()));
        }
        sb.append("</dl></details>");

        // Les élévations d'abord : c'est là qu'une porte manquante saute aux yeux, et c'est la
        // question qu'on se pose en premier devant un bâtiment généré.
        sb.append(slices("Façades (vues de l'extérieur)", preview.elevations()));
        sb.append(slices("Plans par niveau (nord en haut)", preview.plans()));
        return sb.toString();
    }

    /**
     * Les coupes, en texte à espacement fixe.
     *
     * <p>Pas d'image, pas de JavaScript : la politique de sécurité du panel interdit le script en
     * ligne, et un tableau de plusieurs milliers de cellules HTML serait lourd sans rien apprendre
     * de plus. Du texte dans un {@code <pre>} se lit, se copie, et défile horizontalement sur un
     * téléphone.</p>
     */
    private static String slices(String title, List<BuildingPlanPreview.Slice> all) {
        if (all.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(detail("box", title));
        for (BuildingPlanPreview.Slice slice : all) {
            sb.append("<details class=\"npc-registry\"><summary class=\"muted\">")
                    .append(Http.esc(slice.label()))
                    .append(" <span class=\"faint\">").append(slice.width()).append(" × ")
                    .append(slice.rows().size()).append("</span></summary>")
                    .append("<p class=\"faint\">").append(Http.esc(slice.axes())).append("</p>")
                    .append("<pre class=\"rpgq-code\">").append(Http.esc(slice.text()))
                    .append("</pre></details>");
        }
        return sb.toString();
    }

    /**
     * Le verdict du serveur sur la version courante.
     *
     * <p>Le vocabulaire est surveillé de près ici : un plan conforme est « conforme », jamais
     * « généré ». C'est exactement la confusion que la V1 a installée en annonçant « prêt à être
     * posé » pour un bâtiment dans lequel on ne pouvait pas entrer. Et un plan conforme au contrat
     * mais qui n'a pas le nombre de niveaux demandé par le brief le dit, au lieu de passer en
     * vert.</p>
     */
    private static String verdictCard(BuildingPlanVerdict verdict, Optional<String> divergence,
                                      String actionId) {
        StringBuilder sb = new StringBuilder();
        boolean clean = verdict.accepted() && divergence.isEmpty();
        sb.append("<div class=\"alert ")
                .append(clean ? "alert-success" : "alert-warning")
                .append(" npc-alert\">")
                .append(Icons.icon(clean ? "check" : "warning"))
                .append("<div><strong>").append(Http.esc(verdict.label()))
                .append(verdict.accepted() && divergence.isPresent()
                        ? " — mais en écart avec le brief" : "")
                .append("</strong><div>").append(Http.esc(verdict.message()))
                .append("</div>");
        if (!verdict.accepted()) {
            sb.append("<div class=\"faint\">Aucun fichier n'a été écrit : un audit ne génère "
                    + "rien. Corrigez le plan ci-dessous, ou demandez une correction à l'IA.</div>");
        }
        divergence.ifPresent(text -> sb.append("<div><strong>").append(Http.esc(text))
                .append("</strong></div>"));
        sb.append("</div></div>");

        sb.append("<dl class=\"npc-dl\">");
        if (!actionId.isEmpty()) {
            row(sb, "Action d'audit", "<a href=\"/actions/" + Http.esc(actionId) + "\">"
                    + Http.esc(actionId) + "</a>");
        }
        if (!verdict.buildingId().isEmpty()) {
            row(sb, "Identifiant", Ui.id(verdict.buildingId())
                    + (verdict.exists()
                            ? " <span class=\"faint\">déjà présent dans la bibliothèque : "
                                    + "générer exigerait de cocher le remplacement</span>"
                            : ""));
        }
        if (!verdict.kind().isEmpty()) {
            row(sb, "Type contrôlé", Http.esc(verdict.kind()));
        }
        if (verdict.blocks() > 0) {
            row(sb, "Emprise mesurée", verdict.sizeX() + " × " + verdict.sizeZ() + " × "
                    + verdict.sizeY() + " — " + verdict.blocks() + " blocs de matière");
        }
        if (!verdict.codes().isEmpty()) {
            row(sb, "Défauts bloquants", "<code class=\"tid\">"
                    + Http.esc(String.join(", ", verdict.codes())) + "</code>");
        }
        if (!verdict.warnings().isEmpty()) {
            row(sb, "Avertissements", list(verdict.warnings()));
        }
        String metrics = BuildingCorrectionLoop.formatMetrics(verdict.metrics());
        if (!metrics.isBlank()) {
            row(sb, "Mesures sur la grille", "<pre class=\"rpgq-code\">"
                    + Http.esc(metrics) + "</pre>");
        }
        sb.append("</dl>");
        return sb.toString();
    }

    /** La réponse brute, repliée : utile pour comprendre un refus, encombrante le reste du temps. */
    private static String rawResponse(BuildingPlanDraft draft) {
        if (draft.rawResponse().isBlank()) {
            return "";
        }
        return "<details class=\"npc-registry\"><summary class=\"muted\">Réponse brute du modèle"
                + "</summary><pre class=\"rpgq-code\">" + Http.esc(draft.rawResponse())
                + "</pre></details>";
    }

    private static String list(List<String> items) {
        StringBuilder sb = new StringBuilder("<ul class=\"tight\">");
        for (String item : items) {
            sb.append("<li>").append(Http.esc(item)).append("</li>");
        }
        return sb.append("</ul>").toString();
    }

    private static String shortId(String id) {
        return id == null || id.length() <= 8 ? String.valueOf(id) : id.substring(0, 8);
    }

    private static String detail(String icon, String title) {
        return "<p class=\"npc-section\">" + Icons.icon(icon) + Http.esc(title) + "</p>";
    }

    private static void row(StringBuilder sb, String key, String valueHtml) {
        sb.append("<div class=\"npc-dl-row\"><dt>").append(Http.esc(key)).append("</dt><dd>")
                .append(valueHtml).append("</dd></div>");
    }

    private static String hidden(String name, String value) {
        return "<input type=\"hidden\" name=\"" + Http.esc(name) + "\" value=\""
                + Http.esc(value) + "\">";
    }

    private static String charCount(String fieldId, int current, int max) {
        return "<p class=\"field-help charcount\" data-charcount-for=\"" + Http.esc(fieldId)
                + "\" data-charcount-max=\"" + max + "\"><span class=\"cc-n\">" + current
                + "</span> / " + max + " caractères</p>";
    }
}
