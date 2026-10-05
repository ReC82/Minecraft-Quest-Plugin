package com.lodygames.rpgquest.discord.github;

import java.util.List;
import java.util.Optional;

/**
 * Ce que le service a besoin de faire côté GitHub (issue #202) — rien de plus.
 *
 * <p>Le jeton attendu est à <strong>permissions fines, Issues lecture/écriture sur le seul dépôt
 * visé</strong>. Aucune méthode ici ne touche au code, aux branches, aux workflows ni aux
 * étiquettes de publication {@code #news}/{@code #soon}.</p>
 */
public interface GitHubApi {

    /**
     * Métadonnées du dépôt, lues au démarrage.
     *
     * @param fullName   {@code propriétaire/dépôt}
     * @param visibility {@code public} ou {@code private} — conditionne le texte d'avertissement
     *                   affiché aux membres du forum : dire « sera visible publiquement » sur un
     *                   dépôt privé serait faux, et l'inverse serait trompeur
     * @param hasIssues  les issues sont-elles activées
     */
    record Repository(String fullName, String visibility, boolean hasIssues) {
        public boolean isPublic() {
            return "public".equalsIgnoreCase(visibility);
        }
    }

    Repository repository();

    /** S'assure qu'une étiquette existe, en la créant au besoin. Idempotent. */
    void ensureLabel(String name, String color, String description);

    Issue createIssue(String title, String body, List<String> labels);

    /** Met à jour titre et corps. {@code null} = champ laissé tel quel. */
    Issue updateIssue(int number, String title, String body);

    Optional<Issue> issue(int number);

    /**
     * Issues portant l'étiquette donnée, ouvertes <strong>et</strong> fermées, triées par date de
     * modification décroissante.
     *
     * @param etag ETag du dernier relevé, ou {@code null}. Une requête conditionnelle qui renvoie
     *             304 ne consomme pas de quota GitHub : c'est ce qui rend la scrutation soutenable.
     */
    Listing listIssuesByLabel(String label, String etag, int limit);

    /**
     * Résultat d'un relevé.
     *
     * @param notModified vrai si GitHub a répondu 304 : {@code issues} est alors vide et il n'y a
     *                    rien à faire — surtout pas conclure que les issues ont disparu
     */
    record Listing(boolean notModified, String etag, List<Issue> issues) {
    }
}
