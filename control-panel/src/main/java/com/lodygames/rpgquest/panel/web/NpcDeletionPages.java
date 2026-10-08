package com.lodygames.rpgquest.panel.web;

import com.lodygames.rpgquest.panel.http.Http;
import com.lodygames.rpgquest.panel.npc.NpcDeletionPlan;
import com.lodygames.rpgquest.panel.npc.NpcView;
import java.util.List;
import java.util.Locale;

/**
 * Aperçu des dépendances, confirmation et résultat d'une suppression de PNJ (issue #226).
 *
 * <p><strong>Ce que l'interface garantit</strong>, exactement comme #194 pour les quêtes :</p>
 * <ul>
 *   <li>on n'arrive jamais ici par un clic unique — la page montre d'abord <em>toutes</em> les
 *       couches, présentes et absentes, puis chaque opération avec ses effets énumérés ;</li>
 *   <li>une opération bloquée n'a <strong>pas de formulaire</strong> : il n'y a rien à cliquer, donc
 *       rien à cliquer par erreur ;</li>
 *   <li>la confirmation demande de <strong>retaper l'identifiant</strong>, et l'identifiant Citizens
 *       en plus quand l'opération touche à l'entité. Un bouton « Confirmer » seul se clique par
 *       réflexe ; un identifiant se tape volontairement ;</li>
 *   <li>aucune opération ne supprime un dialogue, et la page le répète à chaque étape.</li>
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

    // ---- Aperçu --------------------------------------------------------------------------------

    public static String preview(NpcDeletionPlan plan, String csrfToken, String errorMessage,
                                 boolean canDeleteCitizens) {
        StringBuilder sb = new StringBuilder();
        sb.append(Ui.pageHeader("npc", "Supprimer un PNJ",
                "Chaque couche est une décision distincte. Rien n'est supprimé en cascade, et "
                        + "aucun dialogue n'est jamais supprimé par cette page.", ""));

        sb.append("<section class=\"card\">");
        sb.append(Ui.sectionTitle("npc", "PNJ visé"));
        sb.append("<p><strong>").append(MiniText.html(plan.label())).append("</strong> ")
                .append(Ui.id(plan.npcId())).append("</p>");
        if (errorMessage != null && !errorMessage.isBlank()) {
            sb.append(Ui.banner("error", Http.esc(errorMessage)));
        }
        if (!plan.surveyAvailable()) {
            sb.append(Ui.banner("warning",
                    "Aucun relevé du catalogue PNJ n'a encore été fait. <strong>Aucune suppression "
                    + "n'est proposée</strong> : une liste de dépendances vide ne veut pas dire "
                    + "« aucune dépendance » quand on n'a pas regardé. "
                    + "<a href=\"/npcs\">Rafraîchir le catalogue PNJ</a>, puis revenir."));
        } else if (plan.npc().isEmpty()) {
            sb.append(Ui.banner("warning", "Ce PNJ ne figure pas dans le dernier relevé du "
                    + "serveur : il n'y a rien à supprimer."));
        } else {
            sb.append("<p class=\"muted\">").append(Http.esc(plan.npc().get().provenance()))
                    .append("</p>");
        }
        sb.append("</section>");

        if (!plan.layers().isEmpty()) {
            sb.append(layersCard(plan));
        }
        if (plan.npc().isPresent()) {
            sb.append(operationsCard(plan, csrfToken, canDeleteCitizens));
        }
        sb.append(notesCard(plan));

        sb.append("<p><a class=\"btn secondary\" href=\"/npcs\">").append(Icons.icon("open"))
                .append("Annuler et revenir au catalogue PNJ</a></p>");
        return sb.toString();
    }

    /**
     * Les couches, <strong>présentes et absentes</strong>. Afficher les absentes n'est pas du
     * remplissage : « aucun PNJ Citizens lié » est précisément ce qu'il faut savoir avant de
     * choisir une opération, et son absence à l'écran se lirait comme un oubli.
     */
    private static String layersCard(NpcDeletionPlan plan) {
        StringBuilder sb = new StringBuilder("<section class=\"card\">");
        sb.append(Ui.sectionTitle("warning", "Ce qui existe, couche par couche"));
        sb.append("<dl class=\"npc-dl\">");
        for (NpcDeletionPlan.Layer layer : plan.layers()) {
            sb.append("<div class=\"npc-dl-row\"><dt>").append(Http.esc(layer.name()))
                    .append("</dt><dd>")
                    .append(layer.present()
                            ? "<span class=\"badge text-bg-secondary\">présent</span> "
                            : "<span class=\"badge text-bg-light text-dark\">absent</span> ")
                    .append(Http.esc(layer.detail())).append("</dd></div>");
        }
        return sb.append("</dl></section>").toString();
    }

    // ---- Opérations ----------------------------------------------------------------------------

    private static String operationsCard(NpcDeletionPlan plan, String csrfToken,
                                         boolean canDeleteCitizens) {
        StringBuilder sb = new StringBuilder("<section class=\"card\">");
        sb.append(Ui.sectionTitle("target", "Opérations possibles"));
        sb.append("<p class=\"field-help\">De la moins à la plus destructrice. Chacune dit ce "
                + "qu'elle fait <em>et</em> ce qu'elle ne fait pas. Une opération bloquée n'a pas de "
                + "bouton : il n'y a rien à cliquer.</p>");
        for (NpcDeletionPlan.Operation op : plan.operations()) {
            sb.append(operationBlock(plan, op, csrfToken, canDeleteCitizens));
        }
        return sb.append("</section>").toString();
    }

    private static String operationBlock(NpcDeletionPlan plan, NpcDeletionPlan.Operation op,
                                         String csrfToken, boolean canDeleteCitizens) {
        boolean needsCitizensRight = op.op() == NpcDeletionPlan.Op.DELETE_CITIZENS
                || op.op() == NpcDeletionPlan.Op.FULL_CLEANUP;
        boolean allowed = op.available() && (!needsCitizensRight || canDeleteCitizens);

        StringBuilder sb = new StringBuilder("<div class=\"npc-item\">");
        sb.append("<p class=\"meta-line\"><span class=\"badge ")
                .append(op.available() ? (op.reversible() ? "text-bg-secondary" : "text-bg-warning")
                        : "text-bg-light text-dark")
                .append("\">").append(op.available()
                        ? (op.reversible() ? "réversible" : "irréversible") : "indisponible")
                .append("</span> <strong>").append(Http.esc(op.label())).append("</strong></p>");
        sb.append("<p class=\"muted\">").append(Http.esc(op.description())).append("</p>");

        sb.append("<ul class=\"muted\">");
        for (String effect : op.effects()) {
            sb.append("<li>").append(Http.esc(effect)).append("</li>");
        }
        sb.append("</ul>");

        if (!op.blockers().isEmpty()) {
            for (String blocker : op.blockers()) {
                sb.append(Ui.banner("warning", Http.esc(blocker)));
            }
            return sb.append("</div>").toString();
        }
        if (needsCitizensRight && !canDeleteCitizens) {
            sb.append(Ui.banner("info", "Détruire un PNJ Citizens est l'inverse de sa création : "
                    + "cela exige en plus le droit d'apparition de PNJ, que votre rôle n'a pas. "
                    + "Les autres opérations restent disponibles."));
            return sb.append("</div>").toString();
        }
        sb.append(confirmForm(plan, op, csrfToken));
        return sb.append("</div>").toString();
    }

    /**
     * Le formulaire de confirmation. Il exige de <strong>retaper</strong> l'identifiant, et
     * l'identifiant Citizens en plus quand l'opération touche à l'entité : c'est la seule
     * confirmation qui ne puisse pas être donnée par réflexe, et c'est aussi elle qui garantit
     * qu'on ne détruit pas le voisin — l'identifiant tapé est confronté à celui du plan avant
     * d'envoyer quoi que ce soit, puis par le serveur à la liaison réelle.
     */
    private static String confirmForm(NpcDeletionPlan plan, NpcDeletionPlan.Operation op,
                                      String csrfToken) {
        Integer citizens = plan.citizensNumericId();
        boolean askCitizens = op.op().touchesCitizens() && citizens != null;
        StringBuilder sb = new StringBuilder(
                "<form method=\"post\" action=\"/npcs/delete\" class=\"actform\">");
        sb.append("<input type=\"hidden\" name=\"_csrf\" value=\"").append(Http.esc(csrfToken))
                .append("\">");
        sb.append("<input type=\"hidden\" name=\"npc\" value=\"").append(Http.esc(plan.npcId()))
                .append("\">");
        sb.append("<input type=\"hidden\" name=\"op\" value=\"").append(op.op().name())
                .append("\">");
        String idField = CONFIRM_FIELD + "-" + op.op().name().toLowerCase(Locale.ROOT);
        sb.append("<div class=\"field\"><label for=\"").append(idField)
                .append("\">Pour confirmer, retaper l'identifiant exact du PNJ : <code>")
                .append(Http.esc(plan.npcId())).append("</code></label>");
        sb.append("<input id=\"").append(idField).append("\" type=\"text\" name=\"")
                .append(CONFIRM_FIELD).append("\" autocomplete=\"off\" placeholder=\"")
                .append(Http.esc(plan.npcId())).append("\" required></div>");
        if (askCitizens) {
            String cField = CONFIRM_CITIZENS_FIELD + "-" + op.op().name().toLowerCase(Locale.ROOT);
            sb.append("<div class=\"field\"><label for=\"").append(cField)
                    .append("\">Et l'identifiant numérique du PNJ Citizens : <code>")
                    .append(citizens).append("</code></label>");
            sb.append("<input id=\"").append(cField).append("\" type=\"number\" name=\"")
                    .append(CONFIRM_CITIZENS_FIELD).append("\" min=\"1\" autocomplete=\"off\" ")
                    .append("required></div>");
            sb.append("<p class=\"field-help\">Le serveur confronte cet identifiant à la liaison "
                    + "réelle avant d'agir, puis l'UUID de l'entité : un identifiant recyclé ou un "
                    + "écran périmé ne peut donc pas faire détruire un autre PNJ.</p>");
        }
        sb.append("<div class=\"btnrow\"><button class=\"btn danger\" type=\"submit\">")
                .append(Icons.icon("warning")).append(Http.esc(op.label()))
                .append("</button></div>");
        return sb.append("</form>").toString();
    }

    private static String notesCard(NpcDeletionPlan plan) {
        if (plan.notes().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("<section class=\"card\">");
        sb.append(Ui.sectionTitle("docs", "À savoir"));
        sb.append("<ul class=\"muted\">");
        for (String note : plan.notes()) {
            sb.append("<li>").append(Http.esc(note)).append("</li>");
        }
        return sb.append("</ul></section>").toString();
    }

    // ---- Résultat ------------------------------------------------------------------------------

    /**
     * Page de résultat. Les opérations étant asynchrones, elle annonce une <strong>demande</strong>
     * et renvoie au journal : prétendre que c'est fait serait faux tant que l'agent n'a pas répondu.
     *
     * @param queued les actions réellement mises en file, dans l'ordre
     */
    public static String result(NpcDeletionPlan plan, NpcDeletionPlan.Op op, List<String> queued) {
        StringBuilder sb = new StringBuilder();
        sb.append(Ui.pageHeader("npc", "Suppression demandée",
                "La demande est partie vers le serveur. Le résultat se lit dans le journal "
                        + "d'actions.", ""));
        sb.append("<section class=\"card\">");
        sb.append(Ui.sectionTitle("check", "Ce qui a été demandé"));
        sb.append("<p><strong>").append(Http.esc(operationLabel(plan, op)))
                .append("</strong> pour ").append(Ui.id(plan.npcId())).append("</p>");
        sb.append("<ul class=\"muted\">");
        for (String action : queued) {
            sb.append("<li><code class=\"tid\">").append(Http.esc(action)).append("</code></li>");
        }
        sb.append("</ul>");
        sb.append(Ui.banner("info",
                "Ces actions sont <strong>asynchrones</strong>. Le serveur revérifie les "
                + "dépendances au moment d'exécuter et peut refuser : vérifiez le verdict dans "
                + "<a href=\"/actions\">le journal d'actions</a>, puis rafraîchissez le catalogue "
                + "PNJ pour voir l'état réel."));
        plan.npc().filter(NpcView::hasLinkedDialogue).ifPresent(npc ->
                sb.append(Ui.banner("info", "Le dialogue <code>"
                        + Http.esc(npc.linkedDialogueId()) + "</code> n'a pas été touché.")));
        sb.append("<div class=\"btnrow\"><a class=\"btn\" href=\"/npcs\">")
                .append(Icons.icon("open")).append("Revenir au catalogue PNJ</a>")
                .append("<a class=\"btn secondary\" href=\"/actions\">")
                .append(Icons.icon("docs")).append("Journal d'actions</a></div>");
        return sb.append("</section>").toString();
    }

    private static String operationLabel(NpcDeletionPlan plan, NpcDeletionPlan.Op op) {
        return plan.operation(op).map(NpcDeletionPlan.Operation::label).orElse(op.name());
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
