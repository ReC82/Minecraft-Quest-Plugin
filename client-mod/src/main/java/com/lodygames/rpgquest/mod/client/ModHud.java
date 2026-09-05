package com.lodygames.rpgquest.mod.client;

import com.lodygames.rpgquest.mod.network.ConnectionStatus;
import com.lodygames.rpgquest.mod.network.ModNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;

/**
 * Petite indication client (mission étape 23, point 5, 4e puce) : un simple
 * texte en haut à gauche affichant l'état de la détection de compatibilité
 * plugin ↔ mod. Ne montre jamais d'information de jeu déclarée par le
 * client lui-même — uniquement l'état renvoyé par le protocole.
 */
public final class ModHud {

    private static final Identifier HUD_ID = Identifier.of("rpgquest", "status");

    private ModHud() {
    }

    public static void register() {
        HudElementRegistry.addLast(HUD_ID, ModHud::render);
    }

    private static void render(DrawContext context, net.minecraft.client.render.RenderTickCounter tickCounter) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) {
            return;
        }
        ConnectionStatus status = ModNetworking.status();
        String label = switch (status) {
            case COMPATIBLE -> "RPGQuest : connecté";
            case WRONG_VERSION -> "RPGQuest : version incompatible";
            case NOT_CONNECTED -> "RPGQuest : serveur non détecté";
        };
        int color = switch (status) {
            case COMPATIBLE -> 0x55FF55;
            case WRONG_VERSION -> 0xFFAA00;
            case NOT_CONNECTED -> 0xAAAAAA;
        };
        context.drawText(client.textRenderer, label, 4, 4, color, true);
    }
}
