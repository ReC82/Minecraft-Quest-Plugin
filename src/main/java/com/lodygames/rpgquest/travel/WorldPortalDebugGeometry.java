package com.lodygames.rpgquest.travel;

import com.lodygames.rpgquest.travel.model.WorldPortalDefinition;
import java.util.ArrayList;
import java.util.List;

/**
 * TODO(debug bug TP hub) : outil de diagnostic ajouté pour visualiser les zones {@code
 * WorldPortalDefinition} en jeu (voir {@code /rpgadmin worldportal debug}), pas une instrumentation
 * temporaire comme {@link TpTraceLogger} — reste utile après la résolution du bug pour tout futur
 * diagnostic de zone.
 *
 * <p>Calcul purement géométrique (aucune dépendance Bukkit), donc testable sans MockBukkit :
 * échantillonne les 12 arêtes du cuboïde {@code [minX,minY,minZ] → [maxX+1,maxY+1,maxZ+1]} (bornes
 * <strong>+1</strong> côté max — {@link WorldPortalDefinition#contains} inclut le bloc {@code maxX},
 * donc le coin visuel réel de la zone est la face extérieure de ce bloc, pas son coin intérieur ;
 * sans ce +1 le contour affiché serait visuellement plus petit d'un bloc que le volume réellement
 * actif, trompeur pour un diagnostic).</p>
 */
final class WorldPortalDebugGeometry {

    private WorldPortalDebugGeometry() {
    }

    record Point(double x, double y, double z) {
    }

    /** Les 8 coins exacts du cuboïde visuel (voir le Javadoc de la classe pour le +1 sur les bornes max). */
    static List<Point> corners(WorldPortalDefinition portal) {
        double minX = portal.minX(), minY = portal.minY(), minZ = portal.minZ();
        double maxX = portal.maxX() + 1.0, maxY = portal.maxY() + 1.0, maxZ = portal.maxZ() + 1.0;
        List<Point> corners = new ArrayList<>(8);
        for (double x : new double[]{minX, maxX}) {
            for (double y : new double[]{minY, maxY}) {
                for (double z : new double[]{minZ, maxZ}) {
                    corners.add(new Point(x, y, z));
                }
            }
        }
        return corners;
    }

    /**
     * Points échantillonnés le long des 12 arêtes, espacés d'environ {@code step} blocs (jamais
     * moins d'un point à chaque extrémité d'arête, même si {@code step} dépasse sa longueur) —
     * inclut nécessairement les 4 arêtes verticales (coins au sol + limites verticales réelles,
     * exigence explicite du diagnostic) et les 8 arêtes horizontales (contour complet en bas et en
     * haut). {@code maxPoints} borne le total (une zone démesurée ne doit jamais spammer des
     * milliers de particules) : au-delà, l'espacement est agrandi uniformément plutôt que de perdre
     * des arêtes entières.
     */
    static List<Point> edgePoints(WorldPortalDefinition portal, double step, int maxPoints) {
        double minX = portal.minX(), minY = portal.minY(), minZ = portal.minZ();
        double maxX = portal.maxX() + 1.0, maxY = portal.maxY() + 1.0, maxZ = portal.maxZ() + 1.0;

        List<double[]> edges = List.of(
                // 4 arêtes verticales (une par coin XZ) — priorité explicite du diagnostic.
                new double[]{minX, minY, minZ, minX, maxY, minZ},
                new double[]{maxX, minY, minZ, maxX, maxY, minZ},
                new double[]{minX, minY, maxZ, minX, maxY, maxZ},
                new double[]{maxX, minY, maxZ, maxX, maxY, maxZ},
                // 4 arêtes horizontales basses (contour au sol).
                new double[]{minX, minY, minZ, maxX, minY, minZ},
                new double[]{maxX, minY, minZ, maxX, minY, maxZ},
                new double[]{maxX, minY, maxZ, minX, minY, maxZ},
                new double[]{minX, minY, maxZ, minX, minY, minZ},
                // 4 arêtes horizontales hautes (contour au plafond).
                new double[]{minX, maxY, minZ, maxX, maxY, minZ},
                new double[]{maxX, maxY, minZ, maxX, maxY, maxZ},
                new double[]{maxX, maxY, maxZ, minX, maxY, maxZ},
                new double[]{minX, maxY, maxZ, minX, maxY, minZ});

        double effectiveStep = Math.max(step, 0.25);
        // Première passe pour estimer le total à ce pas ; si ça dépasse maxPoints, l'agrandit
        // proportionnellement pour tout tracer avec un pas plus grossier plutôt que de tronquer.
        double totalLengthEstimate = edges.stream().mapToDouble(WorldPortalDebugGeometry::edgeLength).sum();
        int estimatedCount = (int) Math.ceil(totalLengthEstimate / effectiveStep) + edges.size();
        if (estimatedCount > maxPoints && maxPoints > 0) {
            effectiveStep = totalLengthEstimate / Math.max(1, maxPoints - edges.size());
        }

        List<Point> points = new ArrayList<>();
        for (double[] edge : edges) {
            points.addAll(sampleEdge(edge, effectiveStep));
        }
        return points;
    }

    private static double edgeLength(double[] edge) {
        return Math.sqrt(Math.pow(edge[3] - edge[0], 2) + Math.pow(edge[4] - edge[1], 2) + Math.pow(edge[5] - edge[2], 2));
    }

    private static List<Point> sampleEdge(double[] edge, double step) {
        double length = edgeLength(edge);
        int samples = Math.max(1, (int) Math.ceil(length / step));
        List<Point> points = new ArrayList<>(samples + 1);
        for (int i = 0; i <= samples; i++) {
            double t = samples == 0 ? 0 : (double) i / samples;
            points.add(new Point(
                    edge[0] + (edge[3] - edge[0]) * t,
                    edge[1] + (edge[4] - edge[1]) * t,
                    edge[2] + (edge[5] - edge[2]) * t));
        }
        return points;
    }

    /** Centre exact du volume (utile pour placer l'étiquette d'identifiant du portail). */
    static Point center(WorldPortalDefinition portal) {
        double maxX = portal.maxX() + 1.0, maxY = portal.maxY() + 1.0, maxZ = portal.maxZ() + 1.0;
        return new Point((portal.minX() + maxX) / 2.0, (portal.minY() + maxY) / 2.0, (portal.minZ() + maxZ) / 2.0);
    }
}
