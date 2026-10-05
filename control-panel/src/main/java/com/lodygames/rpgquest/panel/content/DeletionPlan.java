package com.lodygames.rpgquest.panel.content;

import java.util.List;

/**
 * Conséquences d'une suppression de quête ou de story, calculées <strong>avant</strong> toute
 * mutation (issue #194).
 *
 * <p><strong>Pourquoi un plan séparé de son exécution.</strong> Le ticket demande d'afficher
 * toutes les dépendances avant confirmation, de ne rien supprimer en cascade, et de bloquer plutôt
 * que de laisser un lien orphelin. Cela n'est possible que si l'analyse est complète et
 * reproductible : le plan contient donc le <em>contenu exact</em> qui sera écrit dans chaque
 * fichier touché. L'aperçu montre ce plan, et l'exécution ne fait que l'appliquer — ce qui est
 * montré est donc exactement ce qui sera fait.</p>
 *
 * @param kind             {@code "quests"} ou {@code "stories"}
 * @param slug             nom de fichier sans extension
 * @param plainId          identifiant sans le namespace {@code rpgquest:}
 * @param label            titre humain, pour que la confirmation parle de contenu et pas de fichier
 * @param sourcePresent    un fichier existe dans la source éditable
 * @param sourceSha        hash du fichier source, revérifié à l'exécution
 * @param runtimePresent   le serveur connaît ce contenu (dernier relevé)
 * @param bundledExample   le contenu fait partie des exemples embarqués dans le JAR, donc
 *                         <strong>recréé au démarrage</strong> s'il manque sur le serveur
 * @param edits            fichiers de la source que la suppression va réécrire pour retirer la
 *                         référence — jamais supprimer
 * @param blockers         raisons de refuser la suppression. Non vide ⇒ aucune mutation
 * @param notes            informations à afficher sans bloquer (progression joueur, runtime…)
 */
public record DeletionPlan(
        String kind,
        String slug,
        String plainId,
        String label,
        boolean sourcePresent,
        String sourceSha,
        boolean runtimePresent,
        boolean bundledExample,
        List<Edit> edits,
        List<Blocker> blockers,
        List<String> notes) {

    /**
     * Une réécriture de fichier décidée par le plan : retrait d'un prérequis, retrait d'une quête
     * d'une chaîne de story.
     *
     * @param newYaml     contenu complet à écrire — calculé à l'analyse, pas à l'exécution
     * @param expectedSha hash attendu au moment de l'écriture ; une divergence annule tout
     */
    public record Edit(String kind, String slug, String repoPath, String description,
                       String newYaml, String expectedSha) {
    }

    /**
     * Une raison de refuser la suppression.
     *
     * @param where chemin ou identifiant concerné, pour aller droit au bon endroit
     * @param hint  ce que l'opérateur peut faire pour débloquer — jamais « contactez un
     *              administrateur »
     */
    public record Blocker(String reason, String where, String hint) {
    }

    public DeletionPlan {
        edits = List.copyOf(edits == null ? List.of() : edits);
        blockers = List.copyOf(blockers == null ? List.of() : blockers);
        notes = List.copyOf(notes == null ? List.of() : notes);
    }

    /** La suppression peut-elle être exécutée ? */
    public boolean deletable() {
        return blockers.isEmpty() && (sourcePresent || runtimePresent);
    }

    /** Y a-t-il quelque chose à faire côté source ? */
    public boolean touchesSource() {
        return sourcePresent || !edits.isEmpty();
    }
}
