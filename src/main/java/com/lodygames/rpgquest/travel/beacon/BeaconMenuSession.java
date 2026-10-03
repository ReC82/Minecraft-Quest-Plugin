package com.lodygames.rpgquest.travel.beacon;

import java.util.List;

/** État en mémoire du menu de voyage ouvert pour un joueur (issues #132/#150, mondes #149-suite). */
final class BeaconMenuSession {

    private final int page;
    private final String filter;
    private final String world;
    private final List<String> worldsShown;

    BeaconMenuSession(int page, String filter, String world) {
        this(page, filter, world, List.of());
    }

    /** {@code worldsShown} : ordre exact affiché par {@code openWorldSelection} — jamais recalculé au clic. */
    BeaconMenuSession(int page, String filter, String world, List<String> worldsShown) {
        this.page = page;
        this.filter = filter;
        this.world = world;
        this.worldsShown = List.copyOf(worldsShown);
    }

    int page() {
        return page;
    }

    /** Déjà normalisé (casse/accents) — jamais vide, {@code ""} = aucun filtre. */
    String filter() {
        return filter;
    }

    /** Monde dans lequel la liste de waypoints actuellement affichée est filtrée ({@code ""} = non applicable). */
    String world() {
        return world;
    }

    /** Mondes affichés par le menu de sélection, dans l'ordre exact des slots 0..n — {@code List.of()} sinon. */
    List<String> worldsShown() {
        return worldsShown;
    }
}
