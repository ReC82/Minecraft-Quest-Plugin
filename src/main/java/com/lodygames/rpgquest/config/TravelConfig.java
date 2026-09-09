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
 *   <li>{@code waypoint} : génération paresseuse de waypoints par instance de biome dans
 *       {@code wildWorld} (issue #124, voir {@code waypoint.WaypointService}) — distinct des
 *       Waystones (réseau de voyage sur grille).</li>
 * </ul>
 */
public record TravelConfig(String wildWorld, RuneConfig rune, WaystoneConfig waystone, WaypointConfig waypoint) {

    /**
     * Constructeur historique à 3 composantes : conserve la compatibilité des sites d'appel
     * antérieurs à l'issue #124 en appliquant les valeurs par défaut des waypoints.
     */
    public TravelConfig(String wildWorld, RuneConfig rune, WaystoneConfig waystone) {
        this(wildWorld, rune, waystone, WaypointConfig.defaults());
    }

    /** Canalisation/cooldown de la Rune de rappel, en secondes. */
    public record RuneConfig(int channelSeconds, int cooldownSeconds) {
    }

    /**
     * Génération paresseuse de waypoints par instance de biome (issue #124).
     *
     * <ul>
     *   <li>{@code enabled} : coupe complètement le système si {@code false} ;</li>
     *   <li>{@code regionSize} : côté (en blocs) des tuiles qui, combinées au type de biome,
     *       définissent une « instance de biome » (voir {@code waypoint.model.BiomeInstanceKey}) —
     *       deux zones du même biome séparées de plus de {@code regionSize} ont deux waypoints ;</li>
     *   <li>{@code minDistance}/{@code maxDistance} : anneau (blocs) autour du joueur où le waypoint
     *       est cherché — {@code minDistance >= 8}, le waypoint n'apparaît jamais au pied du joueur ;</li>
     *   <li>{@code candidateAttempts} : nombre de points candidats testés avant d'abandonner et de
     *       programmer un retry borné ;</li>
     *   <li>{@code moveThrottleMillis} : intervalle minimal entre deux évaluations de l'instance de
     *       biome pour un même joueur (jamais de scan à chaque {@code PlayerMoveEvent}) ;</li>
     *   <li>{@code minimumSpacing} : distance minimale (blocs) entre deux waypoints ;</li>
     *   <li>{@code modelVersion} : version du modèle de rendu stampée sur les nouveaux waypoints.</li>
     * </ul>
     */
    public record WaypointConfig(boolean enabled, long regionSize, int minDistance, int maxDistance,
                                 int candidateAttempts, long moveThrottleMillis, int minimumSpacing,
                                 int modelVersion) {

        public static WaypointConfig defaults() {
            return new WaypointConfig(true, 256L, 24, 72, 12, 1500L, 80, 1);
        }
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
