package com.lodygames.rpgquest.city;

import com.lodygames.rpgquest.travel.beacon.model.VillageCenter;
import java.util.Optional;

/**
 * Géométrie d'une ville (issue #178) : un <strong>carré horizontal</strong> centré sur le bloc
 * central, jamais un cercle.
 *
 * <p>Avec un centre en ({@code cx}, {@code cz}) et une distance {@code d}, un point appartient à la
 * ville si et seulement si :</p>
 * <ul>
 *   <li>il est dans le même monde ;</li>
 *   <li>{@code cx − d ≤ x ≤ cx + d} ;</li>
 *   <li>{@code cz − d ≤ z ≤ cz + d}.</li>
 * </ul>
 *
 * <p>L'altitude est ignorée et les coins sont inclus. Une distance de 50 couvre donc 50 blocs vers le
 * nord, le sud, l'est et l'ouest, soit un carré de 101 × 101 blocs (le bloc central compte).</p>
 *
 * <p>Les coordonnées sont celles du <strong>bloc</strong> du joueur, comme partout ailleurs dans le
 * plugin : la frontière ne dépend donc pas de la position du joueur à l'intérieur d'un bloc.</p>
 */
public final class CityPerimeter {

    /** Distance par défaut, rappelée ici pour les appelants qui ne manipulent pas de centre. */
    public static final int DEFAULT_DISTANCE = VillageCenter.DEFAULT_DETECTION_DISTANCE;
    /** Plus petite distance acceptée : une ville d'un bloc de rayon serait un point, pas une zone. */
    public static final int MIN_DISTANCE = 5;
    /**
     * Plus grande distance acceptée : 500 blocs dans chaque direction, soit un carré de 1 001 blocs
     * de côté. Au-delà, une « ville » couvrirait une région entière du Hub.
     */
    public static final int MAX_DISTANCE = 500;

    private CityPerimeter() {
    }

    /** {@code true} si le bloc ({@code x}, {@code z}) du monde {@code world} est dans la ville. */
    public static boolean contains(VillageCenter city, String world, int x, int z) {
        if (city == null || world == null || !city.world().equals(world)) {
            return false;
        }
        int d = city.detectionDistance();
        return Math.abs(x - city.blockX()) <= d && Math.abs(z - city.blockZ()) <= d;
    }

    /** Limites calculées, coins inclus. */
    public static Bounds bounds(VillageCenter city) {
        int d = city.detectionDistance();
        return new Bounds(city.blockX() - d, city.blockX() + d, city.blockZ() - d, city.blockZ() + d);
    }

    /** Message d'erreur en français si la distance est hors bornes, vide sinon. */
    public static Optional<String> checkDistance(int distance) {
        if (distance < MIN_DISTANCE || distance > MAX_DISTANCE) {
            return Optional.of("Distance de détection invalide : " + distance + ". Attendu entre "
                    + MIN_DISTANCE + " et " + MAX_DISTANCE + " blocs dans chaque direction.");
        }
        return Optional.empty();
    }

    /** Deux villes du même monde se chevauchent-elles ? */
    public static boolean overlaps(VillageCenter a, VillageCenter b) {
        if (!a.world().equals(b.world())) {
            return false;
        }
        Bounds ba = bounds(a);
        Bounds bb = bounds(b);
        return ba.minX() <= bb.maxX() && bb.minX() <= ba.maxX()
                && ba.minZ() <= bb.maxZ() && bb.minZ() <= ba.maxZ();
    }

    /**
     * Limites inclusives d'une ville.
     *
     * @param minX ouest
     * @param maxX est
     * @param minZ nord
     * @param maxZ sud
     */
    public record Bounds(int minX, int maxX, int minZ, int maxZ) {

        /** Largeur totale en blocs, bloc central compris ({@code 2d + 1}). */
        public int width() {
            return maxX - minX + 1;
        }
    }
}
