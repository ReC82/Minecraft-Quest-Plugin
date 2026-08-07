package be.lloyd.rpgquest.player;

import be.lloyd.rpgquest.RPGQuestPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class PlayerConnectionListener implements Listener {

    private final RPGQuestPlugin plugin;
    private final PlayerProfileService profileService;

    public PlayerConnectionListener(RPGQuestPlugin plugin, PlayerProfileService profileService) {
        this.plugin = plugin;
        this.profileService = profileService;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        var uuid = event.getPlayer().getUniqueId();
        var name = event.getPlayer().getName();

        profileService.loadOnJoin(uuid, name).whenComplete((profile, error) -> {
            if (error != null) {
                plugin.getServer().getScheduler().runTask(plugin, () ->
                        plugin.getSLF4JLogger().warn("Impossible de charger le profil de {}", name, error));
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        profileService.invalidate(event.getPlayer().getUniqueId());
    }
}
