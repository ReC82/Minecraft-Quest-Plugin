package com.lodygames.rpgquest.content.publish;

import com.lodygames.rpgquest.content.reload.ReloadFamily;
import java.util.List;

/**
 * Ce que la publication a besoin de savoir faire du moteur de contenu (issue #47).
 *
 * <h2>Pourquoi cette interface existe</h2>
 *
 * <p>Le rechargement réel exige tous les moteurs du plugin — quêtes, stories, dialogues, PNJ,
 * objets, mobs — donc Bukkit. Dépendre de lui directement rendrait les tests de publication
 * <em>dépendants des moteurs</em> : on vérifierait le chargement d'un YAML de quête là où l'on veut
 * vérifier l'<strong>ordre des opérations</strong>, la détection de conflit, la sauvegarde avant
 * écriture et le refus d'accorder « Synchronisé » sans preuve.</p>
 *
 * <p>Avec cette frontière, toute la logique de #47 s'exécute dans des tests ordinaires, sans
 * serveur. L'implémentation réelle, {@link ReloadServiceApplier}, ne fait que déléguer.</p>
 */
public interface ContentApplier {

    /**
     * Le détail des dépendances d'un rechargement (issue #371) : ce qui a bloqué, ce qui était déjà
     * cassé, et quelles familles ont réellement été rechargées — pour que la fiche du panel nomme
     * la référence fautive au lieu d'un simple code.
     *
     * @param referenceErrors     références que la publication aurait cassées (bloquantes)
     * @param preexistingReferenceErrors références déjà cassées avant, non bloquantes
     * @param families            familles visées par l'opération (jetons {@code wire})
     * @param autoIncludedFamilies familles ajoutées d'office parce que liées
     * @param suggestedFamilies   familles qu'il faudrait encore recharger/publier
     * @param contentIssues       erreurs du validateur du moteur, {@code "fichier : motif"}
     *                            (issue #427) — sans elles, un refus {@code INVALID_CONTENT} ne dit
     *                            ni quel fichier ni pourquoi
     */
    record ReloadDetail(List<String> referenceErrors, List<String> preexistingReferenceErrors,
                        List<String> families, List<String> autoIncludedFamilies,
                        List<String> suggestedFamilies, List<String> contentIssues) {
        public static final ReloadDetail EMPTY =
                new ReloadDetail(List.of(), List.of(), List.of(), List.of(), List.of(), List.of());

        public ReloadDetail(List<String> referenceErrors, List<String> preexistingReferenceErrors,
                            List<String> families, List<String> autoIncludedFamilies,
                            List<String> suggestedFamilies) {
            this(referenceErrors, preexistingReferenceErrors, families, autoIncludedFamilies,
                    suggestedFamilies, List.of());
        }

        public ReloadDetail {
            referenceErrors = referenceErrors == null ? List.of() : List.copyOf(referenceErrors);
            preexistingReferenceErrors = preexistingReferenceErrors == null ? List.of()
                    : List.copyOf(preexistingReferenceErrors);
            families = families == null ? List.of() : List.copyOf(families);
            autoIncludedFamilies = autoIncludedFamilies == null ? List.of() : List.copyOf(autoIncludedFamilies);
            suggestedFamilies = suggestedFamilies == null ? List.of() : List.copyOf(suggestedFamilies);
            contentIssues = contentIssues == null ? List.of() : List.copyOf(contentIssues);
        }
    }

    /** Le résultat d'un rechargement, réduit à ce que la publication en fait. */
    record ApplyResult(boolean applied, String code, String message, int loaded, int issues,
                       String runtimeHash, ReloadDetail detail) {
        public ApplyResult {
            detail = detail == null ? ReloadDetail.EMPTY : detail;
        }

        public ApplyResult(boolean applied, String code, String message, int loaded, int issues,
                           String runtimeHash) {
            this(applied, code, message, loaded, issues, runtimeHash, ReloadDetail.EMPTY);
        }
    }

    /**
     * Recharge la famille <strong>et celles dont elle dépend</strong> (issue #371), en une seule
     * opération : publier une quête ne doit jamais exiger de deviner qu'il faut aussi les PNJ.
     */
    ApplyResult reload(ReloadFamily family);

    /**
     * Recharge plusieurs familles (et leurs dépendances) en <strong>une seule</strong> opération
     * atomique (issue #427) : c'est ce qui permet de publier un pack dont les ressources se citent
     * entre elles sans valider un ensemble temporairement incomplet.
     */
    ApplyResult reload(java.util.Set<ReloadFamily> families);

    /**
     * Même verdict que {@link #reload(java.util.Set)}, <strong>sans rien appliquer</strong> : la
     * vérification préalable d'un pack (issue #427). {@code applied} est toujours faux.
     */
    ApplyResult check(java.util.Set<ReloadFamily> families);

    /** Le moteur porte-t-il cet identifiant ? Comparaison sur la forme « nue ». */
    boolean runtimeHas(ReloadFamily family, String id);

    /** Les identifiants réellement chargés, pour l'affichage et le diagnostic. */
    List<String> loadedIds(ReloadFamily family);

    /** Empreinte du runtime courant. */
    String runtimeHash();
}
