package com.lodygames.rpgquest.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.npc.NpcIdentityService.BindResult;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/**
 * Transaction applicative du spawn (#81 phase 2) : {@code create → bind → success}, sinon
 * {@code delete Citizens créé → failure}. Fakes purs, aucune API Citizens.
 */
class CitizensSpawnCoordinatorTest {

    private static final CitizensNpc CREATED =
            new CitizensNpc(31, UUID.fromString("33333333-3333-3333-3333-333333333333"), "Bûcheron Bob", true);

    /** Spawner de test : mémorise le PNJ qu'on lui demande de détruire. */
    private static final class FakeSpawner implements CitizensSpawnCoordinator.Spawner {
        private final CitizensNpc toCreate;
        private final boolean destroySucceeds;
        int createCalls;
        CitizensNpc destroyedRef;

        FakeSpawner(CitizensNpc toCreate, boolean destroySucceeds) {
            this.toCreate = toCreate;
            this.destroySucceeds = destroySucceeds;
        }

        @Override
        public CompletableFuture<Optional<CitizensNpc>> createAndSpawn() {
            createCalls++;
            return CompletableFuture.completedFuture(Optional.ofNullable(toCreate));
        }

        @Override
        public CompletableFuture<Boolean> destroyCreated(CitizensNpc created) {
            destroyedRef = created;
            return CompletableFuture.completedFuture(destroySucceeds);
        }
    }

    private static CitizensSpawnCoordinator.Binder binder(BindResult result) {
        return (npcId, ref) -> CompletableFuture.completedFuture(result);
    }

    @Test
    void createThenBindSucceeds_isCreatedWithoutRollback() {
        FakeSpawner spawner = new FakeSpawner(CREATED, true);
        CitizensSpawnCoordinator.Result r = CitizensSpawnCoordinator.run("woodcutter_bob", "world_hub 1/64/2",
                spawner, binder(new BindResult(true, "LINKED", "lié", 31))).join();

        assertTrue(r.ok());
        assertEquals("CREATED", r.code());
        assertEquals(Integer.valueOf(31), r.citizensNumericId());
        assertFalse(r.rolledBack());
        assertNull(spawner.destroyedRef, "aucun rollback quand la liaison réussit");
        assertTrue(r.effects().stream().anyMatch(e -> e.contains("binding woodcutter_bob")));
    }

    @Test
    void spawnFailure_isCreateFailed_andNoBindAttempted() {
        FakeSpawner spawner = new FakeSpawner(null, true);
        CitizensSpawnCoordinator.Result r = CitizensSpawnCoordinator.run("woodcutter_bob", "pos",
                spawner, binder(new BindResult(true, "LINKED", "lié", 0))).join();

        assertFalse(r.ok());
        assertEquals("CREATE_FAILED", r.code());
        assertNull(r.citizensNumericId());
        assertNull(spawner.destroyedRef);
    }

    @Test
    void bindFailureAfterCreate_rollsBackExactlyTheCreatedNpc() {
        FakeSpawner spawner = new FakeSpawner(CREATED, true);
        CitizensSpawnCoordinator.Result r = CitizensSpawnCoordinator.run("woodcutter_bob", "pos",
                spawner, binder(new BindResult(false, "NPC_ID_TAKEN", "déjà lié", 31))).join();

        assertFalse(r.ok());
        assertEquals("BIND_FAILED_ROLLED_BACK", r.code());
        assertTrue(r.rolledBack());
        assertEquals(CREATED, spawner.destroyedRef, "le rollback vise le PNJ tout juste créé, aucun autre");
        assertTrue(r.message().contains("déjà lié"));
        assertTrue(r.message().contains("rollback effectué"));
    }

    @Test
    void bindFailureAndRollbackAlsoFails_isReportedForManualCleanup() {
        FakeSpawner spawner = new FakeSpawner(CREATED, false);
        CitizensSpawnCoordinator.Result r = CitizensSpawnCoordinator.run("woodcutter_bob", "pos",
                spawner, binder(new BindResult(false, "CITIZENS_TAKEN", "course", 31))).join();

        assertFalse(r.ok());
        assertEquals("BIND_FAILED_ROLLED_BACK", r.code());
        assertFalse(r.rolledBack());
        assertEquals(CREATED, spawner.destroyedRef);
        assertTrue(r.message().contains("NON supprimé"));
    }
}
