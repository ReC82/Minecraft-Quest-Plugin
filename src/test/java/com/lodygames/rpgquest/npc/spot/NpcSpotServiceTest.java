package com.lodygames.rpgquest.npc.spot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.database.DatabaseManager;
import com.lodygames.rpgquest.database.NpcSpotRepository;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Issue #381 — emplacements de PNJ sur une vraie base SQLite temporaire. Plusieurs tests
 * reconstruisent un service neuf sur la même base : c'est ce que fait un redémarrage du serveur.
 */
class NpcSpotServiceTest {

    private static final long TIMEOUT = 5;

    @TempDir
    Path tempDir;

    private DatabaseManager database;
    private NpcSpotService service;

    @BeforeEach
    void setUp() throws Exception {
        database = new DatabaseManager(tempDir.resolve("data.db"));
        database.initialize().get(TIMEOUT, TimeUnit.SECONDS);
        service = new NpcSpotService(new NpcSpotRepository(database));
        service.load().get(TIMEOUT, TimeUnit.SECONDS);
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    private NpcSpot mark(String name, double x, double y, double z) throws Exception {
        NpcSpotService.CreateResult result = service.create("world", x, y, z, 90f, 10f, name, "Lody", null)
                .get(TIMEOUT, TimeUnit.SECONDS);
        assertTrue(result.created(), "réservation attendue");
        return result.spot();
    }

    private NpcSpotService reloaded() throws Exception {
        NpcSpotService fresh = new NpcSpotService(new NpcSpotRepository(database));
        fresh.load().get(TIMEOUT, TimeUnit.SECONDS);
        return fresh;
    }

    @Test
    void aReservedSpotKeepsItsExactPositionAndOrientationAcrossARestart() throws Exception {
        NpcSpot spot = mark("Place du marché", 10.25, 64.0, -3.75);
        assertEquals("npcspot_0001", spot.id());

        NpcSpot read = reloaded().find("npcspot_0001").orElseThrow();
        assertEquals("Place du marché", read.name());
        assertEquals(10.25, read.x());
        assertEquals(64.0, read.y());
        assertEquals(-3.75, read.z());
        assertEquals(90f, read.yaw());
        assertEquals(10f, read.pitch());
        assertEquals("Lody", read.createdBy());
        assertTrue(read.free());
    }

    @Test
    void markingTheSameBlockTwiceReturnsTheExistingSpot() throws Exception {
        NpcSpot first = mark("A", 10.2, 64, 10.2);
        NpcSpotService.CreateResult again = service.create("world", 10.8, 64.5, 10.9, 0f, 0f, "B", "Lody", null)
                .get(TIMEOUT, TimeUnit.SECONDS);
        assertEquals(NpcSpotService.CreateOutcome.ALREADY_THERE, again.outcome());
        assertEquals(first.id(), again.spot().id());
        assertEquals(1, service.all().size());
    }

    @Test
    void idsAreNeverReusedAfterADeletion() throws Exception {
        NpcSpot first = mark("A", 0, 64, 0);
        assertEquals(NpcSpotService.DeleteOutcome.DELETED, service.delete(first.id()).get(TIMEOUT, TimeUnit.SECONDS));
        NpcSpot second = mark("B", 5, 64, 5);
        assertEquals("npcspot_0002", second.id());
    }

    @Test
    void aTooLongNameIsRefusedAndAnEmptyOneFallsBackToTheDefault() throws Exception {
        NpcSpotService.CreateResult refused = service.create("world", 0, 64, 0, 0f, 0f, "x".repeat(65), "Lody", null)
                .get(TIMEOUT, TimeUnit.SECONDS);
        assertEquals(NpcSpotService.CreateOutcome.INVALID, refused.outcome());
        assertTrue(service.all().isEmpty(), "rien n'est écrit pour un nom refusé");
        assertTrue(mark("", 1, 64, 1).name().startsWith(NpcSpot.DEFAULT_NAME + " "), "nom par défaut numéroté");
    }

    @Test
    void assigningANpcMovesItsReservationFromAToBInOneStep() throws Exception {
        NpcSpot a = mark("A", 0, 64, 0);
        NpcSpot b = mark("B", 20, 64, 20);
        assertEquals(NpcSpotService.AssignOutcome.ASSIGNED, service.assign(a.id(), "test_npc").get(TIMEOUT, TimeUnit.SECONDS));
        assertEquals(NpcSpotService.AssignOutcome.ASSIGNED, service.assign(b.id(), "test_npc").get(TIMEOUT, TimeUnit.SECONDS));

        for (NpcSpotService view : new NpcSpotService[] {service, reloaded()}) {
            assertTrue(view.find(a.id()).orElseThrow().free(), "A est libéré");
            assertEquals("test_npc", view.find(b.id()).orElseThrow().npcId());
            assertEquals(b.id(), view.assignedTo("test_npc").orElseThrow().id());
        }
    }

    @Test
    void anOccupiedSpotCannotBeTakenByAnotherNpcAndTheLoserKeepsItsSpot() throws Exception {
        NpcSpot a = mark("A", 0, 64, 0);
        NpcSpot b = mark("B", 20, 64, 20);
        service.assign(a.id(), "guard").get(TIMEOUT, TimeUnit.SECONDS);
        service.assign(b.id(), "baker").get(TIMEOUT, TimeUnit.SECONDS);

        assertEquals(NpcSpotService.AssignOutcome.OCCUPIED, service.assign(a.id(), "baker").get(TIMEOUT, TimeUnit.SECONDS));
        NpcSpotService fresh = reloaded();
        assertEquals("guard", fresh.find(a.id()).orElseThrow().npcId());
        assertEquals("baker", fresh.find(b.id()).orElseThrow().npcId());
    }

    @Test
    void reassigningTheSameNpcIsANoOp() throws Exception {
        NpcSpot a = mark("A", 0, 64, 0);
        service.assign(a.id(), "guard").get(TIMEOUT, TimeUnit.SECONDS);
        assertEquals(NpcSpotService.AssignOutcome.ALREADY_ASSIGNED,
                service.assign(a.id(), "guard").get(TIMEOUT, TimeUnit.SECONDS));
    }

    @Test
    void anOccupiedSpotIsProtectedFromDeletionUntilReleased() throws Exception {
        NpcSpot a = mark("A", 0, 64, 0);
        service.assign(a.id(), "guard").get(TIMEOUT, TimeUnit.SECONDS);
        assertEquals(NpcSpotService.DeleteOutcome.OCCUPIED, service.delete(a.id()).get(TIMEOUT, TimeUnit.SECONDS));
        assertTrue(reloaded().find(a.id()).isPresent());

        assertEquals(NpcSpotService.ReleaseOutcome.RELEASED, service.release(a.id()).get(TIMEOUT, TimeUnit.SECONDS));
        assertEquals(NpcSpotService.ReleaseOutcome.ALREADY_FREE, service.release(a.id()).get(TIMEOUT, TimeUnit.SECONDS));
        assertEquals(NpcSpotService.DeleteOutcome.DELETED, service.delete(a.id()).get(TIMEOUT, TimeUnit.SECONDS));
        assertEquals(NpcSpotService.DeleteOutcome.ABSENT, service.delete(a.id()).get(TIMEOUT, TimeUnit.SECONDS));
        assertTrue(reloaded().all().isEmpty());
    }

    @Test
    void renameAndOrientationArePersistedWithoutMovingTheSpot() throws Exception {
        NpcSpot a = mark("A", 3.5, 70, 3.5);
        service.rename(a.id(), "Porte nord").get(TIMEOUT, TimeUnit.SECONDS);
        service.orient(a.id(), 270f, -5f).get(TIMEOUT, TimeUnit.SECONDS);
        NpcSpot read = reloaded().find(a.id()).orElseThrow();
        assertEquals("Porte nord", read.name());
        assertEquals(-90f, read.yaw(), "angle ramené dans [-180, 180)");
        assertEquals(-5f, read.pitch());
        assertEquals(3.5, read.x());
        assertEquals(70.0, read.y());
    }

    @Test
    void renamingToBlankIsRefused() throws Exception {
        NpcSpot a = mark("A", 0, 64, 0);
        ExecutionException error = assertThrows(ExecutionException.class,
                () -> service.rename(a.id(), "  ").get(TIMEOUT, TimeUnit.SECONDS));
        assertTrue(error.getCause() instanceof IllegalArgumentException);
        assertEquals("A", service.find(a.id()).orElseThrow().name());
    }

    @Test
    void capturingANpcPositionCreatesAnOccupiedSpotAndFreesItsOldOne() throws Exception {
        NpcSpot old = mark("Ancien", 0, 64, 0);
        service.assign(old.id(), "guard").get(TIMEOUT, TimeUnit.SECONDS);
        NpcSpotService.CreateResult captured = service.create("world", 50.5, 65, 50.5, 180f, 0f, "Ici",
                "panel", "guard").get(TIMEOUT, TimeUnit.SECONDS);
        assertTrue(captured.created());
        assertEquals("guard", captured.spot().npcId());
        NpcSpotService fresh = reloaded();
        assertTrue(fresh.find(old.id()).orElseThrow().free());
        assertEquals(captured.spot().id(), fresh.assignedTo("guard").orElseThrow().id());
    }

    @Test
    void unknownIdsAreAnsweredWithoutWriting() throws Exception {
        assertEquals(NpcSpotService.AssignOutcome.ABSENT, service.assign("npcspot_0999", "guard").get(TIMEOUT, TimeUnit.SECONDS));
        assertEquals(NpcSpotService.ReleaseOutcome.ABSENT, service.release("npcspot_0999").get(TIMEOUT, TimeUnit.SECONDS));
        assertFalse(service.rename("npcspot_0999", "X").get(TIMEOUT, TimeUnit.SECONDS).isPresent());
        assertNull(service.find(null).orElse(null));
    }
}
