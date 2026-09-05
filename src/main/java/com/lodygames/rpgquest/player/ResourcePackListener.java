package com.lodygames.rpgquest.player;

import com.lodygames.rpgquest.config.ConfigService;
import com.lodygames.rpgquest.config.ResourcePackConfig;
import java.util.UUID;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.slf4j.Logger;

/**
 * Envoie le resource pack optionnel du serveur à la connexion et réagit à son
 * acceptation/refus/échec ({@code config.yml} → {@code resource-pack}). Le
 * plugin fonctionne normalement (apparence vanilla) si le resource pack est
 * désactivé, absent ou refusé — {@code required} ne fait qu'afficher un
 * message plus insistant, il ne déconnecte jamais le joueur automatiquement
 * (décision volontairement non destructive).
 */
public final class ResourcePackListener implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final ConfigService configService;
    private final Logger logger;

    public ResourcePackListener(ConfigService configService, Logger logger) {
        this.configService = configService;
        this.logger = logger;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        ResourcePackConfig config = configService.current().resourcePack();
        if (!config.enabled()) {
            return;
        }

        Player player = event.getPlayer();
        UUID packId = UUID.nameUUIDFromBytes(config.url().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        try {
            player.setResourcePack(packId, config.url(), hexToBytes(config.sha1()),
                    MM.deserialize("<gray>RPGQuest propose un resource pack optionnel (textures d'objets personnalisés).</gray>"),
                    config.required());
        } catch (RuntimeException e) {
            // Ne doit jamais empêcher la connexion : un envoi raté (client trop ancien, etc.) est
            // simplement journalisé, le joueur continue avec l'apparence vanilla.
            logger.warn("Impossible d'envoyer le resource pack à {}.", player.getName(), e);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onStatus(PlayerResourcePackStatusEvent event) {
        if (!configService.current().resourcePack().enabled()) {
            return;
        }
        Player player = event.getPlayer();
        switch (event.getStatus()) {
            case SUCCESSFULLY_LOADED -> {
                if (configService.current().debug()) {
                    logger.info("Resource pack chargé avec succès par {}.", player.getName());
                }
            }
            case DECLINED -> warnIfRequired(player, "<yellow>Vous avez refusé le resource pack de RPGQuest ; certains objets afficheront leur apparence vanilla.</yellow>");
            case FAILED_DOWNLOAD, INVALID_URL, FAILED_RELOAD ->
                    warnIfRequired(player, "<red>Le téléchargement du resource pack de RPGQuest a échoué ; certains objets afficheront leur apparence vanilla.</red>");
            case ACCEPTED, DOWNLOADED, DISCARDED -> {
                // États intermédiaires ou sans action requise côté plugin.
            }
        }
    }

    private void warnIfRequired(Player player, String miniMessage) {
        if (configService.current().resourcePack().required()) {
            player.sendMessage(MM.deserialize(miniMessage));
        }
    }

    /** Package-visible pour être testable directement (pure, sans Bukkit). */
    byte[] hexToBytes(String hex) {
        if (hex == null || hex.isEmpty()) {
            return new byte[0];
        }
        int length = hex.length();
        byte[] bytes = new byte[length / 2];
        for (int i = 0; i < length; i += 2) {
            bytes[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4) + Character.digit(hex.charAt(i + 1), 16));
        }
        return bytes;
    }
}
