package com.lodygames.rpgquest.city;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.database.DatabaseManager;
import com.lodygames.rpgquest.database.VillageCenterRepository;
import com.lodygames.rpgquest.npc.CitizensNpc;
import com.lodygames.rpgquest.npc.NpcPlacementPlanner;
import com.lodygames.rpgquest.npc.NpcPlacementPlanner.BlockKind;
import com.lodygames.rpgquest.travel.beacon.model.VillageCenter;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/**
 * Issue #322 — PNJ central optionnel et point d'arrivée distinct du centre : diagnostic (la ville ne
 * suit jamais son PNJ), sûreté de l'arrivée, persistance et compatibilité des villes existantes.
 */
class CityAnchorsTest {

    @TempDir
    Path tempDir;

    private static VillageCenter city(String npcId, VillageCenter.Arrival arrival) {
        return new VillageCenter("hub", "Hub", "world_hub", 100.5, 64, 200.5, 0f, 0f, true, Instant.EPOCH, 50,
                npcId, arrival);
    }

    private static CitizensNpc npcAt(String world, double x, double z, boolean live) {
        return new CitizensNpc(3, UUID.randomUUID(), "Guide", live, world, x, 64, z, 0f, 0f, live, true, live,
                null, null, "PLAYER");
    }

    @Test
    void sansPnjCentralLaVilleNEstQuUnPoint() {
        assertEquals("NONE", CityAnchors.npc(city(null, null), false, true, false, null).code());
    }

    @Test
    void chaqueAbsenceDuPnjADonSonPropreCodeSansPositionInventee() {
        VillageCenter c = city("guide", null);
        assertEquals("DEFINITION_MISSING", CityAnchors.npc(c, false, true, true, null).code());
        assertEquals("CITIZENS_UNAVAILABLE", CityAnchors.npc(c, true, false, false, null).code());
        assertEquals("NOT_LINKED", CityAnchors.npc(c, true, true, false, null).code());
        CityAnchors.NpcStatus deleted = CityAnchors.npc(c, true, true, true, null);
        assertEquals("CITIZENS_MISSING", deleted.code());
        assertEquals(CityAnchors.Severity.ERROR, deleted.severity());
        assertNull(deleted.x());
        CitizensNpc noLocation = new CitizensNpc(3, UUID.randomUUID(), "Guide", false, null, 0, 0, 0, 0f, 0f,
                false, true, false, null, null, null);
        assertEquals("LOCATION_UNKNOWN", CityAnchors.npc(c, true, true, true, noLocation).code());
    }

    @Test
    void pnjDansLaVilleHorsDeLaVilleOuDansUnAutreMonde() {
        VillageCenter c = city("guide", null);
        CityAnchors.NpcStatus inside = CityAnchors.npc(c, true, true, true, npcAt("world_hub", 110.2, 190.9, true));
        assertEquals("INSIDE", inside.code());
        assertEquals(10, inside.distance());
        assertTrue(inside.live());

        // Bord inclusif : à 50 blocs exactement, toujours dans la ville.
        assertEquals("INSIDE", CityAnchors.npc(c, true, true, true, npcAt("world_hub", 150.5, 200.5, true)).code());

        CityAnchors.NpcStatus moved = CityAnchors.npc(c, true, true, true, npcAt("world_hub", 151.5, 200.5, true));
        assertEquals("OUTSIDE", moved.code());
        assertEquals(51, moved.distance());
        assertEquals(CityAnchors.Severity.WARNING, moved.severity());

        CityAnchors.NpcStatus unloaded = CityAnchors.npc(c, true, true, true, npcAt("world_hub", 101, 201, false));
        assertEquals("INSIDE", unloaded.code());
        assertFalse(unloaded.live());
        assertTrue(unloaded.message().contains("dernière position enregistrée"), unloaded.message());

        CityAnchors.NpcStatus elsewhere = CityAnchors.npc(c, true, true, true, npcAt("wild", 100, 200, true));
        assertEquals("OTHER_WORLD", elsewhere.code());
        assertEquals(-1, elsewhere.distance());
    }

    @Test
    void arriveeParDefautAuCentreSinonDistincte() {
        VillageCenter plain = city(null, null);
        assertEquals("CENTER", CityAnchors.arrival(plain).code());
        assertEquals(new VillageCenter.Arrival("world_hub", 100.5, 64, 200.5, 0f, 0f), plain.travelTarget());

        VillageCenter.Arrival gate = new VillageCenter.Arrival("world_hub", 120.5, 65, 230.5, 90f, 5f);
        VillageCenter withGate = city(null, gate);
        assertEquals("SET", CityAnchors.arrival(withGate).code());
        assertEquals(gate, withGate.travelTarget());
        assertEquals("OUTSIDE", CityAnchors.arrival(city(null,
                new VillageCenter.Arrival("world_hub", 400, 64, 200, 0f, 0f))).code());
    }

