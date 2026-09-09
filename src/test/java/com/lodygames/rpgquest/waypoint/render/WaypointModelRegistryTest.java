package com.lodygames.rpgquest.waypoint.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.Test;

/** Issue #124 : le rendu est abstrait/versionné et remplaçable sans toucher à la logique métier. */
class WaypointModelRegistryTest {

    @Test
    void v1DescribesAGoldBlockWithASideButtonAsInteractor() {
        WaypointModelV1 v1 = new WaypointModelV1();
        assertEquals(1, v1.version());
        for (BlockFace facing : new BlockFace[] {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
            BlockOffset interactor = v1.interactor(facing);
            assertEquals(1, interactor.dy(), "le bouton est à hauteur du bloc d'or");
            assertNotEquals(new BlockOffset(0, 1, 0), interactor, "le bouton n'est jamais le bloc d'or lui-même");
            Set<BlockOffset> protectedBlocks = v1.protectedBlocks(facing);
            assertTrue(protectedBlocks.contains(interactor), "l'interacteur fait partie des blocs protégés");
            assertTrue(protectedBlocks.contains(new BlockOffset(0, 0, 0)), "le support est protégé");
            assertTrue(protectedBlocks.contains(new BlockOffset(0, 1, 0)), "le bloc d'or est protégé");
            assertTrue(protectedBlocks.contains(new BlockOffset(0, -1, 0)), "le sol porteur est protégé");
        }
    }

    @Test
    void registryExposesTheDesiredCurrentVersionWhenItExists() {
        WaypointModelRegistry registry = new WaypointModelRegistry(1, new WaypointModelV1());
        assertEquals(1, registry.currentVersion());
        assertSame(registry.current(), registry.forVersion(1).orElseThrow());
    }

    @Test
    void registryFallsBackToHighestKnownVersionWhenDesiredIsUnknown() {
        WaypointModelRegistry registry = new WaypointModelRegistry(99, new WaypointModelV1(), new FakeModel(2));
        assertEquals(2, registry.currentVersion(), "version demandée absente → plus haute version connue");
    }

    @Test
    void resolveOrCurrentDegradesGracefullyForUnknownStoredVersions() {
        WaypointModelRegistry registry = new WaypointModelRegistry(1, new WaypointModelV1());
        assertFalse(registry.forVersion(7).isPresent());
        assertSame(registry.current(), registry.resolveOrCurrent(7),
                "un waypoint stocké avec une version inconnue est quand même rendu, au mieux");
    }

    @Test
    void rejectsDuplicateVersionsAndEmptyRegistry() {
        assertThrows(IllegalArgumentException.class, () -> new WaypointModelRegistry(1));
        assertThrows(IllegalArgumentException.class,
                () -> new WaypointModelRegistry(1, new WaypointModelV1(), new FakeModel(1)));
    }

    /** Modèle factice pour tester le registre sans dépendre d'un vrai rendu. */
    private static final class FakeModel implements WaypointModel {
        private final int version;

        private FakeModel(int version) {
            this.version = version;
        }

        @Override
        public int version() {
            return version;
        }

        @Override
        public void place(World world, int anchorX, int anchorY, int anchorZ, BlockFace facing) {
        }

        @Override
        public BlockOffset interactor(BlockFace facing) {
            return new BlockOffset(0, 1, 1);
        }

        @Override
        public Set<BlockOffset> protectedBlocks(BlockFace facing) {
            return Set.of(new BlockOffset(0, 1, 1));
        }
    }
}
