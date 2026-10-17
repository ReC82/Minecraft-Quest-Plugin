package com.lodygames.rpgquest.economy;

import java.util.UUID;

/**
 * Prévenu après chaque écriture <strong>réussie</strong> dans un portefeuille (issue #184), pour
 * qu'un affichage (HUD, action bar) se rafraîchisse depuis la source réelle au lieu de tenir un
 * solde parallèle. Appelé hors du thread principal : l'implémentation doit y revenir elle-même.
 */
@FunctionalInterface
public interface WalletChangeListener {

    /** {@code delta} : variation signée ; {@code 0} quand le solde a été fixé (réglage admin). */
    void walletChanged(UUID playerId, long delta);

    WalletChangeListener NONE = (playerId, delta) -> {
    };
}
