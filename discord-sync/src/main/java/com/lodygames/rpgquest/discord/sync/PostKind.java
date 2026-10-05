package com.lodygames.rpgquest.discord.sync;

import com.lodygames.rpgquest.discord.discord.DiscordApi;
import java.util.List;
import java.util.Locale;

/**
 * Nature d'un sujet du forum, déduite de ses tags Discord (issue #202).
 *
 * <p>La déduction se fait sur le <strong>nom</strong> du tag, pas sur son identifiant : le
 * propriétaire configure ses tags lui-même, et un identifiant codé en dur casserait à la première
 * recréation de tag. Si aucun tag connu n'est posé, le sujet est traité comme une
 * <strong>suggestion</strong> — c'est le choix le moins engageant : étiqueter à tort un message
 * comme bug gonflerait artificiellement le nombre de défauts.</p>
 */
public enum PostKind {

    /** Signalement de défaut. */
    BUG("Bug", "type:bug"),

    /** Demande ou idée. */
    REQUEST("Suggestion", "type:request");

    private final String frenchLabel;
    private final String githubLabel;

    PostKind(String frenchLabel, String githubLabel) {
        this.frenchLabel = frenchLabel;
        this.githubLabel = githubLabel;
    }

    public String frenchLabel() {
        return frenchLabel;
    }

    /** Étiquette GitHub correspondante. */
    public String githubLabel() {
        return githubLabel;
    }

    /**
     * Déduit la nature d'un sujet depuis les tags qui lui sont appliqués.
     *
     * @param appliedTagIds identifiants des tags posés sur le sujet
     * @param availableTags tags configurés dans le salon, pour retrouver leur nom
     */
    public static PostKind fromTags(List<String> appliedTagIds,
                                    List<DiscordApi.ForumTag> availableTags) {
        if (appliedTagIds == null || availableTags == null) {
            return REQUEST;
        }
        for (String tagId : appliedTagIds) {
            for (DiscordApi.ForumTag tag : availableTags) {
                if (!tag.id().equals(tagId)) {
                    continue;
                }
                String name = tag.name() == null ? "" : tag.name().toLowerCase(Locale.ROOT);
                if (name.contains("bug") || name.contains("défaut") || name.contains("defaut")
                        || name.contains("problème") || name.contains("probleme")) {
                    return BUG;
                }
            }
        }
        return REQUEST;
    }
}
