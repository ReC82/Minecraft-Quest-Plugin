package com.lodygames.rpgquest.panel.web;

import com.lodygames.rpgquest.panel.ai.AiProvider;
import com.lodygames.rpgquest.panel.ai.AiQuestStudio;
import com.lodygames.rpgquest.panel.ai.QuestPromptBuilder;
import com.lodygames.rpgquest.panel.content.ContentPackImport;
import com.lodygames.rpgquest.panel.content.Diagnostic;
import com.lodygames.rpgquest.panel.content.RefData;
import com.lodygames.rpgquest.panel.http.Http;
import java.util.List;

/**
 * Atelier « Créer avec une IA » (issue #146) : une page séparée, dédiée à la génération d'<strong>une
 * quête</strong>.
 *
 * <p>L'administrateur ne remplit qu'un formulaire en français. Le contrat de contenu, le schéma, les
 * types réellement supportés et les références réellement disponibles sont joints automatiquement —
 * c'est toute la différence avec un copier-coller manuel dans ChatGPT.</p>
 *
 * <p><strong>Cette page n'écrit jamais.</strong> Elle s'arrête à l'aperçu validé ; l'enregistrement
 * passe par la page d'import (#109), avec son arbitrage des collisions et sa confirmation explicite.
 * Il n'existe donc qu'un seul chemin d'écriture dans le panel, et l'IA n'en obtient aucun
 * raccourci.</p>
 *
 * <p><strong>Texte stylé</strong> : le champ « titre exact souhaité » est du texte destiné au
 * joueur ; il utilise donc le composant guidé partagé (palette, styles, aperçu), comme partout
 * ailleurs. Aucune balise MiniMessage n'est demandée dans le parcours normal.</p>
 */
public final class AiStudioPages {

    private AiStudioPages() {
    }

    public static String render(List<AiProvider> usable, QuestPromptBuilder.QuestRequest form,
                                String selectedProvider, AiQuestStudio.Generation generation,
                                RefData refs, boolean canImport, String error) {
        StringBuilder sb = new StringBuilder();
        sb.append(Ui.pageHeader("gift", "Créer avec une IA",
                "Décrire une quête en français. L'IA propose, les validateurs réels tranchent, "
                        + "et rien n'est enregistré sans votre confirmation.", ""));

        if (error != null && !error.isBlank()) {
            sb.append(Ui.banner("error", Http.esc(error)));
        }
        if (usable.isEmpty()) {
            sb.append(Ui.banner("warning",
                    "Aucun fournisseur d'IA n'est utilisable. Il faut en activer un et y enregistrer "
                    + "une clé API dans <a href=\"/ai/providers\">Fournisseurs d'IA</a>."));
            sb.append(aboutCard());
            return sb.toString();
        }

        sb.append(formCard(usable, form, selectedProvider, refs));
        if (generation != null) {
            sb.append(resultCard(generation, canImport));
        }
        sb.append(aboutCard());
        return sb.toString();
    }

    // ---- Formulaire ----------------------------------------------------------------------------