    @Test
    void deplacerLeCentreGardeLArriveeDuMemeMondeEtOublieCelleDUnAutre() {
        VillageCenter.Arrival gate = new VillageCenter.Arrival("world_hub", 120.5, 65, 230.5, 90f, 5f);
        VillageCenter c = city("guide", gate);
        assertEquals(gate, c.movedTo("world_hub", 0, 64, 0, 0f, 0f).arrival());
        assertNull(c.movedTo("wild", 0, 64, 0, 0f, 0f).arrival());
        assertEquals("guide", c.movedTo("wild", 0, 64, 0, 0f, 0f).centralNpcId());
    }

    @Test
    void arriveeSureExigeUnSolPleinEtDeuxBlocsLibres() {
        assertNull(CityAnchors.unsafeArrival(probe(Map.of(63, BlockKind.SOLID)), 0, 64, 0));
        assertTrue(CityAnchors.unsafeArrival(probe(Map.of()), 0, 64, 0).contains("sol"));
        assertTrue(CityAnchors.unsafeArrival(probe(Map.of(63, BlockKind.SOLID, 65, BlockKind.SOLID)), 0, 64, 0)
                .contains("deux blocs"));
        assertTrue(CityAnchors.unsafeArrival(probe(Map.of(63, BlockKind.SOLID, 64, BlockKind.LIQUID)), 0, 64, 0)
                .contains("liquide"));
        assertTrue(CityAnchors.unsafeArrival(probe(Map.of(63, BlockKind.SOLID, 64, BlockKind.PORTAL)), 0, 64, 0)
                .contains("portail"));
    }

    /** Colonne fictive : la hauteur donnée → son type, tout le reste est de l'air. */
    private static NpcPlacementPlanner.Probe probe(Map<Integer, BlockKind> byY) {
        return new NpcPlacementPlanner.Probe() {
            @Override
            public BlockKind kindAt(int x, int y, int z) {
                return byY.getOrDefault(y, BlockKind.PASSABLE);
            }

            @Override
            public boolean occupiedByNpc(int x, int y, int z) {
                return false;
            }
        };
    }

    @Test
    void pnjCentralEtArriveePersistentSansChangerLIdentiteNiLeCentre() throws Exception {
        DatabaseManager database = new DatabaseManager(tempDir.resolve("anchors.db"));
        database.initialize().get(5, TimeUnit.SECONDS);
        try {
            VillageCenterRepository repository = new VillageCenterRepository(database);
            CityRegistry registry = new CityRegistry(repository, Runnable::run, LoggerFactory.getLogger("test"));
            registry.load().get(5, TimeUnit.SECONDS);
            VillageCenter created = registry.create("Port", "world_hub", 10.5, 64, 20.5, 0f, 0f)
                    .get(5, TimeUnit.SECONDS).city();
            assertNull(created.centralNpcId());
            assertNull(created.arrival());

            assertTrue(registry.linkNpc(created.id(), "julius").get(5, TimeUnit.SECONDS).ok());
            VillageCenter.Arrival gate = new VillageCenter.Arrival("world_hub", 30.5, 66, 20.5, -90f, 10f);
            assertTrue(registry.setArrival(created.id(), gate).get(5, TimeUnit.SECONDS).ok());
            CityRegistry.Result otherWorld = registry.setArrival(created.id(),
                    new VillageCenter.Arrival("wild", 0, 64, 0, 0f, 0f)).get(5, TimeUnit.SECONDS);
            assertFalse(otherWorld.ok());
            assertFalse(registry.linkNpc(created.id(), "Guide Central").get(5, TimeUnit.SECONDS).ok());

            // Relu depuis la base, comme après un redémarrage.
            CityRegistry reloaded = new CityRegistry(repository, Runnable::run, LoggerFactory.getLogger("test"));
            reloaded.load().get(5, TimeUnit.SECONDS);
            VillageCenter read = reloaded.find(created.id()).orElseThrow();
            assertEquals("julius", read.centralNpcId());
            assertEquals(gate, read.arrival());
            assertEquals(10.5, read.x());
            assertEquals(20.5, read.z());

            // Retrait : la ville redevient un simple point, arrivée au centre.
            assertTrue(reloaded.linkNpc(created.id(), "").get(5, TimeUnit.SECONDS).ok());
            assertTrue(reloaded.clearArrival(created.id()).get(5, TimeUnit.SECONDS).ok());
            CityRegistry again = new CityRegistry(repository, Runnable::run, LoggerFactory.getLogger("test"));
            again.load().get(5, TimeUnit.SECONDS);
            VillageCenter cleared = again.find(created.id()).orElseThrow();
            assertNull(cleared.centralNpcId());
            assertNull(cleared.arrival());
            assertEquals("CENTER", CityAnchors.arrival(cleared).code());
        } finally {
            database.shutdown();
        }
    }
}
