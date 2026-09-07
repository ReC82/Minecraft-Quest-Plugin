package com.lodygames.rpgquest.web.agent;

import com.lodygames.rpgquest.RPGQuestPlugin;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

/**
 * Implémentation réelle de {@link PlayerDirectory} adossée à l'API publique Paper.
 *
 * <p>Un UUID canonique est résolu directement ({@code getOfflinePlayer(UUID)}, non bloquant). Un
 * nom est d'abord cherché parmi les joueurs en ligne ; sinon la résolution hors ligne
 * ({@code getOfflinePlayer(String)}, potentiellement bloquante) est faite sur un thread asynchrone
 * Bukkit — <strong>jamais</strong> sur le thread principal.</p>
 */
public final class BukkitPlayerDirectory implements PlayerDirectory {

    private final RPGQuestPlugin plugin;

    public BukkitPlayerDirectory(RPGQuestPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public CompletableFuture<Optional<ResolvedPlayer>> resolve(String nameOrUuid) {
        if (nameOrUuid == null || nameOrUuid.isBlank()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        String raw = nameOrUuid.trim();

        UUID uuid = tryParseUuid(raw);
        if (uuid != null) {
            OfflinePlayer offline = plugin.getServer().getOfflinePlayer(uuid);
            String name = offline.getName() != null ? offline.getName() : raw;
            boolean known = offline.isOnline() || offline.hasPlayedBefore();
            return CompletableFuture.completedFuture(
                    known ? Optional.of(new ResolvedPlayer(uuid, name)) : Optional.empty());
        }

        Player online = plugin.getServer().getPlayerExact(raw);
        if (online != null) {
            return CompletableFuture.completedFuture(Optional.of(new ResolvedPlayer(online.getUniqueId(), online.getName())));
        }

        CompletableFuture<Optional<ResolvedPlayer>> future = new CompletableFuture<>();
        try {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    @SuppressWarnings("deprecation")
                    OfflinePlayer offline = plugin.getServer().getOfflinePlayer(raw);
                    if (offline.getUniqueId() == null || (!offline.isOnline() && !offline.hasPlayedBefore())) {
                        future.complete(Optional.empty());
                        return;
                    }
                    String name = offline.getName() != null ? offline.getName() : raw;
                    future.complete(Optional.of(new ResolvedPlayer(offline.getUniqueId(), name)));
                } catch (RuntimeException e) {
                    future.completeExceptionally(e);
                }
            });
        } catch (RuntimeException e) {
            // Plugin en cours d'arrêt : le scheduler refuse la tâche. Échec propre.
            future.completeExceptionally(e);
        }
        return future;
    }

    private static UUID tryParseUuid(String raw) {
        if (raw.length() != 36) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
