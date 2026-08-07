package com.lodygames.rpgquest.mod;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Encodage/décodage du protocole de compatibilité plugin ↔ mod (mission
 * étape 23, point 4), séparé de {@link ModCompatService} pour rester
 * testable en JUnit pur, sans Bukkit ni MockBukkit. Pure JDK ({@code
 * java.io}), aucune dépendance vers l'API réseau de Minecraft (le plugin
 * Paper n'a pas accès à {@code PacketByteBuf}) — voir docs/CLIENT_MOD.md
 * pour le protocole complet et la justification de chaque choix
 * d'encodage.
 *
 * <p>Le canal de handshake ({@value ModCompatService#HANDSHAKE_CHANNEL})
 * n'échange jamais qu'un entier magique et un numéro de version — jamais
 * une déclaration d'état de jeu (mission point 7). Le canal cosmétique
 * ({@value ModCompatService#COSMETIC_CHANNEL}) est à sens unique
 * (serveur → client uniquement) : ce fichier n'a donc besoin d'en
 * <em>encoder</em> que le contenu, jamais de le décoder.</p>
 */
final class HandshakeProtocol {

    static final int PROTOCOL_MAGIC = 0x52504751; // "RPGQ"
    static final byte SERVER_PROTOCOL_VERSION = 1;

    private HandshakeProtocol() {
    }

    static byte[] encodeHello() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(PROTOCOL_MAGIC);
            out.writeByte(SERVER_PROTOCOL_VERSION);
        } catch (IOException e) {
            throw new UncheckedIOException(e); // ByteArrayOutputStream n'échoue jamais en pratique
        }
        return bytes.toByteArray();
    }

    record HelloResponse(int magic, byte clientProtocolVersion) {
    }

    /** {@link Optional#empty()} sur tout paquet trop court ou illisible (mission, test "paquet réseau invalide"). */
    static Optional<HelloResponse> decodeHelloResponse(byte[] message) {
        if (message.length < 5) {
            return Optional.empty();
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(message))) {
            int magic = in.readInt();
            byte version = in.readByte();
            return Optional.of(new HelloResponse(magic, version));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /**
     * Encode un entier réseau d'entité (int, 4 octets big-endian — même format que
     * {@code PacketCodecs.INTEGER} côté mod) suivi d'une chaîne au format protocole Minecraft
     * (VarInt de longueur puis UTF-8 — même format que {@code PacketCodecs.STRING}, voir
     * {@link #writeMinecraftString}).
     */
    static byte[] encodeMobVariantTag(int entityNetworkId, String variantDisplayName) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(entityNetworkId);
            writeMinecraftString(out, variantDisplayName);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    /** VarInt(longueur UTF-8) + octets UTF-8 — format {@code PacketByteBuf#writeString} de Minecraft. */
    static void writeMinecraftString(DataOutputStream out, String value) throws IOException {
        byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, utf8.length);
        out.write(utf8);
    }

    /** VarInt protocole Minecraft : 7 bits utiles par octet, bit de poids fort = "continue". */
    static void writeVarInt(DataOutputStream out, int value) throws IOException {
        while (true) {
            if ((value & ~0x7F) == 0) {
                out.writeByte(value);
                return;
            }
            out.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
    }
}
