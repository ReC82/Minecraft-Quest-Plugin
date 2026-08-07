package be.lloyd.rpgquest.mod.network;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

/**
 * Enregistre le protocole de compatibilité et le canal cosmétique (mission
 * étape 23, points 4/6/7). Toute la logique critique (progression, drops,
 * économie, droits, achats) reste côté serveur ; ce mod ne fait
 * qu'annoncer sa présence/version et afficher ce que le serveur choisit de
 * lui montrer, jamais l'inverse. Voir docs/CLIENT_MOD.md pour le protocole
 * complet.
 */
public final class ModNetworking {

    public static final int PROTOCOL_MAGIC = 0x52504751; // "RPGQ"
    public static final byte CLIENT_PROTOCOL_VERSION = 1;
    public static final String MOD_VERSION = "0.1.0-prototype";

    private static volatile ConnectionStatus status = ConnectionStatus.NOT_CONNECTED;

    private ModNetworking() {
    }

    public static ConnectionStatus status() {
        return status;
    }

    public static void register() {
        PayloadTypeRegistry.playS2C().register(HandshakeHelloS2CPayload.ID, HandshakeHelloS2CPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(HandshakeHelloC2SPayload.ID, HandshakeHelloC2SPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(MobVariantTagS2CPayload.ID, MobVariantTagS2CPayload.CODEC);

        ClientPlayNetworking.registerGlobalReceiver(HandshakeHelloS2CPayload.ID, (payload, context) -> {
            if (payload.magic() != PROTOCOL_MAGIC) {
                // Paquet malformé/inattendu (mission, test "paquet réseau invalide") : ignoré sans
                // jamais lever d'exception, le statut reste NOT_CONNECTED.
                return;
            }
            status = payload.serverProtocolVersion() == CLIENT_PROTOCOL_VERSION
                    ? ConnectionStatus.COMPATIBLE
                    : ConnectionStatus.WRONG_VERSION;
            ClientPlayNetworking.send(new HandshakeHelloC2SPayload(PROTOCOL_MAGIC, CLIENT_PROTOCOL_VERSION));
        });

        ClientPlayNetworking.registerGlobalReceiver(MobVariantTagS2CPayload.ID, (payload, context) -> {
            MinecraftClient client = context.client();
            client.execute(() -> {
                if (client.player != null) {
                    client.player.sendMessage(Text.literal("⚡ Variante détectée : " + payload.variantDisplayName()), true);
                }
            });
        });

        // Reconnexion (mission, test "reconnexion") : le statut ne reste jamais figé sur un ancien
        // état après une déconnexion — le prochain hello serveur le remettra à jour normalement.
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> status = ConnectionStatus.NOT_CONNECTED);
    }
}
