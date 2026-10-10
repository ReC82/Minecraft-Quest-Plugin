package com.lodygames.rpgquest.panel.travel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Issue #156 — le diagnostic du réseau de voyage, vérifié chiffre par chiffre.
 *
 * <p>Ces tests existent parce que la question posée au départ — « pourquoi seulement 13 bornes ? » —
 * est restée sans réponse tant que les chiffres étaient calculés à la main dans une page. Chaque cas
 * limite qui pourrait faire mentir l'écran est donc exécuté ici : réseau vide, borne seule, spawn
 * inconnu, borne orpheline, borne posée à la main, coordonnées négatives.</p>
 */
class TravelNetworkDiagnosticTest {

    private static final String HUB = "world_hub";

    @Test
    @DisplayName("aucun relevé : « indisponible », volontairement distinct d'un réseau vide")
    void nullDetailsAreUnavailableAndNotAnEmptyNetwork() {
        TravelNetworkDiagnostic diag = TravelNetworkDiagnostic.from(null);

        assertFalse(diag.available());
        assertFalse(diag.hubComplete(), "« indisponible » ne doit jamais se lire « réseau complet »");
        assertTrue(diag.beacons().isEmpty());
        assertFalse(diag.neighbourDistance().known());
    }

    @Test
    @DisplayName("un réseau réellement vide est disponible, mais rien n'y est calculable")
    void anEmptyNetworkIsAvailableWithNoComputableStatistic() {
        TravelNetworkDiagnostic diag = TravelNetworkDiagnostic.from(details(List.of(), List.of(), List.of()));

        assertTrue(diag.available());
        assertEquals(0, diag.hubWaypoints());
        assertEquals(0, diag.hubBeacons());
        assertFalse(diag.extent().known(), "sans borne, aucune étendue — jamais un rectangle de zéro");
        assertEquals(0L, diag.areaPerBeacon());
        assertTrue(diag.hubComplete(), "aucun manque et aucune orpheline : le réseau vide est cohérent");
    }

    @Test
    @DisplayName("paires, manques et orphelines sont comptés séparément")
    void pairsGapsAndOrphansAreCountedSeparately() {
        List<Map<String, Object>> waypoints = List.of(
                waypoint("wp_a", HUB, "minecraft:plains@0,0", 100, 100),
                waypoint("wp_b", HUB, "minecraft:forest@1,0", 600, 100),
                waypoint("wp_c", "wild", "minecraft:taiga@3,3", 3000, 3000));
        List<Map<String, Object>> beacons = List.of(
                beacon("b_a", HUB, "minecraft:plains@0,0", 110, 100, true, "wp_a"),
                // Auto-générée pour une instance dont le waypoint a disparu : orpheline.
                beacon("b_ghost", HUB, "minecraft:desert@9,9", 900, 900, true, ""),
                // Hors du Hub : ne compte dans aucun chiffre du Hub.
                beacon("b_wild", "wild", "", 3000, 3000, false, ""));
        List<Map<String, Object>> gaps = List.of(gap("wp_b", "minecraft:forest@1,0", 600, 100, 0, null, false, 490));

        TravelNetworkDiagnostic diag = TravelNetworkDiagnostic.from(details(waypoints, beacons, gaps));

        assertEquals(2, diag.hubWaypoints(), "seules les instances du Hub comptent comme instances du Hub");
        assertEquals(1, diag.otherWorldWaypoints());
        assertEquals(2, diag.hubBeacons(), "la borne du Wild ne doit pas gonfler le réseau du Hub");
        assertEquals(1, diag.completePairs());
        assertEquals(1, diag.missingPairs());
        assertEquals(1, diag.orphanBeacons());
        assertFalse(diag.hubComplete());
        assertEquals(1, diag.anomalies().size());
        assertEquals("b_ghost", diag.anomalies().get(0).id());
    }

