package com.lodygames.rpgquest.panel.web;

import com.lodygames.rpgquest.panel.content.ContentDeletionAnalyzer;
import com.lodygames.rpgquest.panel.content.ContentDeletionExecutor;
import com.lodygames.rpgquest.panel.content.ContentWorkspace;
import com.lodygames.rpgquest.panel.content.DeletionPlan;
import com.lodygames.rpgquest.panel.http.Http;
import java.util.List;
import java.util.Locale;

/**
 * Aperçu des conséquences, confirmation et résultat d'une suppression de quête, de story
 * (issue #194) ou de dialogue (issue #248).
 *
 * <p><strong>Ce que l'interface garantit.</strong> On ne supprime jamais depuis un clic unique :
 * la page montre d'abord <em>toutes</em> les dépendances trouvées, ce qui sera nettoyé, ce qui
 * bloque, et ce qui ne sera pas touché. La confirmation demande de <strong>retaper
 * l'identifiant</strong> — un bouton « Confirmer » seul se clique par réflexe, un identifiant se
 * tape volontairement. Et tant qu'un blocage subsiste, le formulaire de confirmation n'existe
 * pas : il n'y a rien à cliquer.</p>
 */
public final class ContentDeletionPages {

    /** Nom du champ de confirmation : l'identifiant exact, retapé par l'opérateur. */
    public static final String CONFIRM_FIELD = "confirm_id";

    /**
     * Empreinte de la source au moment de l'aperçu (issue #248). Une modification faite entre
     * l'aperçu et la confirmation — autre onglet, autre administrateur — ne doit pas être supprimée
     * sans avoir été vue : la confirmation est alors refusée.
     */
    public static final String EXPECTED_SHA_FIELD = "expected_sha";

    /** Ce que l'écran dit d'un type de contenu. */
    record Kind(String noun, String the, String list, String deleteAction) {
        static Kind of(String kind) {
            return switch (kind == null ? "" : kind) {
                case "quests" -> new Kind("quête", "la quête", "/quests", "/quests/delete");
                case "dialogues" -> new Kind("dialogue", "le dialogue", "/dialogues", "/dialogues/delete");
                default -> new Kind("story", "la story", "/stories", "/stories/delete");
            };
        }
    }

    private final ContentWorkspace workspace;
    private final ContentDeletionAnalyzer analyzer;
    private final ContentDeletionExecutor executor;

    public ContentDeletionPages(ContentWorkspace workspace, ContentDeletionAnalyzer analyzer) {
        this.workspace = workspace;
        this.analyzer = analyzer;
        this.executor = new ContentDeletionExecutor(workspace);
    }

    /** Page d'aperçu : conséquences complètes, puis confirmation si et seulement si c'est possible. */
    public String preview(String kind, String slug, boolean runtimePresent, String csrfToken,
                          String errorMessage) {
        return preview(kind, slug, runtimePresent, true, csrfToken, errorMessage);
    }

    /** @param listingAvailable un relevé du serveur existe-t-il (voir {@code ContentDeletionAnalyzer}) */
    public String preview(String kind, String slug, boolean runtimePresent,
                          boolean listingAvailable, String csrfToken, String errorMessage) {
        return preview(analyzer.analyze(kind, slug, runtimePresent, listingAvailable), csrfToken,
                errorMessage, "");
    }

