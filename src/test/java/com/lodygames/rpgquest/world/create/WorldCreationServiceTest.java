package com.lodygames.rpgquest.world.create;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.world.create.WorldCreationService.Result;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/**
 * Création confirmée, idempotente, une seule à la fois, relue et journalisée (issue #562). Faux
 * serveur uniquement : aucun monde réel n'est créé.
 */
class WorldCreationServiceTest {

    @TempDir
    Path dir;

    private WorldCreationFixtures.FakeGateway gateway;
    private WorldCreationService service;
    private Path auditFile;

    @BeforeEach
    void setUp() {
        gateway = new WorldCreationFixtures.FakeGateway();
        auditFile = dir.resolve("world-create-audit.yml");
        service = new WorldCreationService(gateway, audit(), Clock.fixed(Instant.parse("2026-10-20T12:00:00Z"),
                ZoneOffset.UTC));
    }

    private WorldCreationAuditLog audit() {
        return new WorldCreationAuditLog(auditFile, Runnable::run, LoggerFactory.getLogger("test"));
    }

    private String fingerprint(WorldCreationRequest r) {
        return service.preview(r, Set.of()).fingerprint();
    }

    @Test
    void previewNeverCreates() {
        var p = service.preview(WorldCreationFixtures.normal("terres"), Set.of());
        assertTrue(p.ok());
        assertTrue(gateway.created.isEmpty());
        assertFalse(Files.exists(auditFile));
    }

    @Test
    void confirmedCreationIsRereadAndAudited() {
        var r = new WorldCreationRequest("terres", "NORMAL", "FLAT", "7", false, "CLASSIQUE", List.of(), "");
        var c = service.create(r, Set.of(), fingerprint(r), "tok-0001", "lody");
        assertEquals(Result.CREATED, c.result(), c.message());
        assertEquals(List.of("terres"), gateway.created);
        assertTrue(c.checks().stream().allMatch(WorldCreationService.Check::ok));
        assertTrue(c.checks().stream().anyMatch(k -> k.label().startsWith("Couches")));
        assertTrue(Files.exists(auditFile));
        var reloaded = audit();
        reloaded.load();
        assertEquals("CREATED", reloaded.byToken("tok-0001").orElseThrow().result());
        assertEquals("lody", reloaded.recent(1).get(0).actor());
    }

    @Test
    void sameTokenNeverCreatesTwiceEvenAfterRestart() {
        var r = WorldCreationFixtures.normal("terres");
        String fp = fingerprint(r);
        service.create(r, Set.of(), fp, "tok-0002", "lody");
        var again = service.create(r, Set.of(), fp, "tok-0002", "lody");
        assertTrue(again.replayed());
        assertEquals(Result.CREATED, again.result());
        var restarted = audit();
        restarted.load();
        var afterRestart = new WorldCreationService(gateway, restarted, Clock.systemUTC())
                .create(r, Set.of(), fp, "tok-0002", "lody");
        assertTrue(afterRestart.replayed());
        assertEquals(List.of("terres"), gateway.created, "une seule création");
    }

    @Test
    void anotherTokenForAnExistingWorldIsRefused() {
        var r = WorldCreationFixtures.normal("terres");
        String fp = fingerprint(r);
        service.create(r, Set.of(), fp, "tok-0003", "lody");
        var second = service.create(r, Set.of(), fp, "tok-0004", "lody");
        assertEquals(Result.REFUSED, second.result());
        assertTrue(second.message().contains("déjà chargé"), second.message());
        assertEquals(1, gateway.created.size());
    }

    @Test
    void folderAppearedSinceThePreviewIsRefused() {
        var r = WorldCreationFixtures.normal("terres");
        var c = service.create(r, Set.of("terres"), fingerprint(r), "tok-0005", "lody");
        assertEquals(Result.REFUSED, c.result());
        assertTrue(gateway.created.isEmpty());
    }

    @Test
    void requestDifferentFromThePreviewIsStale() {
        var r = WorldCreationFixtures.normal("terres");
        String fp = fingerprint(r);
        var changed = new WorldCreationRequest("terres", "NORMAL", "AMPLIFIED", "", true, "", List.of(), "");
        assertEquals(Result.STALE, service.create(changed, Set.of(), fp, "tok-0006", "lody").result());
        assertTrue(gateway.created.isEmpty());
    }

    @Test
    void onlyOneCreationAtATime() {
        var r = WorldCreationFixtures.normal("terres");
        var other = WorldCreationFixtures.normal("autre");
        String fpOther = fingerprint(other);
        WorldCreationService.Creation[] nested = new WorldCreationService.Creation[1];
        gateway.duringCreate = () -> nested[0] = service.create(other, Set.of(), fpOther, "tok-0008", "lody");
        service.create(r, Set.of(), fingerprint(r), "tok-0007", "lody");
        assertEquals(Result.BUSY, nested[0].result());
        assertEquals(List.of("terres"), gateway.created);
    }

    @Test
    void multiverseFailureAndRereadMismatchAreReported() {
        gateway.failure = "un dossier de ce nom existe déjà (rien n'a été écrasé)";
        var r = WorldCreationFixtures.normal("terres");
        var failed = service.create(r, Set.of(), fingerprint(r), "tok-0009", "lody");
        assertEquals(Result.FAILED, failed.result());
        assertTrue(failed.message().contains("rien n'a été écrasé"));
        gateway.failure = null;
        gateway.rereadType = "FLAT";
        gateway.keepSpawn = true;
        var mismatch = service.create(r, Set.of(), fingerprint(r), "tok-0010", "lody");
        assertEquals(Result.MISMATCH, mismatch.result());
        assertEquals(2, mismatch.checks().stream().filter(k -> !k.ok()).count());
    }
}
