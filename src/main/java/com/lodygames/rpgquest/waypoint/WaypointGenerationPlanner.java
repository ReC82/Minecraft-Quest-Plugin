package com.lodygames.rpgquest.waypoint;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Produit une liste ordonnée de points candidats (bloc x/z) autour du point d'entrée du joueur
 * dans une instance de biome (issue #124). Sans état, sans Bukkit — testable en JUnit pur.
 *
 * <p>Chaque candidat est tiré à un angle uniforme et à une distance uniforme dans
 * {@code [minDistance, maxDistance]} : le waypoint ne peut donc <strong>jamais</strong> apparaître
 * au pied du joueur ({@code minDistance} est validé &gt;= 8), il reste quelque chose à chercher.</p>
 *
 * <p>Le tirage est <strong>déterministe</strong> pour une {@code seed} donnée (dérivée de l'id de
 * l'instance) : deux déclenchements concurrents pour la même instance évaluent exactement la même
 * séquence de candidats, et un retry plus tard repart de la même liste — aucune boucle de scan
 * terrain aléatoire à chaque déplacement.</p>
 */
public final class WaypointGenerationPlanner {

    /** Point candidat en coordonnées de bloc (la colonne (x, z) ; le Y sûr est cherché ensuite). */
    public record Candidate(int blockX, int blockZ) {
    }

    public List<Candidate> candidates(int originX, int originZ, long seed,
                                      int minDistance, int maxDistance, int attempts) {
        if (minDistance < 1 || maxDistance < minDistance || attempts < 1) {
            throw new IllegalArgumentException(
                    "paramètres de génération invalides : min=" + minDistance + " max=" + maxDistance + " attempts=" + attempts);
        }
        Random rng = new Random(seed);
        List<Candidate> out = new ArrayList<>(attempts);
        for (int i = 0; i < attempts; i++) {
            double angle = rng.nextDouble() * 2 * Math.PI;
            double distance = minDistance + rng.nextDouble() * (maxDistance - minDistance);
            int x = originX + (int) Math.round(Math.cos(angle) * distance);
            int z = originZ + (int) Math.round(Math.sin(angle) * distance);
            out.add(new Candidate(x, z));
        }
        return out;
    }
}
