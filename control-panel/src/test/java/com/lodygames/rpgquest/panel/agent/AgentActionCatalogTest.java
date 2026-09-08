package com.lodygames.rpgquest.panel.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.panel.authz.Permission;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Liste blanche côté panel : types autorisés, permission, bornes des paramètres, confirmation. */
class AgentActionCatalogTest {

    @Test
    void unknownTypeIsRejected() {
        assertFalse(AgentActionCatalog.isWhitelisted("console.run"));
        assertFalse(AgentActionCatalog.validate("console.run", Map.of()).valid());
    }

    @Test
    void arbitraryCommandShapeIsNotWhitelisted() {
        assertFalse(AgentActionCatalog.isWhitelisted("rcon"));
        assertFalse(AgentActionCatalog.isWhitelisted("server.command"));
    }

    @Test
    void readActionNeedsNoConfirmation() {
        AgentActionCatalog.Validation v = AgentActionCatalog.validate("player.list", Map.of());
        assertTrue(v.valid());
        assertTrue(v.params().isEmpty());
    }

    @Test
    void mutationRequiresExplicitConfirm() {
        Map<String, String> noConfirm = Map.of("player", "LoDyMcFly", "quest_id", "rpgquest:crystal_hunt");
        assertFalse(AgentActionCatalog.validate("quest.complete", noConfirm).valid());

        Map<String, String> withConfirm = Map.of("player", "LoDyMcFly",
                "quest_id", "rpgquest:crystal_hunt", "confirm", "true");
        assertTrue(AgentActionCatalog.validate("quest.complete", withConfirm).valid());
    }

    @Test
    void giveAmountIsBounded() {
        assertFalse(AgentActionCatalog.validate("player.item.give",
                Map.of("player", "X", "item_id", "rpgquest:rune_rappel", "amount", "0", "confirm", "true")).valid());
        assertFalse(AgentActionCatalog.validate("player.item.give",
                Map.of("player", "X", "item_id", "rpgquest:rune_rappel", "amount", "65", "confirm", "true")).valid());
        AgentActionCatalog.Validation ok = AgentActionCatalog.validate("player.item.give",
                Map.of("player", "X", "item_id", "rpgquest:rune_rappel", "amount", "3", "confirm", "true"));
        assertTrue(ok.valid());
        assertEquals("3", ok.params().get("amount"));
    }

    @Test
    void playerRefIsValidated() {
        assertFalse(AgentActionCatalog.validate("quest.player.status", Map.of("player", "bad name!")).valid());
        assertTrue(AgentActionCatalog.validate("quest.player.status", Map.of("player", "LoDyMcFly")).valid());
    }

    @Test
    void storyIdIsNormalisedAndValidated() {
        assertFalse(AgentActionCatalog.validate("story.advance",
                Map.of("player", "X", "story_id", "Not Valid", "confirm", "true")).valid());
        AgentActionCatalog.Validation ok = AgentActionCatalog.validate("story.advance",
                Map.of("player", "X", "story_id", "Main_Story", "confirm", "true"));
        assertTrue(ok.valid());
        assertEquals("main_story", ok.params().get("story_id"));
    }

    @Test
    void variableSetKeepsKeyPatternAndBoundsValue() {
        assertFalse(AgentActionCatalog.validate("player.variable.set",
                Map.of("player", "X", "key", "bad key", "value", "true", "confirm", "true")).valid());
        assertTrue(AgentActionCatalog.validate("player.variable.set",
                Map.of("player", "X", "key", "CLAIM_TIER_1", "value", "true", "confirm", "true")).valid());
    }

    @Test
    void permissionsAreMappedPerType() {
        assertEquals(Permission.ACTION_QUEST, AgentActionCatalog.spec("quest.start").orElseThrow().permission());
        assertEquals(Permission.ACTION_STORY, AgentActionCatalog.spec("story.complete").orElseThrow().permission());
        assertEquals(Permission.ACTION_ITEM_GIVE, AgentActionCatalog.spec("player.item.give").orElseThrow().permission());
        assertEquals(Permission.PLAYERS_READ, AgentActionCatalog.spec("player.list").orElseThrow().permission());
        assertEquals(Permission.ACTION_PLAYER_RESET, AgentActionCatalog.spec("player.resetnew.confirm").orElseThrow().permission());
    }

