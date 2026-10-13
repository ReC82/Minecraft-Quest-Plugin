package com.lodygames.rpgquest.panel.web;

import com.lodygames.rpgquest.panel.http.Http;
import com.lodygames.rpgquest.panel.npc.NpcDeletionPlan;
import com.lodygames.rpgquest.panel.npc.NpcView;
import java.util.List;
import java.util.Locale;

/**
 * Aperçu des dépendances, confirmation et résultat d'une suppression de PNJ (issue #226).
 *
 * <p>Issue #325 : la page est courte (ce qui part, ce qui reste, les quêtes à corriger) ; le
 * détail par couche et les autres opérations sont repliés.</p>
 *
 * <p><strong>Ce que l'interface garantit</strong>, exactement comme #194 pour les quêtes :</p>
 * <ul>
 *   <li>on n'arrive jamais ici par un clic unique — la page montre d'abord <em>toutes</em> les
 *       couches, présentes et absentes, puis chaque opération avec ses effets énumérés ;</li>
 *   <li>une opération bloquée n'a <strong>pas de formulaire</strong> : il n'y a rien à cliquer, donc
 *       rien à cliquer par erreur ;</li>
 *   <li>la confirmation demande de <strong>retaper l'identifiant</strong> ; l'identifiant Citizens
 *       attendu est joint au formulaire et doit correspondre au plan recalculé à l'envoi ;</li>
 *   <li>aucune opération ne supprime un dialogue ; un dialogue qui n'est à personne d'autre peut
 *       ensuite être supprimé par son propre parcours.</li>
 * </ul>
 *
 * <p><strong>Pourquoi un aperçu ne suffit pas.</strong> Les suppressions passent par l'agent, donc
 * de façon asynchrone : le serveur revérifie les dépendances au moment d'exécuter et peut refuser
 * même si cette page proposait l'opération. La page le dit, plutôt que de laisser croire qu'un
 * aperçu vaut une garantie.</p>
 */
public final class NpcDeletionPages {

    /** Champ de confirmation : l'identifiant exact du PNJ, retapé. */
    public static final String CONFIRM_FIELD = "confirm_id";
    /** Champ de confirmation Citizens : l'identifiant numérique, retapé lui aussi. */
    public static final String CONFIRM_CITIZENS_FIELD = "confirm_citizens";

    private NpcDeletionPages() {
    }

    /**
     * Ce que la page doit savoir en plus du plan (issue #325) : combien de PNJ partagent son
     * dialogue, et le nom de fichier de ce dialogue dans la source s'il y est — pour proposer de le
     * supprimer aussi, ensuite, seulement quand il n'est à personne d'autre.
     */
    public record Context(int dialogueHolders, String dialogueSourceSlug) {
        public static Context none() {
            return new Context(0, "");
        }
    }

    // ---- Aperçu --------------------------------------------------------------------------------

    public static String preview(NpcDeletionPlan plan, String csrfToken, String errorMessage,
                                 boolean canDeleteCitizens) {
        return preview(plan, csrfToken, errorMessage, canDeleteCitizens, Context.none());
    }

