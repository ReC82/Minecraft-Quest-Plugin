package com.lodygames.rpgquest.discord.discord;

import com.lodygames.rpgquest.discord.http.RestClient;
import com.lodygames.rpgquest.discord.http.RestException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Implémentation réelle de {@link DiscordApi} sur l'API REST v10 (issue #202).
 *
 * <p><strong>Pourquoi de la scrutation et pas la passerelle temps réel.</strong> Un bot Discord
 * peut écouter les événements via une connexion WebSocket permanente. Pour ce service, la
 * scrutation REST a été préférée : elle évite tout l'appareillage de la passerelle (identification,
 * battements de cœur, reprise de session, tempêtes de reconnexion), elle rend le redémarrage
 * trivialement sûr — on relit l'état, on ne rejoue pas un flux d'événements — et un forum
 * communautaire n'a pas besoin de mieux qu'une minute de latence. Le coût assumé est cette
 * latence ; la passerelle reste possible plus tard sans rien changer à cette interface.</p>
 *
 * <p><strong>Lectures bornées.</strong> Les listes de sujets ne portent pas le contenu du message
 * initial : il est lu à la demande, sujet par sujet, et seulement pour les sujets réellement
 * concernés. Un forum qui grossit ne provoque donc pas un balayage complet à chaque tour.</p>
 */
public final class HttpDiscordApi implements DiscordApi {

    private static final String BASE = "https://discord.com/api/v10";

    /** Bit de la permission Administrateur, que ce service refuse d'utiliser. */
    public static final long PERMISSION_ADMINISTRATOR = 0x8L;

    private final RestClient client;
    private final String authorization;

    public HttpDiscordApi(RestClient client, String botToken) {
        this.client = client;
        this.authorization = "Bot " + botToken;
    }

    private Map<String, String> headers() {
        return Map.of("Authorization", authorization);
    }

    @Override
    public Identity self() {
        Map<String, Object> me = client.call("GET", URI.create(BASE + "/users/@me"), headers()).object();
        return new Identity(str(me.get("id")), str(me.get("username")));
    }

    @Override
    public long guildPermissions(String guildId) {
        List<Object> guilds =
                client.call("GET", URI.create(BASE + "/users/@me/guilds"), headers()).array();
        for (Object entry : guilds) {
            if (entry instanceof Map<?, ?> guild && guildId.equals(str(guild.get("id")))) {
                String permissions = str(guild.get("permissions"));
                try {
                    return permissions == null ? 0L : Long.parseUnsignedLong(permissions);
                } catch (NumberFormatException e) {
                    return 0L;
                }
            }
        }
        throw new RestException("Le bot n'est membre d'aucun serveur portant l'identifiant "
                + guildId + ". Vérifier DISCORD_GUILD_ID et l'invitation du bot.", 404, false);
    }

    @Override
    public ForumChannel forumChannel(String forumChannelId) {
        Map<String, Object> channel =
                client.call("GET", URI.create(BASE + "/channels/" + forumChannelId), headers()).object();
        List<ForumTag> tags = new ArrayList<>();
        if (channel.get("available_tags") instanceof List<?> list) {
            for (Object entry : list) {
                if (entry instanceof Map<?, ?> tag) {
                    tags.add(new ForumTag(str(tag.get("id")), str(tag.get("name"))));
                }
            }
        }
        return new ForumChannel(str(channel.get("id")), str(channel.get("name")),
                (int) num(channel.get("type")), tags);
    }

    @Override
    public List<ForumPost> activePosts(String guildId, String forumChannelId) {
        Map<String, Object> response = client.call("GET",
                URI.create(BASE + "/guilds/" + guildId + "/threads/active"), headers()).object();
        return threadsOf(response.get("threads"), forumChannelId);
    }

    @Override
    public List<ForumPost> recentlyArchivedPosts(String forumChannelId, int limit) {
        int bounded = Math.max(1, Math.min(limit, 100));
        Map<String, Object> response = client.call("GET",
                URI.create(BASE + "/channels/" + forumChannelId + "/threads/archived/public?limit="
                        + bounded), headers()).object();
        return threadsOf(response.get("threads"), forumChannelId);
    }

