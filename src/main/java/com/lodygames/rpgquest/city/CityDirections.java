package com.lodygames.rpgquest.city;

import com.lodygames.rpgquest.travel.beacon.model.VillageCenter;

/**
 * Indication de chemin vers une ville, lisible par un joueur (issue #180) : « au nord, à environ
 * 420 blocs ». Logique pure, sans Bukkit : l'appelant fournit la position du joueur.
 *
 * <p>Rien n'est inventé : la direction et la distance sont calculées depuis le <strong>centre
 * réel</strong> de la ville (celui posé avec l'outil de ville, #178), relu à chaque appel — déplacer
 * la ville depuis le panel corrige donc aussitôt l'indication. Aucune coordonnée n'est montrée,
 * aucun waypoint n'est débloqué.</p>
 *
 * <p>Convention Minecraft : {@code -Z} est le nord, {@code +X} l'est.</p>
 */
public final class CityDirections {

    private static final String[] POINTS = {
            "au nord", "au nord-est", "à l'est", "au sud-est", "au sud", "au sud-ouest", "à l'ouest", "au nord-ouest"};

    private CityDirections() {
    }

    /**
     * @param world monde du joueur, ou {@code null} si inconnu
     * @return le texte à afficher ; jamais {@code null}
     */
    public static String describe(VillageCenter city, String world, double x, double z) {
        if (world == null || !world.equals(city.world())) {
            return "loin d'ici, dans un autre monde que celui où tu te trouves";
        }
        double dx = city.x() - x;
        double dz = city.z() - z;
        // Même mesure que le périmètre de détection (#178) : à l'intérieur, le joueur y est déjà.
        if (Math.max(Math.abs(dx), Math.abs(dz)) <= city.detectionDistance()) {
            return "tout près : tu y es déjà";
        }
        return pointOf(dx, dz) + ", à environ " + roundedDistance(Math.hypot(dx, dz)) + " blocs";
    }

    /** Huit directions, mesurées depuis le nord dans le sens des aiguilles d'une montre. */
    static String pointOf(double dx, double dz) {
        double degrees = Math.toDegrees(Math.atan2(dx, -dz));
        int sector = (int) Math.floorMod(Math.round(degrees / 45.0), 8L);
        return POINTS[sector];
    }

    /** Arrondie à la dizaine : une précision au bloc près serait trompeuse pour un trajet à pied. */
    static long roundedDistance(double distance) {
        return Math.max(10, Math.round(distance / 10.0) * 10);
    }
}
