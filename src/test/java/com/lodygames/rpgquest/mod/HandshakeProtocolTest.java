package com.lodygames.rpgquest.mod;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Vérifie l'encodage/décodage du protocole (mission étape 23, point 4),
 * y compris la compatibilité de format avec le décodage {@code
 * PacketCodecs.STRING} de Minecraft (VarInt de longueur + UTF-8) côté mod,
 * et la robustesse face à un paquet réseau invalide (mission, test dédié).
 */
class HandshakeProtocolTest {

    @Test
    void encodeHelloProducesExactlyFiveBytesWithMagicAndVersion() {
        byte[] encoded = HandshakeProtocol.encodeHello();

        assertEquals(5, encoded.length);
        Optional<HandshakeProtocol.HelloResponse> decoded = HandshakeProtocol.decodeHelloResponse(encoded);
        assertTrue(decoded.isPresent());
        assertEquals(HandshakeProtocol.PROTOCOL_MAGIC, decoded.get().magic());
        assertEquals(HandshakeProtocol.SERVER_PROTOCOL_VERSION, decoded.get().clientProtocolVersion());
    }

    @Test
    void decodeHelloResponseRejectsTooShortPackets() {
        for (int length = 0; length < 5; length++) {
            assertTrue(HandshakeProtocol.decodeHelloResponse(new byte[length]).isEmpty(),
                    "un paquet de " + length + " octet(s) ne doit jamais être décodé comme valide");
        }
    }

    @Test
    void decodeHelloResponseNeverThrowsOnArbitraryBytes() {
        // Paquet réseau invalide (mission, test dédié) : jamais une exception, quel que soit le
        // contenu — la validité "magique" est vérifiée par l'appelant (ModCompatService), pas ici.
        byte[] garbage = {0x00, (byte) 0xFF, 0x12, 0x34, 0x56, 0x78, (byte) 0x9A};
        assertDoesNotThrow(() -> HandshakeProtocol.decodeHelloResponse(garbage));
        assertTrue(HandshakeProtocol.decodeHelloResponse(garbage).isPresent(), "5+ octets valides restent décodables, même si le contenu est arbitraire");
    }

    @Test
    void encodeMobVariantTagUsesMinecraftVarIntPrefixedUtf8ForTheString() throws IOException {
        byte[] encoded = HandshakeProtocol.encodeMobVariantTag(4242, "Golden Creeper");

        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded))) {
            assertEquals(4242, in.readInt());
            int length = readMinecraftVarInt(in);
            byte[] nameBytes = in.readNBytes(length);
            assertEquals("Golden Creeper", new String(nameBytes, StandardCharsets.UTF_8));
            assertEquals(-1, in.read(), "aucun octet superflu après la chaîne");
        }
    }

    @Test
    void encodeMobVariantTagRoundTripsUnicodeNames() throws IOException {
        String name = "Créature dorée 🐷";
        byte[] encoded = HandshakeProtocol.encodeMobVariantTag(1, name);

        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded))) {
            in.readInt();
            int length = readMinecraftVarInt(in);
            byte[] nameBytes = in.readNBytes(length);
            assertEquals(name, new String(nameBytes, StandardCharsets.UTF_8));
        }
    }

    /** Décodeur VarInt indépendant, réplique du format protocole Minecraft (voir docs/CLIENT_MOD.md). */
    private int readMinecraftVarInt(DataInputStream in) throws IOException {
        int value = 0;
        int position = 0;
        while (true) {
            int currentByte = in.readUnsignedByte();
            value |= (currentByte & 0x7F) << position;
            if ((currentByte & 0x80) == 0) {
                break;
            }
            position += 7;
            if (position >= 32) {
                throw new IOException("VarInt trop long");
            }
        }
        return value;
    }
}
