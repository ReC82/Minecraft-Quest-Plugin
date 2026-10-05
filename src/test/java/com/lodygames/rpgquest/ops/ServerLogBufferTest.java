package com.lodygames.rpgquest.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Issue #95 — le tampon de console est la seule source du flux affiché par PlugAdmin. Ce qui est
 * testé ici est donc exactement ce qui doit rester vrai pour que la console ne mente pas :
 * capacité finie, curseur fiable, et <strong>aveu explicite</strong> quand des lignes ont été
 * perdues entre deux consultations.
 */
class ServerLogBufferTest {

    @Test
    void anEmptyBufferReturnsNothingAndNoCursor() {
        ServerLogBuffer buffer = new ServerLogBuffer(100);

        ServerLogBuffer.Snapshot snapshot = buffer.tail(0, 50);

        assertTrue(snapshot.lines().isEmpty());
        assertEquals(0L, snapshot.firstSequence());
        assertEquals(0L, snapshot.lastSequence());
        assertFalse(snapshot.gap());
    }

    @Test
    void sequencesAreStrictlyIncreasing() {
        ServerLogBuffer buffer = new ServerLogBuffer(100);
        buffer.append(1L, "INFO", "RPGQuest", "a");
        buffer.append(1L, "INFO", "RPGQuest", "b"); // même milliseconde : la séquence doit trancher

        List<ServerLogBuffer.Line> lines = buffer.tail(0, 50).lines();

        assertEquals(2, lines.size());
        assertEquals(1L, lines.get(0).sequence());
        assertEquals(2L, lines.get(1).sequence());
    }

    @Test
    void theFirstReadReturnsTheMostRecentLinesNotTheOldest() {
        ServerLogBuffer buffer = new ServerLogBuffer(100);
        for (int i = 1; i <= 30; i++) {
            buffer.append(i, "INFO", "server", "ligne " + i);
        }

        List<ServerLogBuffer.Line> lines = buffer.tail(0, 5).lines();

        // Une console s'ouvre sur le récent. Renvoyer les 5 PLUS ANCIENNES serait inutilisable.
        assertEquals(5, lines.size());
        assertEquals("ligne 26", lines.get(0).message());
        assertEquals("ligne 30", lines.get(4).message());
    }

    @Test
    void aCursorReturnsOnlyWhatIsStrictlyNewer() {
        ServerLogBuffer buffer = new ServerLogBuffer(100);
        for (int i = 1; i <= 5; i++) {
            buffer.append(i, "INFO", "server", "ligne " + i);
        }

        List<ServerLogBuffer.Line> lines = buffer.tail(3, 50).lines();

        assertEquals(2, lines.size());
        assertEquals(4L, lines.get(0).sequence());
        assertEquals(5L, lines.get(1).sequence());
    }

    @Test
    void anUpToDateCursorReturnsNothing() {
        ServerLogBuffer buffer = new ServerLogBuffer(100);
        buffer.append(1L, "INFO", "server", "une seule ligne");

        assertTrue(buffer.tail(1, 50).lines().isEmpty(), "rien de plus récent : aucune ligne");
    }

    @Test
    void theBufferNeverGrowsBeyondItsCapacityAndCountsWhatItDropped() {
        ServerLogBuffer buffer = new ServerLogBuffer(ServerLogBuffer.MIN_CAPACITY);
        int total = ServerLogBuffer.MIN_CAPACITY + 20;
        for (int i = 1; i <= total; i++) {
            buffer.append(i, "INFO", "server", "ligne " + i);
        }

        ServerLogBuffer.Snapshot snapshot = buffer.tail(0, ServerLogBuffer.MAX_TAIL);

        assertEquals(ServerLogBuffer.MIN_CAPACITY, snapshot.lines().size());
        assertEquals(20L, snapshot.dropped(), "les lignes évincées sont comptées, pas oubliées");
        assertEquals(total, snapshot.lastSequence());
        assertEquals(21L, snapshot.firstSequence());
    }

    @Test
    void aCursorOlderThanTheBufferIsReportedAsAGap() {
        ServerLogBuffer buffer = new ServerLogBuffer(ServerLogBuffer.MIN_CAPACITY);
        for (int i = 1; i <= ServerLogBuffer.MIN_CAPACITY + 10; i++) {
            buffer.append(i, "INFO", "server", "ligne " + i);
        }

        // Le panel avait lu jusqu'à 3 ; tout jusqu'à 11 a été évincé depuis.
        ServerLogBuffer.Snapshot snapshot = buffer.tail(3, ServerLogBuffer.MAX_TAIL);

        assertTrue(snapshot.gap(), "il manque des lignes : l'interface doit pouvoir le dire");
        assertFalse(snapshot.lines().isEmpty(), "on renvoie malgré tout ce qui reste");
    }

