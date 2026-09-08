package com.lodygames.rpgquest.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Chargement d'un dossier de définitions PNJ : fichier invalide isolé, id dupliqué rejeté. */
class NpcDefinitionLoaderTest {

    @TempDir
    Path dir;

    @Test
    void loadsValidFilesAndIsolatesInvalidOnes() throws Exception {
        Files.writeString(dir.resolve("guard.yml"), "id: guard\ndisplay_name: \"Garde\"\ndialogue: rpgquest:guard\n");
        Files.writeString(dir.resolve("bad.yml"), "id: Bad Id\ndisplay_name: \"X\"\n");
        Files.writeString(dir.resolve("bob.yml"), "id: woodcutter_bob\ndisplay_name: \"Bob\"\n");

        NpcLoadReport report = new NpcDefinitionLoader().loadDirectory(dir);
        assertEquals(2, report.loaded().size());
        assertEquals(1, report.issues().size());
    }

    @Test
    void duplicateIdAcrossFilesIsRejected() throws Exception {
        Files.writeString(dir.resolve("a.yml"), "id: guard\ndisplay_name: \"A\"\n");
        Files.writeString(dir.resolve("b.yml"), "id: guard\ndisplay_name: \"B\"\n");
        NpcLoadReport report = new NpcDefinitionLoader().loadDirectory(dir);
        assertTrue(report.loaded().isEmpty());
        assertTrue(report.issues().stream().anyMatch(i -> i.message().contains("dupliqué")));
    }

    @Test
    void missingDirectoryIsZeroNpcNotAnError() {
        NpcLoadReport report = new NpcDefinitionLoader().loadDirectory(dir.resolve("absent"));
        assertTrue(report.loaded().isEmpty());
        assertTrue(report.issues().isEmpty());
    }
}
