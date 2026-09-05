package com.lodygames.rpgquest.travel;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.item.RpgItemKeys;
import com.lodygames.rpgquest.item.YamlCustomItemRegistry;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import com.lodygames.rpgquest.travel.model.WorldPortalDefinition;

/**
 * {@link WorldPortalEntryGuard} pour l'entrée dans le Wild (mission « avertissement avant entrée
 * dans le Wild ») : quand un joueur <strong>sans Rune de rappel</strong> tente d'emprunter un
 * portail simple dont la destination est le monde d'exploration configuré, la téléportation est
 * suspendue et un avertissement compact et cliquable est envoyé dans le chat — jamais un titre
 * plein écran. Deux boutons : « Continuer » relance la téléportation, « Annuler » le laisse au Hub.
 *
 * <ul>
 *   <li>Un joueur qui possède déjà une Rune n'est jamais averti.</li>
 *   <li>Les autres portails simples (destination ≠ Wild) ne sont jamais concernés.</li>
 *   <li>Anti-spam : l'avertissement n'est pas renvoyé plus d'une fois toutes les
 *       {@value #WARN_COOLDOWN_MS} ms pour un même joueur.</li>
 * </ul>
 */
public final class WildEntryWarningService implements WorldPortalEntryGuard {

    static final long WARN_COOLDOWN_MS = 4_000L;
    private static final long BYPASS_TTL_TICKS = 200L; // 10 s pour franchir le portail après « Continuer ».
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final RPGQuestPlugin plugin;
    private final YamlCustomItemRegistry customItemRegistry;
    private final Supplier<String> wildWorld;
    private final PortalTeleporter teleporter;

    private final Map<UUID, Long> lastWarnedAt = new ConcurrentHashMap<>();
    private final java.util.Set<UUID> bypass = ConcurrentHashMap.newKeySet();

    public WildEntryWarningService(RPGQuestPlugin plugin, YamlCustomItemRegistry customItemRegistry,
                                    Supplier<String> wildWorld, PortalTeleporter teleporter) {
        this.plugin = plugin;
        this.customItemRegistry = customItemRegistry;
        this.wildWorld = wildWorld;
        this.teleporter = teleporter;
    }

    @Override
    public boolean allowEntry(Player player, WorldPortalDefinition portal) {
        if (!portal.destinationWorld().equals(wildWorld.get())) {
            return true; // pas un portail vers le Wild : jamais concerné.
        }
        if (hasReturnMeans(player)) {
            return true; // possède une Rune : aucun avertissement.
        }
        UUID playerId = player.getUniqueId();
        if (bypass.remove(playerId)) {
            return true; // « Continuer » explicite tout juste cliqué.
        }
        long now = System.currentTimeMillis();
        Long last = lastWarnedAt.get(playerId);
        if (last == null || now - last > WARN_COOLDOWN_MS) {
            lastWarnedAt.put(playerId, now);
            sendWarning(player, portal);
        }
        return false;
    }

    private boolean hasReturnMeans(Player player) {
        return Arrays.stream(player.getInventory().getContents())
                .filter(Objects::nonNull)
                .anyMatch(this::isRune);
    }

    private boolean isRune(ItemStack stack) {
        return customItemRegistry.identify(stack).map(RpgItemKeys.RUNE_RAPPEL::equals).orElse(false);
    }

    private void sendWarning(Player player, WorldPortalDefinition portal) {
        UUID playerId = player.getUniqueId();
        Component message = MM.deserialize(
                "<gold>⚠</gold> <white>Vous partez sans moyen de rappel. Pour revenir au Hub, "
                        + "vous devrez trouver une Pierre de voyage.</white>")
                .append(Component.newline())
                .append(MM.deserialize("<green>[Continuer]</green>")
                        .clickEvent(ClickEvent.callback(audience -> onContinue(playerId, portal))))
                .append(Component.text("  "))
                .append(MM.deserialize("<red>[Annuler]</red>")
                        .clickEvent(ClickEvent.callback(audience -> onCancel(playerId))));
        player.sendMessage(message);
    }

    /** « Continuer » : accorde un laissez-passer bref puis relance la téléportation du portail. */
    void onContinue(UUID playerId, WorldPortalDefinition portal) {
        Player online = plugin.getServer().getPlayer(playerId);
        if (online == null || !online.isOnline()) {
            return;
        }
        bypass.add(playerId);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> bypass.remove(playerId), BYPASS_TTL_TICKS);
        teleporter.teleportNow(online, portal);
    }

    /** « Annuler » : le joueur reste où il est (au Hub), simple accusé de réception. */
    void onCancel(UUID playerId) {
        Player online = plugin.getServer().getPlayer(playerId);
        if (online != null && online.isOnline()) {
            online.sendMessage(MM.deserialize("<gray>Tu restes au Hub.</gray>"));
        }
    }
}
