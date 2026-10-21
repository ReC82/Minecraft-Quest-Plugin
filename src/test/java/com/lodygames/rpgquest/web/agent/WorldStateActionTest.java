package com.lodygames.rpgquest.web.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.world.state.WorldStatePlanner;
import com.lodygames.rpgquest.world.state.WorldStateService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/**
 * Actions {@code world.load / unload / import / autoload} (issue #563) : confirmation, auteur, jeton
 * et « charger au démarrage » exigés côté plugin avant toute opération. Aucun monde n'est touché.
 */
class WorldStateActionTest {

    private final List<String> calls = new ArrayList<>();

    private AgentActionOutcome run(String type, Map<String, String> params) {
        AgentActions actions = new StubAgentActions() {
            @Override
            public CompletableFuture<WorldStateService.Outcome> worldStateApply(WorldStatePlanner.Request request,
                    String token, String actor) {
                calls.add(request.operation() + " " + request.world() + " " + request.environment() + " "
                        + request.autoLoad() + " " + token + " " + actor);
                return CompletableFuture.completedFuture(new WorldStateService.Outcome(request.world(),
                        request.operation(), WorldStateService.Result.DONE, "fait", false, List.of(), List.of(), null,
                        List.of()));
            }
        };
        var executor = new AgentActionExecutor(ref -> CompletableFuture.completedFuture(Optional.empty()),
                (uuid, key) -> CompletableFuture.completedFuture(Optional.empty()), actions);
        return executor.execute(new AgentAction("a1", type, params)).join();
    }

    private static Map<String, String> params() {
        Map<String, String> p = new HashMap<>();
        p.put("world", "terres");
        p.put("auto_load", "false");
        p.put("token", "0f4c9e8a-1111");
        p.put("by", "lody");
        p.put("confirm", "true");
        return p;
    }

    @Test
    void unavailableOnAPluginWithoutTheService() {
        AgentActions bare = new StubAgentActions();
        var executor = new AgentActionExecutor(ref -> CompletableFuture.completedFuture(Optional.empty()),
                (uuid, key) -> CompletableFuture.completedFuture(Optional.empty()), bare);
        assertEquals(AgentActionOutcome.FAILED,
                executor.execute(new AgentAction("a0", "world.state.options", Map.of())).join().status());
        assertEquals(AgentActionOutcome.FAILED,
                executor.execute(new AgentAction("a0", "world.unload", params())).join().status());
    }

    @Test
    void everyOperationNeedsConfirmationActorTokenAndAutoLoad() {
        for (String type : List.of("world.load", "world.unload", "world.import", "world.autoload")) {
            for (String missing : List.of("confirm", "by", "token", "auto_load", "world")) {
                Map<String, String> p = params();
                p.remove(missing);
                AgentActionOutcome out = run(type, p);
                assertEquals(AgentActionOutcome.REJECTED, out.status(), type + " sans " + missing);
                assertTrue(out.message().contains("rien n'a été fait"), out.message());
            }
        }
        Map<String, String> p = params();
        p.put("auto_load", "peut-être");
        assertEquals(AgentActionOutcome.REJECTED, run("world.unload", p).status());
        assertTrue(calls.isEmpty(), "aucune opération tentée");
    }

    @Test
    void operationAndParametersReachTheService() {
        assertEquals(AgentActionOutcome.SUCCESS, run("world.unload", params()).status());
        Map<String, String> imp = params();
        imp.put("environment", "NETHER");
        imp.put("auto_load", "true");
        assertEquals(AgentActionOutcome.SUCCESS, run("world.import", imp).status());
        assertEquals(List.of("UNLOAD terres  false 0f4c9e8a-1111 lody", "IMPORT terres NETHER true 0f4c9e8a-1111 lody"),
                calls);
    }
}
