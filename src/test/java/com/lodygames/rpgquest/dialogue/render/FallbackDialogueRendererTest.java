package com.lodygames.rpgquest.dialogue.render;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Vérifie le repli automatique vers le chat quand le renderer principal échoue à l'exécution. */
class FallbackDialogueRendererTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(FallbackDialogueRendererTest.class);

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
    void fallsBackToChatWhenPrimaryRendererThrows() {
        PlayerMock player = server.addPlayer();
        DialogueRenderer failingPrimary = (p, dialogue, node, visibleChoices) -> {
            throw new IllegalStateException("simulated failure");
        };
        ChatDialogueRenderer chat = new ChatDialogueRenderer((p, dialogueId, nodeId, choiceIndex) -> { });
        FallbackDialogueRenderer renderer = new FallbackDialogueRenderer(failingPrimary, chat, LOGGER);

        renderer.render(player, simpleDialogue(), simpleDialogue().startNode(), List.of(new VisibleChoice(0, "Accepter")));

        String speakerLine = player.nextMessage();
        assertNotNull(speakerLine, "le repli doit tout de même afficher le dialogue dans le chat");
        assertTrue(speakerLine.contains("Garde"), speakerLine);
    }

    @Test
    void doesNotFallBackWhenPrimaryRendererSucceeds() {
        Player[] rendered = new Player[1];
        DialogueRenderer succeedingPrimary = (p, dialogue, node, visibleChoices) -> rendered[0] = p;
        ChatDialogueRenderer chat = new ChatDialogueRenderer((p, dialogueId, nodeId, choiceIndex) -> { });
        FallbackDialogueRenderer renderer = new FallbackDialogueRenderer(succeedingPrimary, chat, LOGGER);
        PlayerMock player = server.addPlayer();

        renderer.render(player, simpleDialogue(), simpleDialogue().startNode(), List.of());

        assertNotNull(rendered[0]);
        assertTrue(player.nextMessage() == null, "aucun message de repli ne doit être envoyé si le renderer principal réussit");
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