    @Test
    @DisplayName("une borne posée à la main n'est ni une paire ni une orpheline")
    void anAdminPlacedBeaconIsNeitherAPairNorAnOrphan() {
        List<Map<String, Object>> waypoints = List.of(waypoint("wp_a", HUB, "minecraft:plains@0,0", 100, 100));
        List<Map<String, Object>> beacons = List.of(
                beacon("b_manual", HUB, "", 300, 100, false, ""));
        List<Map<String, Object>> gaps = List.of(gap("wp_a", "minecraft:plains@0,0", 100, 100, 2, null, false, 200));

        TravelNetworkDiagnostic diag = TravelNetworkDiagnostic.from(details(waypoints, beacons, gaps));

        // C'est le cœur du défaut constaté : une borne administrée existe bien, mais elle n'est
        // l'équipement d'aucune instance — elle ne ferme donc aucun appariement.
        assertEquals(0, diag.completePairs());
        assertEquals(1, diag.missingPairs());
        assertEquals(0, diag.orphanBeacons(), "sans instance revendiquée, elle n'est pas orpheline");
        assertEquals(1, diag.hubBeacons());
        assertEquals(-1, diag.beacons().get(0).waypointDistance(),
                "aucune distance à un waypoint ne peut être calculée : « inconnu », pas « zéro »");
    }

    @Test
    @DisplayName("distances entre bornes : min, moyenne, médiane, max sur une série impaire")
    void nearestNeighbourStatisticsAreComputedOverEveryBeacon() {
        // Trois bornes alignées : 0, 100, 400. Plus proche voisin = 100, 100, 300.
        List<Map<String, Object>> waypoints = List.of(
                waypoint("wp_a", HUB, "a@0,0", 0, 0),
                waypoint("wp_b", HUB, "b@0,0", 100, 0),
                waypoint("wp_c", HUB, "c@0,0", 400, 0));
        List<Map<String, Object>> beacons = List.of(
                beacon("b_a", HUB, "a@0,0", 0, 0, true, "wp_a"),
                beacon("b_b", HUB, "b@0,0", 100, 0, true, "wp_b"),
                beacon("b_c", HUB, "c@0,0", 400, 0, true, "wp_c"));

        TravelNetworkDiagnostic.Stats stats =
                TravelNetworkDiagnostic.from(details(waypoints, beacons, List.of())).neighbourDistance();

        assertTrue(stats.known());
        assertEquals(3, stats.count());
        assertEquals(100, stats.min());
        assertEquals(300, stats.max());
        assertEquals(100, stats.median());
        assertEquals(167, stats.mean(), "moyenne de 100, 100 et 300");
    }

    @Test
    @DisplayName("médiane d'une série paire : moyenne des deux valeurs centrales")
    void medianOfAnEvenSeriesAveragesTheTwoMiddleValues() {
        TravelNetworkDiagnostic.Stats stats = TravelNetworkDiagnostic.Stats.of(List.of(10, 20, 31, 40));

        assertEquals(26, stats.median());
        assertEquals(25, stats.mean());
    }

    @Test
    @DisplayName("une borne seule : aucune distance entre bornes, et surtout pas zéro")
    void aLoneBeaconHasNoNeighbourDistanceRatherThanZero() {
        List<Map<String, Object>> waypoints = List.of(waypoint("wp_a", HUB, "a@0,0", 50, 50));
        List<Map<String, Object>> beacons = List.of(beacon("b_a", HUB, "a@0,0", 60, 50, true, "wp_a"));

        TravelNetworkDiagnostic diag = TravelNetworkDiagnostic.from(details(waypoints, beacons, List.of()));

        assertFalse(diag.neighbourDistance().known());
        assertEquals(-1, diag.beacons().get(0).nearestBeaconDistance());
        assertEquals(10, diag.beacons().get(0).waypointDistance());
    }

    @Test
    @DisplayName("spawn inconnu : les distances au spawn ne sont pas inventées")
    void anUnknownSpawnYieldsNoSpawnDistanceAtAll() {
        List<Map<String, Object>> waypoints = List.of(waypoint("wp_a", HUB, "a@0,0", 50, 50));
        List<Map<String, Object>> beacons = List.of(beacon("b_a", HUB, "a@0,0", 60, 50, true, "wp_a"));
        Map<String, Object> details = details(waypoints, beacons, List.of());
        details.put("hubSpawnKnown", false);

        TravelNetworkDiagnostic diag = TravelNetworkDiagnostic.from(details);

        assertFalse(diag.spawnDistance().known());
        assertEquals(-1, diag.beacons().get(0).spawnDistance());
    }

