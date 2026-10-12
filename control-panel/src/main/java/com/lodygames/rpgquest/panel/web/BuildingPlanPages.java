package com.lodygames.rpgquest.panel.web;

import com.lodygames.rpgquest.panel.ai.AiBuildingStudio;
import com.lodygames.rpgquest.panel.ai.AiProvider;
import com.lodygames.rpgquest.panel.ai.BuildingCorrectionLoop;
import com.lodygames.rpgquest.panel.building.BuildingPlanPreview;
import com.lodygames.rpgquest.panel.building.BuildingPlanVerdict;
import com.lodygames.rpgquest.panel.building.BuildingSiteView;
import com.lodygames.rpgquest.panel.http.Http;
import com.lodygames.rpgquest.panel.security.Session;
import java.util.List;

/**
 * Génération d'un bâtiment par IA depuis la fiche d'un emplacement (issue #94).
 *
 * <h2>Deux gestes, jamais un seul</h2>
 *
 * <p>Demander un plan et générer le bâtiment sont deux soumissions distinctes, et c'est
 * volontaire. Le plan arrive d'un modèle de langue : il peut être excellent, maladroit, ou
 * refusé par le serveur. L'administrateur le <strong>voit</strong> avant qu'un fichier soit
 * écrit, et décide. Un bouton unique « génère et pose » transformerait une proposition en fait
 * accompli, et c'est exactement ce que le ticket interdit : « aucun faux succès ».</p>
 *
 * <h2>Le plan est montré brut, et c'est un choix</h2>
 *
 * <p>Le panel n'embarque aucune bibliothèque YAML et ne cherche pas à juger le plan. Le serveur
 * est le validateur autoritaire : lui seul connaît les bornes du moteur et le vocabulaire des
 * instructions. Afficher ici un « plan valide » que le serveur refuserait ensuite serait pire que
 * de ne rien dire.</p>
 */
public final class BuildingPlanPages {

    private BuildingPlanPages() {
    }

    /**
     * L'écran complet.
     *
     * @param site      l'emplacement visé, déjà relevé
     * @param providers fournisseurs d'IA réellement utilisables
     * @param chosen    fournisseur retenu, ou vide
     * @param proposal  la proposition du modèle, ou {@code null} si on n'a encore rien demandé
     * @param canWrite  l'utilisateur peut-il lancer la génération côté serveur
     * @param error     refus arrivé avant tout appel (brief absent, fournisseur inconnu…)
     * @param refusal   motif d'un refus précédent du serveur, pour la reprise
     * @param verdict   dernier audit rendu par le serveur sur un plan, ou
     *                  {@link BuildingPlanVerdict#none()}
     */
    public static String render(Session session, String agentId, BuildingSiteView site,
                                List<AiProvider> providers, String chosen,
                                AiBuildingStudio.Proposal proposal, boolean canWrite,
                                String error, String refusal, BuildingPlanVerdict verdict,
                                BuildingPlanPreview preview) {
        BuildingPlanVerdict seen = verdict == null ? BuildingPlanVerdict.none() : verdict;
        BuildingPlanPreview drawn = preview == null ? BuildingPlanPreview.none() : preview;
        StringBuilder sb = new StringBuilder();
        sb.append(Ui.pageHeader("box", "Générer un bâtiment avec l'IA",
                "Le brief de l'emplacement est envoyé à l'IA, qui répond par un plan. "
                        + "Vous relisez le plan, puis vous décidez de générer.",
                ""));
        sb.append("<p><a class=\"btn btn-sm btn-outline-secondary\" href=\"/buildings/sites?agent=")
                .append(Http.esc(agentId)).append("&focus=").append(Http.esc(site.id()))
                .append("\">").append(Icons.icon("open")).append("Retour à l'emplacement</a></p>");

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
            return sb.toString();
        }

        sb.append(detail("edit", "Brief envoyé à l'IA"));
        sb.append("<p class=\"brief-text\">").append(Http.esc(site.aiBrief())).append("</p>");

        sb.append(verdictCard(seen));
        sb.append(previewCard(drawn));

        if (error != null && !error.isBlank()) {
            sb.append("<div class=\"alert alert-danger npc-alert\">").append(Icons.icon("warning"))
                    .append("<div>").append(Http.esc(error)).append("</div></div>");
        }

