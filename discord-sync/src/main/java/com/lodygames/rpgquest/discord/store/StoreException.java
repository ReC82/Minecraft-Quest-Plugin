package com.lodygames.rpgquest.discord.store;

/** Échec d'accès à la base d'état du service (issue #202). Message destiné au journal. */
public class StoreException extends RuntimeException {

    public StoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