    @Test
    @DisplayName("étendue et densité observée, y compris en coordonnées négatives")
    void extentAndObservedDensityHandleNegativeCoordinates() {
        List<Map<String, Object>> waypoints = List.of(
                waypoint("wp_a", HUB, "a@0,0", -400, -300),
                waypoint("wp_b", HUB, "b@0,0", 600, 700));
        List<Map<String, Object>> beacons = List.of(
                beacon("b_a", HUB, "a@0,0", -400, -300, true, "wp_a"),
                beacon("b_b", HUB, "b@0,0", 600, 700, true, "wp_b"));

        TravelNetworkDiagnostic diag = TravelNetworkDiagnostic.from(details(waypoints, beacons, List.of()));
        TravelNetworkDiagnostic.Extent extent = diag.extent();

        assertTrue(extent.known());
        assertEquals(-400, extent.minX());
        assertEquals(600, extent.maxX());
        assertEquals(-300, extent.minZ());
        assertEquals(700, extent.maxZ());
        assertEquals(1000, extent.width());
        assertEquals(1000, extent.depth());
        assertEquals(1_000_000L, extent.area());
        assertEquals(500_000L, diag.areaPerBeacon());
    }

    @Test
    @DisplayName("« jamais tenté » n'est pas un échec de terrain : la cause distingue les deux")
    void theReasonDistinguishesANeverTriedPairingFromARejectedTerrain() {
        assertTrue(TravelNetworkDiagnostic.reasonFor(0, null, false).contains("jamais tenté"));
        assertTrue(TravelNetworkDiagnostic.reasonFor(0, null, false).contains("joueur traverse"),
                "la cause doit nommer le déclencheur, sinon elle n'est pas actionnable");
        assertTrue(TravelNetworkDiagnostic.reasonFor(3, null, false).contains("espacement"));
        assertTrue(TravelNetworkDiagnostic.reasonFor(1, System.currentTimeMillis() + 60_000, false)
                .contains("programmé"));
        assertTrue(TravelNetworkDiagnostic.reasonFor(1, null, true).contains("en cours"));
    }

    @Test
    @DisplayName("les instances portent leur cause, et l'instance appariée n'en porte aucune")
    void instanceRowsCarryTheirCauseAndCompleteOnesCarryNone() {
        List<Map<String, Object>> waypoints = List.of(
                waypoint("wp_a", HUB, "minecraft:plains@0,0", 100, 100),
                waypoint("wp_b", HUB, "minecraft:forest@1,0", 600, 100));
        List<Map<String, Object>> beacons = List.of(
                beacon("b_a", HUB, "minecraft:plains@0,0", 110, 100, true, "wp_a"));
        List<Map<String, Object>> gaps = List.of(gap("wp_b", "minecraft:forest@1,0", 600, 100, 0, null, false, 490));

        TravelNetworkDiagnostic diag = TravelNetworkDiagnostic.from(details(waypoints, beacons, gaps));

        assertEquals(2, diag.instances().size());
        assertEquals(1, diag.missing().size());
        TravelNetworkDiagnostic.InstanceRow missing = diag.missing().get(0);
        assertEquals("wp_b", missing.waypointId());
        assertEquals("MISSING_BEACON", missing.state());
        assertEquals("forest", missing.biomeKey());
        assertTrue(missing.reason().contains("jamais tenté"));
        TravelNetworkDiagnostic.InstanceRow complete = diag.instances().stream()
                .filter(TravelNetworkDiagnostic.InstanceRow::complete).findFirst().orElseThrow();
        assertEquals("b_a", complete.beaconId());
        assertTrue(complete.reason().isEmpty(), "une instance équipée n'a aucune cause à expliquer");
    }

