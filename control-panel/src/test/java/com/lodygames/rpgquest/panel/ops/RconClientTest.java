package com.lodygames.rpgquest.panel.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Issue #95 — le protocole RCON (Source) est binaire et peu indulgent : une erreur d'ordre d'octets
 * ou de longueur ne se verrait qu'en production, au pire moment (pendant un redémarrage). Ces tests
 * confrontent donc le client à un <strong>vrai serveur RCON minimal</strong> ouvert dans la JVM de
 * test, plutôt qu'à un double qui validerait notre propre interprétation du protocole.
 */
class RconClientTest {

    private static final int TYPE_AUTH = 3;
    private static final int TYPE_AUTH_RESPONSE = 2;
    private static final int TYPE_COMMAND = 2;
    private static final int TYPE_RESPONSE = 0;
    private static final String PASSWORD = "mot-de-passe-de-test";

    private FakeRconServer server;

    @AfterEach
    void tearDown() throws Exception {
        if (server != null) {
            server.close();
            server = null;
        }
    }

    /** Serveur RCON minimal : authentifie, puis répond à chaque commande reçue. */
    private static final class FakeRconServer implements AutoCloseable {
        private final ServerSocket socket;
        private final Thread thread;
        final List<String> received = new CopyOnWriteArrayList<>();
        volatile boolean rejectAuth;
        volatile boolean closeAfterCommand;
        volatile String response = "There are 0 of a max of 20 players online: ";

        FakeRconServer(boolean rejectAuth) throws IOException {
            this.rejectAuth = rejectAuth;
            this.socket = new ServerSocket(0);
            this.thread = new Thread(this::serve, "fake-rcon");
            this.thread.setDaemon(true);
            this.thread.start();
        }

        int port() {
            return socket.getLocalPort();
        }

        private void serve() {
            while (!socket.isClosed()) {
                try (Socket client = socket.accept()) {
                    DataInputStream in = new DataInputStream(client.getInputStream());
                    OutputStream out = client.getOutputStream();
                    while (!client.isClosed()) {
                        int length = Integer.reverseBytes(in.readInt());
                        byte[] rest = new byte[length];
                        in.readFully(rest);
                        ByteBuffer buffer = ByteBuffer.wrap(rest).order(ByteOrder.LITTLE_ENDIAN);
                        int id = buffer.getInt();
                        int type = buffer.getInt();
                        byte[] body = new byte[Math.max(0, length - 10)];
                        buffer.get(body);
                        String text = new String(body, StandardCharsets.UTF_8);
                        if (type == TYPE_AUTH) {
                            // Le protocole signale un refus par un identifiant de réponse à -1.
                            send(out, rejectAuth ? -1 : id, TYPE_AUTH_RESPONSE, "");
                        } else if (type == TYPE_COMMAND) {
                            received.add(text);
                            if (closeAfterCommand) {
                                return; // comme un vrai « stop » : la connexion tombe
                            }
                            send(out, id, TYPE_RESPONSE, response);
                        }
                    }
                } catch (IOException e) {
                    if (socket.isClosed()) {
                        return;
                    }
                }
            }
        }

        private void send(OutputStream out, int id, int type, String body) throws IOException {
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            ByteBuffer buffer = ByteBuffer.allocate(14 + payload.length).order(ByteOrder.LITTLE_ENDIAN);
            buffer.putInt(10 + payload.length);
            buffer.putInt(id);
            buffer.putInt(type);
            buffer.put(payload);
            buffer.put((byte) 0);
            buffer.put((byte) 0);
            out.write(buffer.array());
            out.flush();
        }

        @Override
        public void close() throws IOException {
            socket.close();
            thread.interrupt();
        }
    }

    @Test
    void aCommandIsSentAndItsAnswerIsRead() throws Exception {
        server = new FakeRconServer(false);
        server.response = "There are 2 of a max of 20 players online: a, b";

        try (RconClient client = RconClient.connect("127.0.0.1", server.port(), PASSWORD,
                Duration.ofSeconds(5))) {
            String answer = client.execute(RconCommand.LIST);

            assertEquals("There are 2 of a max of 20 players online: a, b", answer);
        }
        assertEquals(List.of("list"), server.received);
    }

    @Test
    void everyWhitelistedCommandTravelsWithItsExactWireName() throws Exception {
        server = new FakeRconServer(false);

        try (RconClient client = RconClient.connect("127.0.0.1", server.port(), PASSWORD,
                Duration.ofSeconds(5))) {
            for (RconCommand command : RconCommand.values()) {
                client.execute(command);
            }
        }
        // Les noms attendus par Minecraft, pas des approximations.
        assertEquals(List.of("list", "save-all", "stop"), server.received);
    }

    @Test
    void aRefusedPasswordIsAClearFailureAndNeverEchoesTheSecret() throws Exception {
        server = new FakeRconServer(true);

        RconClient.RconException thrown = assertThrows(RconClient.RconException.class,
                () -> RconClient.connect("127.0.0.1", server.port(), PASSWORD, Duration.ofSeconds(5)));

        assertTrue(thrown.getMessage().contains("refusée"), thrown.getMessage());
        assertFalse(thrown.getMessage().contains(PASSWORD),
                "un message d'erreur ne doit jamais contenir le mot de passe");
    }

    @Test
    void aClosedPortFailsWithoutHangingForever() {
        RconClient.RconException thrown = assertThrows(RconClient.RconException.class,
                // Port réservé/fermé : la connexion doit échouer vite et proprement.
                () -> RconClient.connect("127.0.0.1", 1, PASSWORD, Duration.ofSeconds(2)));

        assertTrue(thrown.getMessage().contains("Connexion RCON impossible"), thrown.getMessage());
    }

    @Test
    void aConnectionDroppedByAStopIsReportedWithoutThrowingOnTheHappyPath() throws Exception {
        server = new FakeRconServer(false);
        server.closeAfterCommand = true;

        try (RconClient client = RconClient.connect("127.0.0.1", server.port(), PASSWORD,
                Duration.ofSeconds(5))) {
            // Un vrai serveur coupe souvent la connexion avant de répondre au « stop » : c'est
            // normal, et RestartService le traite comme tel.
            RconClient.RconException thrown = assertThrows(RconClient.RconException.class,
                    () -> client.execute(RconCommand.STOP));
            assertTrue(thrown.getMessage().contains("STOP"), thrown.getMessage());
        }
        assertEquals(List.of("stop"), server.received);
    }

    @Test
    void closingTwiceIsHarmless() throws Exception {
        server = new FakeRconServer(false);

        RconClient client = RconClient.connect("127.0.0.1", server.port(), PASSWORD, Duration.ofSeconds(5));
        client.close();
        client.close();
    }

    @Test
    void theCommandSetIsClosedByConstruction() {
        // Il n'existe aucun chemin permettant d'émettre une commande arbitraire : c'est le type
        // qui le garantit, pas un commentaire. Ce test fige cette propriété.
        assertEquals(3, RconCommand.values().length);
        assertEquals("list", RconCommand.LIST.wire());
        assertEquals("save-all", RconCommand.SAVE_ALL.wire());
        assertEquals("stop", RconCommand.STOP.wire());
    }
}
