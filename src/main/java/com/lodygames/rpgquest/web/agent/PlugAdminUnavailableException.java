package com.lodygames.rpgquest.web.agent;

/**
 * PlugAdmin est temporairement injoignable (connexion refusée, DNS, timeout, TLS, corps trop
 * volumineux, réponse illisible…). L'agent doit alors appliquer un backoff et réessayer plus tard —
 * <strong>jamais</strong> considérer l'opération comme réussie, jamais perturber le serveur
 * Minecraft. Message toujours affichable, sans secret.
 */
public final class PlugAdminUnavailableException extends RuntimeException {

    public PlugAdminUnavailableException(String message) {
        super(message);
    }

    public PlugAdminUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
