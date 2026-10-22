package com.lodygames.rpgquest.hub;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;

/**
 * Message discret d'un refus du Hub (issue #675) : un au plus toutes les {@value #COOLDOWN_MILLIS} ms
 * par joueur, quel que soit le refus — cliquer en boucle sur un coffre ou un établi ne spamme pas.
 */
public final class HubRefusalMessages {

    static final long COOLDOWN_MILLIS = 2_000L;

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final LongSupplier clock;
    private final Map<UUID, Long> lastMessageAt = new ConcurrentHashMap<>();

    public HubRefusalMessages() {
        this(System::currentTimeMillis);
    }

    HubRefusalMessages(LongSupplier clock) {
        this.clock = clock;
    }

    void send(Player player, String miniMessage) {
        long now = clock.getAsLong();
        Long last = lastMessageAt.get(player.getUniqueId());
        if (last == null || now - last >= COOLDOWN_MILLIS) {
            lastMessageAt.put(player.getUniqueId(), now);
            player.sendMessage(MM.deserialize(miniMessage));
        }
    }

    void forget(UUID playerId) {
        lastMessageAt.remove(playerId);
    }
}