        // ---- Geste 1 : demander un plan ------------------------------------------------------
        sb.append(detail("wrench", "1. Demander un plan"));
        if (providers.isEmpty()) {
            sb.append("<div class=\"alert alert-info npc-alert\">").append(Icons.icon("info"))
                    .append("<div>Aucun fournisseur d'IA utilisable : il faut une clé API "
                            + "enregistrée et le fournisseur activé. Voir "
                            + "<a href=\"/ai/providers\">Fournisseurs d'IA</a>.</div></div>");
        } else {
            sb.append("<form method=\"post\" action=\"/buildings/plan\" class=\"actform\">");
            sb.append(hidden("_csrf", session.csrfToken()));
            sb.append(hidden("agent", agentId));
            sb.append(hidden("site", site.id()));
            sb.append(hidden("_action", "propose"));
            sb.append("<div class=\"field\"><label for=\"bp-provider\">Fournisseur</label>");
            sb.append("<select id=\"bp-provider\" name=\"provider\">");
            for (AiProvider provider : providers) {
                sb.append("<option value=\"").append(Http.esc(provider.id())).append('"')
                        .append(provider.id().equals(chosen) ? " selected" : "").append('>')
                        .append(Http.esc(provider.label())).append("</option>");
            }
            sb.append("</select></div>");
            sb.append("<div class=\"field\"><label for=\"bp-width\">Emprise maximale (blocs)</label>");
            sb.append("<input id=\"bp-width\" type=\"number\" name=\"max_width\" min=\"5\" "
                    + "max=\"64\" value=\"19\">");
            sb.append("<p class=\"field-help\">Largeur et profondeur maximales. Le Hub est dense : "
                    + "une emprise trop grande produit un bâtiment qui ne tiendra pas.</p></div>");
            sb.append("<div class=\"field\"><label for=\"bp-height\">Hauteur maximale (blocs)</label>");
            sb.append("<input id=\"bp-height\" type=\"number\" name=\"max_height\" min=\"4\" "
                    + "max=\"64\" value=\"14\">");
            sb.append("<p class=\"field-help\">Toit compris.</p></div>");
            // Le budget de sortie du fournisseur borne la taille du plan, et un document coupé en
            // deux n'est pas un « plan partiel » : il est inutilisable. Mieux vaut le dire ici que
            // laisser découvrir un échec d'extraction après l'appel.
            sb.append("<p class=\"field-help\">").append(Icons.icon("info"))
                    .append("Le plan est borné par la limite de <strong>jetons de sortie</strong> du "
                            + "fournisseur : une instruction coûte environ 30 jetons, donc 4 000 "
                            + "jetons autorisent à peu près 80 instructions. Si les plans reviennent "
                            + "coupés ou trop sobres, augmenter cette limite dans "
                            + "<a href=\"/ai/providers\">Fournisseurs d'IA</a>.</p>");
            if (refusal != null && !refusal.isBlank() && proposal != null && proposal.hasPlan()) {
                sb.append(hidden("previous_plan", proposal.plan()));
                sb.append(hidden("refusal", refusal));
                sb.append("<p class=\"form-text\">").append(Icons.icon("info"))
                        .append("Le motif du refus précédent sera transmis à l'IA avec le plan "
                                + "refusé : elle corrigera ce point sans repartir de zéro.</p>");
            }
            // Un appel au modèle coûte de l'argent. L'écran le dit avant le bouton, et jamais
            // après : personne ne doit découvrir une dépense.
            sb.append("<p class=\"field-help\">").append(Icons.icon("warning"))
                    .append("Chaque demande est un <strong>appel facturé</strong> au fournisseur. "
                            + "Une seule proposition est demandée par clic : la correction "
                            + "éventuelle reste votre décision, avec le motif du refus sous les "
                            + "yeux.</p>");
            sb.append("<div class=\"btnrow\"><button class=\"btn btn-sm btn-primary\" "
                    + "type=\"submit\">").append(Icons.icon("wrench"))
                    .append(refusal != null && !refusal.isBlank()
                            ? "Demander un plan corrigé" : "Demander un plan")
                    .append("</button></div></form>");
        }

        if (proposal == null) {
            return sb.toString();
        }

        // ---- La proposition ------------------------------------------------------------------
        sb.append(detail("wrench", "2. Le plan proposé"));
        sb.append("<dl class=\"npc-dl\">");
        row(sb, "Fournisseur", Http.esc(proposal.providerLabel())
                + (proposal.model() == null ? "" : " <code class=\"tid\">"
                        + Http.esc(proposal.model()) + "</code>"));
        if (proposal.usage() != null) {
            row(sb, "Consommation", Http.esc(proposal.usage()));
        }
        sb.append("</dl>");

