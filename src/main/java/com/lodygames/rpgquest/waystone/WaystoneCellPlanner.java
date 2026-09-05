package com.lodygames.rpgquest.waystone;

import com.lodygames.rpgquest.config.TravelConfig.WaystoneConfig;
import java.util.Optional;
import java.util.Random;

/**
 * Décide, de façon <strong>purement déterministe</strong> et sans aucun accès au monde, si une
 * cellule de génération contient une Waystone et à quelle position (bloc) dans la cellule (mission
 * « Waystones Wild » : « décision déterministe seed + cellule »). Deux appels avec la même seed de
 * monde et les mêmes coordonnées de cellule renvoient toujours le même résultat — donc aucun
 * doublon au reload/redémarrage, quelle que soit l'ordre d'exploration des chunks.
 *
 * <p>Classe sans état, testable en JUnit pur (aucune dépendance Bukkit).</p>
 */
public final class WaystoneCellPlanner {

    /** Marge (en blocs) laissée sur chaque bord de la cellule — le point candidat n'y touche jamais. */
    private static final int EDGE_MARGIN = 24;

    private static final String[] NAME_PREFIX = {
            "Vieille", "Ancienne", "Grande", "Petite", "Haute", "Basse", "Silencieuse", "Brumeuse",
            "Mousseuse", "Solitaire", "Perdue", "Oubliée"
    };
    private static final String[] NAME_PLACE = {
            "clairière", "colline", "combe", "lisière", "sente", "borne", "ravine", "crête",
            "source", "gorge", "lande", "futaie"
    };

    /** Résultat d'une cellule qui contient bien une Waystone : coordonnées de bloc + nom lisible. */
    public record Candidate(int blockX, int blockZ, String name) {
    }

    /**
     * @return le point candidat de cette cellule, ou {@link Optional#empty()} si le tirage
     *         déterministe décide qu'elle n'en contient pas.
     */
    public Optional<Candidate> planCell(long worldSeed, long cellX, long cellZ, WaystoneConfig config) {
        Random rng = new Random(mix(worldSeed, cellX, cellZ));
        if (rng.nextDouble() >= config.chance()) {
            return Optional.empty();
        }

        long size = config.cellSize();
        long usable = Math.max(1, size - 2L * EDGE_MARGIN);
        int localX = (int) (EDGE_MARGIN + Math.floorMod(rng.nextLong(), usable));
        int localZ = (int) (EDGE_MARGIN + Math.floorMod(rng.nextLong(), usable));
        int blockX = (int) (cellX * size + localX);
        int blockZ = (int) (cellZ * size + localZ);

        String name = "Pierre de voyage — " + NAME_PREFIX[rng.nextInt(NAME_PREFIX.length)] + " "
                + NAME_PLACE[rng.nextInt(NAME_PLACE.length)];
        return Optional.of(new Candidate(blockX, blockZ, name));
    }

    /** Coordonnées de la cellule contenant un bloc du monde. */
    public long cellOf(int blockCoord, long cellSize) {
        return Math.floorDiv((long) blockCoord, cellSize);
    }

    private static long mix(long worldSeed, long cellX, long cellZ) {
        long h = worldSeed;
        h = h * 6364136223846793005L + 1442695040888963407L + (cellX * 0x9E3779B97F4A7C15L);
        h ^= (h >>> 29);
        h = h * 6364136223846793005L + 1442695040888963407L + (cellZ * 0xC2B2AE3D27D4EB4FL);
        h ^= (h >>> 32);
        return h;
    }
}
