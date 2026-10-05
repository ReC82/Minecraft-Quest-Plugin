package com.lodygames.rpgquest.discord.discord;

import java.util.List;

/**
 * Ce que le service a besoin de faire côté Discord (issue #202) — rien de plus.
 *
 * <p>L'interface existe pour que la logique de synchronisation soit testable sans réseau : les
 * tests fournissent une implémentation en mémoire qui reproduit les comportements qui comptent
 * (sujet déjà traité, événement dupliqué, écriture ambiguë, tags à préserver).</p>
 *
 * <p><strong>Permissions visées, volontairement minimales</strong> : voir le salon, lire
 * l'historique, écrire dans les fils, et — seulement si disponible — gérer les tags du sujet.
 * <strong>Jamais Administrateur.</strong></p>
 */
public interface DiscordApi {

    /** Identité du bot, pour le journal de démarrage. Jamais le jeton. */
    record Identity(String id, String username) {
    }

    /** Un tag disponible dans le salon de forum. */
    record ForumTag(String id, String name) {
    }

    /**
     * Description du salon de forum surveillé.
     *
     * @param id            identifiant du salon
     * @param name          nom du salon
     * @param type          type Discord (15 = forum) — vérifié au démarrage
     * @param availableTags tags configurés par le propriétaire (Bug, Suggestion, statuts…)
     */
    record ForumChannel(String id, String name, int type, List<ForumTag> availableTags) {
        /** Type Discord d'un salon de forum. */
        public static final int TYPE_GUILD_FORUM = 15;

        public boolean isForum() {
            return type == TYPE_GUILD_FORUM;
        }
    }

    Identity self();

    /**
     * Permissions du bot au niveau du serveur, sous forme de masque Discord. Sert à refuser de
     * fonctionner avec la permission Administrateur, que ce service n'a aucune raison d'avoir.
     */
    long guildPermissions(String guildId);

    ForumChannel forumChannel(String forumChannelId);

    /** Sujets actifs du forum, du plus récent au plus ancien. */
    List<ForumPost> activePosts(String guildId, String forumChannelId);

    /** Sujets récemment archivés, bornés : un forum ancien ne doit pas être importé en masse. */
    List<ForumPost> recentlyArchivedPosts(String forumChannelId, int limit);

    /** Un sujet précis, pour l'adoption explicite d'un sujet existant (ex. le sujet TEST). */
    ForumPost post(String threadId);

    /**
     * Publie un message dans le sujet. <strong>Mentions désactivées</strong> : aucun ping de rôle,
     * d'utilisateur ou de {@code @everyone}, quel que soit le contenu du message.
     */
    void postMessage(String threadId, String content);

    /**
     * Remplace la liste des tags du sujet. Discord n'offre pas d'ajout unitaire : l'appelant
     * <strong>doit</strong> transmettre la liste complète voulue, Bug/Suggestion inclus.
     */
    void setAppliedTags(String threadId, List<String> tagIds);
}
