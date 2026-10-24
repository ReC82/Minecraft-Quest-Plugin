package com.lodygames.rpgquest.web.push;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.web.Json;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Issue #421 : le relevé d'état agrégé ne contient que « en ligne ? » et deux compteurs, numérotés
 * par démarrage ; il part du fil dédié et un envoi en cours fait sauter le suivant.
 */
class ServerStatusReporterTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-22T17:00:00Z"), ZoneOffset.UTC);

    @Test
    void reportCarriesOnlyTheClosedScopeAcceptedByThePortal() {
        Map<String, Object> report = ServerStatusReporter.report("boot-0123456789", 7,
                Instant.parse("2026-10-22T17:00:00Z"), true, 3, 20);

        // Contrat partagé avec StatusReportIngest (web-api) : toute autre clé y est refusée (400).
        assertEquals(Set.of("schemaVersion", "kind", "generatedAt", "bootId", "sequence", "server"), report.keySet());
        assertEquals(Set.of("online", "playerCount", "maxPlayers"), ((Map<?, ?>) report.get("server")).keySet());
        assertEquals("status", report.get("kind"));
        assertEquals(1, report.get("schemaVersion"));
        assertEquals(0, ((Map<?, ?>) ServerStatusReporter.report("boot-0123456789", 1, Instant.EPOCH, true, -2, 20)
                .get("server")).get("playerCount"));
    }

    @Test
    void serverLoadIsRoundedAndOmittedWhenUnknown() {
        Map<?, ?> server = (Map<?, ?>) ServerStatusReporter.report("boot-0123456789", 1, Instant.EPOCH, true, 0, 20,
                19.98765, 12.3456).get("server");

        // Contrat partagé avec StatusReportIngest.SERVER_KEYS (web-api).
        assertEquals(Set.of("online", "playerCount", "maxPlayers", "tps", "mspt"), server.keySet());
        assertEquals(19.99, server.get("tps"));
        assertEquals(12.35, server.get("mspt"));
        Map<?, ?> unknown = (Map<?, ?>) ServerStatusReporter.report("boot-0123456789", 1, Instant.EPOCH, true, 0, 20,
                Double.NaN, null).get("server");
        assertEquals(Set.of("online", "playerCount", "maxPlayers"), unknown.keySet());
    }

    @Test
    void bootIdIsRandomAndMatchesThePortalPattern() {
        String first = ServerStatusReporter.newBootId();
        String second = ServerStatusReporter.newBootId();

        assertTrue(first.matches("[A-Za-z0-9-]{8,64}"), first);
        assertFalse(first.equals(second));
    }

    @Test
    void sequenceIncreasesAndReportsArePostedWithTheDedicatedToken() throws Exception {
        List<String> bodies = new CopyOnWriteArrayList<>();
        List<String> auths = new CopyOnWriteArrayList<>();
        CountDownLatch received = new CountDownLatch(2);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/snapshot", exchange -> {
            auths.add(exchange.getRequestHeaders().getFirst("Authorization"));
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
            received.countDown();
        });
        server.start();
        try {
            ServerStatusReporter reporter = reporter("http://127.0.0.1:" + server.getAddress().getPort());

            assertTrue(reporter.submit(true, 2, 20));
            awaitIdle(reporter);
            assertTrue(reporter.submit(true, 1, 20));
            assertTrue(received.await(5, TimeUnit.SECONDS));

            Map<?, ?> first = (Map<?, ?>) Json.parse(bodies.get(0));
            Map<?, ?> second = (Map<?, ?>) Json.parse(bodies.get(1));
            assertEquals(1, ((Number) first.get("sequence")).intValue());
            assertEquals(2, ((Number) second.get("sequence")).intValue());
            assertEquals("boot-test-0001", second.get("bootId"));
            assertEquals(1, ((Number) ((Map<?, ?>) second.get("server")).get("playerCount")).intValue());
            assertEquals(List.of("Bearer jeton-instantane", "Bearer jeton-instantane"), auths);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aSlowSendMakesTheNextOneSkipInsteadOfQueueing() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/snapshot", exchange -> {
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            ServerStatusReporter reporter = reporter("http://127.0.0.1:" + server.getAddress().getPort());

            assertTrue(reporter.submit(true, 1, 20));
            assertFalse(reporter.submit(true, 1, 20), "envoi précédent en cours : sauté");
            release.countDown();
            awaitIdle(reporter);
            assertTrue(reporter.submit(true, 1, 20));
        } finally {
            release.countDown();
            server.stop(0);
        }
    }

    @Test
    void unreachablePortalNeverThrows() throws Exception {
        ServerStatusReporter reporter = reporter("http://127.0.0.1:1");

        assertTrue(reporter.submit(true, 0, 20));
        awaitIdle(reporter);
    }

    private static ServerStatusReporter reporter(String baseUrl) {
        SnapshotPushClient client = new SnapshotPushClient(
                new SnapshotPushConfig(true, baseUrl, "jeton-instantane", 500, 2000), LoggerFactory.getLogger("test"),
                "du relevé d'état");
        return new ServerStatusReporter(null, client, 30, CLOCK, "boot-test-0001");
    }

    /** Attend que l'envoi en cours se termine (un nouvel envoi redevient possible). */
    private static void awaitIdle(ServerStatusReporter reporter) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (reporter.isSending() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertFalse(reporter.isSending(), "envoi terminé");
    }
}
