package com.lodygames.rpgquest.web.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.config.RandomSafeArrivalConfig;
import com.lodygames.rpgquest.travel.PortalLoadIssue;
import com.lodygames.rpgquest.travel.PortalOverview;
import com.lodygames.rpgquest.travel.model.DestinationStrategy;
import com.lodygames.rpgquest.travel.model.WorldPortalDefinition;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/** Action {@code portal.list} (issue #564) : contrat JSON lu par la page Portails, lecture seule. */
class PortalListActionTest {

    private static AgentActionOutcome run(AgentActions actions) {
        var executor = new AgentActionExecutor(ref -> CompletableFuture.completedFuture(Optional.empty()),
                (uuid, key) -> CompletableFuture.completedFuture(Optional.empty()), actions);
        return executor.execute(new AgentAction("a1", "portal.list", Map.of())).join();
    }

    @Test
    void unavailableOnAPluginThatCannotListPortals() {
        assertEquals(AgentActionOutcome.FAILED, run(new StubAgentActions()).status());
    }

    @Test
    @SuppressWarnings("unchecked")
    void listsPortalsWithZoneStrategyGuardsAndIssues() {
        var view = PortalOverview.build(new PortalOverview.Inputs(
                List.of(new WorldPortalDefinition("hub_to_wild", "world_hub", 737, 66, -673, 740, 69, -673, "wild",
                        true, DestinationStrategy.RANDOM_SAFE)),
                List.of("hub_to_wild.yml"), List.of(new PortalLoadIssue("broken.yml", "id manquant")), List.of(),
                List.of(), List.of(), Map.of("world_hub", "0, 64, 0"),
                new PortalOverview.Roles("world_hub", "wild", "claims"), new RandomSafeArrivalConfig(500, 5000, 20),
                new PortalOverview.ClaimArrival(200, 3000, 64, 40), new PortalOverview.GuideNpc("guide", "Elias")));
        AgentActionOutcome out = run(new StubAgentActions() {
            @Override
            public CompletableFuture<PortalOverview.View> portalList() {
                return CompletableFuture.completedFuture(view);
            }
        });
        assertEquals(AgentActionOutcome.SUCCESS, out.status(), out.message());
        assertEquals("1 portail(s), 1 actif(s) — 1 fichier(s) ignoré(s)", out.message());
        List<Map<String, Object>> portals = (List<Map<String, Object>>) out.details().get("portals");
        Map<String, Object> p = portals.get(0);
        assertEquals("hub_to_wild", p.get("id"));
        assertEquals("WORLD_PORTAL", p.get("kind"));
        assertEquals("737, 66, -673", p.get("min"));
        assertEquals("740, 69, -673", p.get("max"));
        assertEquals("RANDOM_SAFE", p.get("strategy"));
        assertEquals(false, p.get("destinationLoaded"));
        List<Map<String, Object>> guards = (List<Map<String, Object>>) p.get("guards");
        assertEquals("WILD_WARNING", guards.get(0).get("code"));
        assertTrue(((List<Map<String, Object>>) guards.get(0).get("texts")).size() >= 5);
        assertEquals("PORTAL_DESTINATION_NOT_LOADED",
                ((List<Map<String, Object>>) p.get("issues")).get(0).get("code"));
        assertEquals("PORTAL_FILE_SKIPPED",
                ((List<Map<String, Object>>) out.details().get("issues")).get(0).get("code"));
    }
}
