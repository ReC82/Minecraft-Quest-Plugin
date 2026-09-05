package com.lodygames.rpgquest.dialogue.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.dialogue.model.DialogueDefinition;
import com.lodygames.rpgquest.dialogue.model.DialogueNode;
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

    @Test
    void miniMessageTagsInSpeakerTextAndChoicesAreActuallyRenderedNotShownRaw() {
        // Régression : speaker/text/choix étaient auparavant substitués via Placeholder.unparsed(),
        // qui traite les balises MiniMessage comme du texte brut, affichant littéralement
        // "<white>...</white>" au joueur au lieu d'interpréter la couleur.
        PlayerMock player = server.addPlayer();
        ChatDialogueRenderer renderer = new ChatDialogueRenderer((p, dialogueId, nodeId, choiceIndex) -> { });

        Map<String, DialogueNode> nodes = new LinkedHashMap<>();
        nodes.put("greeting", new DialogueNode(
                "greeting", "<red>Guide</red>",
                com.lodygames.rpgquest.quest.model.LocalizedText.of(
                        "<white>Bienvenue au village !</white> <gray>Va donc voir notre libraire.</gray>"),
                List.of(new com.lodygames.rpgquest.dialogue.model.DialogueChoice(
                        com.lodygames.rpgquest.quest.model.LocalizedText.of("<italic>Très bien, j'y vais.</italic>"),
                        List.of(), List.of(), null))));
        DialogueDefinition dialogue = new DialogueDefinition(new NamespacedKey("rpgquest", "guide"), "greeting", nodes);
        DialogueNode node = dialogue.startNode();

        renderer.render(player, dialogue, node, List.of(new VisibleChoice(0, "<italic>Très bien, j'y vais.</italic>")));

        String speakerLine = player.nextMessage();
        assertNotNull(speakerLine);
        assertTrue(speakerLine.contains("Guide"), speakerLine);
        assertTrue(!speakerLine.contains("<red>") && !speakerLine.contains("</red>"), speakerLine);

        String textLine = player.nextMessage();
        assertNotNull(textLine);
        assertTrue(textLine.contains("Bienvenue au village !"), textLine);
        assertTrue(!textLine.contains("<white>") && !textLine.contains("<gray>"), textLine);

        String choiceLine = player.nextMessage();
        assertNotNull(choiceLine);
        assertTrue(choiceLine.contains("j'y vais"), choiceLine);
        assertTrue(!choiceLine.contains("<italic>") && !choiceLine.contains("</italic>"), choiceLine);
    }

    private DialogueDefinition simpleDialogue() {
        Map<String, DialogueNode> nodes = new LinkedHashMap<>();
        nodes.put("greeting", new DialogueNode(
                "greeting", "Garde",
                com.lodygames.rpgquest.quest.model.LocalizedText.of("Bienvenue."),
                List.of(new com.lodygames.rpgquest.dialogue.model.DialogueChoice(
                        com.lodygames.rpgquest.quest.model.LocalizedText.of("Accepter"), List.of(), List.of(), null))));
        return new DialogueDefinition(new NamespacedKey("rpgquest", "guard"), "greeting", nodes);
    }
}