    /**
     * Aperçu d'un plan déjà calculé par l'appelant (issue #248 : avec le relevé du serveur).
     *
     * @param refreshHtml formulaire « rafraîchir le relevé » à proposer quand la vérification est
     *                    incomplète ; vide sinon
     */
    public String preview(DeletionPlan plan, String csrfToken, String errorMessage,
                          String refreshHtml) {
        StringBuilder sb = new StringBuilder();
        Kind k = Kind.of(plan.kind());
        String what = k.noun();
        String back = k.list();

        // Le libellé peut porter du MiniMessage (titre de quête) : il est RENDU, jamais affiché
        // en balises littérales (anomalie relevée sur la suppression de quêtes, #248).
        sb.append("<h1>Supprimer ").append(k.the()).append(" « ")
                .append(MiniText.html(plan.label())).append(" »</h1>");
        sb.append("<p class=\"muted\">Identifiant : <code>").append(Http.esc(plan.plainId()))
                .append("</code></p>");

        if (errorMessage != null && !errorMessage.isBlank()) {
            sb.append(Ui.banner("err", Http.esc(errorMessage)));
        }

        sb.append(presence(plan, what));
        sb.append(blockers(plan));
        if (refreshHtml != null && !refreshHtml.isBlank() && !plan.blockers().isEmpty()) {
            sb.append(refreshHtml);
        }
        sb.append(edits(plan));
        sb.append(notes(plan));

        if (plan.deletable()) {
            sb.append(confirmForm(plan, k, csrfToken));
        } else {
            sb.append(Ui.banner("warn", "<strong>Suppression impossible en l'état.</strong> "
                    + "Rien n'a été modifié. Traiter les points bloquants ci-dessus, puis revenir "
                    + "sur cette page."));
        }

        sb.append("<p><a class=\"btn btn-outline-secondary\" href=\"").append(back)
                .append("\">Annuler et revenir au catalogue</a></p>");
        return sb.toString();
    }

    /** Page de résultat, après application. */
    public String result(String kind, String slug, ContentDeletionExecutor.Result result,
                         boolean runtimeDeletionQueued) {
        return result(kind, slug, result, runtimeDeletionQueued, null);
    }

    /**
     * @param runtimeActionId action agent de suppression serveur, pour suivre son issue ; peut être
     *                        {@code null}
     */
    public String result(String kind, String slug, ContentDeletionExecutor.Result result,
                         boolean runtimeDeletionQueued, String runtimeActionId) {
        Kind k = Kind.of(kind);
        String back = k.list();
        StringBuilder sb = new StringBuilder();

        sb.append("<h1>Suppression ").append("dialogues".equals(kind) ? "du dialogue"
                        : "quests".equals(kind) ? "de la quête" : "de la story").append(" « ")
                .append(Http.esc(slug)).append(" »</h1>");
        sb.append(Ui.banner(result.ok() ? "ok" : "err", Http.esc(result.message())));

        if (!result.rewritten().isEmpty()) {
            sb.append("<h2>Références nettoyées</h2><ul>");
            for (String path : result.rewritten()) {
                sb.append("<li><code>").append(Http.esc(path)).append("</code></li>");
            }
            sb.append("</ul>");
        }
        if (result.deletedPath() != null) {
            sb.append("<h2>Fichier supprimé</h2><p><code>")
                    .append(Http.esc(result.deletedPath())).append("</code></p>");
        }
        if (result.backupDir() != null) {
            sb.append("<h2>Sauvegarde</h2><p>Tout ce qui a été touché a été copié avant "
                            + "modification dans :<br><code>")
                    .append(Http.esc(result.backupDir()))
                    .append("</code></p><p class=\"muted\">Restaurer = recopier ces fichiers dans "
                            + "<code>src/main/resources/&lt;type&gt;/</code>.</p>");
        }
        if (!result.rollbackNotes().isEmpty()) {
            sb.append("<h2>Annulation</h2><ul>");
            for (String note : result.rollbackNotes()) {
                sb.append("<li>").append(Http.esc(note)).append("</li>");
            }
            sb.append("</ul>");
        }

        if (runtimeDeletionQueued) {
            sb.append(Ui.banner("info", "La suppression de la copie <strong>serveur</strong> a été "
                    + "demandée à l'agent. Elle est exécutée de façon asynchrone : vérifier le "
                    + "journal d'actions dans un instant. Tant qu'elle n'a pas abouti, le contenu "
                    + "reste chargé sur le serveur — c'est normal, et visible dans le journal."
                    + (runtimeActionId == null ? "" : " <a href=\"/actions/" + Http.esc(runtimeActionId)
                            + "\">Suivre l'action " + Http.esc(runtimeActionId.substring(0,
                            Math.min(8, runtimeActionId.length()))) + "</a> : le serveur y rend son "
                            + "constat (sauvegarde, rechargement, ou refus motivé).")));
        } else if (result.ok()) {
            // Formulation prudente : « le dernier relevé ne le connaissait pas » n'est pas la même
            // chose que « le serveur ne l'a pas ». Les notes de l'aperçu ont déjà fait la
            // distinction ; ce bandeau ne doit pas l'effacer.
            sb.append(Ui.banner("info", "Aucune suppression côté serveur n'a été demandée : le "
                    + "dernier relevé ne connaissait pas ce contenu. Si le serveur l'avait malgré "
                    + "tout, rafraîchir le catalogue puis relancer la suppression."));
        }

        sb.append("<p><a class=\"btn btn-primary\" href=\"").append(back)
                .append("\">Revenir au catalogue</a></p>");
        return sb.toString();
    }

