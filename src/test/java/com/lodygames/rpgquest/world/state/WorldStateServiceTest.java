package com.lodygames.rpgquest.world.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.world.create.WorldCreationAuditLog;
import com.lodygames.rpgquest.world.state.WorldStatePlanner.Folder;
import com.lodygames.rpgquest.world.state.WorldStatePlanner.Operation;
import com.lodygames.rpgquest.world.state.WorldStatePlanner.Request;
import com.lodygames.rpgquest.world.state.WorldStateService.Result;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/**
 * Opérations confirmées, revérifiées, une seule à la fois, idempotentes, relues et journalisées
 * (issue #563). Faux serveur uniquement : aucun monde réel n'est chargé, déchargé ni importé.
 */
class WorldStateServiceTest {

    @TempDir
    Path dir;

    private WorldStateFixtures.FakeGateway server;
    private WorldStateService service;
    private Path auditFile;

    @BeforeEach
    void setUp() {
        server = new WorldStateFixtures.FakeGateway();
        auditFile = dir.resolve("world-state-audit.yml");
        service = new WorldStateService(server, new WorldCreationAuditLog(auditFile, Runnable::run,
                LoggerFactory.getLogger("test"), "Chargement / déchargement / import", List.of("test")),
                Clock.fixed(Instant.parse("2026-10-21T08:00:00Z"), ZoneOffset.UTC));
    }

    private WorldStateService.Outcome apply(Operation op, String world, boolean autoLoad, String token) {
        return service.apply(new Request(op, world, "", autoLoad), List.of(), token, "lody");
    }

    @Test
    void optionsNeverAct() {
        server.withLoaded("terres", 0);
        var o = service.options(List.of(new Folder("ancien", "NORMAL")));
        assertTrue(server.calls.isEmpty());
        assertFalse(Files.exists(auditFile));
        assertTrue(o.worlds().get("terres").get(Operation.UNLOAD).ok());
        assertFalse(o.worlds().get("world").get(Operation.UNLOAD).ok());
        assertEquals(List.of(new Folder("ancien", "NORMAL")), o.importable());
    }

    @Test
    void unloadThenReloadKeepsTheWorldData() {
        server.withLoaded("terres", 0);
        var unloaded = apply(Operation.UNLOAD, "terres", true, "tok-unload1");
        assertEquals(Result.DONE, unloaded.result(), unloaded.message());
        assertFalse(server.players.containsKey("terres"));
        assertTrue(server.known.containsKey("terres"), "toujours connu de Multiverse");
        var reloaded = apply(Operation.LOAD, "terres", true, "tok-reload1");
        assertEquals(Result.DONE, reloaded.result(), reloaded.message());
        assertEquals("blocs de terres", server.data.get("terres"), "données intactes");
        assertEquals(List.of("unload terres", "load terres"), server.calls);
        assertTrue(reloaded.checks().stream().allMatch(WorldStateService.Check::ok));
    }

    @Test
    void refusalsNeverReachTheServerAndAreAudited() {
        server.withLoaded("terres", 1);
        var o = apply(Operation.UNLOAD, "terres", true, "tok-refuse1");
        assertEquals(Result.REFUSED, o.result());
        assertTrue(o.message().contains("Rien n'a été fait"), o.message());
        for (String essential : List.of("world", "hub", "wild", "claims")) {
            assertEquals(Result.REFUSED, apply(Operation.UNLOAD, essential, true, "tok-" + essential + "-x").result());
        }
        assertTrue(server.calls.isEmpty());
        assertTrue(Files.exists(auditFile));
    }

    @Test
    void stateIsRecheckedAtApplyTime() {
        server.withLoaded("terres", 0);
        assertTrue(service.options(List.of()).worlds().get("terres").get(Operation.UNLOAD).ok());
        server.players.put("terres", 1); // un joueur arrive entre le relevé et la confirmation
        assertEquals(Result.REFUSED, apply(Operation.UNLOAD, "terres", true, "tok-late01").result());
        assertTrue(server.calls.isEmpty());
    }

    @Test
    void sameTokenIsNeverReplayed() {
        server.withLoaded("terres", 0);
        assertEquals(Result.DONE, apply(Operation.UNLOAD, "terres", true, "tok-once01").result());
        var again = apply(Operation.UNLOAD, "terres", true, "tok-once01");
        assertTrue(again.replayed());
        assertEquals(Result.DONE, again.result());
        assertEquals(List.of("unload terres"), server.calls);
    }

    @Test
    void autoLoadIsAppliedAfterTheOperation() {
        server.withLoaded("terres", 0);
        var o = apply(Operation.UNLOAD, "terres", false, "tok-auto01");
        assertEquals(Result.DONE, o.result(), o.message());
        assertEquals(List.of("unload terres", "autoload terres false"), server.calls);
        assertFalse(server.known.get("terres").autoLoad());
    }

    @Test
    void autoLoadNotReadBackIsAMismatch() {
        server.withUnloaded("archives", true);
        server.ignoreAutoLoad = true;
        var o = apply(Operation.LOAD, "archives", false, "tok-mism01");
        assertEquals(Result.MISMATCH, o.result());
    }

    @Test
    void importChecksDimensionThenLoads() {
        var folders = List.of(new Folder("ancien", "NETHER"));
        var refused = service.apply(new Request(Operation.IMPORT, "ancien", "NORMAL", true), folders, "tok-imp001", "lody");
        assertEquals(Result.REFUSED, refused.result());
        var done = service.apply(new Request(Operation.IMPORT, "ancien", "NETHER", true), folders, "tok-imp002", "lody");
        assertEquals(Result.DONE, done.result(), done.message());
        assertEquals(List.of("import ancien NETHER"), server.calls);
    }

    @Test
    void multiverseFailureIsReported() {
        server.withLoaded("terres", 0);
        server.failNext = "le serveur a refusé le déchargement";
        var o = apply(Operation.UNLOAD, "terres", true, "tok-fail01");
        assertEquals(Result.FAILED, o.result());
        assertTrue(o.message().contains("refusé le déchargement"));
        assertTrue(server.players.containsKey("terres"));
    }
}
