package com.lodygames.rpgquest.web.link;

/**
 * Réponse du portail à une preuve de liaison (issue #53) — mêmes noms que {@code LinkOutcome} côté
 * portail, plus {@link #UNAVAILABLE} quand le portail ne répond pas ou répond autre chose.
 */
public enum LinkReply {
    LINKED,
    INVALID_CODE,
    UUID_ALREADY_LINKED,
    ACCOUNT_ALREADY_LINKED,
    ACCOUNT_RESTRICTED,
    TOO_MANY_ATTEMPTS,
    UNAVAILABLE;

    static LinkReply parse(String result) {
        for (LinkReply reply : values()) {
            if (reply != UNAVAILABLE && reply.name().equals(result)) {
                return reply;
            }
        }
        return UNAVAILABLE;
    }

    /** Message MiniMessage montré au joueur : jamais d'information sur le compte web. */
    public String playerMessage() {
        return switch (this) {
            case LINKED -> "<green>C'est fait : ton compte Minecraft est relié à ton compte du portail.</green>";
            case INVALID_CODE -> "<red>Code invalide ou expiré.</red> <gray>Demande un nouveau code sur le portail, "
                    + "page « Mon compte ».</gray>";
            case UUID_ALREADY_LINKED -> "<red>Ton compte Minecraft est déjà relié à un compte du portail.</red> "
                    + "<gray>Délie-le d'abord depuis ce compte.</gray>";
            case ACCOUNT_ALREADY_LINKED -> "<red>Ce compte du portail est déjà relié à un autre compte Minecraft.</red>";
            case ACCOUNT_RESTRICTED -> "<red>Ce compte du portail ne peut pas encore être relié.</red>";
            case TOO_MANY_ATTEMPTS -> "<red>Trop d'essais.</red> <gray>Réessaie dans quelques minutes.</gray>";
            case UNAVAILABLE -> "<red>Le portail ne répond pas pour le moment.</red> <gray>Réessaie plus tard.</gray>";
        };
    }
}
