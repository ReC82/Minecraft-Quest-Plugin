package com.lodygames.rpgquest.web.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/**
 * Actions {@code npc.citizens.lookclose} et {@code npc.citizens.wander} (issue #165), vues depuis
 * l'exécuteur : validation des paramètres et <strong>absence de toute bascule implicite</strong>.
 *
 * <p>Le point sensible n'est pas la validation en elle-même mais l'<em>idempotence</em> : ces deux
 * actions traversent une file réseau où une requête peut être rejouée (double clic, retry, rejeu
 * par le cache d'idempotence). Une action « inverser l'état » produirait alors un résultat
 * dépendant du nombre de rejeux. Ces tests figent le contrat : {@code enabled} est un état
 * explicite, et rien ne l'infère.</p>
 */
class NpcBehaviourActionTest {

    /** Enregistre les appels reçus pour vérifier ce qui est réellement transmis au métier. */
    private static final class Recorder extends StubAgentActions {
        final List<String> lookClose = new ArrayList<>();
        final List<String> wander = new ArrayList<>();

        @Override
        public CompletableFuture<MutationResult> citizensLookClose(String npcId, boolean enabled, Double range) {
            lookClose.add(npcId + "|" + enabled + "|" + range);
            return CompletableFuture.completedFuture(MutationResult.of(true, "LOOKCLOSE_SET", "fait"));
        }

        @Override
        public CompletableFuture<MutationResult> citizensWander(String npcId, boolean enabled,
                                                                String world, Double x, Double y, Double z,
                                                                int xRange, int yRange, boolean confirmReplace) {
            wander.add(npcId + "|" + enabled + "|" + world + "/" + x + "/" + y + "/" + z
                    + "|" + xRange + "x" + yRange + "|confirm=" + confirmReplace);
            return CompletableFuture.completedFuture(MutationResult.of(true, "WANDER_ENABLED", "fait"));
        }
    }

    private final Recorder actions = new Recorder();
    private final AgentActionExecutor executor = new AgentActionExecutor(
            ref -> CompletableFuture.completedFuture(Optional.empty()),
            (uuid, key) -> CompletableFuture.completedFuture(Optional.empty()),
            actions);

    private AgentActionOutcome run(String type, Map<String, String> params) {
        return executor.execute(new AgentAction("a1", type, params)).join();
    }