    // ---- Éditeur guidé de dialogue (issue #82 phase 1) --------------------------------------

    @Test
    void dialogueEditActionsRequireDialogueWriteAndConfirm() {
        for (String type : new String[] {"dialogue.node.create", "dialogue.node.update",
                "dialogue.choice.add", "dialogue.choice.update", "dialogue.choice.delete"}) {
            assertEquals(Permission.DIALOGUE_WRITE, AgentActionCatalog.spec(type).orElseThrow().permission());
            assertTrue(AgentActionCatalog.spec(type).orElseThrow().mutation());
        }
        assertFalse(AgentActionCatalog.validate("dialogue.node.update", Map.of(
                "dialogue_id", "guard", "node_id", "greeting", "speaker", "G", "text", "T")).valid(), "confirm requis");
    }

    @Test
    void dialogueNodeUpdateNormalisesIdsAndRejectsBadInput() {
        AgentActionCatalog.Validation ok = AgentActionCatalog.validate("dialogue.node.update", Map.of(
                "dialogue_id", "Guard", "node_id", "Greeting", "speaker", "Capitaine",
                "text", "<y>Salut</y>", "confirm", "true"));
        assertTrue(ok.valid());
        assertEquals("rpgquest:guard", ok.params().get("dialogue_id"));
        assertEquals("greeting", ok.params().get("node_id"));

        assertFalse(AgentActionCatalog.validate("dialogue.node.update", Map.of(
                "dialogue_id", "guard", "node_id", "bad node", "speaker", "G", "text", "T", "confirm", "true")).valid());
        assertFalse(AgentActionCatalog.validate("dialogue.node.update", Map.of(
                "dialogue_id", "guard", "node_id", "greeting", "speaker", "G",
                "text", "a\nb", "confirm", "true")).valid(), "texte multi-ligne");
    }

    @Test
    void dialogueChoiceAddNeedsExactlyOneTarget() {
        assertFalse(AgentActionCatalog.validate("dialogue.choice.add", Map.of(
                "dialogue_id", "guard", "node_id", "accepted", "choice_text", "Retour", "confirm", "true")).valid());
        assertFalse(AgentActionCatalog.validate("dialogue.choice.add", Map.of(
                "dialogue_id", "guard", "node_id", "accepted", "choice_text", "Retour",
                "next_node_id", "greeting", "close", "true", "confirm", "true")).valid());
        AgentActionCatalog.Validation next = AgentActionCatalog.validate("dialogue.choice.add", Map.of(
                "dialogue_id", "guard", "node_id", "accepted", "choice_text", "Retour",
                "next_node_id", "greeting", "confirm", "true"));
        assertTrue(next.valid());
        assertEquals("greeting", next.params().get("next_node_id"));
        AgentActionCatalog.Validation close = AgentActionCatalog.validate("dialogue.choice.add", Map.of(
                "dialogue_id", "guard", "node_id", "accepted", "choice_text", "Fin", "close", "true", "confirm", "true"));
        assertTrue(close.valid());
        assertEquals("true", close.params().get("close"));
        assertFalse(close.params().containsKey("next_node_id"));
    }

    @Test
    void dialogueChoiceUpdateAndDeleteBoundTheIndex() {
        assertFalse(AgentActionCatalog.validate("dialogue.choice.update", Map.of(
                "dialogue_id", "guard", "node_id", "greeting", "choice_index", "-1",
                "choice_text", "X", "close", "true", "confirm", "true")).valid());
        AgentActionCatalog.Validation ok = AgentActionCatalog.validate("dialogue.choice.update", Map.of(
                "dialogue_id", "guard", "node_id", "greeting", "choice_index", "2",
                "choice_text", "X", "next_node_id", "accepted", "confirm", "true"));
        assertTrue(ok.valid());
        assertEquals("2", ok.params().get("choice_index"));
        assertFalse(AgentActionCatalog.validate("dialogue.choice.delete", Map.of(
                "dialogue_id", "guard", "node_id", "greeting", "choice_index", "abc", "confirm", "true")).valid());
    }
}
