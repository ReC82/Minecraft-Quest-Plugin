package com.lodygames.rpgquest.web.link;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.slf4j.helpers.NOPLogger;

/**
 * Issue #53 : seule une sonde (UUID seul) part pour un joueur dont la liaison est inconnue ; la
 * progression ne part que pour un joueur relié, et plus jamais après un « NOT_LINKED ».
 */
class LinkedProgressPublisherTest {

    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000a11c");

    /** Faux portail : enregistre chaque envoi, répond selon l'état de liaison choisi par le test. */
    private static final class FakePortal implements LinkedProgressPublisher.Sender {
        final List<Map<String, Object>> sent = new ArrayList<>();
        boolean linked;

        @Override
        public CompletableFuture<ProgressReply> send(UUID player, String name, Map<String, Object> progress) {
            sent.add(progress);
            if (!linked) {
                return CompletableFuture.completedFuture(ProgressReply.NOT_LINKED);
            }
            return CompletableFuture.completedFuture(progress == null ? ProgressReply.LINKED : ProgressReply.STORED);
        }
    }

    private final AtomicInteger builds = new AtomicInteger();

    private LinkedProgressPublisher publisher(FakePortal portal) {
        return new LinkedProgressPublisher(null, portal, uuid -> {
            builds.incrementAndGet();
            return CompletableFuture.completedFuture(Map.of("quests", Map.of("completed", 2)));
        }, 15, NOPLogger.NOP_LOGGER);
    }

    @Test
    void anUnlinkedPlayerOnlyEverSendsOneProbeWithoutStatistics() {
        FakePortal portal = new FakePortal();
        LinkedProgressPublisher publisher = publisher(portal);

        assertEquals(ProgressReply.NOT_LINKED, publisher.publish(ALICE, "Alice").join());
        assertNull(publisher.publish(ALICE, "Alice").join(), "état retenu : rien n'est renvoyé");

        assertEquals(1, portal.sent.size());
        assertNull(portal.sent.get(0), "une sonde ne porte aucune statistique");
        assertEquals(0, builds.get(), "la progression n'est même pas calculée");
        assertTrue(publisher.knownNotLinked(ALICE));
    }

    @Test
    void aLinkedPlayerIsProbedOnceThenPushedDirectly() {
        FakePortal portal = new FakePortal();
        portal.linked = true;
        LinkedProgressPublisher publisher = publisher(portal);

        assertEquals(ProgressReply.STORED, publisher.publish(ALICE, "Alice").join());
        assertEquals(ProgressReply.STORED, publisher.publish(ALICE, "Alice").join());

        assertEquals(3, portal.sent.size(), "sonde, envoi, envoi");
        assertNull(portal.sent.get(0));
        assertEquals(Map.of("quests", Map.of("completed", 2)), portal.sent.get(2));
    }

    @Test
    void lienAfterARefusalReopensAndUnlinkOnTheSiteStopsAgain() {
        FakePortal portal = new FakePortal();
        LinkedProgressPublisher publisher = publisher(portal);
        publisher.publish(ALICE, "Alice").join();

        portal.linked = true;
        publisher.onLinked(ALICE, "Alice");
        assertTrue(publisher.knownLinked(ALICE));
        assertEquals(Map.of("quests", Map.of("completed", 2)), portal.sent.get(1), "envoi direct après /lien, sans sonde");

        portal.linked = false; // dissociation faite sur le site
        assertEquals(ProgressReply.NOT_LINKED, publisher.publish(ALICE, "Alice").join());
        assertFalse(publisher.knownLinked(ALICE));
        assertNull(publisher.publish(ALICE, "Alice").join());
        assertEquals(3, portal.sent.size());
    }

    @Test
    void aFailingBuildIsReportedAsUnavailableAndRetriedLater() {
        FakePortal portal = new FakePortal();
        portal.linked = true;
        LinkedProgressPublisher publisher = new LinkedProgressPublisher(null, portal,
                uuid -> CompletableFuture.failedFuture(new IllegalStateException("base indisponible")), 15,
                NOPLogger.NOP_LOGGER);

        assertEquals(ProgressReply.UNAVAILABLE, publisher.publish(ALICE, "Alice").join());
        assertEquals(ProgressReply.UNAVAILABLE, publisher.publish(ALICE, "Alice").join(), "aucun verrou resté coincé");
    }
}
