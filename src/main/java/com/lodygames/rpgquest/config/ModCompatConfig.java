package com.lodygames.rpgquest.config;

/**
 * Politique de compatibilité avec le mod client prototype (mission étape
 * 23, point 9) : {@code requireMod=false} (par défaut) autorise le client
 * vanilla avec repli — un joueur sans mod ou avec la mauvaise version joue
 * normalement, simplement sans le contenu cosmétique du mod ;
 * {@code requireMod=true} exige le mod (kick sinon), seulement si activé
 * explicitement. Voir docs/CLIENT_MOD.md.
 */
public record ModCompatConfig(boolean requireMod, int handshakeTimeoutTicks) {
}
