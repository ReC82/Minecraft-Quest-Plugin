package com.lodygames.rpgquest.claim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.claim.model.Claim;
import com.lodygames.rpgquest.claim.model.ClaimFlags;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Purement géométrique (pas de MockBukkit), mission « visualisation des limites du claim » :
 * couvre notamment l'exigence explicite « bounds affichés = bounds actifs, réservation 100×100
 * jamais utilisée comme frontière active ».
 */
class ClaimBorderGeometryTest {

    /** Cuboïde actif 5×5 (-2..2) avec une réservation 100×100 bien plus large — jamais utilisée par le contour. */
    private Claim claim() {
        return new Claim("main_test", UUID.randomUUID(), "claims",
                -2, 60, -2, 2, 63, 2,
                -50, 60, -50, 49, 63, 49,
                Set.of(), ClaimFlags.defaults());
    }

    @Test
    void perimeterStaysWithinTheActiveBoundsNeverTheReservation() {
        List<ClaimBorderGeometry.Point> points = ClaimBorderGeometry.perimeter(claim(), 61, 1.0);

        assertTrue(points.stream().allMatch(p -> p.x() >= -2 && p.x() <= 3 && p.z() >= -2 && p.z() <= 3),
                "le contour doit rester dans le cuboïde actif (+1 sur la face extérieure), jamais dans la réservation");
    }

    @Test
    void perimeterCoversAllFourSidesAtTheGivenHeight() {
        List<ClaimBorderGeometry.Point> points = ClaimBorderGeometry.perimeter(claim(), 61, 1.0);

        assertTrue(points.stream().allMatch(p -> p.y() == 61), "toute la hauteur doit être celle demandée");
        assertTrue(points.stream().anyMatch(p -> p.x() == -2), "côté minX manquant");
        assertTrue(points.stream().anyMatch(p -> p.x() == 3), "côté maxX (+1) manquant");
        assertTrue(points.stream().anyMatch(p -> p.z() == -2), "côté minZ manquant");
        assertTrue(points.stream().anyMatch(p -> p.z() == 3), "côté maxZ (+1) manquant");
    }

    @Test
    void cornerColumnsAreExactlyTheFourXzCorners() {
        List<ClaimBorderGeometry.Point> points = ClaimBorderGeometry.cornerColumns(claim(), 61, 3.0, 1.0);

        Set<String> corners = Set.of("-2.0,-2.0", "3.0,-2.0", "-2.0,3.0", "3.0,3.0");
        for (ClaimBorderGeometry.Point p : points) {
            assertTrue(corners.contains(p.x() + "," + p.z()), "point hors des 4 coins attendus : " + p);
        }
    }

    @Test
    void cornerColumnsSpanFromBaseYToBaseYPlusHeight() {
        List<ClaimBorderGeometry.Point> points = ClaimBorderGeometry.cornerColumns(claim(), 61, 3.0, 1.0);

        assertTrue(points.stream().anyMatch(p -> p.y() == 61.0), "base de colonne manquante");
        assertTrue(points.stream().anyMatch(p -> p.y() == 64.0), "sommet de colonne manquant (baseY + height)");
        assertTrue(points.stream().allMatch(p -> p.y() >= 61.0 && p.y() <= 64.0), "point hors de [baseY, baseY+height]");
    }

    @Test
    void geometryNeverReferencesTheReservationBounds() {
        // La réservation vaut (-50..49) alors que l'actif vaut (-2..2) : si un point dépassait
        // largement le cuboïde actif, ce serait le signe d'un bug référençant reservedMinX/maxX.
        List<ClaimBorderGeometry.Point> perimeter = ClaimBorderGeometry.perimeter(claim(), 61, 1.0);
        List<ClaimBorderGeometry.Point> corners = ClaimBorderGeometry.cornerColumns(claim(), 61, 3.0, 1.0);

        assertEquals(0, perimeter.stream().filter(p -> Math.abs(p.x()) > 10 || Math.abs(p.z()) > 10).count());
        assertEquals(0, corners.stream().filter(p -> Math.abs(p.x()) > 10 || Math.abs(p.z()) > 10).count());
    }
}
