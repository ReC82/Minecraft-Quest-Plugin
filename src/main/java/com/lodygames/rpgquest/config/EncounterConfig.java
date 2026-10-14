package com.lodygames.rpgquest.config;

import java.util.List;

/**
 * Section {@code encounters:} de {@code config.yml} (issue #13) — apparition des PNJ récurrents.
 *
 * <p>L'instance de biome est celle des waypoints ({@code travel.waypoint.region-size}) : les deux
 * systèmes découpent le monde de la même façon, mais leurs états restent indépendants. Toutes les
 * valeurs numériques sont bornées par {@link #bounded()} : une configuration trop agressive est
 * ramenée dans une plage sûre plutôt que de dégrader le serveur.</p>
 *
 * @param enabled            interrupteur global ; {@code false} = plus aucune apparition ni réparation
 *                           (les PNJ déjà apparus restent en place)
 * @param worlds             mondes autorisés ; vide = le seul {@code travel.wild-world}. Hub et claims
 *                           sont toujours exclus
 * @param maxPerWorld        plafond d'occurrences par monde
 * @param minDistance        distance minimale (blocs) entre le point d'entrée du joueur et le PNJ
 * @param maxDistance        rayon maximal de recherche autour du point d'entrée
 * @param candidateAttempts  points candidats testés avant d'abandonner et de programmer un nouvel essai
 * @param moveThrottleMillis intervalle minimal entre deux évaluations pour un même joueur
 * @param minimumSpacing     distance minimale entre deux rencontres
 * @param waypointClearance  distance minimale entre une rencontre et un waypoint
 */
public record EncounterConfig(boolean enabled, List<String> worlds, int maxPerWorld, int minDistance,
                              int maxDistance, int candidateAttempts, long moveThrottleMillis,
                              int minimumSpacing, int waypointClearance) {

    public EncounterConfig {
        worlds = worlds == null ? List.of() : List.copyOf(worlds);
    }

    public static EncounterConfig defaults() {
        return new EncounterConfig(true, List.of(), 200, 24, 64, 12, 2000L, 96, 12);
    }

    public EncounterConfig bounded() {
        int min = clamp(minDistance, 8, 128);
        return new EncounterConfig(enabled, worlds, clamp(maxPerWorld, 0, 5000), min,
                clamp(maxDistance, min, 160), clamp(candidateAttempts, 1, 32),
                Math.max(250L, Math.min(60_000L, moveThrottleMillis)), clamp(minimumSpacing, 0, 1024),
                clamp(waypointClearance, 0, 64));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