    private static Map<String, String> params(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    // ---- Look Close ------------------------------------------------------------------------

    @Test
    void lookCloseRequiresAnExplicitState() {
        // Pas de paramètre « enabled » = refus. Surtout pas un défaut, et surtout pas une bascule.
        assertEquals(AgentActionOutcome.REJECTED,
                run("npc.citizens.lookclose", params("npc_id", "andy")).status());
        assertEquals(AgentActionOutcome.REJECTED,
                run("npc.citizens.lookclose", params("npc_id", "andy", "enabled", "")).status());
        assertEquals(AgentActionOutcome.REJECTED,
                run("npc.citizens.lookclose", params("npc_id", "andy", "enabled", "toggle")).status());
        assertEquals(AgentActionOutcome.REJECTED,
                run("npc.citizens.lookclose", params("npc_id", "andy", "enabled", "inverser")).status());
        assertTrue(actions.lookClose.isEmpty(), "aucun appel métier ne doit partir sur un état ambigu");
    }

    @Test
    void replayingTheSameLookCloseRequestAlwaysYieldsTheSameState() {
        for (int i = 0; i < 3; i++) {
            assertEquals(AgentActionOutcome.SUCCESS,
                    run("npc.citizens.lookclose", params("npc_id", "andy", "enabled", "true")).status());
        }
        assertEquals(List.of("andy|true|null", "andy|true|null", "andy|true|null"), actions.lookClose,
                "trois rejeux doivent produire trois fois le MÊME état, jamais une alternance");
    }

    @Test
    void lookCloseAcceptsAnOptionalRangeWithinPanelBounds() {
        assertEquals(AgentActionOutcome.SUCCESS,
                run("npc.citizens.lookclose", params("npc_id", "andy", "enabled", "true", "range", "16")).status());
        assertEquals("andy|true|16.0", actions.lookClose.get(0));
    }

    @Test
    void lookCloseRefusesARangeOutsideItsBounds() {
        for (String bad : new String[] {"0", "-3", "65", "abc", "NaN"}) {
            assertEquals(AgentActionOutcome.REJECTED,
                    run("npc.citizens.lookclose",
                            params("npc_id", "andy", "enabled", "true", "range", bad)).status(),
                    "portée acceptée à tort : " + bad);
        }
        assertTrue(actions.lookClose.isEmpty());
    }

    @Test
    void lookCloseRefusesAnInvalidNpcIdentifier() {
        assertEquals(AgentActionOutcome.REJECTED,
                run("npc.citizens.lookclose", params("npc_id", "../etc", "enabled", "true")).status());
    }

    // ---- Wander ----------------------------------------------------------------------------

    @Test
    void wanderRequiresAnExplicitState() {
        assertEquals(AgentActionOutcome.REJECTED,
                run("npc.citizens.wander", params("npc_id", "andy", "x_range", "12", "y_range", "2")).status());
        assertTrue(actions.wander.isEmpty());
    }

    @Test
    void disablingWanderNeedsNeitherAnchorNorZone() {
        assertEquals(AgentActionOutcome.SUCCESS,
                run("npc.citizens.wander", params("npc_id", "andy", "enabled", "false",
                        "x_range", "12", "y_range", "2")).status());
        assertTrue(actions.wander.get(0).contains("|false|"));
    }

    @Test
    void aPartialAnchorIsRefusedRatherThanCompletedByGuesswork() {
        for (Map<String, String> partial : List.of(
                params("npc_id", "andy", "enabled", "true", "x_range", "12", "y_range", "2",
                        "world", "world_hub"),
                params("npc_id", "andy", "enabled", "true", "x_range", "12", "y_range", "2",
                        "world", "world_hub", "x", "10"),
                params("npc_id", "andy", "enabled", "true", "x_range", "12", "y_range", "2",
                        "x", "10", "y", "64", "z", "10"))) {
            assertEquals(AgentActionOutcome.REJECTED, run("npc.citizens.wander", partial).status(),
                    "ancre partielle acceptée à tort : " + partial);
        }
        assertTrue(actions.wander.isEmpty());
    }

    @Test
    void anAbsentAnchorIsAllowedAndLeavesTheChoiceToTheServer() {
        // Rien n'est inventé ici : le plugin reprendra la position réellement connue du PNJ.
        assertEquals(AgentActionOutcome.SUCCESS,
                run("npc.citizens.wander", params("npc_id", "andy", "enabled", "true",
                        "x_range", "12", "y_range", "2")).status());
        assertTrue(actions.wander.get(0).contains("null/null/null/null"));
    }

    @Test
    void aCompleteAnchorIsTransmittedAsIs() {
        assertEquals(AgentActionOutcome.SUCCESS,
                run("npc.citizens.wander", params("npc_id", "andy", "enabled", "true",
                        "world", "world_hub", "x", "737.5", "y", "67", "z", "-684.5",
                        "x_range", "12", "y_range", "2")).status());
        assertEquals("andy|true|world_hub/737.5/67.0/-684.5|12x2|confirm=false", actions.wander.get(0));
    }

    @Test
    void theZoneIsMandatoryAndBounded() {
        for (String[] bad : new String[][] {{"0", "2"}, {"-1", "2"}, {"65", "2"}, {"12", "-1"},
            {"12", "33"}, {"", "2"}, {"12", ""}, {"abc", "2"}}) {
            assertEquals(AgentActionOutcome.REJECTED,
                    run("npc.citizens.wander", params("npc_id", "andy", "enabled", "true",
                            "x_range", bad[0], "y_range", bad[1])).status(),
                    "zone acceptée à tort : " + bad[0] + " / " + bad[1]);
        }
        assertTrue(actions.wander.isEmpty());
    }

    @Test
    void aFlatVerticalZoneIsLegitimate() {
        // y_range = 0 : promenade strictement sur un plan. C'est une valeur utile, pas une erreur.
        assertEquals(AgentActionOutcome.SUCCESS,
                run("npc.citizens.wander", params("npc_id", "andy", "enabled", "true",
                        "x_range", "8", "y_range", "0")).status());
        assertTrue(actions.wander.get(0).contains("|8x0|"));
    }

    @Test
    void theReplacementConfirmationIsNeverAssumed() {
        run("npc.citizens.wander", params("npc_id", "andy", "enabled", "true",
                "x_range", "8", "y_range", "2"));
        assertTrue(actions.wander.get(0).endsWith("confirm=false"),
                "sans confirmation explicite, l'action ne doit jamais prétendre être confirmée");

        actions.wander.clear();
        run("npc.citizens.wander", params("npc_id", "andy", "enabled", "true",
                "x_range", "8", "y_range", "2", "confirm_replace", "true"));
        assertTrue(actions.wander.get(0).endsWith("confirm=true"));
    }

    @Test
    void anUnparseableConfirmationIsTreatedAsNotConfirmed() {
        run("npc.citizens.wander", params("npc_id", "andy", "enabled", "true",
                "x_range", "8", "y_range", "2", "confirm_replace", "peut-être"));
        assertTrue(actions.wander.get(0).endsWith("confirm=false"));
    }

    @Test
    void bothActionsAreOnTheExecutorWhitelist() {
        // Un type inconnu est rejeté : ce test prouve que les deux nouveaux le sont bien, eux.
        assertEquals(AgentActionOutcome.REJECTED,
                run("npc.citizens.lookfar", params("npc_id", "andy")).status());
        assertEquals(AgentActionOutcome.SUCCESS,
                run("npc.citizens.lookclose", params("npc_id", "andy", "enabled", "false")).status());
        assertEquals(AgentActionOutcome.SUCCESS,
                run("npc.citizens.wander", params("npc_id", "andy", "enabled", "false",
                        "x_range", "8", "y_range", "2")).status());
    }
}
