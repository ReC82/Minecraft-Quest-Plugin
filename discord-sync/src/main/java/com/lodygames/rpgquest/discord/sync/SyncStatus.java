package com.lodygames.rpgquest.discord.sync;

import com.lodygames.rpgquest.discord.github.Issue;

/**
 * Statut d'un signalement, <strong>déduit de GitHub qui fait autorité</strong> (issue #202).
 *
 * <p><strong>Correspondance documentée</strong> — c'est la table de référence, reprise telle quelle
 * dans {@code docs/discord-sync/README.md} :</p>
 *
 * <table border="1">
 *   <caption>GitHub → statut annoncé dans le sujet Discord</caption>
 *   <tr><th>État GitHub</th><th>Statut</th></tr>
 *   <tr><td>ouverte, étiquette {@code status:needs-testing}</td><td>À tester</td></tr>
 *   <tr><td>ouverte, étiquette {@code status:in-progress}</td><td>En cours</td></tr>
 *   <tr><td>ouverte, sinon (y compris {@code triage})</td><td>À trier</td></tr>
 *   <tr><td>fermée, raison {@code completed}</td><td>Résolu</td></tr>
 *   <tr><td>fermée, raison {@code not_planned}</td><td>Refusé</td></tr>
 *   <tr><td>fermée, raison {@code duplicate}</td><td>Doublon</td></tr>
 *   <tr><td>fermée, sans raison</td><td>Fermé</td></tr>
 * </table>
 *
 * <p><strong>Honnêteté de la clôture.</strong> GitHub distingue « fermée parce que faite » et
 * « fermée parce qu'on ne la fera pas ». Confondre les deux ferait croire à un membre que sa
 * demande est réglée alors qu'elle est refusée : les deux cas ont donc des statuts distincts et
 * des messages distincts. Et <strong>« Résolu » ne veut pas dire « déployé »</strong> : le message
 * publié dans le sujet le dit explicitement.</p>
 */
public enum SyncStatus {

    TRIAGE("À trier", "Pris en compte, en attente de tri."),
    IN_PROGRESS("En cours", "Le travail a commencé."),
    NEEDS_TESTING("À tester", "Un correctif existe et attend une vérification."),
    RESOLVED("Résolu", "Marqué comme réglé côté développement. "
            + "**Attention : cela ne veut pas dire que c'est déjà en ligne sur le serveur.**"),
    DECLINED("Refusé", "Ne sera pas réalisé. La discussion reste ouverte ici."),
    DUPLICATE("Doublon", "Déjà signalé ailleurs ; le suivi continue sur l'autre ticket."),
    CLOSED("Fermé", "Fermé sans raison précisée.");

    /** Étiquette GitHub qui force le statut « en cours ». */
    public static final String LABEL_IN_PROGRESS = "status:in-progress";

    /** Étiquette GitHub qui force le statut « à tester ». */
    public static final String LABEL_NEEDS_TESTING = "status:needs-testing";

    private final String label;
    private final String explanation;

    SyncStatus(String label, String explanation) {
        this.label = label;
        this.explanation = explanation;
    }

    /** Libellé court, affiché dans le sujet et utilisé pour retrouver un tag de forum. */
    public String label() {
        return label;
    }

    /** Phrase explicative publiée dans le sujet avec le changement de statut. */
    public String explanation() {
        return explanation;
    }

    /** Déduit le statut d'une issue. L'ordre des tests est la règle de précédence. */
    public static SyncStatus of(Issue issue) {
        if (issue.closed()) {
            String reason = issue.stateReason() == null ? "" : issue.stateReason();
            return switch (reason.toLowerCase(java.util.Locale.ROOT)) {
                case "completed" -> RESOLVED;
                case "not_planned" -> DECLINED;
                case "duplicate" -> DUPLICATE;
                default -> CLOSED;
            };
        }
        // Une issue en attente de test est plus avancée qu'une issue en cours : si les deux
        // étiquettes coexistent, c'est la plus avancée qui est annoncée.
        if (issue.hasLabel(LABEL_NEEDS_TESTING)) {
            return NEEDS_TESTING;
        }
        if (issue.hasLabel(LABEL_IN_PROGRESS)) {
            return IN_PROGRESS;
        }
        return TRIAGE;
    }
}
