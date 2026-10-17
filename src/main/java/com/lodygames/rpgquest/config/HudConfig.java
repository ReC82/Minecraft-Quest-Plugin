package com.lodygames.rpgquest.config;

/**
 * Section {@code hud:} (issue #184, prototype d'affichage à valider en jeu).
 *
 * @param defaultMode    surface proposée à un joueur qui n'a encore rien choisi avec {@code /hud}
 * @param walletFeedback afficher dans l'action bar chaque gain/dépense réellement passé au portefeuille
 */
public record HudConfig(HudMode defaultMode, boolean walletFeedback) {

    public static HudConfig defaults() {
        return new HudConfig(HudMode.OFF, true);
    }
}
