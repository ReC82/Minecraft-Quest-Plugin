package com.lodygames.rpgquest.discord.support;

import com.lodygames.rpgquest.discord.discord.DiscordApi;
import com.lodygames.rpgquest.discord.discord.ForumPost;
import com.lodygames.rpgquest.discord.http.RestException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Forum Discord en mémoire pour les tests (issue #202).
 *
 * <p>Reproduit les comportements qui comptent réellement : les listes ne portent pas le message
 * initial (comme l'API), les tags appliqués sont remplacés en bloc, et une panne peut être
 * injectée sur n'importe quelle écriture.</p>
 */
public final class FakeDiscord implements DiscordApi {

    private final Map<String, ForumPost> posts = new LinkedHashMap<>();
    private final List<ForumTag> availableTags = new ArrayList<>();

    /** Messages publiés, par identifiant de sujet — ce que les membres verraient. */
    public final Map<String, List<String>> messages = new LinkedHashMap<>();

    public long guildPermissions = 0L;
    public int forumType = ForumChannel.TYPE_GUILD_FORUM;

    /** Si non nul, toute publication de message échoue avec cette erreur. */
    public RestException failPostMessage;

    public void addTag(String id, String name) {
        availableTags.add(new ForumTag(id, name));
    }

    public void addPost(ForumPost post) {
        posts.put(post.threadId(), post);
    }

    public ForumPost storedPost(String threadId) {
        return posts.get(threadId);
    }

    @Override
    public Identity self() {
        return new Identity("1", "bot-de-test");
    }

    @Override
    public long guildPermissions(String guildId) {
        return guildPermissions;
    }

    @Override
    public ForumChannel forumChannel(String forumChannelId) {
        return new ForumChannel(forumChannelId, "bugs-et-suggestions", forumType,
                List.copyOf(availableTags));
    }

    @Override
    public List<ForumPost> activePosts(String guildId, String forumChannelId) {
        // Comme l'API réelle : pas de contenu de message initial dans une liste.
        List<ForumPost> out = new ArrayList<>();
        for (ForumPost post : posts.values()) {
            if (!post.archived()) {
                out.add(withoutContent(post));
            }
        }
        return out;
    }

    @Override
    public List<ForumPost> recentlyArchivedPosts(String forumChannelId, int limit) {
        List<ForumPost> out = new ArrayList<>();
        for (ForumPost post : posts.values()) {
            if (post.archived() && out.size() < limit) {
                out.add(withoutContent(post));
            }
        }
        return out;
    }

    private static ForumPost withoutContent(ForumPost post) {
        return new ForumPost(post.threadId(), post.title(), post.authorId(), "", null, "",
                List.of(), post.appliedTagIds(), post.archived());
    }

    @Override
    public ForumPost post(String threadId) {
        ForumPost post = posts.get(threadId);
        if (post == null) {
            throw new RestException("Sujet " + threadId + " introuvable.", 404, false);
        }
        return post;
    }

    @Override
    public void postMessage(String threadId, String content) {
        if (failPostMessage != null) {
            throw failPostMessage;
        }
        messages.computeIfAbsent(threadId, key -> new ArrayList<>()).add(content);
    }

    @Override
    public void setAppliedTags(String threadId, List<String> tagIds) {
        ForumPost post = post(threadId);
        posts.put(threadId, new ForumPost(post.threadId(), post.title(), post.authorId(),
                post.authorName(), post.createdAt(), post.content(), post.attachments(),
                List.copyOf(tagIds), post.archived()));
    }
}
