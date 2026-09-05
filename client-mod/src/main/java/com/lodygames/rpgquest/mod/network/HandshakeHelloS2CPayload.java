package com.lodygames.rpgquest.mod.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Serveur → client, envoyé à la connexion (mission étape 23, point 4 :
 * vérification de compatibilité plugin ↔ mod). Ne contient aucune
 * information de jeu, uniquement le numéro de protocole attendu par le
 * serveur — voir docs/CLIENT_MOD.md pour le protocole complet.
 */
public record HandshakeHelloS2CPayload(int magic, byte serverProtocolVersion) implements CustomPayload {

    public static final CustomPayload.Id<HandshakeHelloS2CPayload> ID =
            new CustomPayload.Id<>(Identifier.of("rpgquest", "handshake_hello"));

    public static final PacketCodec<RegistryByteBuf, HandshakeHelloS2CPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.INTEGER, HandshakeHelloS2CPayload::magic,
            PacketCodecs.BYTE, HandshakeHelloS2CPayload::serverProtocolVersion,
            HandshakeHelloS2CPayload::new);

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