    @Test
    @DisplayName("le type de biome est extrait de la clé d'instance")
    void theBiomeTypeIsExtractedFromTheInstanceKey() {
        assertEquals("forest", TravelNetworkDiagnostic.biomeOf("minecraft:forest@2,-3"));
        assertEquals("forest", TravelNetworkDiagnostic.biomeOf("minecraft:forest"));
        assertEquals("custom_grove", TravelNetworkDiagnostic.biomeOf("mypack:custom_grove@0,0"));
        assertEquals("", TravelNetworkDiagnostic.biomeOf(""));
        assertEquals("", TravelNetworkDiagnostic.biomeOf(null));
    }

    @Test
    @DisplayName("le réseau du Wild est lu à part, et « aucune découverte » est dit")
    void theWildNetworkIsReadApartAndSaysWhenNothingWasDiscovered() {
        Map<String, Object> details = details(List.of(), List.of(), List.of());
        Map<String, Object> network = new LinkedHashMap<>();
        network.put("world", "wild");
        network.put("total", 7);
        network.put("discovered", 0);
        network.put("cellSize", 1000);
        network.put("chance", 0.6);
        network.put("minimumSpacing", 96);
        network.put("minX", -1000);
        network.put("maxX", 1000);
        network.put("minZ", -500);
        network.put("maxZ", 500);
        details.put("waystoneNetworks", List.of(network));

        TravelNetworkDiagnostic diag = TravelNetworkDiagnostic.from(details);

        assertEquals(1, diag.waystoneNetworks().size());
        TravelNetworkDiagnostic.WaystoneRow row = diag.waystoneNetworks().get(0);
        assertEquals("wild", row.world());
        assertEquals(7, row.total());
        assertTrue(row.discoveryKnown());
        assertTrue(row.noneDiscovered(), "7 Waystones dont aucune découverte = aucun voyage possible");
        assertEquals(2_000_000L / 7, row.areaPerWaystone());
        assertEquals("60 %", TravelNetworkDiagnostic.percent(0.6));
    }

    @Test
    @DisplayName("un champ manquant donne « inconnu », jamais une exception")
    void aMissingFieldYieldsUnknownRatherThanAnException() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("hubWorld", HUB);
        // Ni waypoints, ni bornes, ni seuils, ni réseau Wild : un diagnostic qui refuserait de
        // s'afficher parce qu'une clé manque ne diagnostiquerait rien.
        TravelNetworkDiagnostic diag = TravelNetworkDiagnostic.from(details);

