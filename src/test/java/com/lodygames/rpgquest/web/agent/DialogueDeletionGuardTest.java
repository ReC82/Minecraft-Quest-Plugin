package com.lodygames.rpgquest.web.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Issue #248 — ce que le serveur revérifie avant de supprimer un dialogue. */
class DialogueDeletionGuardTest {

    private static AgentActions.DialogueSummary dialogue(String id, List<String> npcs,
                                                         AgentActions.DialogueActionSummary... actions) {
        var choice = new AgentActions.DialogueChoiceSummary("Choix", "", List.of(actions), List.of());
        var node = new AgentActions.DialogueNodeSummary("start", "", "", true, true, List.of(choice));
        return new AgentActions.DialogueSummary(id, id.substring(id.indexOf(':') + 1), "start", npcs,
                1, 1, List.of(), List.of(), List.of(node), List.of());
    }

    private static AgentActions.DialogueActionSummary open(String target) {
        return new AgentActions.DialogueActionSummary("OPEN_DIALOGUE", target, null, "OPEN_DIALOGUE " + target);
    }

    @Test
    void theRealHelpCaseHasNothingBlocking() {
        var help = dialogue("rpgquest:help", List.of(),
                new AgentActions.DialogueActionSummary("START_QUEST", "rpgquest:help_recolte", null, "x"));
        assertTrue(DialogueDeletionGuard.blockers("help", List.of(help)).isEmpty(),
                "une référence SORTANTE ne bloque pas");
    }

    @Test
    void aLinkedNpcBlocks() {
        var jo = dialogue("rpgquest:jo", List.of("jo"));
        assertEquals(List.of("PNJ lié(s) : jo"), DialogueDeletionGuard.blockers("rpgquest:jo", List.of(jo)));
    }

    @Test
    void anIncomingOpenDialogueBlocks() {
        var target = dialogue("rpgquest:help", List.of());
        var other = dialogue("rpgquest:guide", List.of("guide"), open("rpgquest:help"));
        List<String> blockers = DialogueDeletionGuard.blockers("HELP", List.of(target, other));
        assertEquals(1, blockers.size());
        assertTrue(blockers.get(0).contains("rpgquest:guide"), blockers.toString());
    }

    @Test
    void openDialogueTowardsAnotherIdDoesNotBlock() {
        var target = dialogue("rpgquest:help", List.of());
        var other = dialogue("rpgquest:guide", List.of(), open("rpgquest:help_more"));
        assertTrue(DialogueDeletionGuard.blockers("help", List.of(target, other)).isEmpty());
    }
}
