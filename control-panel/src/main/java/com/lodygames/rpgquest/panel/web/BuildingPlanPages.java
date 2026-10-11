package com.lodygames.rpgquest.panel.web;

import com.lodygames.rpgquest.panel.ai.AiBuildingStudio;
import com.lodygames.rpgquest.panel.ai.AiProvider;
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
     */
    public static String render(Session session, String agentId, BuildingSiteView site,
                                List<AiProvider> providers, String chosen,
                                AiBuildingStudio.Proposal proposal, boolean canWrite,
                                String error, String refusal) {
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
        sb.append(rawResponse(proposal));
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
