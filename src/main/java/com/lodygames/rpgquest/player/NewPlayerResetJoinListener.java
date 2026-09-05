package com.lodygames.rpgquest.player;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.database.PlayerVariableRepository;
import com.lodygames.rpgquest.item.YamlCustomItemRegistry;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Deuxième moitié, différée, du reset admin « nouveau joueur » ({@link PlayerResetService}) : quand
 * un joueur qui était <strong>hors ligne</strong> au moment du reset se reconnecte, on nettoie son
 * inventaire des objets personnalisés RPGQuest et on efface le marqueur
 * {@link PlayerResetService#PENDING_INVENTORY_KEY}.
 *
 * <p>Priorité {@link EventPriority#LOWEST} : ce nettoyage passe <strong>avant</strong>
 * {@code StarterKitListener} (priorité {@code NORMAL}), qui redistribue ensuite la Rune de rappel
 * de départ. L'ordre est déterministe : cet écouteur est dispatché en premier, sa lecture asynchrone
 * du marqueur est soumise en premier au thread unique de la base (FIFO), et sa tâche de nettoyage
 * est planifiée en premier sur le thread principal (FIFO) — le kit de départ n'est donc jamais
 * retiré par ce nettoyage.</p>
 */
public final class NewPlayerResetJoinListener implements Listener {

    private final RPGQuestPlugin plugin;
    private final PlayerVariableRepository variableRepository;
    private final YamlCustomItemRegistry customItemRegistry;

    public NewPlayerResetJoinListener(RPGQuestPlugin plugin, PlayerVariableRepository variableRepository,
                                       YamlCustomItemRegistry customItemRegistry) {
        this.plugin = plugin;
        this.variableRepository = variableRepository;
        this.customItemRegistry = customItemRegistry;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        variableRepository.get(player.getUniqueId(), PlayerResetService.PENDING_INVENTORY_KEY).thenAccept(pending -> {
            if (pending.isEmpty() || pending.get().isBlank()) {
                return;
            }
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) {
                    return;
                }
                int removed = PlayerResetService.removeRpgItems(player, customItemRegistry);
                variableRepository.set(player.getUniqueId(), PlayerResetService.PENDING_INVENTORY_KEY, "")
                        .exceptionally(error -> {
                            plugin.getSLF4JLogger().error(
                                    "Impossible d'effacer le marqueur de nettoyage d'inventaire différé pour {}",
                                    player.getUniqueId(), error);
                            return null;
                        });
                plugin.getSLF4JLogger().info(
                        "[player resetnew] Inventaire RPGQuest de {} nettoyé à la reconnexion ({} objet(s) retiré(s)).",
                        player.getName(), removed);
            });
        }).exceptionally(error -> {
            plugin.getSLF4JLogger().error("Impossible de vérifier le marqueur de reset différé pour {}",
                    player.getUniqueId(), error);
            return null;
        });
    }
}
