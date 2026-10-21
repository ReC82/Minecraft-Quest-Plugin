package com.lodygames.rpgquest.web.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.lodygames.rpgquest.claim.ClaimOverview;
import com.lodygames.rpgquest.claim.model.Claim;
import com.lodygames.rpgquest.claim.model.ClaimFlags;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/** Actions {@code claim.list} et {@code claim.teleport} (issue #637) : contrat JSON et validation. */
class ClaimActionsTest {

    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID ADMIN = UUID.fromString("00000000-0000-0000-0000-0000000000ad");

    private static AgentActionOutcome run(AgentActions actions, String type, Map<String, String> params) {
        var executor = new AgentActionExecutor(
                ref -> CompletableFuture.completedFuture(Optional.of(new PlayerDirectory.ResolvedPlayer(ADMIN, "Admin"))),
                (uuid, key) -> CompletableFuture.completedFuture(Optional.empty()), actions);
        return executor.execute(new AgentAction("a1", type, params)).join();
    }

    @Test
    void listIsUnavailableOnAPluginWithoutClaims() {
        assertEquals(AgentActionOutcome.FAILED, run(new StubAgentActions(), "claim.list", Map.of()).status());
    }

    @Test
    @SuppressWarnings("unchecked")
    void listExposesEachClaimForThePanel() {
        Claim claim = new Claim("main_a", OWNER, "claims", -2, 60, -2, 2, 70, 2, -50, 60, -50, 49, 70, 49,
                Set.of(), ClaimFlags.defaults());
        var view = ClaimOverview.build(List.of(claim), owner -> Optional.of(claim), owner -> null, w -> true, "claims");
        AgentActionOutcome out = run(new StubAgentActions() {
            @Override
            public CompletableFuture<ClaimOverview.View> claimList() {
                return CompletableFuture.completedFuture(view);
            }
        }, "claim.list", Map.of());

        assertEquals(AgentActionOutcome.SUCCESS, out.status(), out.message());
        assertEquals("1 claim(s), 1 propriétaire(s)", out.message());
        Map<String, Object> row = ((List<Map<String, Object>>) out.details().get("claims")).get(0);
        assertEquals("main_a", row.get("id"));
        assertEquals(OWNER.toString(), row.get("owner"));
        assertNull(row.get("ownerName"));
        assertEquals(true, row.get("main"));
        assertEquals("-2, 60, -2", row.get("min"));
        assertEquals("TIER_1", row.get("tier"));
        assertEquals("claims", out.details().get("claimsWorld"));
    }

    @Test
    void teleportMovesTheResolvedAdministratorAndReportsTheRealOutcome() {
        List<String> calls = new ArrayList<>();
        StubAgentActions actions = new StubAgentActions() {
            @Override
            public CompletableFuture<MutationResult> claimTeleport(UUID traveler, String name, String claimId,
                                                                   UUID expectedOwner) {
                calls.add(traveler + "|" + claimId + "|" + expectedOwner);
                return CompletableFuture.completedFuture(MutationResult.of(false, "NO_SAFE_LOCATION", "Aucune position sûre."));
            }
        };
        AgentActionOutcome out = run(actions, "claim.teleport",
                Map.of("player", ADMIN.toString(), "claim", "main_a", "owner", OWNER.toString()));

        assertEquals(List.of(ADMIN + "|main_a|" + OWNER), calls);
        assertEquals(AgentActionOutcome.FAILED, out.status(), "un refus n'est jamais un succès");
        assertEquals("NO_SAFE_LOCATION", out.details().get("code"));
    }

    @Test
    void teleportRejectsAMalformedClaimOrOwnerWithoutCallingTheServer() {
        List<String> calls = new ArrayList<>();
        StubAgentActions actions = new StubAgentActions() {
            @Override
            public CompletableFuture<MutationResult> claimTeleport(UUID traveler, String name, String claimId,
                                                                   UUID expectedOwner) {
                calls.add(claimId);
                return CompletableFuture.completedFuture(MutationResult.of(true, "TELEPORTED", "ok"));
            }
        };
        assertEquals(AgentActionOutcome.REJECTED, run(actions, "claim.teleport",
                Map.of("player", ADMIN.toString(), "claim", "../x")).status());
        assertEquals(AgentActionOutcome.REJECTED, run(actions, "claim.teleport",
                Map.of("player", ADMIN.toString(), "claim", "main_a", "owner", "pas-un-uuid")).status());
        assertEquals(List.of(), calls);
    }
}
