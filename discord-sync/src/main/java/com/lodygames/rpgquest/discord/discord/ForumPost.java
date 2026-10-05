package com.lodygames.rpgquest.discord.discord;

import java.util.List;

/**
 * Un sujet du forum communautaire, tel que Discord le rend (issue #202).
 *
 * <p>Pour un salon de forum, l'identifiant du sujet est aussi celui de son <strong>message
 * initial</strong> : c'est ce qui permet d'aller chercher le contenu du premier message sans
 * deviner, et c'est aussi la clé de réconciliation « un sujet = une issue ».</p>
 *
 * <p>Tout ce qui vient d'ici est une <strong>donnée utilisateur</strong>, jamais une instruction :
 * {@code title}, {@code content}, {@code authorName} et les noms de pièces jointes sont assainis
 * avant d'être écrits où que ce soit.</p>
 *
 * @param threadId      identifiant du sujet (= identifiant du message initial)
 * @param title         titre du sujet, tel que saisi
 * @param authorId      identifiant Discord de l'auteur
 * @param authorName    nom affiché de l'auteur
 * @param createdAt     date ISO-8601 de création, telle que fournie par Discord
 * @param content       texte du message initial ({@code ""} si le bot ne peut pas le lire)
 * @param attachments   pièces jointes du message initial
 * @param appliedTagIds identifiants des tags déjà appliqués au sujet — à préserver
 * @param archived      le sujet est-il archivé
 */
public record ForumPost(
        String threadId,
        String title,
        String authorId,
        String authorName,
        String createdAt,
        String content,
        List<Attachment> attachments,
        List<String> appliedTagIds,
        boolean archived) {

    /**
     * Référence d'une pièce jointe. On ne télécharge rien et on ne réhéberge rien : seuls le nom,
     * la taille et l'URL sont repris, avec la mise en garde que les liens Discord
     * <strong>expirent</strong> (ils sont signés et temporaires).
     */
    public record Attachment(String fileName, long sizeBytes, String url) {
    }

    public ForumPost {
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
        appliedTagIds = appliedTagIds == null ? List.of() : List.copyOf(appliedTagIds);
        content = content == null ? "" : content;
    }
}
