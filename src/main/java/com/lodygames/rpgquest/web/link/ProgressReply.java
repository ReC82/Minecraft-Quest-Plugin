package com.lodygames.rpgquest.web.link;

/**
 * Réponse du portail à {@code POST /api/link/progress} (issue #53), plus {@link #UNAVAILABLE} quand
 * il ne répond pas ou répond autre chose.
 */
public enum ProgressReply {
    /** Sonde : cet UUID est relié à un compte du portail. */
    LINKED,
    /** Progression enregistrée pour le titulaire du compte relié. */
    STORED,
    /** Aucun compte relié : rien n'a été conservé. */
    NOT_LINKED,
    /** Envoi trop rapproché du précédent pour ce joueur : ignoré, réessayer plus tard. */
    THROTTLED,
    UNAVAILABLE;

    static ProgressReply parse(String result) {
        for (ProgressReply reply : values()) {
            if (reply != UNAVAILABLE && reply.name().equals(result)) {
                return reply;
            }
        }
        return UNAVAILABLE;
    }
}
