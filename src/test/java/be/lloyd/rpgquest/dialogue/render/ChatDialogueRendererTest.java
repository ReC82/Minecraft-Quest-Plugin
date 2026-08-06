package be.lloyd.rpgquest.dialogue.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import be.lloyd.rpgquest.dialogue.model.DialogueDefinition;
import be.lloyd.rpgquest.dialogue.model.DialogueNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * Vérifie le renderer de secours (texte cliquable dans le chat) : aucune
 * API expérimentale, fonctionne avec n'importe quel client.
 */
class ChatDialogueRendererTest {

    private ServerMock server;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void rendersSpeakerTextAndOneLinePerVisibleChoice() {
        PlayerMock player = server.addPlayer();
        Player[] clicked = new Player[1];
        int[] clickedIndex = new int[1];
        ChatDialogueRenderer renderer = new ChatDialogueRenderer(
                (p, dialogueId, nodeId, choiceIndex) -> {
                    clicked[0] = p;
                    clickedIndex[0] = choiceIndex;
                });

        DialogueDefinition dialogue = simpleDialogue();
        DialogueNode node = dialogue.startNode();
        List<VisibleChoice> visible = List.of(new VisibleChoice(0, "Accepter"), new VisibleChoice(2, "Refuser"));

        renderer.render(player, dialogue, node, visible);

        String speakerLine = player.nextMessage();
        assertNotNull(speakerLine);
        assertTrue(speakerLine.contains("Garde"), speakerLine);

        String textLine = player.nextMessage();
        assertNotNull(textLine);
        assertTrue(textLine.contains("Bienvenue"), textLine);

        String choiceOne = player.nextMessage();
        assertNotNull(choiceOne);
        assertTrue(choiceOne.contains("Accepter"), choiceOne);

        String choiceTwo = player.nextMessage();
        assertNotNull(choiceTwo);
        assertTrue(choiceTwo.contains("Refuser"), choiceTwo);
    }

    private DialogueDefinition simpleDialogue() {
        Map<String, DialogueNode> nodes = new LinkedHashMap<>();
        nodes.put("greeting", new DialogueNode(
                "greeting", "Garde",
                be.lloyd.rpgquest.quest.model.LocalizedText.of("Bienvenue."),
                List.of(new be.lloyd.rpgquest.dialogue.model.DialogueChoice(
                        be.lloyd.rpgquest.quest.model.LocalizedText.of("Accepter"), List.of(), List.of(), null))));
        return new DialogueDefinition(new NamespacedKey("rpgquest", "guard"), "greeting", nodes);
    }
}
