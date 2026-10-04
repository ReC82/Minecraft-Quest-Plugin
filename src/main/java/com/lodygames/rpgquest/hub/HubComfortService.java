package com.lodygames.rpgquest.hub;

import com.lodygames.rpgquest.bootstrap.PluginService;
import com.lodygames.rpgquest.config.HubConfig;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.slf4j.Logger;

/**
 * Le Hub n'est pas un monde de survie (issue #33) : courir/sauter/explorer n'y diminue jamais la
 * faim ni la saturation, et un joueur qui y revient blessé/affamé depuis le Wild récupère sans
 * commande ni PNJ. Les dégâts y sont déjà entièrement annulés par
 * {@link HubWorldProtectionListener} ; ce service couvre le reste de l'audit demandé :
 *
 * <ul>
 *   <li><strong>Restauration à l'arrivée</strong> (Wild → Hub, reconnexion, réapparition) : vie,
 *       faim et saturation remises au maximum — jamais seulement à l'entrée (voir ci-dessous), mais
 *       ce point couvre l'état visible immédiatement au changement de monde/connexion.</li>
 *   <li><strong>Épuisement</strong> ({@code Player#getExhaustion()}, incrémenté par le sprint/les
 *       sauts) : {@link FoodLevelChangeEvent} annulé dans le Hub (jamais de perte de faim), et une
 *       tâche périodique légère remet faim/saturation/épuisement au maximum pour parer le cas où
 *       la saturation se viderait silencieusement (sans événement dédié côté API Bukkit) avant
 *       d'atteindre un palier de faim.</li>
 * </ul>
 *
 * <p>Jamais hors du Hub : seul {@link HubConfig#world()} est concerné, le Wild et les autres
 * mondes gardent leur survie/épuisement normaux.</p>
 */
public final class HubComfortService implements PluginService {

    /** Fréquence de la garde périodique anti-épuisement silencieux — coût négligeable (peu de joueurs par tick). */
    private static final long SWEEP_PERIOD_TICKS = 20L;

    private final Plugin plugin;
    private final Supplier<HubConfig> config;
    private final Logger logger;
    private BukkitTask sweepTask;

    public HubComfortService(Plugin plugin, Supplier<HubConfig> config, Logger logger) {
        this.plugin = plugin;
        this.config = config;
        this.logger = logger;
    }

    @Override
    public void start() {
        sweepTask = Bukkit.getScheduler().runTaskTimer(plugin, this::sweep, SWEEP_PERIOD_TICKS, SWEEP_PERIOD_TICKS);
        logger.info("Confort du Hub actif : faim/saturation/épuisement neutralisés dans {}.", config.get().world());
    }

    @Override
    public void stop() {
        if (sweepTask != null) {
            sweepTask.cancel();
            sweepTask = null;
        }
    }

    public Listener listener() {
        return new HubComfortListener(this);
    }

    private void sweep() {
        String hub = config.get().world();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getWorld().getName().equals(hub)) {
                restoreFoodAndSaturation(player);
            }
        }
    }

    private boolean isHub(Player player) {
        return player.getWorld().getName().equals(config.get().world());
    }

    private static void restoreFoodAndSaturation(Player player) {
        if (player.getFoodLevel() < 20) {
            player.setFoodLevel(20);
        }
        if (player.getSaturation() < 20f) {
            player.setSaturation(20f);
        }
        if (player.getExhaustion() != 0f) {
            player.setExhaustion(0f);
        }
    }

    /** Vie, faim et saturation au maximum — jamais utilisé hors Hub. */
    private static void restoreFull(Player player) {
        var maxHealthAttribute = player.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealthAttribute != null) {
            player.setHealth(maxHealthAttribute.getValue());
        }
        restoreFoodAndSaturation(player);
    }

    private static final class HubComfortListener implements Listener {

        private final HubComfortService service;

        private HubComfortListener(HubComfortService service) {
            this.service = service;
        }

        /** Jamais une perte de faim dans le Hub, quelle que soit la cause (sprint, saut, régénération). */
        @EventHandler(ignoreCancelled = true)
        public void onFoodLevelChange(FoodLevelChangeEvent event) {
            if (event.getEntity() instanceof Player player && service.isHub(player) && event.getFoodLevel() < player.getFoodLevel()) {
                event.setCancelled(true);
            }
        }

        @EventHandler
        public void onJoin(PlayerJoinEvent event) {
            if (service.isHub(event.getPlayer())) {
                restoreFull(event.getPlayer());
            }
        }

        @EventHandler
        public void onChangedWorld(PlayerChangedWorldEvent event) {
            if (service.isHub(event.getPlayer())) {
                restoreFull(event.getPlayer());
            }
        }

        @EventHandler
        public void onRespawn(PlayerRespawnEvent event) {
            if (event.getRespawnLocation().getWorld() != null
                    && event.getRespawnLocation().getWorld().getName().equals(service.config.get().world())) {
                Bukkit.getScheduler().runTask(service.plugin, () -> restoreFull(event.getPlayer()));
            }
        }
    }
}