    /** Sujets d'une réponse, filtrés sur le salon surveillé — jamais « tous les forums ». */
    private List<ForumPost> threadsOf(Object raw, String forumChannelId) {
        List<ForumPost> posts = new ArrayList<>();
        if (!(raw instanceof List<?> list)) {
            return posts;
        }
        for (Object entry : list) {
            if (!(entry instanceof Map<?, ?> thread)) {
                continue;
            }
            if (!forumChannelId.equals(str(thread.get("parent_id")))) {
                continue;
            }
            posts.add(threadWithoutContent(thread));
        }
        return posts;
    }

    /**
     * Sujet sans son message initial : les listes ne le portent pas, et aller le chercher pour
     * chaque sujet à chaque tour serait un balayage inutile.
     */
    private static ForumPost threadWithoutContent(Map<?, ?> thread) {
        boolean archived = false;
        if (thread.get("thread_metadata") instanceof Map<?, ?> metadata) {
            archived = Boolean.TRUE.equals(metadata.get("archived"));
        }
        return new ForumPost(
                str(thread.get("id")),
                str(thread.get("name")),
                str(thread.get("owner_id")),
                "",
                null,
                "",
                List.of(),
                stringList(thread.get("applied_tags")),
                archived);
    }

    @Override
    public ForumPost post(String threadId) {
        Map<String, Object> thread =
                client.call("GET", URI.create(BASE + "/channels/" + threadId), headers()).object();

        // Dans un salon de forum, le message initial porte le MÊME identifiant que le sujet.
        // C'est une propriété de l'API, pas une supposition : elle évite de lister l'historique.
        Map<String, Object> starter = Map.of();
        try {
            starter = client.call("GET",
                    URI.create(BASE + "/channels/" + threadId + "/messages/" + threadId),
                    headers()).object();
        } catch (RestException e) {
            // Message initial supprimé, ou permission « Lire l'historique » absente. Le sujet
            // reste exploitable : le corps d'issue le dira explicitement au lieu d'inventer.
            if (e.status() != 403 && e.status() != 404) {
                throw e;
            }
        }

        String authorId = str(thread.get("owner_id"));
        String authorName = "";
        if (starter.get("author") instanceof Map<?, ?> author) {
            String globalName = str(author.get("global_name"));
            authorName = globalName != null && !globalName.isBlank()
                    ? globalName : str(author.get("username"));
            String id = str(author.get("id"));
            if (id != null && !id.isBlank()) {
                authorId = id;
            }
        }

        List<ForumPost.Attachment> attachments = new ArrayList<>();
        if (starter.get("attachments") instanceof List<?> list) {
            for (Object entry : list) {
                if (entry instanceof Map<?, ?> attachment) {
                    attachments.add(new ForumPost.Attachment(
                            str(attachment.get("filename")),
                            (long) num(attachment.get("size")),
                            str(attachment.get("url"))));
                }
            }
        }

        boolean archived = false;
        if (thread.get("thread_metadata") instanceof Map<?, ?> metadata) {
            archived = Boolean.TRUE.equals(metadata.get("archived"));
        }

        return new ForumPost(
                str(thread.get("id")),
                str(thread.get("name")),
                authorId,
                authorName,
                str(starter.get("timestamp")),
                str(starter.get("content")),
                attachments,
                stringList(thread.get("applied_tags")),
                archived);
    }

    @Override
    public void postMessage(String threadId, String content) {
        // allowed_mentions vide = aucune mention effective, quel que soit le texte. Un contenu
        // utilisateur recopié ne peut donc jamais déclencher de ping.
        Map<String, Object> body = Map.of(
                "content", content,
                "allowed_mentions", Map.of("parse", List.of()));
        client.call("POST", URI.create(BASE + "/channels/" + threadId + "/messages"),
                headers(), body);
    }

    @Override
    public void setAppliedTags(String threadId, List<String> tagIds) {
        client.call("PATCH", URI.create(BASE + "/channels/" + threadId), headers(),
                Map.of("applied_tags", List.copyOf(tagIds)));
    }

    // ---- Lecture défensive du JSON ---------------------------------------------------------

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static double num(Object value) {
        return value instanceof Number n ? n.doubleValue() : 0d;
    }

    private static List<String> stringList(Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object entry : list) {
                if (entry != null) {
                    out.add(String.valueOf(entry));
                }
            }
        }
        return out;
    }
}
