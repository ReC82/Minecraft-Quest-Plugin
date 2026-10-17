package com.lodygames.rpgquest.panel.web;

import com.lodygames.rpgquest.panel.content.DialogueAiExport;
import com.lodygames.rpgquest.panel.http.Http;

/**
 * Issue #465 — page « Modifier ce dialogue avec une IA » : le prompt prêt à copier (dialogue
 * enregistré + contexte réel + vocabulaire du moteur), le pack seul, et le chemin du retour par
 * l'import existant (#109). PlugAdmin n'appelle aucune IA ici, et rien n'est écrit.
 */
public final class DialogueAiPages {

    private DialogueAiPages() {
    }

    public static String render(String slug, String title, DialogueAiExport.Result r, boolean canImport) {
        StringBuilder sb = new StringBuilder();
        String back = "/dialogues/edit/" + Http.esc(slug);
        sb.append(Ui.pageHeader("dialogues", "Modifier « " + title + " » avec une IA",
                "Exporter le dialogue enregistré, le faire modifier par une IA externe, le réimporter et "
                        + "relire les changements avant de remplacer le brouillon.",
                "<a class=\"btn secondary\" href=\"" + back + "\">" + Icons.icon("back") + "Retour au dialogue</a>"));
        if (!r.ok()) {
            sb.append(Ui.banner("error", Http.esc(r.problem())));
            return sb.toString();
        }
        sb.append(Ui.banner("info", "C'est la version <strong>enregistrée</strong> du dialogue qui est exportée. "
                + "Des modifications non enregistrées dans l'éditeur n'y sont pas : enregistrez-les d'abord, "
                + "ou laissez-les de côté. PlugAdmin n'appelle aucune IA : vous copiez le prompt dans l'IA de "
                + "votre choix."));

        sb.append("<section class=\"card\">").append(Ui.sectionTitle("docs", "Comment faire"));
        sb.append("<ol class=\"ai-steps\">"
                + "<li><strong>Copier</strong> le prompt ci-dessous (il contient le dialogue, ses quêtes, ses "
                + "listes de quêtes et les conditions/effets possibles).</li>"
                + "<li><strong>Le coller</strong> dans une IA (ChatGPT, Claude…) en remplaçant la ligne "
                + "<code>&lt;&lt;&lt; ÉCRIS ICI … &gt;&gt;&gt;</code> par ce que vous voulez changer.</li>"
                + "<li><strong>Copier</strong> le bloc YAML rendu par l'IA, puis l'importer dans "
                + "<a href=\"/content/import\">Import contenu</a>.</li>"
                + "<li><strong>Relire</strong> « Changements dans le dialogue » (répliques, réponses, conditions, "
                + "effets) et les erreurs éventuelles, puis choisir « Remplacer l'existant » et confirmer : seul "
                + "le brouillon PlugAdmin change (sauvegarde automatique), rien n'est en jeu.</li>"
                + "<li><strong>Revenir</strong> sur ce dialogue, vérifier l'aperçu, puis « Mettre en jeu ».</li>"
                + "</ol>");
        sb.append("<p class=\"muted\">Le réimport est refusé si l'IA change l'identifiant du dialogue, cite une "
                + "quête ou une liste inconnue, ou si le dialogue a été modifié ici depuis cet export.</p>");
        sb.append("<div class=\"field full\"><label for=\"f-dlg-ai-prompt\">Prompt prêt à copier</label>"
                + "<textarea id=\"f-dlg-ai-prompt\" rows=\"14\" readonly spellcheck=\"false\">")
                .append(Http.esc(r.prompt())).append("</textarea></div>");
        sb.append("<div class=\"btnrow\">");
        if (canImport) {
            sb.append("<a class=\"btn\" href=\"/content/import\">").append(Icons.icon("export"))
                    .append("Importer la version modifiée</a>");
        }
        sb.append("<a class=\"btn secondary\" href=\"/dialogues/ai/").append(Http.esc(slug))
                .append("?download=pack\">").append(Icons.icon("export")).append("Télécharger le pack seul</a></div>");
        sb.append("</section>");

        sb.append("<section class=\"card\"><details><summary>")
                .append(Ui.sectionTitle("dialogues", "Le pack exporté (sans le prompt)")).append("</summary>");
        if (!r.quests().isEmpty()) {
            sb.append("<p class=\"muted\">Dépendances déclarées : ").append(Http.esc(String.join(", ", r.quests())))
                    .append(".</p>");
        }
        sb.append("<div class=\"field full\"><label for=\"f-dlg-ai-pack\">Pack</label>"
                + "<textarea id=\"f-dlg-ai-pack\" rows=\"10\" readonly spellcheck=\"false\">")
                .append(Http.esc(r.pack())).append("</textarea></div></details></section>");
        return sb.toString();
    }
}