        if (!proposal.callOk()) {
            sb.append("<div class=\"alert alert-danger npc-alert\">").append(Icons.icon("warning"))
                    .append("<div><strong>L'appel a échoué.</strong> ")
                    .append(Http.esc(proposal.error()))
                    .append(" Rien n'a été écrit sur le serveur.</div></div>");
            return sb.toString();
        }
        if (!proposal.hasPlan()) {
            sb.append("<div class=\"alert alert-warning npc-alert\">").append(Icons.icon("warning"))
                    .append("<div><strong>Aucun plan exploitable dans la réponse.</strong> ")
                    .append(Http.esc(proposal.extractionNote() == null
                            ? "" : proposal.extractionNote()))
                    .append(" Relancer la demande.</div></div>");
            sb.append(rawResponse(proposal));
            return sb.toString();
        }
        if (proposal.extractionNote() != null && !proposal.extractionNote().isBlank()) {
            sb.append("<p class=\"form-text\">").append(Icons.icon("info"))
                    .append(Http.esc(proposal.extractionNote())).append("</p>");
        }

        // ---- Geste 2 : générer ---------------------------------------------------------------
        sb.append("<form method=\"post\" action=\"/agents/action\" class=\"actform\">");
        sb.append(hidden("_csrf", session.csrfToken()));
        sb.append(hidden("agent", agentId));
        sb.append(hidden("type", "building.generate"));
        sb.append(hidden("return", "/buildings/sites"));
        sb.append("<div class=\"field\"><label for=\"bp-plan\">Plan (modifiable avant envoi)</label>");
        sb.append("<textarea id=\"bp-plan\" name=\"plan\" rows=\"22\" data-charcount=\"64000\" "
                + "spellcheck=\"false\">").append(Http.esc(proposal.plan())).append("</textarea>");
        sb.append(charCount("bp-plan", proposal.plan().length(), 64000));
        sb.append("<p class=\"field-help\">Le serveur revalide ce plan intégralement. S'il le "
                + "refuse, il dira quelle instruction pose problème — vous pourrez corriger ici, "
                + "ou redemander un plan corrigé à l'IA.</p></div>");
        sb.append("<label class=\"inline confirm\"><input type=\"checkbox\" name=\"replace\" "
                + "value=\"true\"> Remplacer un bâtiment existant portant le même identifiant"
                + "</label>");
        sb.append("<p class=\"field-help\">Décoché, la génération est refusée si l'identifiant "
                + "est déjà pris — un bâtiment retouché à la main ne disparaît pas par accident.</p>");
        sb.append(hidden("confirm", "true"));
        if (canWrite) {
            sb.append("<div class=\"btnrow\"><button class=\"btn btn-sm btn-primary\" "
                    + "type=\"submit\">").append(Icons.icon("save"))
                    .append("Générer le bâtiment</button></div>");
            sb.append("<p class=\"field-help\">Écrit un fichier <code>.schem</code> et une "
                    + "définition sur le serveur, et ajoute le bâtiment à la bibliothèque. "
                    + "<strong>Aucun bloc n'est posé dans le monde</strong> : la pose reste une "
                    + "opération distincte, depuis la fiche de l'emplacement.</p>");
        } else {
            sb.append("<p class=\"field-help\">").append(Icons.icon("warning"))
                    .append("Droit d'écriture des bâtiments requis pour lancer la génération.</p>");
        }
        sb.append("</form>");

