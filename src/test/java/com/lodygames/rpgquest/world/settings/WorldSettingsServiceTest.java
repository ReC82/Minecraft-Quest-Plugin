package com.lodygames.rpgquest.world.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.world.settings.WorldSettingsService.Result;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/** Aperçu, application confirmée, relecture et journal des réglages de monde (issue #561). */
class WorldSettingsServiceTest {

    @TempDir
    Path dir;

    private WorldSettingsFixtures.FakeGateway gateway;
    private WorldSettingsAuditLog audit;
    private WorldSettingsService service;

    @BeforeEach
    void setUp() {
        gateway = new WorldSettingsFixtures.FakeGateway("terres", List.of());
        audit = new WorldSettingsAuditLog(dir.resolve("world-settings-audit.yml"), Runnable::run,
                LoggerFactory.getLogger("test"));
        service = new WorldSettingsService(gateway, audit,
                Clock.fixed(Instant.parse("2026-10-20T10:00:00Z"), ZoneOffset.UTC));
    }

    private String fingerprint() {
        return service.read("terres").fingerprint();
    }

    @Test
    void previewNeverWrites() {
        var p = service.preview("terres", Map.of("difficulty", "HARD", "rule.keep_inventory", "true"), null);
        assertEquals(2, p.plan().orElseThrow().applicable());
        assertTrue(gateway.writes.isEmpty());
        assertEquals(0, gateway.persists);
        assertTrue(p.message().contains("Rien n'a été écrit"), p.message());
    }

    @Test
    void previewTakesThePositionOfAPlayerInThatWorld() {
        gateway.playerAt = "10,70,-20,90";
        var p = service.preview("terres", Map.of(), "Lody");
        var spawn = p.plan().orElseThrow().changes().get(0);
        assertEquals("10,70,-20,90", spawn.proposed());
        var absent = service.preview("terres", Map.of(), "Autre");
        var refused = absent.plan().orElseThrow().changes().get(0);
        assertEquals(WorldSettingsPlanner.Status.REFUSED, refused.status());
        assertTrue(refused.reason().contains("n'est pas connecté"), refused.reason());
    }

    @Test
    void appliesRereadsAndJournalsBeforeAndAfter() throws Exception {
        Map<String, String> req = new LinkedHashMap<>();
        req.put("difficulty", "HARD");
        req.put("rule.keep_inventory", "true");
        var a = service.apply("terres", req, fingerprint(), "lody");
        assertEquals(Result.APPLIED, a.result(), a.message());
        assertEquals(List.of("difficulty=HARD@BUKKIT", "rule.keep_inventory=true@GAMERULE"), gateway.writes);
        assertEquals("EASY", a.applied().get(0).before());
        assertEquals("HARD", a.applied().get(0).after());
        assertEquals("OK", a.applied().get(0).outcome());
        var history = audit.recent("terres", 5);
        assertEquals(1, history.size());
        assertEquals("lody", history.get(0).actor());
        assertEquals("2026-10-20T10:00:00Z", history.get(0).at());
        assertEquals("EASY", history.get(0).lines().get(0).before());
        String file = Files.readString(dir.resolve("world-settings-audit.yml"));
        assertTrue(file.contains("actor: lody") && file.contains("requested: HARD"), file);
        var reloaded = new WorldSettingsAuditLog(dir.resolve("world-settings-audit.yml"), Runnable::run,
                LoggerFactory.getLogger("test"));
        reloaded.load();
        assertEquals("HARD", reloaded.recent("terres", 1).get(0).lines().get(0).after());
    }

    @Test
    void staleFingerprintWritesNothing() {
        String old = fingerprint();
        gateway.values.put("time", "5000");
        var a = service.apply("terres", Map.of("difficulty", "HARD"), old, "lody");
        assertEquals(Result.STALE, a.result());
        assertTrue(gateway.writes.isEmpty());
        assertTrue(audit.recent("terres", 5).isEmpty());
    }

    @Test
    void refusedOrEmptyRequestsWriteNothing() {
        var hub = new WorldSettingsFixtures.FakeGateway("world_hub", List.of("HUB"));
        var hubService = new WorldSettingsService(hub, audit, Clock.systemUTC());
        var a = hubService.apply("world_hub", Map.of("time", "18000"),
                hubService.read("world_hub").fingerprint(), "lody");
        assertEquals(Result.REFUSED, a.result());
        assertTrue(hub.writes.isEmpty());
        var same = service.apply("terres", Map.of("difficulty", "EASY"), fingerprint(), "lody");
        assertEquals(Result.REFUSED, same.result());
        assertTrue(gateway.writes.isEmpty());
    }

    @Test
    void unloadedWorldIsNeverTouched() {
        gateway.loaded = false;
        assertEquals(Result.NOT_LOADED, service.apply("terres", Map.of("difficulty", "HARD"), "x", "lody").result());
        assertTrue(service.preview("terres", Map.of("difficulty", "HARD"), null).plan().isEmpty());
        assertTrue(gateway.writes.isEmpty());
    }

    @Test
    void rereadMismatchIsReported() {
        gateway.driftOn = "time";
        var a = service.apply("terres", Map.of("time", "6000"), fingerprint(), "lody");
        assertEquals(Result.FAILED, a.result());
        assertEquals("MISMATCH", a.applied().get(0).outcome());
        assertTrue(a.applied().get(0).detail().contains("drift"), a.applied().get(0).detail());
    }

    @Test
    void stopsAtTheFirstFailureAndSaysWhatWasNotAttempted() {
        gateway.failOn = "time";
        Map<String, String> req = new LinkedHashMap<>();
        req.put("time", "6000");
        req.put("difficulty", "HARD");
        req.put("rule.keep_inventory", "true");
        var a = service.apply("terres", req, fingerprint(), "lody");
        assertEquals(Result.PARTIAL, a.result());
        assertEquals(List.of("OK", "FAILED", "NOT_ATTEMPTED"), a.applied().stream().map(WorldSettingsService.Applied::outcome)
                .toList(), "ordre de la fiche : difficulté, heure, puis règles — " + a.applied());
        assertEquals(2, gateway.writes.size());
    }
}
