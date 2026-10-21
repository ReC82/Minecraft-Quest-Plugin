package com.lodygames.rpgquest.waypoint.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Issue #135, diagnostic « Avant-poste » du 21/10/2026 : reproduit en simulation pure (aucune
 * base, aucun serveur) l'épuisement de la réserve bundlée observé sur DEV — 220 noms, puis
 * uniquement des noms de secours — et valide le lot de noms proposé
 * ({@code docs/waypoints/waypoint-names-proposition-lot1.txt}), qui n'est PAS chargé par le plugin.
 */
class WaypointNameExhaustionDiagnosticTest {

    private static final Path PROPOSAL = Path.of("docs/waypoints/waypoint-names-proposition-lot1.txt");

    @Test
    void bundledReserveIsExhaustedAfterItsSizeAndEveryLaterWaypointGetsAFallback() {
        WaypointNameCatalog catalog = WaypointNameCatalog.loadBundled();
        Set<String> used = new HashSet<>();
        for (int i = 0; i < catalog.size(); i++) {
            assertFalse(catalog.reserveName(i, used).startsWith("Avant-poste"),
                    "tant que la réserve n'est pas vide, aucun nom de secours");
        }
        for (int n = 1; n <= 26; n++) {
            assertEquals("Avant-poste " + n, catalog.reserveName(1000L + n, used),
                    "réserve épuisée : suffixes consécutifs dans l'ordre de génération");
        }
    }

    @Test
    void proposedBatchIsLargeValidAndDisjointFromTheBundledReserve() throws IOException {
        List<String> names = Files.readAllLines(PROPOSAL, StandardCharsets.UTF_8).stream()
                .map(String::trim)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .toList();
        WaypointNameCatalog proposal;
        try (InputStream in = Files.newInputStream(PROPOSAL)) {
            proposal = WaypointNameCatalog.load(in);
        }
        assertEquals(names.size(), proposal.size(), "aucun doublon interne (casse/accents/espaces)");
        assertTrue(names.size() >= 500, "lot d'au moins 500 noms");

        Set<String> bundled = new HashSet<>();
        WaypointNameCatalog.loadBundled().reserve().forEach(name -> bundled.add(WaypointNameCatalog.normalize(name)));
        for (String name : names) {
            assertEquals(name, WaypointNameCatalog.cleanDisplayName(name), "espaces superflus : " + name);
            assertTrue(WaypointNameCatalog.validateDisplayName(name).isEmpty(), "nom invalide : " + name);
            assertFalse(bundled.contains(WaypointNameCatalog.normalize(name)), "déjà dans la réserve : " + name);
            assertFalse(name.startsWith("Avant-poste"), "collision avec les noms de secours : " + name);
        }
    }
}
