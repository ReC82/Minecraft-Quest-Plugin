package be.lloyd.rpgquest.mod.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Client → serveur, réponse à {@link HandshakeHelloS2CPayload}. Ne déclare
 * jamais un état de jeu (mission point 7 : le client ne peut jamais
 * s'auto-déclarer possesseur d'un objet ou avoir terminé une action) —
 * uniquement le numéro de protocole, purement informatif pour la détection
 * de compatibilité. Volontairement limité à magic+byte (pas de chaîne) :
 * évite tout risque de désaccord de format entre l'encodage VarInt+UTF-8
 * de Minecraft et le décodage manuel côté plugin Paper (qui ne dispose pas
 * de {@code PacketByteBuf}) — voir docs/CLIENT_MOD.md, "Protocole".
 */
public record HandshakeHelloC2SPayload(int magic, byte clientProtocolVersion) implements CustomPayload {

    public static final CustomPayload.Id<HandshakeHelloC2SPayload> ID =
            new CustomPayload.Id<>(Identifier.of("rpgquest", "handshake_hello"));

    public static final PacketCodec<RegistryByteBuf, HandshakeHelloC2SPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.INTEGER, HandshakeHelloC2SPayload::magic,
            PacketCodecs.BYTE, HandshakeHelloC2SPayload::clientProtocolVersion,
            HandshakeHelloC2SPayload::new);

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
