package com.lodygames.rpgquest.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.RPGQuestPlugin;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * Issue #95 — l'annonce globale. Le point le plus important n'est pas « ça envoie » mais
 * <strong>ce qui est refusé</strong> : un message traité comme une commande, un canal inventé, et
 * surtout un « succès » affiché alors que personne n'était connecté.
 */
class ServerOpsServiceTest {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private ServerMock server;
    private RPGQuestPlugin plugin;
    private ServerOpsService service;
    private ServerLogBuffer buffer;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(RPGQuestPlugin.class);
        buffer = new ServerLogBuffer(100);
        service = new ServerOpsService(plugin, buffer, Optional::empty);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private String nextPlain(PlayerMock player) {
        Component component = player.nextComponentMessage();
        return component == null ? null : PLAIN.serialize(component);
    }

    @Test
    void aChatAnnounceReachesEveryConnectedPlayer() {
        PlayerMock first = server.addPlayer();
        PlayerMock second = server.addPlayer();

        ServerOpsService.AnnounceOutcome outcome = service.announce("Redémarrage dans 5 minutes.", "chat");

        assertTrue(outcome.ok());
        assertEquals("SENT", outcome.code());
        assertEquals(2, outcome.recipients());
        assertEquals(2, outcome.online());
        for (PlayerMock player : new PlayerMock[] {first, second}) {
            String message = lastMessageContaining(player, "Redémarrage");
            assertTrue(message != null && message.contains("[Serveur]"),
                    () -> "message reçu : " + message);
        }
    }

    /** Vide la file de messages du joueur et renvoie le premier qui contient {@code needle}. */
    private String lastMessageContaining(PlayerMock player, String needle) {
        String found = null;
        String next;
        while ((next = nextPlain(player)) != null) {
            if (next.contains(needle)) {
                found = next;
            }
        }
        return found;
    }

    @Test
    void theMessageIsSentAsLiteralTextNotAsMiniMessage() {
        PlayerMock player = server.addPlayer();

        service.announce("<click:run_command:/op pirate>Clique ici</click>", "chat");

        // Le point de sécurité du lot : si ce texte était passé à MiniMessage, un clic exécuterait
        // « /op pirate » chez tous les joueurs. Il doit donc apparaître TEL QUEL.
        String message = lastMessageContaining(player, "click");
        assertTrue(message != null && message.contains("<click:run_command:/op pirate>"),
                () -> "le balisage doit rester littéral : " + message);
    }

    @Test
    void anAnnounceWithNobodyConnectedIsNotPresentedAsASuccessfulBroadcast() {
        ServerOpsService.AnnounceOutcome outcome = service.announce("personne n'écoute", "chat");

        assertTrue(outcome.ok(), "ce n'est pas une erreur d'administration");
        assertEquals("NO_PLAYERS", outcome.code());
        assertEquals(0, outcome.recipients());
        assertEquals(0, outcome.online());
        assertTrue(outcome.message().contains("Aucun joueur"), outcome.message());
    }

    @Test
    void aCommandIsRefusedRatherThanBroadcastLiterally() {
        PlayerMock player = server.addPlayer();

        ServerOpsService.AnnounceOutcome outcome = service.announce("/say coucou", "chat");

        assertFalse(outcome.ok());
        assertEquals("INVALID_MESSAGE", outcome.code());
        assertNull(outcome.channel(), "aucun canal n'a été utilisé");
        assertNull(nextPlain(player), "rien ne doit partir");
    }

    @Test
    void emptyTooLongAndMultilineMessagesAreRefused() {
        assertEquals("INVALID_MESSAGE", service.announce("   ", "chat").code());
        assertEquals("INVALID_MESSAGE", service.announce(null, "chat").code());
        assertEquals("INVALID_MESSAGE",
                service.announce("x".repeat(ServerOpsService.MAX_MESSAGE_CHARS + 1), "chat").code());
        assertEquals("INVALID_MESSAGE", service.announce("a\nb", "chat").code());
    }

    @Test
    void anUnknownChannelIsRefused() {
        ServerOpsService.AnnounceOutcome outcome = service.announce("test", "bossbar");

        assertFalse(outcome.ok());
        assertEquals("INVALID_CHANNEL", outcome.code());
        assertTrue(outcome.message().contains("chat"), outcome.message());
    }

    @Test
    void everySupportedChannelIsAcceptedAndCounted() {
        server.addPlayer();

        for (String channel : ServerOpsService.CHANNELS) {
            ServerOpsService.AnnounceOutcome outcome = service.announce("annonce " + channel, channel);
            assertTrue(outcome.ok(), () -> channel + " : " + outcome.message());
            assertEquals(channel, outcome.channel());
            assertEquals(1, outcome.recipients(),
                    () -> "le canal « " + channel + " » doit réellement délivrer");
        }
    }

    @Test
    void theChannelNameIsNormalized() {
        server.addPlayer();

        assertEquals("chat", service.announce("test", "  CHAT  ").channel());
    }

    @Test
    void logsAreReadThroughTheServiceWithoutAnyCapture() {
        buffer.append(1L, "WARN", "Citizens", "PNJ perdu");

        ServerLogBuffer.Snapshot snapshot = service.logs(0, 10);

        assertEquals(1, snapshot.lines().size());
        assertEquals("WARN", snapshot.lines().get(0).level());
        assertEquals(Optional.empty(), service.consoleLimitation());
    }

    @Test
    void aCaptureLimitationIsPassedThroughVerbatim() {
        ServerOpsService limited = new ServerOpsService(plugin, buffer,
                () -> Optional.of("Capture indisponible : raison exacte."));

        assertEquals(Optional.of("Capture indisponible : raison exacte."), limited.consoleLimitation());
    }
}