    private static String formCard(List<AiProvider> usable, QuestPromptBuilder.QuestRequest f,
                                   String selectedProvider, RefData refs) {
        StringBuilder sb = new StringBuilder("<section class=\"card\">");
        sb.append(Ui.sectionTitle("gift", "1. Décrire la quête"));
        sb.append("<form method=\"post\" action=\"/ai/studio\" class=\"editor\" novalidate>%CSRF%");

        sb.append("<div class=\"form-grid\">");
        sb.append("<div class=\"field full\"><label for=\"f-intent\">Ce que la quête doit raconter "
                + "<span aria-hidden=\"true\">*</span></label>");
        sb.append("<textarea id=\"f-intent\" name=\"intent\" rows=\"4\" placeholder=\"Ex. : une quête "
                + "donnée par le Guide, où le joueur descend dans une mine abandonnée, casse de la "
                + "pierre, puis lui rapporte du fer.\">")
                .append(Http.esc(f == null ? "" : f.intent())).append("</textarea>");
        sb.append("<p class=\"field-help\">Le seul champ vraiment nécessaire. Écrivez en français, "
                + "comme à un collègue : tout le contexte technique est ajouté automatiquement.</p>"
                + "</div>");

        // Le titre est du texte vu par le joueur : éditeur guidé, jamais de balise à taper.
        sb.append("<div class=\"full\">").append(StyleField.render("title", "ai-title",
                "Titre exact souhaité (facultatif)", f == null ? "" : f.title(), false,
                "Laisser vide pour que l'IA propose un titre. Si vous en imposez un, choisissez la "
                + "couleur et les styles ici : aucun code à écrire, et l'IA le reprendra tel quel.",
                false, false)).append("</div>");

        sb.append(text("f-questId", "questId", "Identifiant souhaité",
                f == null ? "" : f.questId(), "rpgquest:mines_oubliees",
                "Vide = l'IA en propose un. Minuscules, chiffres et « _ »."));
        sb.append(select("f-category", "category", "Catégorie", f == null ? "" : f.category(),
                "dl-category", "Regroupement éditorial (aventure, combat, artisanat…)."));
        sb.append(select("f-giver", "giver", "PNJ donneur", f == null ? "" : f.giver(), "dl-npc",
                refs != null && refs.npcsKnown() && !refs.npcs().isEmpty()
                        ? "Choisir parmi les PNJ réellement existants."
                        : "Aucun relevé de PNJ disponible : laisser vide évite une référence inventée."));
        sb.append(text("f-difficulty", "difficulty", "Difficulté visée",
                f == null ? "" : f.difficulty(), "facile, moyenne, difficile",
                "Intention éditoriale : sert à calibrer les quantités, ce n'est pas une mécanique."));
        sb.append(text("f-duration", "duration", "Durée visée", f == null ? "" : f.duration(),
                "courte, une soirée…", "Aide l'IA à doser le nombre d'objectifs."));
        sb.append(number("f-stepCount", "stepCount", "Nombre d'étapes",
                f == null ? 0 : f.stepCount(), "0 = l'IA décide. Maximum 10."));
        sb.append(text("f-rewardIntent", "rewardIntent", "Récompense souhaitée",
                f == null ? "" : f.rewardIntent(), "un peu d'XP et quelques pièces",
                "En français. Les types réellement disponibles sont joints à la demande."));

        sb.append("<div class=\"field full\"><label for=\"f-constraints\">Contraintes "
                + "supplémentaires</label>");
        sb.append("<textarea id=\"f-constraints\" name=\"constraints\" rows=\"2\" placeholder=\"Ex. : "
                + "pas de combat, rester jouable sans claim.\">")
                .append(Http.esc(f == null ? "" : f.constraints())).append("</textarea></div>");

        sb.append("<div class=\"full\"><label class=\"inline\"><input type=\"checkbox\" "
                + "name=\"repeatable\" value=\"on\"")
                .append(f != null && f.repeatable() ? " checked" : "")
                .append("> Quête répétable</label></div>");

        sb.append("<div class=\"field\"><label for=\"f-provider\">Fournisseur</label>");
        sb.append("<select id=\"f-provider\" name=\"provider\">");
        for (AiProvider p : usable) {
            sb.append("<option value=\"").append(Http.esc(p.id())).append("\"")
                    .append(p.id().equals(selectedProvider) ? " selected" : "").append('>')
                    .append(Http.esc(p.label())).append("</option>");
        }
        sb.append("</select><p class=\"field-help\">Seuls les fournisseurs activés et pourvus d'une "
                + "clé apparaissent ici.</p></div>");
        sb.append("</div>");

        sb.append("<div class=\"btnrow\"><button class=\"btn\" type=\"submit\" name=\"_action\" "
                + "value=\"generate\">").append(Icons.icon("gift"))
                .append("Demander une proposition</button></div>");
        sb.append("<p class=\"field-help\">L'appel part du serveur du panel, jamais de votre "
                + "navigateur. Il peut prendre une minute.</p>");
        sb.append("</form>");
        sb.append(ContentEditorPages.sharedDatalists(refs, false));
        return sb.append("</section>").toString();
    }

    // ---- Résultat ------------------------------------------------------------------------------

