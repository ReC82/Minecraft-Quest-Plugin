package com.lodygames.rpgquest.discord.config;

/**
 * Configuration inutilisable. Le message est destiné à être lu tel quel dans
 * {@code journalctl} : il doit dire <strong>quelle clé</strong> pose problème et
 * <strong>quoi faire</strong>, sans jamais citer la valeur d'un secret.
 */
public class BotConfigException extends RuntimeException {

    public BotConfigException(String message) {
        super(message);
    }

    public BotConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