        sb.append(auditForm(session, agentId, proposal.plan()));
        sb.append(previewForm(session, agentId, proposal.plan()));
        sb.append(rawResponse(proposal));
        return sb.toString();
    }

    /**
     * L'audit du plan : le geste <strong>gratuit</strong> et sans conséquence, à faire avant de
     * générer.
     *
     * <p>Il est proposé séparément parce qu'il n'écrit rien. Avant le lot F, le seul moyen de
     * connaître le verdict du serveur était de générer — donc d'écrire un bâtiment pour apprendre
     * qu'il était mauvais, puis de le remplacer. Auditer d'abord coûte un aller-retour avec l'agent
     * et rien d'autre.</p>
     */
    private static String auditForm(Session session, String agentId, String plan) {
        StringBuilder sb = new StringBuilder();
        sb.append(detail("search", "3. Auditer le plan — n'écrit rien"));
        sb.append("<form method=\"post\" action=\"/agents/action\" class=\"actform\">");
        sb.append(hidden("_csrf", session.csrfToken()));
        sb.append(hidden("agent", agentId));
        sb.append(hidden("type", "building.plan.audit"));
        sb.append(hidden("plan", plan));
        sb.append(hidden("return", "/buildings/plan"));
        sb.append("<p class=\"field-help\">Le serveur lit le plan, le construit en mémoire et "
                + "mesure le résultat : entrée, vitrages, lumière, enveloppe, étages atteignables, "
                + "matériaux. <strong>Aucun fichier n'est écrit</strong> et la bibliothèque n'est "
                + "pas touchée. Le verdict apparaît en haut de cette page.</p>");
        sb.append("<div class=\"btnrow\"><button class=\"btn btn-sm btn-outline-secondary\" "
                + "type=\"submit\">").append(Icons.icon("search"))
                .append("Auditer ce plan</button></div></form>");
        return sb.toString();
    }

    /**
     * L'aperçu 2D : le geste gratuit qui montre ce qui sera réellement construit.
     *
     * <p>Les coupes sont calculées par le <strong>plugin</strong>. Le ticket l'exige, et la raison
     * est la V1 : un aperçu dessiné par le panel depuis le texte du plan aurait reproduit l'erreur du
     * modèle — un percement visant {@code x = 5} là où le mur est en {@code x = 0} — et aurait donc
     * montré une porte qui n'existe pas.</p>
     */
    private static String previewForm(Session session, String agentId, String plan) {
        StringBuilder sb = new StringBuilder();
        sb.append(detail("box", "4. Prévisualiser en coupes 2D — n'écrit rien"));
        sb.append("<form method=\"post\" action=\"/agents/action\" class=\"actform\">");
        sb.append(hidden("_csrf", session.csrfToken()));
        sb.append(hidden("agent", agentId));
        sb.append(hidden("type", "building.plan.preview"));
        sb.append(hidden("plan", plan));
        sb.append(hidden("return", "/buildings/plan"));
        sb.append("<p class=\"field-help\">Le serveur construit le bâtiment <strong>en mémoire</strong> "
                + "et en renvoie les coupes : un plan par niveau, les quatre façades, l'orientation "
                + "de l'entrée, l'ancre et les dimensions mesurées. Les portes, fenêtres, escaliers "
                + "et lumières y sont visibles. <strong>Aucun fichier n'est écrit.</strong></p>");
        sb.append("<div class=\"btnrow\"><button class=\"btn btn-sm btn-outline-secondary\" "
                + "type=\"submit\">").append(Icons.icon("box"))
                .append("Prévisualiser ce plan</button></div></form>");
        return sb.toString();
    }

    /** Les coupes du dernier aperçu, avec leur légende. */
    private static String previewCard(BuildingPlanPreview preview) {
        if (!preview.available() || !preview.hasSlices()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(detail("box", "Dernier aperçu 2D"));
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
            StringBuilder warnings = new StringBuilder("<ul class=\"tight\">");
            for (String warning : preview.warnings()) {
                warnings.append("<li>").append(Http.esc(warning)).append("</li>");
            }
            row(sb, "Avertissements de l'audit", warnings.append("</ul>").toString());
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
     * Le dernier verdict du serveur.
     *
     * <p>Le vocabulaire est surveillé de près ici : un plan conforme est « conforme », jamais
     * « généré ». C'est exactement la confusion que la V1 a installée en annonçant « prêt à être
     * posé » pour un bâtiment dans lequel on ne pouvait pas entrer.</p>
     */
    private static String verdictCard(BuildingPlanVerdict verdict) {
        if (!verdict.available()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(detail("search", "Dernier audit de plan"));
        sb.append("<div class=\"alert ")
                .append(verdict.accepted() ? "alert-success" : "alert-warning")
                .append(" npc-alert\">")
                .append(Icons.icon(verdict.accepted() ? "check" : "warning"))
                .append("<div><strong>").append(Http.esc(verdict.label()))
                .append("</strong><div>").append(Http.esc(verdict.message()))
                .append("</div>");
        if (!verdict.accepted()) {
            sb.append("<div class=\"faint\">Aucun fichier n'a été écrit : un audit ne génère "
                    + "rien. Corrigez le plan ci-dessous, ou demandez un plan corrigé à l'IA.</div>");
        }
        sb.append("</div></div>");

        sb.append("<dl class=\"npc-dl\">");
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
            StringBuilder warnings = new StringBuilder("<ul class=\"tight\">");
            for (String warning : verdict.warnings()) {
                warnings.append("<li>").append(Http.esc(warning)).append("</li>");
            }
            row(sb, "Avertissements", warnings.append("</ul>").toString());
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
    private static String rawResponse(AiBuildingStudio.Proposal proposal) {
        if (proposal.rawResponse() == null || proposal.rawResponse().isBlank()) {
            return "";
        }
        return "<details class=\"npc-registry\"><summary class=\"muted\">Réponse brute du modèle"
                + "</summary><pre class=\"rpgq-code\">" + Http.esc(proposal.rawResponse())
                + "</pre></details>";
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
