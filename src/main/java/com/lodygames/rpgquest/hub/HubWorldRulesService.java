package com.lodygames.rpgquest.hub;

import com.lodygames.rpgquest.bootstrap.PluginService;
import com.lodygames.rpgquest.config.HubConfig;
import com.lodygames.rpgquest.world.WorldService;
import java.util.function.Supplier;
import org.bukkit.GameRules;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldLoadEvent;
import org.slf4j.Logger;

/**
 * Applique les règles de <strong>monde</strong> du Hub (jour et météo permanents) — jamais via une
 * commande {@code /gamerule} manuelle, toujours par code, réappliqué automatiquement à chaque
 * démarrage du plugin et à chaque (re)chargement du monde Hub. Le nom du monde vient uniquement de
 * {@link HubConfig#world()} (voir {@code config.yml}) — jamais codé en dur.
 *
 * <p>Distinct du champ {@code force-day} de {@code zone.ZoneFlags} : celui-ci fige l'heure
 * <em>par joueur</em> ({@code Player#setPlayerTime}) pour une simple zone à l'intérieur d'un monde
 * partagé (le reste du monde garde son cycle normal). Ici, le Hub est un monde entier dédié : figer
 * son horloge et sa météo réelles (jamais {@code World#setTime} sur un monde partagé) n'affecte
 * personne d'autre — solution plus simple et plus fidèle à « jour/météo permanents », sans le
 * mécanisme de suivi par joueur.</p>
 *
 * <p>{@link #applyRules(World)} est idempotent : rappelable sans effet de bord supplémentaire
 * (utile au redémarrage comme au rechargement tardif via {@link WorldLoadEvent}).</p>
 */
public final class HubWorldRulesService implements PluginService {

    private final WorldService worldService;
    private final Supplier<HubConfig> config;
    private final Logger logger;

    public HubWorldRulesService(WorldService worldService, Supplier<HubConfig> config, Logger logger) {
        this.worldService = worldService;
        this.config = config;
        this.logger = logger;
    }

    @Override
    public void start() {
        worldService.find(config.get().world()).ifPresent(this::applyRules);
    }

    @Override
    public void stop() {
        // Rien à libérer : les règles vivent sur l'objet World lui-même, propriété de Bukkit.
    }

    /** Écouteur Bukkit (chargement tardif du monde Hub, ex. par Multiverse-Core après RPGQuest) à enregistrer via {@code PlayerListenerService}. */
    public Listener listener() {
        return new HubWorldLoadListener(this);
    }

    void handleWorldLoad(World world) {
        if (world.getName().equals(config.get().world())) {
            applyRules(world);
        }
    }

    private void applyRules(World world) {
        world.setGameRule(GameRules.ADVANCE_TIME, false);
        world.setTime(6000L); // midi, plein jour.

        world.setGameRule(GameRules.ADVANCE_WEATHER, false);
        world.setStorm(false);
        world.setThundering(false);
        world.setWeatherDuration(Integer.MAX_VALUE);
        world.setThunderDuration(Integer.MAX_VALUE);

        logger.info("Règles du monde Hub appliquées : {} (jour et météo permanents).", world.getName());
    }

    private static final class HubWorldLoadListener implements Listener {

        private final HubWorldRulesService service;

        private HubWorldLoadListener(HubWorldRulesService service) {
            this.service = service;
        }

        @EventHandler
        public void onWorldLoad(WorldLoadEvent event) {
            service.handleWorldLoad(event.getWorld());
        }
    }
}
