package com.lodygames.rpgquest.web;

/** Levée par {@link Json#parse(String)} sur un JSON tronqué ou malformé. */
public final class JsonParseException extends RuntimeException {

    public JsonParseException(String message) {
        super(message);
    }
}