    /**
     * Issue #325 — une page courte : le PNJ, ce qui sera supprimé, ce qui est conservé, les quêtes
     * qui le citent (seulement s'il y en a), une confirmation lisible, « Supprimer le PNJ » et
     * « Annuler ». Les autres choix et le détail par couche sont repliés.
     */
    public static String preview(NpcDeletionPlan plan, String csrfToken, String errorMessage,
                                 boolean canDeleteCitizens, Context ctx) {
        StringBuilder sb = new StringBuilder();
        String name = MiniText.html(plan.label());
        sb.append(Ui.pageHeader("npc", "Supprimer « " + MiniText.plain(plan.label()) + " »", "", ""));
        sb.append("<section class=\"card npc-delete\">");
        if (errorMessage != null && !errorMessage.isBlank()) {
            sb.append(Ui.banner("error", Http.esc(errorMessage)));
        }
        if (!plan.surveyAvailable()) {
            sb.append(Ui.banner("warning", "La liste des PNJ n'est pas encore chargée : impossible de savoir "
                    + "ce qui dépend de ce PNJ. <a href=\"/npcs\">Retournez sur la page PNJ</a>, cliquez sur "
                    + "« Actualiser les PNJ », puis revenez."));
            return sb.append(cancelRow()).append("</section>").toString();
        }
        if (plan.npc().isEmpty()) {
            sb.append(Ui.banner("warning", "Ce PNJ n'est plus dans la liste : il n'y a rien à supprimer."));
            return sb.append(cancelRow()).append("</section>").toString();
        }
        NpcView npc = plan.npc().get();
        NpcDeletionPlan.Operation primary = primaryOperation(plan);

        // ---- Ce qui sera supprimé ----
        sb.append("<h2 class=\"npc-delete-h\">Ce qui sera supprimé</h2><ul class=\"npc-delete-list\">");
        if (npc.citizensBound()) {
            sb.append("<li>").append(Icons.icon("trash")).append("Le personnage ").append(name).append(" dans le jeu</li>");
        }
        if (npc.definitionPresent()) {
            sb.append("<li>").append(Icons.icon("trash")).append("Sa fiche dans RPGQuest</li>");
        }
        sb.append("</ul>");

        // ---- Ce qui est conservé ----
        sb.append("<h2 class=\"npc-delete-h\">Ce qui est conservé</h2><ul class=\"npc-delete-list keep\">");
        if (npc.hasLinkedDialogue()) {
            sb.append("<li>").append(Icons.icon("check")).append("Son dialogue « ")
                    .append(Http.esc(MiniText.prettifyId(npc.linkedDialogueKey()))).append(" »");
            if (ctx.dialogueHolders() > 1) {
                sb.append(" — partagé avec ").append(ctx.dialogueHolders() - 1)
                        .append(" autre(s) PNJ, il n'est pas touché");
            } else if (!ctx.dialogueSourceSlug().isEmpty()) {
                sb.append(" — vous pourrez le supprimer ensuite");
            }
            sb.append("</li>");
        }
        sb.append("<li>").append(Icons.icon("check")).append("La progression des joueurs</li></ul>");

        // ---- Quêtes concernées, seulement s'il y en a ----
        List<String> quests = new java.util.ArrayList<>();
        quests.addAll(npc.questsGiven());
        quests.addAll(npc.questsReferenced());
        quests.addAll(npc.questsDelivering());
        if (!quests.isEmpty()) {
            sb.append("<h2 class=\"npc-delete-h\">Quêtes qui ont besoin de lui</h2>");
            sb.append("<p>Modifiez d'abord ces quêtes pour qu'elles ne parlent plus de ce PNJ :</p><ul class=\"npc-delete-list\">");
            for (String q : new java.util.LinkedHashSet<>(quests)) {
                String plain = q.contains(":") ? q.substring(q.indexOf(':') + 1) : q;
                sb.append("<li>").append(Icons.icon("quests")).append("<a href=\"/quests?q=").append(Http.esc(plain))
                        .append("\">").append(Http.esc(MiniText.prettifyId(plain))).append("</a></li>");
            }
            sb.append("</ul>");
        }

        // ---- Confirmation et boutons ----
        if (primary != null && primary.available() && (canDeleteCitizens || !primary.op().touchesCitizens()
                || primary.op() == NpcDeletionPlan.Op.UNLINK_CITIZENS)) {
            sb.append(confirmForm(plan, primary, csrfToken, "Supprimer le PNJ", true));
        } else {
            if (primary != null && !quests.isEmpty()) {
                // Les quêtes sont listées juste au-dessus, avec leur lien : inutile de les répéter.
                sb.append(Ui.banner("warning", "La suppression sera possible quand plus aucune de ces "
                        + "quêtes n'aura besoin de lui."));
            } else if (primary != null) {
                for (String blocker : primary.blockers()) {
                    sb.append(Ui.banner("warning", Http.esc(simplify(blocker))));
                }
            }
            if (primary != null && primary.blockers().isEmpty() && !canDeleteCitizens) {
                sb.append(Ui.banner("info", "Votre rôle ne permet pas de supprimer un personnage du jeu."));
            }
            sb.append(cancelRow());
        }
        sb.append("</section>");

        // ---- Autres choix et détail technique, repliés ----
        sb.append("<details class=\"card npc-more\"><summary>Autres choix</summary>");
        for (NpcDeletionPlan.Operation op : plan.operations()) {
            if (primary != null && op.op() == primary.op()) {
                continue;
            }
            sb.append(operationBlock(plan, op, csrfToken, canDeleteCitizens));
        }
        sb.append("</details>");
        sb.append("<details class=\"card npc-more\"><summary>Détails techniques</summary><dl class=\"npc-dl\">");
        for (NpcDeletionPlan.Layer layer : plan.layers()) {
            sb.append("<div class=\"npc-dl-row\"><dt>").append(Http.esc(layer.name())).append("</dt><dd>")
                    .append(layer.present() ? "présent — " : "absent — ").append(Http.esc(layer.detail()))
                    .append("</dd></div>");
        }
        sb.append("</dl><ul class=\"muted\">");
        for (String note : plan.notes()) {
            sb.append("<li>").append(Http.esc(note)).append("</li>");
        }
        return sb.append("</ul></details>").toString();
    }