    /** Exécute le plan. Renvoie {@code null} si la confirmation est invalide. */
    public ContentDeletionExecutor.Result apply(String kind, String slug, boolean runtimePresent,
                                                String typedConfirmation) {
        DeletionPlan plan = analyzer.analyze(kind, slug, runtimePresent);
        return applyPlan(plan, typedConfirmation);
    }

    /**
     * Exécute un plan déjà calculé (issue #248 : avec le relevé du serveur). Renvoie {@code null}
     * si la confirmation est invalide.
     */
    public ContentDeletionExecutor.Result apply(DeletionPlan plan, String typedConfirmation) {
        return applyPlan(plan, typedConfirmation);
    }

    /** Le plan complet, relevé du serveur compris. */
    public DeletionPlan plan(String kind, String slug, boolean runtimePresent, boolean listingAvailable,
                             com.lodygames.rpgquest.panel.content.RuntimeDialogueInventory inventory,
                             java.time.Instant now) {
        return analyzer.analyze(kind, slug, runtimePresent, listingAvailable, inventory, now);
    }

    private ContentDeletionExecutor.Result applyPlan(DeletionPlan plan, String typedConfirmation) {
        if (!confirmationMatches(plan, typedConfirmation)) {
            return null;
        }
        return executor.apply(plan);
    }

    /** Le plan tel qu'il sera appliqué — utile aux appelants pour décider de la suite. */
    public DeletionPlan plan(String kind, String slug, boolean runtimePresent) {
        return analyzer.analyze(kind, slug, runtimePresent);
    }

    /**
     * La confirmation doit être l'identifiant exact, à la casse près. Accepter « oui » ou un
     * bouton seul reviendrait à supprimer sur un réflexe.
     */
    public static boolean confirmationMatches(DeletionPlan plan, String typed) {
        if (plan == null || typed == null) {
            return false;
        }
        return plan.plainId().equals(typed.trim().toLowerCase(Locale.ROOT));
    }

    // ---- Fragments -------------------------------------------------------------------------

    private String presence(DeletionPlan plan, String what) {
        StringBuilder sb = new StringBuilder("<h2>Où ce contenu existe</h2><ul>");
        sb.append("<li><strong>Source éditable</strong> : ")
                .append(plan.sourcePresent()
                        ? "présent (<code>src/main/resources/" + Http.esc(plan.kind()) + "/"
                        + Http.esc(plan.slug()) + ".yml</code>) — sera supprimé, après sauvegarde."
                        : "absent — rien à supprimer ici.")
                .append("</li>");
        sb.append("<li><strong>Serveur</strong> : ")
                .append(plan.runtimePresent()
                        ? "connu du dernier relevé — sa suppression sera demandée à l'agent, avec "
                        + "sauvegarde du fichier distant."
                        : "inconnu du dernier relevé.")
                .append("</li>");
        sb.append("</ul>");
        if (plan.bundledExample() && "dialogues".equals(plan.kind())) {
            sb.append(Ui.banner("warn", "Ce dialogue fait partie des <strong>exemples embarqués "
                    + "dans le JAR</strong> : le serveur le recrée au démarrage s'il manque. La "
                    + "suppression serveur inscrit une <strong>pierre tombale</strong> qui l'en "
                    + "empêche (plugin #248 requis) — aucune recompilation nécessaire."));
        } else if (plan.bundledExample()) {
            sb.append(Ui.banner("warn", "Cette " + what + " fait partie des <strong>exemples "
                    + "embarqués dans le JAR</strong> du plugin. Le serveur les recrée au démarrage "
                    + "si le fichier manque : elle réapparaîtra après un redémarrage tant que le "
                    + "JAR déployé la contient. Pour que la suppression soit définitive, il faut "
                    + "reconstruire et redéployer le plugin depuis la source sans elle."));
        }
        return sb.toString();
    }

