package com.lodygames.rpgquest.claim;

import com.lodygames.rpgquest.claim.model.Claim;
import java.util.ArrayList;
import java.util.List;

/**
 * Calcul purement géométrique (aucune dépendance Bukkit, testable sans MockBukkit — même discipline
 * que {@code travel.WorldPortalDebugGeometry}) du contour d'un claim, pour {@link
 * ClaimBorderRenderer}. Utilise <strong>toujours</strong> le cuboïde {@link Claim#minX() actif}
 * (jamais {@code reservedMinX}/{@code reservedMaxX}... — mission « ne jamais afficher la réservation
 * future comme propriété »).
 *
 * <p>Contrairement à {@code WorldPortalDebugGeometry} (contour 3D complet, 12 arêtes), un claim peut
 * s'étendre sur toute la hauteur du monde (des dizaines/centaines de blocs) : dessiner les arêtes
 * verticales complètes serait illisible. Le contour est donc un <strong>périmètre à une seule
 * hauteur</strong> ({@code y}, recalculée à chaque rendu sur la position du joueur — voir {@link
 * ClaimBorderRenderer}) plus une courte colonne verticale à chacun des 4 coins pour rester
 * repérable (mission « légère indication verticale aux coins »).</p>
 */
final class ClaimBorderGeometry {

    private ClaimBorderGeometry() {
    }

    record Point(double x, double y, double z) {
    }

    /**
     * Périmètre (4 côtés) du cuboïde actif à la hauteur {@code y}, échantillonné tous les {@code
     * step} blocs. +1 sur les bornes max : {@link Claim#contains} inclut le bloc {@code maxX}, donc
     * le contour visuel réel est la face extérieure de ce bloc (même raisonnement que {@code
     * WorldPortalDebugGeometry}).
     */
    static List<Point> perimeter(Claim claim, double y, double step) {
        double minX = claim.minX();
        double minZ = claim.minZ();
        double maxX = claim.maxX() + 1.0;
        double maxZ = claim.maxZ() + 1.0;

        List<double[]> edges = List.of(
                new double[]{minX, minZ, maxX, minZ},
                new double[]{maxX, minZ, maxX, maxZ},
                new double[]{maxX, maxZ, minX, maxZ},
                new double[]{minX, maxZ, minX, minZ});

        List<Point> points = new ArrayList<>();
        double effectiveStep = Math.max(step, 0.25);
        for (double[] edge : edges) {
            double length = Math.hypot(edge[2] - edge[0], edge[3] - edge[1]);
            int samples = Math.max(1, (int) Math.ceil(length / effectiveStep));
            for (int i = 0; i <= samples; i++) {
                double t = (double) i / samples;
                points.add(new Point(edge[0] + (edge[2] - edge[0]) * t, y, edge[1] + (edge[3] - edge[1]) * t));
            }
        }
        return points;
    }

    /** Colonnes verticales aux 4 coins XZ du cuboïde actif, de {@code baseY} à {@code baseY + height}. */
    static List<Point> cornerColumns(Claim claim, double baseY, double height, double step) {
        double minX = claim.minX();
        double minZ = claim.minZ();
        double maxX = claim.maxX() + 1.0;
        double maxZ = claim.maxZ() + 1.0;

        double effectiveStep = Math.max(step, 0.25);
        int samples = Math.max(1, (int) Math.ceil(height / effectiveStep));

        List<Point> points = new ArrayList<>();
        for (double x : new double[]{minX, maxX}) {
            for (double z : new double[]{minZ, maxZ}) {
                for (int i = 0; i <= samples; i++) {
                    points.add(new Point(x, baseY + height * i / samples, z));
                }
            }
        }
        return points;
    }
}
