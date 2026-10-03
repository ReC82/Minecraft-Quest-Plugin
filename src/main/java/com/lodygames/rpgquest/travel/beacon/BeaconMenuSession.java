package com.lodygames.rpgquest.travel.beacon;

/** État en mémoire du menu de voyage ouvert pour un joueur (issues #132/#150). */
final class BeaconMenuSession {

    private final int page;
    private final String filter;

    BeaconMenuSession(int page, String filter) {
        this.page = page;
        this.filter = filter;
    }

    int page() {
        return page;
    }

    /** Déjà normalisé (casse/accents) — jamais vide, {@code ""} = aucun filtre. */
    String filter() {
        return filter;
    }
}