    /** L'opération qui correspond à « Supprimer le PNJ » : tout ce qui existe, d'un coup. */
    static NpcDeletionPlan.Operation primaryOperation(NpcDeletionPlan plan) {
        NpcView npc = plan.npc().orElse(null);
        if (npc == null) {
            return null;
        }
        NpcDeletionPlan.Op op = npc.definitionPresent() ? NpcDeletionPlan.Op.FULL_CLEANUP
                : NpcDeletionPlan.Op.DELETE_CITIZENS;
        return plan.operation(op).orElse(null);
    }

    /** Les messages du moteur parlent de « définition logique » : on garde le sens, en plus court. */
    private static String simplify(String blocker) {
        return blocker.replace("Référence encore active : ", "")
                .replace(". Corrigez-la d'abord (page Quêtes), puis revenez.", ".");
    }

    private static String cancelRow() {
        return "<div class=\"btnrow\"><a class=\"btn secondary\" href=\"/npcs\">Annuler</a></div>";
    }

    private static String operationBlock(NpcDeletionPlan plan, NpcDeletionPlan.Operation op,
                                         String csrfToken, boolean canDeleteCitizens) {
        boolean needsCitizensRight = op.op() == NpcDeletionPlan.Op.DELETE_CITIZENS
                || op.op() == NpcDeletionPlan.Op.FULL_CLEANUP;
        StringBuilder sb = new StringBuilder("<div class=\"npc-op\">");
        sb.append("<p><strong>").append(Http.esc(op.label())).append("</strong></p>");
        sb.append("<p class=\"muted\">").append(Http.esc(op.description())).append("</p>");
        if (!op.blockers().isEmpty()) {
            sb.append("<p class=\"faint\">Indisponible : ").append(Http.esc(simplify(String.join(" ", op.blockers()))))
                    .append("</p>");
            return sb.append("</div>").toString();
        }
        if (needsCitizensRight && !canDeleteCitizens) {
            sb.append("<p class=\"faint\">Votre rôle ne le permet pas.</p>");
            return sb.append("</div>").toString();
        }
        sb.append(confirmForm(plan, op, csrfToken, op.label(), false));
        return sb.append("</div>").toString();
    }

