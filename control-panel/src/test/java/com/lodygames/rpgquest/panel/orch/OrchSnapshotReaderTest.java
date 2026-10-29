package com.lodygames.rpgquest.panel.orch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Lecture de l'instantané lqorch (issue #907) : cache sûr, états explicites, lecture tolérante. */
class OrchSnapshotReaderTest {

    @TempDir
    Path tmp;

    private static final String MINIMAL = "{\"version\":1,\"generated_at\":\"2026-10-29T19:00:00Z\",\"repo\":\"acme/demo\","
            + "\"tasks\":[{\"issue\":7,\"title\":\"t\",\"status\":\"READY\",\"priority\":\"pas un nombre\"}],"
            + "\"champ_futur\":{\"x\":1}}";

    @Test
    void unchangedFileIsServedFromCacheAndAChangeIsReread() throws Exception {
        Path file = tmp.resolve("queue.json");
        Files.writeString(file, MINIMAL);
        Files.setLastModifiedTime(file, FileTime.from(Instant.parse("2026-10-29T19:00:00Z")));
        OrchSnapshotReader reader = new OrchSnapshotReader(file);

        OrchSnapshotReader.Result first = reader.read();
        assertEquals(OrchSnapshotReader.State.LOADED, first.state());
        assertSame(first, reader.read());

        Files.writeString(file, MINIMAL.replace("\"t\"", "\"titre\""));
        Files.setLastModifiedTime(file, FileTime.from(Instant.parse("2026-10-29T19:01:00Z")));
        OrchSnapshotReader.Result second = reader.read();
        assertNotSame(first, second);
        assertEquals("titre", second.snapshot().orElseThrow().tickets().get(0).title());
    }

    @Test
    void unknownOrMistypedFieldsNeverBreakTheReading() throws Exception {
        Path file = tmp.resolve("queue.json");
        Files.writeString(file, MINIMAL);
        OrchSnapshot snap = new OrchSnapshotReader(file).read().snapshot().orElseThrow();
        assertEquals(0, snap.tickets().get(0).priority());
        assertTrue(snap.current().isEmpty());
        assertTrue(snap.auto().eligible().isEmpty());
        assertEquals("", snap.supervisor().mode());
    }

    @Test
    void absentInvalidOrUnconfiguredGiveAnExplicitState() throws Exception {
        Path file = tmp.resolve("queue.json");
        assertEquals(OrchSnapshotReader.State.NOT_CONFIGURED, new OrchSnapshotReader(null).read().state());
        assertEquals(OrchSnapshotReader.State.MISSING, new OrchSnapshotReader(file).read().state());
        Files.writeString(file, "[1,2]");
        assertEquals(OrchSnapshotReader.State.INVALID, new OrchSnapshotReader(file).read().state());
        Files.writeString(file, "{\"version\":1}");
        OrchSnapshotReader.Result noDate = new OrchSnapshotReader(file).read();
        assertEquals(OrchSnapshotReader.State.INVALID, noDate.state());
        assertTrue(noDate.problem().contains("generated_at"));
    }
}
