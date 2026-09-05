package com.lodygames.rpgquest.travel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.travel.model.WorldPortalDefinition;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Fonction purement géométrique (aucune dépendance Bukkit) : couvre directement le calcul de
 * contour utilisé par {@code /rpgadmin worldportal debug} pour la visualisation par particules.
 */
class WorldPortalDebugGeometryTest {

    private final WorldPortalDefinition portal =
            new WorldPortalDefinition("hub_to_wild", "world", -5, 60, -5, 5, 70, 5, "wild", true);

    @Test
    void cornersAreOffsetByOneOnTheMaxBoundsToMatchTheInclusiveContainsCheck() {
        List<WorldPortalDebugGeometry.Point> corners = WorldPortalDebugGeometry.corners(portal);

        assertEquals(8, corners.size());
        // portal.contains inclut le bloc x=5 (borne max) : le coin visuel réel est donc à x=6 (face
        // extérieure de ce bloc), jamais à x=5 qui ne serait que son coin intérieur.
        assertTrue(corners.stream().anyMatch(c -> c.x() == -5 && c.y() == 60 && c.z() == -5));
        assertTrue(corners.stream().anyMatch(c -> c.x() == 6 && c.y() == 71 && c.z() == 6));
    }

    @Test
    void edgePointsIncludeAllFourVerticalEdgesFromMinYToMaxYPlusOne() {
        List<WorldPortalDebugGeometry.Point> points = WorldPortalDebugGeometry.edgePoints(portal, 1.0, 10_000);

        // Un point exactement au sommet de chacune des 4 arêtes verticales (coin bas, y=60) et
        // 4 au sommet (y=71 = maxY+1) doivent être présents — "coins au sol" + "limites verticales
        // réelles", exigence explicite du diagnostic.
        Set<double[]> bottomCorners = Set.of(
                new double[]{-5, 60, -5}, new double[]{6, 60, -5}, new double[]{-5, 60, 6}, new double[]{6, 60, 6});
        for (double[] corner : bottomCorners) {
            assertTrue(points.stream().anyMatch(p -> p.x() == corner[0] && p.y() == corner[1] && p.z() == corner[2]),
                    "coin au sol manquant : " + java.util.Arrays.toString(corner));
        }
        assertTrue(points.stream().anyMatch(p -> p.y() == 71.0), "sommet des arêtes verticales manquant (maxY+1)");
    }

    @Test
    void edgePointsRespectTheMaxPointsCapForAnOversizedZone() {
        WorldPortalDefinition huge = new WorldPortalDefinition("huge", "world", 0, 0, 0, 500, 300, 500, "wild", true);

        List<WorldPortalDebugGeometry.Point> points = WorldPortalDebugGeometry.edgePoints(huge, 1.0, 200);

        assertTrue(points.size() <= 220, "le pas doit être agrandi pour respecter approximativement le plafond : "
                + points.size() + " points");
    }

    @Test
    void edgePointsNeverDropsAnEdgeEvenWhenStepExceedsItsLength() {
        WorldPortalDefinition tiny = new WorldPortalDefinition("tiny", "world", 0, 0, 0, 1, 1, 1, "wild", true);

        List<WorldPortalDebugGeometry.Point> points = WorldPortalDebugGeometry.edgePoints(tiny, 100.0, 10_000);

        // 12 arêtes, chacune avec au moins ses deux extrémités même si le pas dépasse sa longueur.
        assertTrue(points.size() >= 12);
    }

    @Test
    void centerIsTheMidpointOfTheVisualCuboid() {
        WorldPortalDebugGeometry.Point center = WorldPortalDebugGeometry.center(portal);

        assertEquals(0.5, center.x());
        assertEquals(65.5, center.y());
        assertEquals(0.5, center.z());
    }
}