        assertTrue(diag.available());
        assertTrue(diag.referenceKnown(), "« hubWorld » est présent : le relevé connaît le référentiel");
        assertEquals(HUB, diag.hubWorld());
        assertEquals(0, diag.instanceRegionSize());
        assertFalse(diag.spawnKnown());
        assertTrue(diag.waystoneNetworks().isEmpty());
        assertTrue(diag.lastBeaconCreatedAt().isEmpty());
    }

    /**
     * Le panel et le plugin se déploient séparément, le panel d'abord. Pendant cette fenêtre, le
     * dernier relevé vient d'une version du plugin qui n'envoyait pas le référentiel — et un écran
     * qui en conclurait « aucun monde Hub configuré » affirmerait quelque chose de faux.
     */
    @Test
    @DisplayName("un relevé antérieur au diagnostic est reconnu comme tel, pas comme un Hub absent")
    void aReadingFromAnOlderPluginIsRecognisedRatherThanReadAsNoHub() {
        Map<String, Object> old = new LinkedHashMap<>();
        old.put("waypoints", List.of(waypoint("wp_a", HUB, "a@0,0", 0, 0)));
        old.put("beacons", List.of(beacon("b_a", HUB, "a@0,0", 10, 0, true, "wp_a")));
        old.put("waypointCount", 1);

        TravelNetworkDiagnostic diag = TravelNetworkDiagnostic.from(old);

        assertTrue(diag.available(), "le relevé existe bel et bien");
        assertFalse(diag.referenceKnown(), "mais il ne porte pas le référentiel du réseau");
        assertTrue(diag.hubWorld().isEmpty());
    }

    @Test
    @DisplayName("les dernières dates servent à répondre à « pourquoi plus rien récemment ? »")
    void theLatestDatesAreExposedForTheWhyNothingRecentlyQuestion() {
        List<Map<String, Object>> waypoints = new ArrayList<>();
        waypoints.add(waypointAt("wp_a", HUB, "a@0,0", 0, 0, "2026-01-01T00:00:00Z"));
        waypoints.add(waypointAt("wp_b", HUB, "b@0,0", 500, 0, "2026-03-05T00:00:00Z"));
        List<Map<String, Object>> beacons = new ArrayList<>();
        beacons.add(beaconAt("b_a", HUB, "a@0,0", 10, 0, "2026-01-01T00:00:17Z"));
        beacons.add(beaconAt("b_b", HUB, "b@0,0", 510, 0, "2026-02-02T00:00:00Z"));

        TravelNetworkDiagnostic diag = TravelNetworkDiagnostic.from(details(waypoints, beacons, List.of()));

        assertEquals("2026-02-02T00:00:00Z", diag.lastBeaconCreatedAt());
        assertEquals("2026-03-05T00:00:00Z", diag.lastInstanceCreatedAt());
        assertEquals("b_a", diag.beacons().get(0).id(), "les fiches sont ordonnées de la plus ancienne");
    }

    // ---- Fabrication de relevés -----------------------------------------------------------------

    private static Map<String, Object> details(List<Map<String, Object>> waypoints,
                                               List<Map<String, Object>> beacons,
                                               List<Map<String, Object>> gaps) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("waypoints", waypoints);
        details.put("beacons", beacons);
        details.put("unpairedHubInstances", gaps);
        details.put("hubWorld", HUB);
        details.put("hubSpawnKnown", true);
        details.put("hubSpawnX", 0);
        details.put("hubSpawnZ", 0);
        details.put("instanceRegionSize", 512);
        details.put("beaconPairMinSpacing", 6);
        details.put("beaconPairMaxSpacing", 16);
        details.put("waypointMinimumSpacing", 64);
        details.put("hubBeaconGenerationEnabled", true);
        return details;
    }

    private static Map<String, Object> waypoint(String id, String world, String instance, int x, int z) {
        return waypointAt(id, world, instance, x, z, "2026-01-01T00:00:00Z");
    }

    private static Map<String, Object> waypointAt(String id, String world, String instance, int x, int z,
                                                  String createdAt) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("world", world);
        row.put("biomeInstance", instance);
        row.put("biomeKey", TravelNetworkDiagnostic.biomeOf(instance));
        row.put("x", x);
        row.put("y", 65);
        row.put("z", z);
        row.put("active", true);
        row.put("createdAt", createdAt);
        return row;
    }

    private static Map<String, Object> beacon(String id, String world, String instance, int x, int z,
                                              boolean auto, String pairedWaypointId) {
        Map<String, Object> row = beaconAt(id, world, instance, x, z, "2026-01-01T00:00:00Z");
        row.put("autoGenerated", auto);
        row.put("pairedWaypointId", pairedWaypointId);
        return row;
    }

    private static Map<String, Object> beaconAt(String id, String world, String instance, int x, int z,
                                                String createdAt) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("world", world);
        row.put("biomeInstance", instance);
        row.put("x", x);
        row.put("y", 65);
        row.put("z", z);
        row.put("active", true);
        row.put("autoGenerated", true);
        row.put("pairedWaypointId", "");
        row.put("createdAt", createdAt);
        return row;
    }

    private static Map<String, Object> gap(String waypointId, String instance, int x, int z, int attempts,
                                           Long nextRetry, boolean inProgress, int nearest) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("waypointId", waypointId);
        row.put("biomeInstance", instance);
        row.put("biomeKey", TravelNetworkDiagnostic.biomeOf(instance));
        row.put("x", x);
        row.put("z", z);
        row.put("attempts", attempts);
        row.put("nextRetryEpochMs", nextRetry);
        row.put("inProgress", inProgress);
        row.put("nearestBeaconDistance", nearest);
        return row;
    }
}
