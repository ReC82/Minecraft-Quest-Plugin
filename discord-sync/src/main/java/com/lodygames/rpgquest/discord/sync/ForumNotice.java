package com.lodygames.rpgquest.discord.sync;

import com.lodygames.rpgquest.discord.github.GitHubApi;
import java.util.List;

/**
 * Texte d'information destiné aux consignes du forum (issue #202).
 *
 * <p><strong>Pourquoi ce texte est généré et non écrit en dur.</strong> Le ticket demande
 * d'expliquer aux membres que leur contenu est transmis à GitHub « en tenant compte de la
 * visibilité réelle du dépôt ». Dire « votre message sera public » sur un dépôt privé serait faux ;
 * taire la publication sur un dépôt public serait trompeur. La visibilité est donc lue sur
 * l'API GitHub, et le texte s'adapte.</p>
 *
 * <p>Le service ne publie pas ce texte lui-même : les consignes d'un forum appartiennent à son
 * propriétaire. La commande {@code notice} l'imprime, prêt à coller.</p>
 */
public final class ForumNotice {

    /**
     * Noms de tags que le service sait reconnaître comme tags de statut. Les créer dans le salon
     * est <strong>facultatif</strong> : sans eux, le statut est annoncé par message seulement.
     */
    public static final List<String> STATUS_TAG_NAMES = List.of(
            SyncStatus.TRIAGE.label(),
            SyncStatus.IN_PROGRESS.label(),
            SyncStatus.NEEDS_TESTING.label(),
            SyncStatus.RESOLVED.label(),
            SyncStatus.DECLINED.label(),
            SyncStatus.DUPLICATE.label());

    private ForumNotice() {
    }

    /** Texte adapté à la visibilité réelle du dépôt. */
    public static String text(GitHubApi.Repository repository) {
        String visibility = repository.isPublic()
                ? """
                **Votre message sera visible publiquement.** Le dépôt `%s` est **public** : le \
                titre, votre message initial, votre nom d'affichage Discord et les noms de vos \
                pièces jointes y seront recopiés et consultables par n'importe qui, moteurs de \
                recherche compris. N'y mettez aucune donnée personnelle, aucun identifiant et \
                aucune capture contenant des informations privées."""
                .formatted(repository.fullName())
                : """
                Le dépôt `%s` est **privé** : le titre, votre message initial, votre nom \
                d'affichage Discord et les noms de vos pièces jointes y seront recopiés, mais ne \
                seront visibles que des personnes ayant accès à ce dépôt. Évitez malgré tout \
                d'y mettre des données personnelles."""
                .formatted(repository.fullName());

        return """
                ## Comment signaler un bug ou proposer une idée

                1. Créez un **nouveau sujet** dans ce forum.
                2. Posez le tag **Bug** ou **Suggestion**.
                3. Décrivez le problème ou l'idée dans le premier message : ce qui se passe, ce \
                que vous attendiez, et comment le reproduire.

                Un robot crée alors automatiquement une fiche de suivi et **répond dans votre \
                sujet avec le lien**. Vous n'avez pas besoin de compte GitHub : la discussion \
                continue ici.

                ### Ce qui est transmis

                %s

                Seuls le **titre** et le **premier message** sont recopiés. Les messages suivants \
                de la discussion restent ici, sur Discord.

                ### Suivi du statut

                Les changements de statut sont annoncés dans votre sujet :

                | Statut | Ce que ça veut dire |
                |---|---|
                | **À trier** | Reçu, en attente de tri. |
                | **En cours** | Le travail a commencé. |
                | **À tester** | Un correctif existe et attend une vérification. |
                | **Résolu** | Réglé côté développement. **Cela ne veut pas dire que c'est déjà \
                en ligne sur le serveur.** |
                | **Refusé** | Ne sera pas réalisé. Vous pouvez continuer à en discuter ici. |
                | **Doublon** | Déjà signalé ailleurs ; le suivi continue sur l'autre fiche. |

                Modifier le titre ou le premier message met la fiche à jour. Les notes de travail \
                ajoutées côté suivi ne sont pas recopiées ici.
                """.formatted(visibility);
    }
}