    private static String resultCard(AiQuestStudio.Generation g, boolean canImport) {
        StringBuilder sb = new StringBuilder("<section class=\"card\">");
        sb.append(Ui.sectionTitle("check", "2. Proposition de l'IA"));

        if (!g.callOk()) {
            sb.append(Ui.banner("error", "L'appel a échoué : " + Http.esc(g.error())
                    + " <strong>Rien n'a été enregistré.</strong>"));
            return sb.append("</section>").toString();
        }

        sb.append("<p class=\"muted\">").append(Http.esc(g.providerLabel()));
        if (g.model() != null) {
            sb.append(" &middot; modèle <code>").append(Http.esc(g.model())).append("</code>");
        }
        if (g.usage() != null) {
            sb.append(" &middot; ").append(Http.esc(g.usage())).append(" jeton(s)");
        }
        sb.append("</p>");

        if (g.extractionNote() != null) {
            sb.append(Ui.banner("warning", Http.esc(g.extractionNote())));
        }

        ContentPackImport.Analysis a = g.analysis();
        if (a == null) {
            sb.append(Ui.banner("error", "Aucun document exploitable dans la réponse."));
            return sb.append(rawBlock(g)).append("</section>").toString();
        }

        for (Diagnostic d : a.envelope()) {
            sb.append(Ui.banner(d.level() == Diagnostic.Level.ERROR ? "error"
                    : d.level() == Diagnostic.Level.WARNING ? "warning" : "info",
                    Http.esc(d.message())));
        }

        for (ContentPackImport.Element e : a.elements()) {
            sb.append(element(e));
        }

        if (a.importable()) {
            sb.append(Ui.banner("success",
                    "La proposition passe les validateurs réels. <strong>Rien n'est encore "
                    + "enregistré</strong> : l'étape suivante ouvre la page d'import, où vous "
                    + "confirmerez — et trancherez d'éventuelles collisions."));
            sb.append("<form method=\"post\" action=\"/content/import\" class=\"actform\">%CSRF%");
            sb.append("<input type=\"hidden\" name=\"pack\" value=\"").append(Http.esc(g.yaml()))
                    .append("\">");
            sb.append("<div class=\"btnrow\"><button class=\"btn\" type=\"submit\"")
                    .append(canImport ? "" : " disabled").append('>').append(Icons.icon("save"))
                    .append("Relire et enregistrer (page d'import)</button></div>");
            if (!canImport) {
                sb.append("<p class=\"field-help\">Votre rôle ne permet pas d'importer du contenu : "
                        + "la proposition est consultable, mais pas enregistrable.</p>");
            }
            sb.append("</form>");
        } else {
            sb.append(Ui.banner("warning",
                    "La proposition ne passe pas les validateurs : elle ne peut pas être "
                    + "enregistrée en l'état. Vous pouvez demander une correction à l'IA, qui "
                    + "recevra sa propre sortie et les erreurs exactes."));
            if (g.correctable()) {
                sb.append("<form method=\"post\" action=\"/ai/studio\" class=\"actform\">%CSRF%");
                sb.append("<input type=\"hidden\" name=\"provider\" value=\"")
                        .append(Http.esc(g.providerId())).append("\">");
                sb.append("<input type=\"hidden\" name=\"previousYaml\" value=\"")
                        .append(Http.esc(g.yaml())).append("\">");
                for (String p : g.problems()) {
                    sb.append("<input type=\"hidden\" name=\"problem\" value=\"")
                            .append(Http.esc(p)).append("\">");
                }
                sb.append("<div class=\"btnrow\"><button class=\"btn\" type=\"submit\" "
                        + "name=\"_action\" value=\"correct\">").append(Icons.icon("refresh"))
                        .append("Demander une correction à l'IA</button></div></form>");
            }
        }

        sb.append(rawBlock(g));
        return sb.append("</section>").toString();
    }

