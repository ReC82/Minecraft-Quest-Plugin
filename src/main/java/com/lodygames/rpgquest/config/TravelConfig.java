package com.lodygames.rpgquest.config;

/**
 * Paramètres de la boucle joueur Hub ↔ Wild (mission « boucle joueur ») :
 *
 * <ul>
 *   <li>{@code wildWorld} : nom exact du monde d'exploration — la Rune de rappel n'y fonctionne que
 *       là, l'avertissement d'entrée ne concerne que les portails qui y mènent, et c'est le seul
 *       monde où des Waystones sont générées.</li>
 *   <li>{@code rune} : canalisation (défaut 10 s) et cooldown (défaut 30 min) de la Rune de rappel
 *       ({@code travel.ItemTravelService}). Le cooldown est persisté par joueur.</li>
 *   <li>{@code waystone} : génération paresseuse de Waystones dans {@code wildWorld} (voir
 *       {@code waystone.WaystoneService}).</li>
 * </ul>
 */
public record TravelConfig(String wildWorld, RuneConfig rune, WaystoneConfig waystone) {

    /** Canalisation/cooldown de la Rune de rappel, en secondes. */
    public record RuneConfig(int channelSeconds, int cooldownSeconds) {
    }

    /**
     * {@code cellSize} : côté (en blocs) des grandes cellules carrées ; au plus une Waystone par
     * cellule, décidée de façon déterministe à partir de la seed du monde et des coordonnées de la
     * cellule. {@code chance} : probabilité (0..1) qu'une cellule contienne une Waystone.
     * {@code minimumSpacing} : distance minimale (blocs) entre deux Waystones. {@code safeAttempts} :
     * nombre d'essais de recherche d'une surface sûre autour du point candidat avant d'abandonner
     * la cellule. {@code channelSeconds} : canalisation d'un retour au Hub depuis une Waystone.
     */
    public record WaystoneConfig(long cellSize, double chance, int minimumSpacing, int safeAttempts, int channelSeconds) {
    }
}
