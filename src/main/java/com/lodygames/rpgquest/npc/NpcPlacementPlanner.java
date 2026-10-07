package com.lodygames.rpgquest.npc;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Cherche un emplacement <strong>libre et sûr</strong> pour y faire apparaître un PNJ, autour d'un
 * point d'ancrage (en pratique : le Guide du Hub).
 *
 * <p><strong>Pourquoi une classe pure.</strong> Toute la décision — ordre de visite des candidats,
 * critères de sûreté, bornes, abandon — est exprimée ici sans aucun type Bukkit : le monde n'est vu
 * qu'à travers {@link Probe}, une interface que les tests implémentent avec une grille en mémoire.
 * C'est la seule façon de couvrir « aucun emplacement libre », « que du vide », « que du liquide »
 * ou « PNJ déjà présent partout » de façon déterministe.</p>
 *
 * <p><strong>Ce que la recherche ne fait jamais</strong> : casser ou poser un bloc, charger un
 * monde, forcer un chunk. Elle ne fait que consulter ce que l'appelant accepte de lui dire.</p>
 */
public final class NpcPlacementPlanner {

    /** Hauteur libre exigée pour un PNJ humanoïde : sa propre case + celle de la tête. */
    public static final int REQUIRED_CLEARANCE = 2;

    private NpcPlacementPlanner() {
    }

    /** Un bloc, tel que la recherche a besoin de le connaître. Aucun type Bukkit ici. */
    public enum BlockKind {
        /** Air ou végétation traversable : un PNJ peut y tenir. */
        PASSABLE,
        /** Sol plein sur lequel se tenir. */
        SOLID,
        /** Eau, lave : jamais un sol ni un emplacement. */
        LIQUID,
        /** Portail, bloc de fin de portail : à éviter absolument (téléportation). */
        PORTAL,
        /** Hors monde (sous le plancher, au-dessus du plafond) ou non consultable. */
        UNKNOWN
    }

    /** Ce que la recherche est autorisée à demander au monde. */
    public interface Probe {
        BlockKind kindAt(int x, int y, int z);

        /** Un PNJ (Citizens ou autre) occupe-t-il déjà cette case ? Évite les chevauchements. */
        boolean occupiedByNpc(int x, int y, int z);
    }

    /** Bornes de recherche, configurables — jamais une exploration illimitée. */
    public record Limits(int horizontalRadius, int verticalRadius, int maxCandidates) {
        public Limits {
            if (horizontalRadius < 0 || verticalRadius < 0 || maxCandidates <= 0) {
                throw new IllegalArgumentException(
                        "Bornes de recherche invalides : rayons >= 0 et maxCandidates > 0 attendus.");
            }
        }
    }

    /** Emplacement retenu : la case où le PNJ se tiendra (son sol est juste dessous). */
    public record Spot(int x, int y, int z, int inspected) {
    }

    /** Pourquoi la recherche a échoué, pour un message exploitable. */
    public enum Failure {
        /** Les bornes ont été épuisées sans trouver un seul emplacement acceptable. */
        NO_SAFE_SPOT,
        /** Le plafond de candidats a été atteint avant d'épuiser le rayon. */
        BUDGET_EXHAUSTED
    }

    public record Result(Optional<Spot> spot, Failure failure, int inspected) {

        public boolean found() {
            return spot.isPresent();
        }
    }

    /**
     * Cherche l'emplacement acceptable <strong>le plus proche</strong> de l'ancre.
     *
     * <p>Les candidats sont visités par <strong>distance totale croissante</strong> (anneau
     * horizontal + écart vertical) : à critères égaux, le PNJ apparaît au plus près du Guide, et un
     * emplacement à côté est préféré à un emplacement perché plus haut. L'ancre elle-même est le
     * premier candidat.</p>
     *
     * @param anchorX ancre de la recherche (position du Guide)
     * @param limits  bornes de recherche
     * @param probe   accès au monde, en lecture seule
     */
    public static Result findNearest(int anchorX, int anchorY, int anchorZ, Limits limits, Probe probe) {
        int inspected = 0;
        int maxDistance = limits.horizontalRadius() + limits.verticalRadius();
        // Distance totale croissante (anneau + écart vertical) : un emplacement à côté est préféré
        // à un emplacement perché deux blocs plus haut, ce qui est le comportement attendu.
        for (int distance = 0; distance <= maxDistance; distance++) {
            for (int dy : verticalOrder(limits.verticalRadius())) {
                int ring = distance - Math.abs(dy);
                if (ring < 0 || ring > limits.horizontalRadius()) {
                    continue;
                }
                for (int[] offset : ringOffsets(ring)) {
                    if (inspected >= limits.maxCandidates()) {
                        return new Result(Optional.empty(), Failure.BUDGET_EXHAUSTED, inspected);
                    }
                    inspected++;
                    int x = anchorX + offset[0];
                    int y = anchorY + dy;
                    int z = anchorZ + offset[1];
                    if (isAcceptable(x, y, z, probe)) {
                        return new Result(Optional.of(new Spot(x, y, z, inspected)), null, inspected);
                    }
                }
            }
        }
        return new Result(Optional.empty(), Failure.NO_SAFE_SPOT, inspected);
    }

    /**
     * Un emplacement est acceptable si le PNJ peut s'y tenir sans danger :
     * sol plein juste dessous, {@link #REQUIRED_CLEARANCE} cases traversables pour son corps,
     * aucun liquide ni portail dans ces cases, et aucun autre PNJ déjà là.
     */
    static boolean isAcceptable(int x, int y, int z, Probe probe) {
        BlockKind ground = probe.kindAt(x, y - 1, z);
        if (ground != BlockKind.SOLID) {
            return false; // vide, liquide, portail ou hors monde : pas de sol praticable
        }
        for (int i = 0; i < REQUIRED_CLEARANCE; i++) {
            BlockKind body = probe.kindAt(x, y + i, z);
            if (body != BlockKind.PASSABLE) {
                return false; // obstacle, liquide, portail, ou hors monde
            }
            if (probe.occupiedByNpc(x, y + i, z)) {
                return false; // chevauchement avec un PNJ existant
            }
        }
        return true;
    }

    /** Écarts verticaux par distance croissante : 0, -1, +1, -2, +2… (le sol d'abord, puis autour). */
    private static List<Integer> verticalOrder(int verticalRadius) {
        List<Integer> out = new ArrayList<>();
        out.add(0);
        for (int d = 1; d <= verticalRadius; d++) {
            out.add(-d);
            out.add(d);
        }
        return out;
    }

    /**
     * Décalages horizontaux de l'anneau de rayon {@code ring} (carré creux). {@code ring == 0}
     * renvoie l'ancre seule. L'ordre à l'intérieur d'un anneau est déterministe, donc la recherche
     * est reproductible — utile pour diagnostiquer deux créations qui tombent au même endroit.
     */
    private static List<int[]> ringOffsets(int ring) {
        List<int[]> out = new ArrayList<>();
        if (ring == 0) {
            out.add(new int[] {0, 0});
            return out;
        }
        for (int dx = -ring; dx <= ring; dx++) {
            for (int dz = -ring; dz <= ring; dz++) {
                if (Math.abs(dx) == ring || Math.abs(dz) == ring) {
                    out.add(new int[] {dx, dz});
                }
            }
        }
        return out;
    }
}
