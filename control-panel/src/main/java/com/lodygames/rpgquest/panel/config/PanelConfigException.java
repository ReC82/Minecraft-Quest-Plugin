package com.lodygames.rpgquest.panel.config;

/** Configuration absente ou invalide — le Control Panel refuse de démarrer (fail-closed). */
public final class PanelConfigException extends RuntimeException {

    public PanelConfigException(String message) {
        super(message);
    }
}
