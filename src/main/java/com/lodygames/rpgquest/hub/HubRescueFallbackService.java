package com.lodygames.rpgquest.hub;

import com.lodygames.rpgquest.bootstrap.PluginService;
import com.lodygames.rpgquest.config.HubConfig;
import com.lodygames.rpgquest.item.RpgItemKeys;
import com.lodygames.rpgquest.item.YamlCustomItemRegistry;
import com.lodygames.rpgquest.spawn.SpawnService;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.slf4j.Logger;

/**
 * Filet de secours graphique du Hub (issue #154, en complément de la Rune de rappel elle-même) :
 * la Rune est soulbound et remise à la première connexion, donc normalement jamais perdue — mais
 * si un joueur se retrouve malgré tout dans le Hub sans elle (cas limite, ex. inventaire plein à
 * la toute première connexion) ce service la lui redonne silencieusement dès qu'une place se
 * libère. Si l'inventaire reste plein, il propose à la place un accès de secours purement
 * graphique (inventaire vanilla, aucun mod/resource pack requis) : un seul bouton, retour
 * volontaire au spawn configuré du Hub — jamais une commande, jamais un PNJ à rejoindre.
 *
 * <p>Ne fait jamais rien hors du Hub et n'interrompt jamais un inventaire déjà ouvert par le
 * joueur : la proposition réapparaît simplement au balayage suivant tant que la situation n'est
 * pas résolue.</p>
 */
public final class HubRescueFallbackService implements PluginService {

    /** Rare par nature (la Rune est normalement toujours présente) : pas besoin d'être plus réactif. */
    private static final long SWEEP_PERIOD_TICKS = 100L;
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final Plugin plugin;
    private final Supplier<HubConfig> config;
    private final YamlCustomItemRegistry customItemRegistry;
    private final SpawnService spawnService;
    private final Logger logger;
    private BukkitTask sweepTask;

    public HubRescueFallbackService(Plugin plugin, Supplier<HubConfig> config, YamlCustomItemRegistry customItemRegistry,
                                     SpawnService spawnService, Logger logger) {
        this.plugin = plugin;
        this.config = config;
        this.customItemRegistry = customItemRegistry;
        this.spawnService = spawnService;
        this.logger = logger;
    }

    @Override
    public void start() {
        sweepTask = Bukkit.getScheduler().runTaskTimer(plugin, this::sweep, SWEEP_PERIOD_TICKS, SWEEP_PERIOD_TICKS);
    }

    @Override
    public void stop() {
        if (sweepTask != null) {
            sweepTask.cancel();
            sweepTask = null;
        }
    }

    public Listener listener() {
        return new HubRescueFallbackListener(this);
    }

    private void sweep() {
        String hub = config.get().world();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getWorld().getName().equals(hub) && !hasRune(player)) {
                restoreRuneOrOfferFallback(player);
            }
        }
    }

    private void restoreRuneOrOfferFallback(Player player) {
        ItemStack rune = customItemRegistry.create(RpgItemKeys.RUNE_RAPPEL, 1).orElse(null);
        if (rune != null) {
            Map<Integer, ItemStack> leftover = player.getInventory().addItem(rune);
            if (leftover.isEmpty()) {
                return; // restaurée silencieusement : jamais besoin du secours graphique.
            }
        }
        offerGraphicalFallback(player);
    }

    private boolean hasRune(Player player) {
        return Arrays.stream(player.getInventory().getContents())
                .filter(Objects::nonNull)
                .anyMatch(stack -> customItemRegistry.identify(stack).map(RpgItemKeys.RUNE_RAPPEL::equals).orElse(false));
    }

    private void offerGraphicalFallback(Player player) {
        if (player.getOpenInventory().getTopInventory().getHolder() instanceof RescueFallbackHolder) {
            return; // déjà proposé, en attente du choix du joueur -- jamais ré-ouvert par-dessus.
        }
        RescueFallbackHolder holder = new RescueFallbackHolder();
        Inventory inventory = Bukkit.createInventory(holder, 9, MM.deserialize("<dark_red>Secours du Hub</dark_red>"));
        ItemStack button = new ItemStack(Material.LODESTONE);
        ItemMeta meta = button.getItemMeta();
        meta.displayName(MM.deserialize("<green>Retour au spawn du Hub</green>")
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(MM.deserialize("<gray>Clique pour revenir, gratuitement.</gray>")
                .decoration(TextDecoration.ITALIC, false)));
        button.setItemMeta(meta);
        inventory.setItem(4, button);
        holder.bind(inventory);
        player.openInventory(inventory);
    }

    void handleFallbackClick(Player player) {
        player.closeInventory();
        spawnService.resolve().ifPresentOrElse(destination -> {
            player.teleportAsync(destination);
            player.sendMessage(MM.deserialize("<green>Retour au point d'apparition du Hub.</green>"));
        }, () -> {
            player.sendMessage(MM.deserialize("<red>Destination indisponible, contacte un administrateur.</red>"));
            logger.warn("Secours Hub demandé par {} mais aucun spawn configuré.", player.getUniqueId());
        });
    }

    /** Holder dédié : identifie l'inventaire de secours sans dépendre du titre ni du contenu. */
    private static final class RescueFallbackHolder implements InventoryHolder {
        private Inventory inventory;

        void bind(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final class HubRescueFallbackListener implements Listener {
        private final HubRescueFallbackService service;

        private HubRescueFallbackListener(HubRescueFallbackService service) {
            this.service = service;
        }

        @EventHandler
        public void onInventoryClick(InventoryClickEvent event) {
            if (!(event.getView().getTopInventory().getHolder() instanceof RescueFallbackHolder)) {
                return;
            }
            event.setCancelled(true);
            if (event.getSlot() == 4 && event.getWhoClicked() instanceof Player player) {
                service.handleFallbackClick(player);
            }
        }
    }
}
