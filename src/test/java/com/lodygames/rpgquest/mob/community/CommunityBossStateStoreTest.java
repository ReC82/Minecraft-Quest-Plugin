package com.lodygames.rpgquest.mob.community;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

class CommunityBossStateStoreTest {

    @TempDir
    Path dir;

    private CommunityBossStateStore store() {
        return new CommunityBossStateStore(dir, LoggerFactory.getLogger("test"));
    }

    private static CommunityBossInstance instance(boolean withWaypoint) {
        return new CommunityBossInstance(UUID.randomUUID(), "rpgquest:ogre", "Ogre", "wild", 10, 64, -20,
                "minecraft:plains", withWaypoint ? "wp-1" : null, withWaypoint ? "Pierre" : null,
                withWaypoint ? 120 : 0, withWaypoint ? "nord" : null, 42L, true);
    }

    @Test
    void missingFileIsAnEmptyState() {
        CommunityBossState state = store().load();
        assertNull(state.nextDueMillis());
        assertTrue(state.instances().isEmpty());
    }

    @Test
    void roundTripKeepsEveryField() throws Exception {
        UUID retired = UUID.randomUUID();
        CommunityBossState state = new CommunityBossState(123L, 45L, 67L, "Plafond atteint",
                List.of(instance(true), instance(false)), Set.of(retired));
        store().save(state, 1);
        assertEquals(state, store().load());
    }

    @Test
    void anOlderRevisionNeverOverwritesANewerOne() throws Exception {
        CommunityBossStateStore store = store();
        store.save(new CommunityBossState(2L, 0, 0, "neuf", List.of(), Set.of()), 2);
        store.save(new CommunityBossState(1L, 0, 0, "périmé", List.of(), Set.of()), 1);
        assertEquals("neuf", store.load().lastOutcome());
    }

    @Test
    void unreadableFileIsKeptAsideNeverSilentlyLost() throws Exception {
        Files.writeString(dir.resolve("community-bosses.yml"), "instances: [ : : not yaml");
        CommunityBossState state = store().load();
        assertTrue(state.instances().isEmpty());
        assertTrue(Files.exists(dir.resolve("community-bosses.yml.broken")));
    }
}
