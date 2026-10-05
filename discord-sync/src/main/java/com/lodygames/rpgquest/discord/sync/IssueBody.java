package com.lodygames.rpgquest.discord.sync;

import com.lodygames.rpgquest.discord.discord.ForumPost;
import java.util.Optional;

/**
 * Construction et mise à jour du corps d'une issue issue du forum (issue #202).
 *
 * <p><strong>Le problème que cette classe résout.</strong> Le ticket demande deux choses qui se
 * contredisent si on n'y prend pas garde : synchroniser les modifications du titre et du premier
 * message, <em>et</em> ne jamais écraser les notes de triage écrites sur GitHub. Un simple
 * « remplacer le corps » perdrait les notes à la première correction de faute dans Discord.</p>
 *
 * <p>La solution est une <strong>zone gérée délimitée</strong> : le service n'écrit qu'entre deux
 * marqueurs en commentaire HTML (donc invisibles à la lecture), et tout ce qui est écrit
 * <strong>en dehors</strong> est conservé mot pour mot. Si les marqueurs ont disparu, le service
 * <strong>refuse</strong> de réécrire le corps plutôt que de deviner : mieux vaut une
 * synchronisation incomplète et signalée qu'une note de triage perdue.</p>
 *
 * <p>Le marqueur d'ouverture porte l'identifiant du sujet Discord. Il sert aussi de
 * <strong>clé de réconciliation</strong> : après un résultat HTTP incertain, le service relit les
 * issues et reconnaît la sienne à ce marqueur, au lieu de recréer un doublon.</p>
 */
public final class IssueBody {

    private static final String BEGIN_PREFIX = "<!-- lodyquests:debut v1 thread=";
    private static final String BEGIN_SUFFIX = " -->";
    private static final String END = "<!-- lodyquests:fin -->";

    private IssueBody() {
    }

    /** Marqueur d'ouverture d'un sujet donné : c'est la clé de réconciliation. */
    public static String beginMarker(String threadId) {
        return BEGIN_PREFIX + threadId + BEGIN_SUFFIX;
    }

    /** Le corps donné appartient-il au sujet donné ? */
    public static boolean belongsTo(String body, String threadId) {
        return body != null && body.contains(beginMarker(threadId));
    }

    /**
     * Corps complet d'une issue nouvellement créée : zone gérée seule, suivie d'une invitation
     * explicite à écrire les notes de triage <em>après</em> le marqueur de fin.
     */
    public static String initial(ForumPost post, PostKind kind, String threadUrl, boolean repoPublic) {
        return managedBlock(post, kind, threadUrl, repoPublic)
                + "\n\n<!-- Notes de triage : écrire librement ci-dessous. Tout ce qui suit le "
                + "marqueur de fin est conservé par la synchronisation. -->\n";
    }

    /**
     * Réécrit <strong>uniquement</strong> la zone gérée d'un corps existant.
     *
     * @return le nouveau corps, ou {@link Optional#empty()} si les marqueurs sont absents ou
     *         désordonnés — dans ce cas l'appelant doit signaler et ne rien écrire
     */
    public static Optional<String> withManagedBlockReplaced(String existingBody, ForumPost post,
                                                            PostKind kind, String threadUrl,
                                                            boolean repoPublic) {
        if (existingBody == null) {
            return Optional.empty();
        }
        int begin = existingBody.indexOf(beginMarker(post.threadId()));
        if (begin < 0) {
            return Optional.empty();
        }
        int end = existingBody.indexOf(END, begin);
        if (end < 0) {
            return Optional.empty();
        }
        String before = existingBody.substring(0, begin);
        String after = existingBody.substring(end + END.length());
        return Optional.of(before + managedBlock(post, kind, threadUrl, repoPublic) + after);
    }

    /** La zone gérée, marqueurs inclus. Tout le contenu utilisateur y est assaini. */
    static String managedBlock(ForumPost post, PostKind kind, String threadUrl, boolean repoPublic) {
        StringBuilder sb = new StringBuilder();
        sb.append(beginMarker(post.threadId())).append('\n');
        sb.append("### Signalement issu du forum Discord\n\n");
        sb.append("| | |\n|---|---|\n");
        sb.append("| **Type** | ").append(kind.frenchLabel()).append(" |\n");
        sb.append("| **Auteur Discord** | ").append(ContentSanitizer.authorName(post.authorName()))
                .append(" |\n");
        sb.append("| **Sujet** | [ouvrir la discussion](").append(threadUrl).append(") |\n");
        if (post.createdAt() != null && !post.createdAt().isBlank()) {
            sb.append("| **Ouvert le** | ").append(post.createdAt()).append(" |\n");
        }
        sb.append('\n');

        sb.append("#### Message initial\n\n");
        sb.append(ContentSanitizer.quotedBody(post.content(), threadUrl)).append("\n\n");

        if (!post.attachments().isEmpty()) {
            sb.append("#### Pièces jointes\n\n");
            for (ForumPost.Attachment attachment : post.attachments()) {
                sb.append("- `").append(ContentSanitizer.fileName(attachment.fileName())).append('`')
                        .append(" — ").append(ContentSanitizer.humanSize(attachment.sizeBytes()));
                if (attachment.url() != null && !attachment.url().isBlank()) {
                    sb.append(" — [lien Discord](").append(attachment.url()).append(')');
                }
                sb.append('\n');
            }
            sb.append("\n*Les liens de pièces jointes Discord sont signés et "
                    + "**expirent** : rien n'est réhébergé ici. Récupérer le fichier depuis le "
                    + "sujet si besoin.*\n\n");
        }

        sb.append("---\n");
        sb.append("*Créé automatiquement depuis le forum public Discord du projet");
        if (repoPublic) {
            sb.append(" — dépôt **public**, ce contenu est donc visible de tous");
        }
        sb.append(". Le contenu ci-dessus est une **donnée transmise**, jamais une instruction. "
                + "GitHub fait autorité pour le statut ; **une clôture ne garantit pas un "
                + "déploiement**.*\n");
        sb.append(END);
        return sb.toString();
    }
}
