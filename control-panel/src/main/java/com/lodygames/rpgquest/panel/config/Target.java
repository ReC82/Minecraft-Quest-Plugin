package com.lodygames.rpgquest.panel.config;

/**
 * Une cible RPGQuest joignable par le Control Panel (issue #37, exigence multi-environnement). Le
 * panel ne raisonne jamais en constantes globales ({@code localhost}, {@code world_hub}…) : chaque
 * requête backend porte une cible.
 *
 * @param id            identifiant court, ex. {@code dev} (segment d'URL / clé de config)
 * @param label         libellé affiché, ex. {@code VeryGames DEV}
 * @param mode          {@link Mode#BRIDGE} (bridge HTTP live) ou {@link Mode#SNAPSHOT} (mode dégradé, futur)
 * @param bridgeBaseUrl base des routes du bridge, ex. {@code http://127.0.0.1:8100/admin/v1}
 * @param bridgeToken   jeton porteur — résolu depuis l'environnement, jamais versionné
 */
public record Target(String id, String label, Mode mode, String bridgeBaseUrl, String bridgeToken) {

    public enum Mode {
        BRIDGE,
        SNAPSHOT
    }

    public Target {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id de cible obligatoire");
        }
        label = label == null || label.isBlank() ? id : label;
        mode = mode == null ? Mode.BRIDGE : mode;
    }

    /** Vrai si la cible est exploitable en mode live (bridge + jeton présents). */
    public boolean bridgeUsable() {
        return mode == Mode.BRIDGE
                && bridgeBaseUrl != null && !bridgeBaseUrl.isBlank()
                && bridgeToken != null && !bridgeToken.isBlank();
    }
}
