package com.lodygames.rpgquest.world.state;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Relevé des dossiers importables et de leur dimension (issue #563), sur un répertoire temporaire. */
class WorldStateFoldersTest {

    @TempDir
    Path container;

    private void world(String name, String... subdirs) throws IOException {
        Path dir = Files.createDirectories(container.resolve(name));
        Files.writeString(dir.resolve("level.dat"), "x");
        for (String sub : subdirs) {
            Files.createDirectories(dir.resolve(sub));
        }
    }

    @Test
    void dimensionComesFromTheFolderContent() throws IOException {
        world("ancien", "region", "DIM-1", "DIM1"); // sauvegarde solo : monde normal
        world("enfer", "DIM-1");
        world("fin", "DIM1");
        world("vide");
        Files.createDirectories(container.resolve("plugins"));
        Files.writeString(container.resolve("server.properties"), "x");
        assertEquals(List.of(new WorldStatePlanner.Folder("ancien", "NORMAL"),
                        new WorldStatePlanner.Folder("enfer", "NETHER"), new WorldStatePlanner.Folder("fin", "THE_END"),
                        new WorldStatePlanner.Folder("vide", "")),
                BukkitWorldStateGateway.folders(container.toFile()));
    }
}