    @Test
    void aContiguousCursorIsNotAGap() {
        ServerLogBuffer buffer = new ServerLogBuffer(ServerLogBuffer.MIN_CAPACITY);
        for (int i = 1; i <= ServerLogBuffer.MIN_CAPACITY + 10; i++) {
            buffer.append(i, "INFO", "server", "ligne " + i);
        }
        long first = buffer.tail(0, 1).firstSequence();

        // Curseur exactement sur la ligne précédant la plus ancienne conservée : rien ne manque.
        assertFalse(buffer.tail(first - 1, ServerLogBuffer.MAX_TAIL).gap());
    }

    @Test
    void levelsAreNormalizedToTheFourTheInterfaceKnows() {
        ServerLogBuffer buffer = new ServerLogBuffer(100);
        buffer.append(1L, "FATAL", "s", "x");
        buffer.append(2L, "SEVERE", "s", "x");
        buffer.append(3L, "WARNING", "s", "x");
        buffer.append(4L, "FINEST", "s", "x");
        buffer.append(5L, null, "s", "x");

        List<ServerLogBuffer.Line> lines = buffer.tail(0, 50).lines();

        assertEquals(List.of("ERROR", "ERROR", "WARN", "DEBUG", "INFO"),
                lines.stream().map(ServerLogBuffer.Line::level).toList());
    }

    @Test
    void messagesAreTruncatedAndFlattenedToASingleLine() {
        ServerLogBuffer buffer = new ServerLogBuffer(100);
        buffer.append(1L, "INFO", "s", "a\nb\rc");
        buffer.append(2L, "INFO", "s", "x".repeat(ServerLogBuffer.MAX_MESSAGE_CHARS + 50));

        List<ServerLogBuffer.Line> lines = buffer.tail(0, 50).lines();

        assertEquals("a b c", lines.get(0).message(), "une ligne de log reste une ligne");
        assertEquals(ServerLogBuffer.MAX_MESSAGE_CHARS + 1, lines.get(1).message().length(),
                "tronqué, avec la marque d'ellipse");
        assertTrue(lines.get(1).message().endsWith("…"));
    }

    @Test
    void theTailLimitIsBounded() {
        ServerLogBuffer buffer = new ServerLogBuffer(ServerLogBuffer.MAX_CAPACITY);
        for (int i = 1; i <= 800; i++) {
            buffer.append(i, "INFO", "s", "ligne " + i);
        }

        assertEquals(ServerLogBuffer.MAX_TAIL, buffer.tail(0, 100_000).lines().size(),
                "une consultation ne peut pas devenir un transfert de masse");
        assertEquals(1, buffer.tail(0, 0).lines().size(), "une limite nulle ou négative devient 1");
    }

    @Test
    void capacityIsClampedIntoItsBounds() {
        assertEquals(ServerLogBuffer.MIN_CAPACITY, new ServerLogBuffer(1).capacity());
        assertEquals(ServerLogBuffer.MAX_CAPACITY, new ServerLogBuffer(1_000_000).capacity());
        assertEquals(500, new ServerLogBuffer(500).capacity());
    }

    @Test
    void clearingKeepsCursorsValid() {
        ServerLogBuffer buffer = new ServerLogBuffer(100);
        buffer.append(1L, "INFO", "s", "avant");
        buffer.clear();
        buffer.append(2L, "INFO", "s", "après");

        List<ServerLogBuffer.Line> lines = buffer.tail(1, 50).lines();

        // La séquence ne redémarre pas à 1 : un curseur de panel resterait sinon « en avance ».
        assertEquals(1, lines.size());
        assertEquals(2L, lines.get(0).sequence());
    }

    @Test
    void appendingFromManyThreadsLosesNothing() throws Exception {
        ServerLogBuffer buffer = new ServerLogBuffer(ServerLogBuffer.MAX_CAPACITY);
        int threads = 4;
        int perThread = 250;
        Thread[] workers = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            workers[t] = new Thread(() -> {
                for (int i = 0; i < perThread; i++) {
                    buffer.append(System.currentTimeMillis(), "INFO", "t", "x");
                }
            });
        }
        for (Thread worker : workers) {
            worker.start();
        }
        for (Thread worker : workers) {
            worker.join(10_000);
        }

        // Log4j peut publier depuis n'importe quel thread : la dernière séquence doit être exacte.
        assertEquals((long) threads * perThread, buffer.tail(0, 1).lastSequence());
    }
}
