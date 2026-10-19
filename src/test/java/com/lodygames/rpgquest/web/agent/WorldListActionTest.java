package com.lodygames.rpgquest.web.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.world.WorldOverview;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/** Action {@code world.list} (issue #560) : contrat JSON lu par la page Mondes, lecture seule. */
class WorldListActionTest {

    private static AgentActionOutcome run(AgentActions actions) {
        var executor = new AgentActionExecutor(ref -> CompletableFuture.completedFuture(Optional.empty()),
                (uuid, key) -> CompletableFuture.completedFuture(Optional.empty()), actions);
        return executor.execute(new AgentAction("a1", "world.list", Map.of())).join();
    }

    @Test
    void unavailableOnAPluginThatCannotListWorlds() {
        AgentActionOutcome out = run(new StubAgentActions());
        assertEquals(AgentActionOutcome.FAILED, out.status());
    }

    @Test
    @SuppressWarnings("unchecked")
    void listsWorldsWithLinksAndUnreadableValuesAsNull() {
        var in = new WorldOverview.Inputs(
                List.of(new WorldOverview.LoadedWorld("world", "NORMAL", "NORMAL", -4172093381123L, true, 2, "EASY",
                        true, false, 1, 70, 2, null)),
                "world", WorldOverview.ExternalSource.unavailable("Multiverse-Core n'est pas installé"), List.of(),
                new WorldOverview.Roles("world", "wild", ""), new WorldOverview.WaypointPolicy(true, true),
                List.of(new WorldOverview.PortalLink("hub_to_wild", "world", "wild", true, "RANDOM_SAFE")),
                List.of(), List.of(), List.of(), List.of(), List.of(), 2);
        var view = WorldOverview.build(in);
        AgentActionOutcome out = run(new StubAgentActions() {
            @Override
            public CompletableFuture<WorldOverview.View> worldList() {
                return CompletableFuture.completedFuture(view);
            }
        });
        assertEquals(AgentActionOutcome.SUCCESS, out.status(), out.message());
        assertTrue(out.message().contains("sans Multiverse-Core"), out.message());
        assertTrue(out.message().contains("2 joueur(s) connecté(s)"), out.message());
        assertEquals(2, out.details().get("onlinePlayers"), "total serveur, même instant que les compteurs");
        List<Map<String, Object>> worlds = (List<Map<String, Object>>) out.details().get("worlds");
        assertEquals(2, worlds.size());
        Map<String, Object> world = worlds.get(0);
        assertEquals("-4172093381123", world.get("seed"), "graine en texte");
        assertEquals("SERVER", world.get("manager"));
        assertEquals(List.of("HUB"), world.get("roles"));
        Map<String, Object> links = (Map<String, Object>) world.get("links");
        assertEquals("hub_to_wild", ((List<Map<String, Object>>) links.get("portalsFrom")).get(0).get("id"));
        Map<String, Object> wild = worlds.get(1);
        assertEquals("UNKNOWN", wild.get("manager"));
        assertEquals(null, wild.get("seed"));
        assertEquals("WORLD_ROLE_NOT_LOADED",
                ((List<Map<String, Object>>) wild.get("issues")).get(0).get("code"));
        assertEquals(false, ((Map<String, Object>) out.details().get("multiverse")).get("available"));
    }
}
