package com.lodygames.rpgquest.config;

import org.bukkit.Material;

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
public record TravelConfig(String wildWorld, RuneConfig rune, WaystoneConfig waystone, WaypointConfig waypoint,
                            BeaconConfig beacon) {

    /**
     * Constructeur historique à 4 composantes : conserve la compatibilité des sites d'appel
     * antérieurs à l'issue #149 en appliquant les valeurs par défaut des bornes.
     */
    public TravelConfig(String wildWorld, RuneConfig rune, WaystoneConfig waystone, WaypointConfig waypoint) {
        this(wildWorld, rune, waystone, waypoint, BeaconConfig.defaults());
    }

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
     *   <li>{@code modelVersion} : version du modèle de rendu stampée sur les nouveaux waypoints ;</li>
     *   <li>{@code hubEnabled} (issue #149) : étend ce même mécanisme au monde Hub configuré
     *       ({@code hub.world}) — décision actée remplaçant l'exclusion historique du Hub
     *       (#136/#137). {@code false} désactive uniquement la génération Hub, jamais celle du
     *       Wild.</li>
     * </ul>
     */
    public record WaypointConfig(boolean enabled, long regionSize, int minDistance, int maxDistance,
                                 int candidateAttempts, long moveThrottleMillis, int minimumSpacing,
                                 int modelVersion, boolean hubEnabled) {

        /** Compatibilité des sites d'appel antérieurs à l'issue #149 (hubEnabled par défaut true, décision actée). */
        public WaypointConfig(boolean enabled, long regionSize, int minDistance, int maxDistance,
                               int candidateAttempts, long moveThrottleMillis, int minimumSpacing, int modelVersion) {
            this(enabled, regionSize, minDistance, maxDistance, candidateAttempts, moveThrottleMillis,
                    minimumSpacing, modelVersion, true);
        }

        public static WaypointConfig defaults() {
            return new WaypointConfig(true, 256L, 24, 72, 12, 1500L, 80, 1, true);
        }
    }

    /**
     * Bornes du réseau de voyage (issues #132/#150/#149) : {@code buttonMaterial} doit être un
     * bouton en bois (essence configurable, {@code OAK_BUTTON} par défaut).
     * {@code hubGenerationEnabled}/{@code pairMinSpacing}/{@code pairMaxSpacing} pilotent la
     * génération automatique d'une borne appariée à chaque waypoint du Hub (issue #149) — jamais
     * dans le Wild, où seul un placement administré existe. {@code pairMinSpacing}/
     * {@code pairMaxSpacing} forment l'anneau (autour du waypoint, même mécanisme que
     * {@code waypoint.WaypointGenerationPlanner}) dans lequel la borne est cherchée : jamais au
     * même endroit que le waypoint (séparation minimale), jamais à l'autre bout de l'instance.
     */
    public record BeaconConfig(Material buttonMaterial, boolean hubGenerationEnabled,
                                int pairMinSpacing, int pairMaxSpacing) {

        public static BeaconConfig defaults() {
            return new BeaconConfig(Material.OAK_BUTTON, true, 6, 16);
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
