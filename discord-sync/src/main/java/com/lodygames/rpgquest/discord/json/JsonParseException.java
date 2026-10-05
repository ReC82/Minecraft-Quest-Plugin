package com.lodygames.rpgquest.discord.json;

/** Levée quand une chaîne n'est pas du JSON valide (voir {@link Json#parse}). */
public final class JsonParseException extends RuntimeException {

    public JsonParseException(String message) {
        super(message);
    }
}
