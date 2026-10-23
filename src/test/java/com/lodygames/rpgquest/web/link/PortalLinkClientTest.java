package com.lodygames.rpgquest.web.link;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Issue #53 : le plugin envoie la preuve et traduit la réponse, sans jamais lever d'exception. */
class PortalLinkClientTest {

    @Test
    void parsesKnownResultsAndTreatsAnythingElseAsUnavailable() {
        assertEquals(LinkReply.LINKED, PortalLinkClient.parse(200, "{\"result\":\"LINKED\"}"));
        assertEquals(LinkReply.UUID_ALREADY_LINKED, PortalLinkClient.parse(200, "{\"result\":\"UUID_ALREADY_LINKED\"}"));
        assertEquals(LinkReply.UNAVAILABLE, PortalLinkClient.parse(200, "{\"result\":\"UNAVAILABLE\"}"));
        assertEquals(LinkReply.UNAVAILABLE, PortalLinkClient.parse(200, "{\"result\":\"AUTRE\"}"));
        assertEquals(LinkReply.UNAVAILABLE, PortalLinkClient.parse(401, "{\"error\":\"unauthorized\"}"));
        assertEquals(LinkReply.UNAVAILABLE, PortalLinkClient.parse(200, "pas du json"));
    }

    @Test
    void sendsTheProofWithTheDedicatedToken() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> auth = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/link/confirm", exchange -> {
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] reply = "{\"result\":\"LINKED\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, reply.length);
            exchange.getResponseBody().write(reply);
            exchange.close();
        });
        server.start();
        try {
            PortalLinkConfig config = new PortalLinkConfig(true,
                    "http://127.0.0.1:" + server.getAddress().getPort(), "jeton-liaison", 2000, 5000);
            UUID uuid = UUID.fromString("11111111-1111-1111-1111-111111111111");

            LinkReply reply = new PortalLinkClient(config).confirm("ABCD2345", uuid, "AliceMC").get(5, TimeUnit.SECONDS);

            assertEquals(LinkReply.LINKED, reply);
            assertEquals("Bearer jeton-liaison", auth.get());
            assertTrue(body.get().contains("\"code\":\"ABCD2345\""));
            assertTrue(body.get().contains("\"uuid\":\"" + uuid + "\""));
            assertTrue(body.get().contains("\"name\":\"AliceMC\""));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aProbeSendsOnlyTheUuidAndAPushSendsTheProgress() throws Exception {
        java.util.List<String> bodies = new java.util.concurrent.CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/link/progress", exchange -> {
            String received = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            bodies.add(received);
            byte[] reply = (received.contains("progress") ? "{\"result\":\"STORED\"}" : "{\"result\":\"LINKED\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, reply.length);
            exchange.getResponseBody().write(reply);
            exchange.close();
        });
        server.start();
        try {
            PortalLinkClient client = new PortalLinkClient(new PortalLinkConfig(true,
                    "http://127.0.0.1:" + server.getAddress().getPort(), "jeton-liaison", 2000, 5000));
            UUID uuid = UUID.fromString("11111111-1111-1111-1111-111111111111");

            assertEquals(ProgressReply.LINKED, client.pushProgress(uuid, "AliceMC", null).get(5, TimeUnit.SECONDS));
            assertEquals(ProgressReply.STORED, client.pushProgress(uuid, "AliceMC",
                    java.util.Map.of("quests", java.util.Map.of("completed", 3))).get(5, TimeUnit.SECONDS));

            assertEquals("{\"uuid\":\"" + uuid + "\"}", bodies.get(0), "la sonde ne contient que l'UUID");
            assertTrue(bodies.get(1).contains("\"name\":\"AliceMC\""));
            assertTrue(bodies.get(1).contains("\"completed\":3"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void unknownProgressRepliesAreUnavailable() {
        assertEquals(ProgressReply.UNAVAILABLE, ProgressReply.parse("AUTRE"));
        assertEquals(ProgressReply.UNAVAILABLE, ProgressReply.parse("UNAVAILABLE"));
        assertEquals(ProgressReply.NOT_LINKED, ProgressReply.parse("NOT_LINKED"));
    }

    @Test
    void unreachablePortalBecomesUnavailable() throws Exception {
        PortalLinkConfig config = new PortalLinkConfig(true, "http://127.0.0.1:1", "t", 500, 1000);

        assertEquals(LinkReply.UNAVAILABLE, new PortalLinkClient(config)
                .confirm("ABCD2345", UUID.randomUUID(), "X").get(5, TimeUnit.SECONDS));
    }

    @Test
    void commandNormalizesCodes() {
        assertEquals("ABCD2345", LinkCommand.normalize("abcd-2345"));
        assertEquals("", LinkCommand.normalize("trop-court"));
        assertEquals("", LinkCommand.normalize("ABCD234!"));
    }
}
