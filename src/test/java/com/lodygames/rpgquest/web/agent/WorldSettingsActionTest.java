package com.lodygames.rpgquest.web.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.world.settings.WorldSettingsService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/**
 * Actions {@code world.settings.*} (issue #561) : bornes revérifiées côté plugin, confirmation,
 * auteur et empreinte exigés avant toute écriture.
 */
class WorldSettingsActionTest {

    private final List<String> applyCalls = new ArrayList<>();

    private AgentActionOutcome run(String type, Map<String, String> params) {
        AgentActions actions = new StubAgentActions() {
            @Override
            public CompletableFuture<WorldSettingsService.Application> worldSettingsApply(String world,
                    Map<String, String> requested, String expected, String actor) {
                applyCalls.add(world + " " + requested + " " + expected + " " + actor);
                return CompletableFuture.completedFuture(new WorldSettingsService.Application(world,
                        WorldSettingsService.Result.APPLIED, "1 réglage(s) appliqué(s)",
                        List.of(new WorldSettingsService.Applied("difficulty", "Difficulté", "EASY", "HARD", "HARD",
                                "OK", "Valeur relue identique")), List.of()));
            }
        };
        var executor = new AgentActionExecutor(ref -> CompletableFuture.completedFuture(Optional.empty()),
                (uuid, key) -> CompletableFuture.completedFuture(Optional.empty()), actions);
        return executor.execute(new AgentAction("a1", type, params)).join();
    }

    private static Map<String, String> apply() {
        Map<String, String> p = new HashMap<>();
        p.put("world", "terres");
        p.put("set.difficulty", "HARD");
        p.put("expected", "0123456789abcdef01234567");
        p.put("actor", "lody");
        p.put("confirm", "true");
        return p;
    }

    @Test
    void readingIsUnavailableOnAPluginWithoutTheService() {
        assertEquals(AgentActionOutcome.FAILED, run("world.settings.get", Map.of("world", "terres")).status());
    }

    @Test
    void applyNeedsConfirmationActorAndPreviewFingerprint() {
        for (String missing : List.of("confirm", "actor", "expected", "world")) {
            Map<String, String> p = apply();
            p.remove(missing);
            AgentActionOutcome out = run("world.settings.apply", p);
            assertEquals(AgentActionOutcome.REJECTED, out.status(), missing);
            assertTrue(out.message().contains("Rien") || out.message().contains("rien")
                    || out.message().contains("monde"), out.message());
        }
        assertTrue(applyCalls.isEmpty(), "aucune écriture tentée");
    }

    @Test
    void outOfBoundsKeysAreRejected() {
        Map<String, String> p = apply();
        p.put("set.Bad Key", "1");
        assertEquals(AgentActionOutcome.REJECTED, run("world.settings.apply", p).status());
        Map<String, String> q = apply();
        q.put("set.rule.keep_inventory", "x".repeat(65));
        assertEquals(AgentActionOutcome.REJECTED, run("world.settings.apply", q).status());
        assertTrue(applyCalls.isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void validApplyReportsBeforeAndRereadValues() {
        AgentActionOutcome out = run("world.settings.apply", apply());
        assertEquals(AgentActionOutcome.SUCCESS, out.status(), out.message());
        assertEquals(List.of("terres {difficulty=HARD} 0123456789abcdef01234567 lody"), applyCalls);
        var row = ((List<Map<String, Object>>) out.details().get("applied")).get(0);
        assertEquals("EASY", row.get("before"));
        assertEquals("HARD", row.get("after"));
        assertEquals("OK", row.get("outcome"));
    }
}
