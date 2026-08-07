package com.lodygames.rpgquest.config;

/** Décrit précisément quelle valeur de {@code config.yml} est invalide et pourquoi. */
public final class ConfigValidationException extends Exception {

    public ConfigValidationException(String message) {
        super(message);
    }
}
