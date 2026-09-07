package com.lodygames.rpgquest.panel.bridge;

/**
 * Le bridge RPGQuest d'une cible n'a pas pu être interrogé ou a répondu de façon inexploitable.
 * Le message est destiné à être <strong>affiché tel quel</strong> à l'owner (« RPGQuest DEV
 * indisponible : … ») — jamais une stacktrace, jamais un secret.
 */
public final class BridgeException extends RuntimeException {

    public BridgeException(String message) {
        super(message);
    }

    public BridgeException(String message, Throwable cause) {
        super(message, cause);
    }
}
