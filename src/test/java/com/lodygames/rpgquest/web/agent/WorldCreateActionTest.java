package com.lodygames.rpgquest.web.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.world.create.WorldCreationRequest;
import com.lodygames.rpgquest.world.create.WorldCreationService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/**
 * Actions {@code world.create.*} (issue #562) : bornes revérifiées côté plugin ; confirmation,
 * auteur, empreinte et jeton exigés avant toute création. Aucun monde n'est créé.
 */
class WorldCreateActionTest {

    private final List<String> createCalls = new ArrayList<>();

    private AgentActionOutcome run(String type, Map<String, String> params) {
        AgentActions actions = new StubAgentActions() {
            @Override
            public CompletableFuture<WorldCreationService.Creation> worldCreateApply(WorldCreationRequest request,
                    String expected, String token, String actor) {
                createCalls.add(request.name() + " " + request.layers() + " " + expected + " " + token + " " + actor);
                return CompletableFuture.completedFuture(new WorldCreationService.Creation("terres",
                        WorldCreationService.Result.CREATED, "créé", false, List.of(), List.of(), null, List.of()));
            }
        };
        var executor = new AgentActionExecutor(ref -> CompletableFuture.completedFuture(Optional.empty()),
                (uuid, key) -> CompletableFuture.completedFuture(Optional.empty()), actions);
        return executor.execute(new AgentAction("a1", type, params)).join();
    }

    private static Map<String, String> apply() {
        Map<String, String> p = new HashMap<>();
        p.put("name", "terres");
        p.put("environment", "NORMAL");
        p.put("terrain", "FLAT");
        p.put("layer.2.block", "minecraft:dirt");
        p.put("layer.2.height", "3");
        p.put("layer.1.block", "minecraft:bedrock");
        p.put("layer.1.height", "1");
        p.put("layer.3.block", "");
        p.put("expected", "0123456789abcdef01234567");
        p.put("token", "preview-123");
        p.put("actor", "lody");
        p.put("confirm", "true");
        return p;
    }

    @Test
    void unavailableOnAPluginWithoutTheService() {
        assertEquals(AgentActionOutcome.FAILED, run("world.create.options", Map.of()).status());
        assertEquals(AgentActionOutcome.FAILED, run("world.create.preview", Map.of("name", "terres")).status());
    }

    @Test
    void applyNeedsConfirmationActorFingerprintAndToken() {
        for (String missing : List.of("confirm", "actor", "expected", "token")) {
            Map<String, String> p = apply();
            p.remove(missing);
            AgentActionOutcome out = run("world.create.apply", p);
            assertEquals(AgentActionOutcome.REJECTED, out.status(), missing);
            assertTrue(out.message().contains("rien n'a été créé"), out.message());
        }
        assertTrue(createCalls.isEmpty(), "aucune création tentée");
    }

    @Test
    void outOfBoundsFieldsAreRejected() {
        Map<String, String> p = apply();
        p.put("layer.1.height", "beaucoup");
        assertEquals(AgentActionOutcome.REJECTED, run("world.create.apply", p).status());
        Map<String, String> q = apply();
        q.put("layer.17.block", "minecraft:stone");
        q.put("layer.17.height", "1");
        assertEquals(AgentActionOutcome.REJECTED, run("world.create.apply", q).status());
        Map<String, String> r = apply();
        r.put("name", "x\nrm");
        assertEquals(AgentActionOutcome.REJECTED, run("world.create.apply", r).status());
        assertTrue(createCalls.isEmpty());
    }

    @Test
    void layersArriveInOrderAndEmptyRowsAreIgnored() {
        AgentActionOutcome out = run("world.create.apply", apply());
        assertEquals(AgentActionOutcome.SUCCESS, out.status(), out.message());
        assertEquals(List.of("terres [Layer[block=minecraft:bedrock, height=1], Layer[block=minecraft:dirt, height=3]] "
                + "0123456789abcdef01234567 preview-123 lody"), createCalls);
    }
}
