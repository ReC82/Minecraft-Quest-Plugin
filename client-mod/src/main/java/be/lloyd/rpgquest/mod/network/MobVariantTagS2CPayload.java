package be.lloyd.rpgquest.mod.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Serveur → client uniquement, canal {@code rpgquest:cosmetic} — purement
 * cosmétique (mission point 6/7) : identifie une entité déjà visible par le
 * client (id réseau vanilla, déjà synchronisé par le protocole standard)
 * comme portant une variante de mob spéciale, pour affichage seulement.
 * N'accorde jamais rien, ne change jamais l'état du jeu côté client.
 */
public record MobVariantTagS2CPayload(int entityNetworkId, String variantDisplayName) implements CustomPayload {

    public static final CustomPayload.Id<MobVariantTagS2CPayload> ID =
            new CustomPayload.Id<>(Identifier.of("rpgquest", "mob_variant_tag"));

    public static final PacketCodec<RegistryByteBuf, MobVariantTagS2CPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.INTEGER, MobVariantTagS2CPayload::entityNetworkId,
            PacketCodecs.STRING, MobVariantTagS2CPayload::variantDisplayName,
            MobVariantTagS2CPayload::new);

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
