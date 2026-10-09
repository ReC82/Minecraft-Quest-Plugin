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
 * Deuxième moitié, différée, du reset admin ({@link PlayerResetService}) : quand un joueur qui était
 * <strong>hors ligne</strong> au moment du reset se reconnecte, on applique le nettoyage
 * d'inventaire prévu par la <strong>portée</strong> enregistrée dans le marqueur
 * {@link PlayerResetService#PENDING_INVENTORY_KEY}, puis on efface ce marqueur.
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
                // La PORTÉE est relue dans le marqueur : un reset « nouveau joueur complet » demandé
                // sur un joueur hors ligne doit vider son inventaire à la reconnexion, pas
                // seulement ses objets RPGQuest (issue #235). Une valeur illisible retombe sur la
                // portée la moins destructrice.
                PlayerResetService.ResetScope scope = PlayerResetService.ResetScope.ofMarker(pending.get());
                int removed = PlayerResetService.applyInventoryReset(player, scope, customItemRegistry);
                variableRepository.set(player.getUniqueId(), PlayerResetService.PENDING_INVENTORY_KEY, "")
                        .exceptionally(error -> {
                            plugin.getSLF4JLogger().error(
                                    "Impossible d'effacer le marqueur de nettoyage d'inventaire différé pour {}",
                                    player.getUniqueId(), error);
                            return null;
                        });
                plugin.getSLF4JLogger().info(
                        "[player reset:{}] Inventaire de {} nettoyé à la reconnexion ({} objet(s) retiré(s), {}).",
                        scope.name(), player.getName(), removed,
                        scope.wipesInventory() ? "inventaire complet vidé" : "objets RPGQuest seulement");
            });
        }).exceptionally(error -> {
            plugin.getSLF4JLogger().error("Impossible de vérifier le marqueur de reset différé pour {}",
                    player.getUniqueId(), error);
            return null;
        });
    }
}
