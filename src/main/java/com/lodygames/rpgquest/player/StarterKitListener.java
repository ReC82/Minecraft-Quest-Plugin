package com.lodygames.rpgquest.player;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.database.PlayerVariableRepository;
import com.lodygames.rpgquest.item.RpgItemKeys;
import com.lodygames.rpgquest.item.YamlCustomItemRegistry;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Remet à chaque joueur, une seule fois, la Rune de rappel de départ (mission « boucle joueur » :
 * tout nouveau joueur doit disposer dès le début d'un moyen de secours pour revenir du Wild au
 * Hub). Marqueur persistant {@link #GRANTED_VARIABLE} dans {@code player_variables} : une fois posé,
 * la Rune n'est plus jamais redonnée automatiquement — s'il la perd (impossible en pratique, elle
 * est soulbound), le Guide la redonne gratuitement (voir {@code dialogues/guide.yml}).
 *
 * <p>Garde-fou supplémentaire : si le joueur possède déjà une Rune (kit d'un autre chemin, don
 * admin…), aucun exemplaire n'est ajouté — jamais de duplication.</p>
 */
public final class StarterKitListener implements Listener {

    static final String GRANTED_VARIABLE = "RUNE_RAPPEL_GRANTED";

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final RPGQuestPlugin plugin;
    private final PlayerVariableRepository variableRepository;
    private final YamlCustomItemRegistry customItemRegistry;

    public StarterKitListener(RPGQuestPlugin plugin, PlayerVariableRepository variableRepository,
                               YamlCustomItemRegistry customItemRegistry) {
        this.plugin = plugin;
        this.variableRepository = variableRepository;
        this.customItemRegistry = customItemRegistry;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        variableRepository.get(playerId, GRANTED_VARIABLE).thenAccept(existing -> {
            if (existing.isPresent() && !existing.get().isBlank()) {
                return;
            }
            plugin.getServer().getScheduler().runTask(plugin, () -> grantIfNeeded(player));
        }).exceptionally(error -> {
            plugin.getSLF4JLogger().error("Impossible de vérifier le kit de départ de {}", playerId, error);
            return null;
        });
    }

    private void grantIfNeeded(Player player) {
        if (!player.isOnline()) {
            return;
        }
        UUID playerId = player.getUniqueId();
        if (!hasRune(player)) {
            customItemRegistry.create(RpgItemKeys.RUNE_RAPPEL, 1)
                    .ifPresent(stack -> player.getInventory().addItem(stack));
            player.sendMessage(MM.deserialize(
                    "<aqua>Tu reçois une Rune de rappel.</aqua> <gray>Clic droit dans le Wild pour revenir au Hub.</gray>"));
        }
        variableRepository.set(playerId, GRANTED_VARIABLE, "true").exceptionally(error -> {
            plugin.getSLF4JLogger().error("Impossible de marquer le kit de départ de {} comme distribué", playerId, error);
            return null;
        });
    }

    private boolean hasRune(Player player) {
        return Arrays.stream(player.getInventory().getContents())
                .filter(Objects::nonNull)
                .anyMatch(this::isRune);
    }

    private boolean isRune(ItemStack stack) {
        return customItemRegistry.identify(stack).map(RpgItemKeys.RUNE_RAPPEL::equals).orElse(false);
    }
}
