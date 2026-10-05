package com.lodygames.rpgquest.panel.ops;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Client RCON minimal (protocole Source), utilisé par {@link RestartService} pour émettre les
 * <strong>trois</strong> commandes de {@link RconCommand} — et rien d'autre.
 *
 * <p><strong>Pourquoi RCON et pas l'agent.</strong> Un plugin ne peut pas garantir son propre
 * redémarrage : au moment où le serveur s'arrête, l'agent s'arrête avec lui. Le mécanisme réellement
 * disponible est celui déjà éprouvé par {@code scripts/verygames-restart.sh} — {@code stop} par RCON
 * depuis la machine AWS, puis relance automatique par l'hébergeur. On réutilise donc ce mécanisme,
 * mais en Java dans le panel plutôt qu'en appelant un script shell : le service PlugAdmin tourne
 * sous {@code systemd} avec {@code ProtectHome=true} et n'a accès ni au {@code $HOME} de l'opérateur
 * ni à son fichier d'identifiants.</p>
 *
 * <p><strong>Secrets.</strong> Le mot de passe n'est ni journalisé, ni renvoyé, ni inclus dans un
 * message d'erreur. Un échec d'authentification est signalé comme tel, sans écho de la valeur.</p>
 */
public final class RconClient implements AutoCloseable {

    private static final int TYPE_AUTH = 3;
    private static final int TYPE_AUTH_RESPONSE = 2;
    private static final int TYPE_COMMAND = 2;
    /** Borne du corps d'une réponse : une réponse RCON n'est pas un transport de données. */
    private static final int MAX_BODY_BYTES = 8 * 1024;

    private final Socket socket;
    private final DataInputStream in;
    private final OutputStream out;
    private int requestId = 1;

    private RconClient(Socket socket) throws IOException {
        this.socket = socket;
        this.in = new DataInputStream(socket.getInputStream());
        this.out = socket.getOutputStream();
    }

    /**
     * Ouvre une connexion et s'authentifie.
     *
     * @throws RconException connexion impossible, délai dépassé, ou authentification refusée
     */
    public static RconClient connect(String host, int port, String password, Duration timeout) throws RconException {
        int millis = (int) Math.max(1_000, Math.min(60_000, timeout.toMillis()));
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), millis);
            socket.setSoTimeout(millis);
            RconClient client = new RconClient(socket);
            client.authenticate(password);
            return client;
        } catch (RconException e) {
            closeQuietly(socket);
            throw e;
        } catch (IOException e) {
            closeQuietly(socket);
            throw new RconException("Connexion RCON impossible (" + e.getClass().getSimpleName() + ").", e);
        }
    }

    private void authenticate(String password) throws RconException {
        int id = requestId++;
        try {
            send(id, TYPE_AUTH, password == null ? "" : password);
            Packet response = receive();
            // Certains serveurs envoient d'abord une réponse vide de type 0 : on lit la suivante.
            if (response.type() != TYPE_AUTH_RESPONSE) {
                response = receive();
            }
            if (response.id() == -1) {
                throw new RconException("Authentification RCON refusée (mot de passe incorrect).", null);
            }
        } catch (IOException e) {
            throw new RconException("Échec de l'authentification RCON (" + e.getClass().getSimpleName() + ").", e);
        }
    }

    /** Exécute une commande de la liste blanche et renvoie la réponse textuelle du serveur. */
    public String execute(RconCommand command) throws RconException {
        int id = requestId++;
        try {
            send(id, TYPE_COMMAND, command.wire());
            Packet response = receive();
            return response.body();
        } catch (IOException e) {
            throw new RconException("Commande RCON « " + command.name() + " » sans réponse ("
                    + e.getClass().getSimpleName() + ").", e);
        }
    }

    private void send(int id, int type, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(14 + payload.length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(10 + payload.length); // taille du reste du paquet
        buffer.putInt(id);
        buffer.putInt(type);
        buffer.put(payload);
        buffer.put((byte) 0);
        buffer.put((byte) 0);
        out.write(buffer.array());
        out.flush();
    }

    private record Packet(int id, int type, String body) {
    }

    private Packet receive() throws IOException, RconException {
        int length = Integer.reverseBytes(in.readInt());
        if (length < 10 || length > MAX_BODY_BYTES) {
            throw new RconException("Réponse RCON invalide (taille annoncée : " + length + ").", null);
        }
        byte[] rest = new byte[length];
        in.readFully(rest);
        ByteBuffer buffer = ByteBuffer.wrap(rest).order(ByteOrder.LITTLE_ENDIAN);
        int id = buffer.getInt();
        int type = buffer.getInt();
        int bodyLength = Math.max(0, length - 10);
        byte[] body = new byte[bodyLength];
        buffer.get(body, 0, bodyLength);
        return new Packet(id, type, new String(body, StandardCharsets.UTF_8));
    }

    @Override
    public void close() {
        closeQuietly(socket);
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // rien à faire : on fermait
        }
    }

    /** Échec RCON exprimé comme un message lisible — jamais un secret, jamais une trace brute. */
    public static final class RconException extends Exception {
        private static final long serialVersionUID = 1L;

        public RconException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