    private String blockers(DeletionPlan plan) {
        if (plan.blockers().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("<h2>Points bloquants</h2>");
        sb.append("<p class=\"muted\">Tant qu'un de ces points subsiste, la suppression est "
                + "refusée — pour ne jamais laisser une référence orpheline derrière elle.</p><ul>");
        for (DeletionPlan.Blocker b : plan.blockers()) {
            sb.append("<li><strong>").append(Http.esc(b.reason())).append("</strong><br>");
            if (b.where() != null && !b.where().isBlank()) {
                sb.append("<code>").append(Http.esc(b.where())).append("</code><br>");
            }
            sb.append("<em>").append(Http.esc(b.hint())).append("</em></li>");
        }
        return sb.append("</ul>").toString();
    }

    private String edits(DeletionPlan plan) {
        if (plan.edits().isEmpty()) {
            return "<h2>Références à nettoyer</h2><p class=\"muted\">Aucune : aucun autre contenu "
                    + "de la source ne référence celui-ci.</p>";
        }
        StringBuilder sb = new StringBuilder("<h2>Références qui seront nettoyées</h2>");
        sb.append("<p class=\"muted\">Ces fichiers seront <strong>réécrits</strong>, jamais "
                + "supprimés. Chacun est sauvegardé avant modification.</p><ul>");
        for (DeletionPlan.Edit e : plan.edits()) {
            sb.append("<li>").append(Http.esc(e.description()))
                    .append("<br><code>").append(Http.esc(e.repoPath())).append("</code></li>");
        }
        return sb.append("</ul>").toString();
    }

    private String notes(DeletionPlan plan) {
        List<String> notes = plan.notes();
        if (notes.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("<h2>À savoir</h2><ul>");
        for (String note : notes) {
            sb.append("<li>").append(Http.esc(note)).append("</li>");
        }
        return sb.append("</ul>").toString();
    }

    private String confirmForm(DeletionPlan plan, Kind k, String csrfToken) {
        String spared = "dialogues".equals(plan.kind())
                ? "Elle ne supprime aucun PNJ, aucune quête (même celles que ce dialogue démarre), "
                : "Elle ne supprime aucun PNJ, aucun dialogue, aucune autre quête, ";
        return "<h2>Confirmation</h2>"
                + "<form method=\"post\" action=\"" + k.deleteAction() + "\" class=\"card card-body\">"
                + "<input type=\"hidden\" name=\"_csrf\" value=\"" + Http.esc(csrfToken) + "\">"
                + "<input type=\"hidden\" name=\"slug\" value=\"" + Http.esc(plan.slug()) + "\">"
                + "<input type=\"hidden\" name=\"" + EXPECTED_SHA_FIELD + "\" value=\""
                + Http.esc(plan.sourceSha() == null ? "" : plan.sourceSha()) + "\">"
                + "<label class=\"form-label\" for=\"" + CONFIRM_FIELD + "\">Pour confirmer, "
                + "retaper l'identifiant exact " + ("dialogues".equals(plan.kind()) ? "du dialogue"
                : "quests".equals(plan.kind()) ? "de la quête" : "de la story") + " : <code>"
                + Http.esc(plan.plainId()) + "</code></label>"
                + "<input class=\"form-control\" id=\"" + CONFIRM_FIELD + "\" name=\""
                + CONFIRM_FIELD + "\" type=\"text\" autocomplete=\"off\" "
                + "placeholder=\"" + Http.esc(plan.plainId()) + "\" required>"
                + "<p class=\"form-text\">Cette action supprime le fichier source s'il existe "
                + "(après sauvegarde), nettoie les références listées ci-dessus et demande au "
                + "serveur de supprimer sa copie. " + spared + "et <strong>n'efface aucune "
                + "progression de joueur</strong>.</p>"
                + "<button class=\"btn btn-danger\" type=\"submit\">Supprimer définitivement</button>"
                + "</form>";
    }
}