    private static String element(ContentPackImport.Element e) {
        StringBuilder sb = new StringBuilder("<div class=\"npc-item\">");
        sb.append("<p class=\"meta-line\"><span class=\"badge ").append(switch (e.status()) {
            case NEW -> "text-bg-success";
            case MODIFIED -> "text-bg-info";
            case UNCHANGED -> "text-bg-secondary";
            case CONFLICT -> "text-bg-warning";
            case SKIPPED -> "text-bg-light text-dark";
            case INVALID -> "text-bg-danger";
        }).append("\">").append(switch (e.status()) {
            case NEW -> "nouveau";
            case MODIFIED -> "modifié";
            case UNCHANGED -> "déjà identique";
            case CONFLICT -> "existe déjà";
            case SKIPPED -> "ignoré";
            case INVALID -> "inexploitable";
        }).append("</span> <code class=\"tid\">").append(Http.esc(e.key())).append("</code> ")
                .append(Ui.id(e.id())).append("</p>");
        if (e.reason() != null && !e.reason().isBlank()) {
            sb.append("<p class=\"muted\">").append(Http.esc(e.reason())).append("</p>");
        }
        for (Diagnostic d : e.diagnostics()) {
            sb.append("<p class=\"muted\"><span class=\"badge ").append(switch (d.level()) {
                case ERROR -> "text-bg-danger";
                case WARNING -> "text-bg-warning";
                case INFO -> "text-bg-secondary";
            }).append("\">").append(d.level().name()).append("</span> ")
                    .append(Http.esc(d.field())).append(" — ").append(Http.esc(d.message()))
                    .append("</p>");
        }
        if (e.yaml() != null) {
            sb.append("<details><summary class=\"muted\">Voir le contenu proposé</summary>")
                    .append("<div class=\"codeblock\"><pre>").append(Http.esc(e.yaml()))
                    .append("</pre></div></details>");
        }
        return sb.append("</div>").toString();
    }

    /** La réponse brute, repliée : utile quand l'IA a mal répondu, inutile le reste du temps. */
    private static String rawBlock(AiQuestStudio.Generation g) {
        if (g.rawResponse() == null || g.rawResponse().isBlank()) {
            return "";
        }
        return "<details><summary class=\"muted\">Réponse brute du modèle (mode avancé)</summary>"
                + "<div class=\"codeblock\"><pre>" + Http.esc(g.rawResponse()) + "</pre></div></details>";
    }

    // ---- Champs --------------------------------------------------------------------------------

    private static String text(String id, String name, String label, String value,
                               String placeholder, String help) {
        return "<div class=\"field\"><label for=\"" + id + "\">" + Http.esc(label) + "</label>"
                + "<input id=\"" + id + "\" type=\"text\" name=\"" + name + "\" value=\""
                + Http.esc(value) + "\" placeholder=\"" + Http.esc(placeholder) + "\">"
                + "<p class=\"field-help\">" + Http.esc(help) + "</p></div>";
    }

    private static String select(String id, String name, String label, String value,
                                 String datalist, String help) {
        return "<div class=\"field\"><label for=\"" + id + "\">" + Http.esc(label) + "</label>"
                + "<input id=\"" + id + "\" type=\"text\" name=\"" + name + "\" value=\""
                + Http.esc(value) + "\" list=\"" + datalist + "\">"
                + "<p class=\"field-help\">" + Http.esc(help) + "</p></div>";
    }

    private static String number(String id, String name, String label, int value, String help) {
        return "<div class=\"field\"><label for=\"" + id + "\">" + Http.esc(label) + "</label>"
                + "<input id=\"" + id + "\" type=\"number\" min=\"0\" max=\"10\" name=\"" + name
                + "\" value=\"" + value + "\">"
                + "<p class=\"field-help\">" + Http.esc(help) + "</p></div>";
    }

    private static String aboutCard() {
        return "<section class=\"card\">"
                + Ui.sectionTitle("docs", "Ce que fait l'atelier, et ce qu'il ne fait pas")
                + "<ul class=\"muted\">"
                + "<li><strong>Vous ne décrivez que votre intention.</strong> Le contrat de contenu, "
                + "le schéma officiel, les types d'objectifs et de récompenses réellement supportés "
                + "et les références réellement existantes sont joints automatiquement.</li>"
                + "<li><strong>L'IA ne remplace pas les validateurs.</strong> Sa proposition passe "
                + "par les mêmes validateurs que l'éditeur guidé ; une erreur de sa part est "
                + "attrapée, affichée, et bloque l'enregistrement.</li>"
                + "<li><strong>Cette page n'écrit rien.</strong> Elle s'arrête à l'aperçu. "
                + "L'enregistrement passe par la page d'import, avec sa confirmation explicite et "
                + "son arbitrage des collisions.</li>"
                + "<li><strong>Rien n'est publié sur le serveur Minecraft.</strong> Même après "
                + "enregistrement, le contenu est dans la source ; le déploiement reste distinct.</li>"
                + "<li><strong>L'appel part du serveur</strong>, jamais de votre navigateur, et la "
                + "clé API n'y transite jamais.</li>"
                + "<li>Un échec, un délai dépassé ou une réponse illisible <strong>ne modifient "
                + "rien</strong>.</li>"
                + "</ul></section>";
    }
}