    /**
     * La confirmation : l'identifiant du PNJ, retapé — une confirmation qui ne se donne pas par
     * réflexe. L'identifiant Citizens attendu voyage en champ caché : au moment de l'envoi, le plan
     * est recalculé sur le dernier relevé et doit le retrouver à l'identique, sinon rien ne part
     * (écran périmé). Le serveur revérifie encore la liaison réelle et l'UUID.
     */
    private static String confirmForm(NpcDeletionPlan plan, NpcDeletionPlan.Operation op,
                                      String csrfToken, String buttonLabel, boolean withCancel) {
        Integer citizens = plan.citizensNumericId();
        String fieldId = CONFIRM_FIELD + "-" + op.op().name().toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder("<form method=\"post\" action=\"/npcs/delete\" class=\"actform npc-delete-form\">");
        sb.append("<input type=\"hidden\" name=\"_csrf\" value=\"").append(Http.esc(csrfToken)).append("\">");
        sb.append("<input type=\"hidden\" name=\"npc\" value=\"").append(Http.esc(plan.npcId())).append("\">");
        sb.append("<input type=\"hidden\" name=\"op\" value=\"").append(op.op().name()).append("\">");
        if (op.op().touchesCitizens() && citizens != null) {
            sb.append("<input type=\"hidden\" name=\"").append(CONFIRM_CITIZENS_FIELD).append("\" value=\"")
                    .append(citizens).append("\">");
        }
        sb.append("<label for=\"").append(fieldId).append("\">Pour confirmer, tapez <strong>")
                .append(Http.esc(plan.npcId())).append("</strong></label>");
        sb.append("<input class=\"form-control\" id=\"").append(fieldId).append("\" type=\"text\" name=\"")
                .append(CONFIRM_FIELD).append("\" autocomplete=\"off\" autocapitalize=\"off\" spellcheck=\"false\" required>");
        sb.append("<div class=\"btnrow\"><button class=\"btn danger\" type=\"submit\">").append(Icons.icon("trash"))
                .append(Http.esc(buttonLabel)).append("</button>");
        if (withCancel) {
            sb.append("<a class=\"btn secondary\" href=\"/npcs\">Annuler</a>");
        }
        return sb.append("</div></form>").toString();
    }

    // ---- Résultat ------------------------------------------------------------------------------

    public static String result(NpcDeletionPlan plan, NpcDeletionPlan.Op op, List<String> queued) {
        return result(plan, op, queued, Context.none());
    }

    /**
     * La demande est partie : on le dit, sans prétendre que c'est fait (le serveur revérifie et peut
     * refuser). Si son dialogue n'est à personne d'autre, on propose de le supprimer aussi — par le
     * parcours de suppression des dialogues, qui a ses propres vérifications.
     */
    public static String result(NpcDeletionPlan plan, NpcDeletionPlan.Op op, List<String> queued, Context ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append(Ui.pageHeader("npc", "Suppression demandée", "", ""));
        sb.append("<section class=\"card\">");
        sb.append("<p>La suppression de <strong>").append(MiniText.html(plan.label()))
                .append("</strong> est envoyée au serveur. La liste des PNJ se met à jour toute seule ; le "
                        + "résultat est aussi dans le <a href=\"/actions\">journal d'actions</a>.</p>");
        plan.npc().filter(NpcView::hasLinkedDialogue).ifPresent(npc -> {
            sb.append("<p>Son dialogue « ").append(Http.esc(MiniText.prettifyId(npc.linkedDialogueKey())))
                    .append(" » est conservé.");
            if (ctx.dialogueHolders() <= 1 && !ctx.dialogueSourceSlug().isEmpty()) {
                sb.append(" Il n'est utilisé par aucun autre PNJ : <a href=\"/dialogues/delete?slug=")
                        .append(Http.esc(ctx.dialogueSourceSlug())).append("\">le supprimer aussi</a>.");
            }
            sb.append("</p>");
        });
        sb.append("<details class=\"npc-more\"><summary>Actions envoyées</summary><ul class=\"muted\">");
        for (String action : queued) {
            sb.append("<li><code class=\"tid\">").append(Http.esc(action)).append("</code></li>");
        }
        sb.append("</ul></details>");
        sb.append("<div class=\"btnrow\"><a class=\"btn\" href=\"/npcs\">").append(Icons.icon("back"))
                .append("Retour aux PNJ</a></div>");
        return sb.append("</section>").toString();
    }

    // ---- Confirmation --------------------------------------------------------------------------

    /**
     * La confirmation est-elle exacte ? L'identifiant doit être retapé à l'identique, et
     * l'identifiant Citizens aussi quand l'opération touche à l'entité. Une confirmation
     * approximative est traitée comme une absence de confirmation.
     */
    public static boolean confirmationMatches(NpcDeletionPlan plan, NpcDeletionPlan.Op op,
                                              String typedId, String typedCitizens) {
        if (plan == null || op == null || typedId == null) {
            return false;
        }
        if (!plan.npcId().equals(typedId.trim().toLowerCase(Locale.ROOT))) {
            return false;
        }
        Integer citizens = plan.citizensNumericId();
        if (!op.touchesCitizens() || citizens == null) {
            return true;
        }
        return String.valueOf(citizens).equals(typedCitizens == null ? "" : typedCitizens.trim());
    }
}
