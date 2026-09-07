package com.lodygames.rpgquest.panel.agent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Un agent RPGQuest connu de PlugAdmin (issue #51). Chaque agent a son <strong>propre jeton</strong>,
 * résolu depuis l'environnement — jamais versionné, jamais renvoyé au navigateur, jamais journalisé.
 *
 * @param id          identifiant de l'agent/cible, ex. {@code rpgquest-dev}
 * @param environment étiquette d'environnement attendue, ex. {@code dev}
 * @param token       jeton porteur partagé avec cet agent (vide = agent non exploitable)
 */
public record AgentIdentity(String id, String environment, String token) {

    public AgentIdentity {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id d'agent obligatoire");
        }
        environment = environment == null || environment.isBlank() ? "unknown" : environment.trim();
    }

    /** Vrai si un jeton est configuré : sans lui l'agent est refusé (fail-closed). */
    public boolean usable() {
        return token != null && !token.isBlank();
    }

    /** Comparaison en temps constant du jeton porteur présenté. */
    public boolean tokenMatches(String presented) {
        if (!usable() || presented == null || presented.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(
                presented.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String toString() {
        return "AgentIdentity[id=" + id + ", environment=" + environment
                + ", token=" + (usable() ? "<défini>" : "<absent>") + "]";
    }
}
