package com.lodygames.rpgquest.travel;

import com.lodygames.rpgquest.config.RandomSafeArrivalConfig;
import com.lodygames.rpgquest.travel.model.DestinationStrategy;
import com.lodygames.rpgquest.travel.model.WorldPortalDefinition;
import com.lodygames.rpgquest.world.WorldService;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.slf4j.Logger;

/**
 * Détecte l'entrée d'un joueur dans un {@link WorldPortalDefinition} et le téléporte immédiatement
 * au spawn <em>actuel</em> du monde destination (résolu à chaque activation via {@code
 * WorldService#find}, jamais mémorisé) — aucune canalisation, aucun coût, contrairement à {@code
 * PortalService}.
 *
 * <p>Anti-boucle : même patron que {@code ZoneProtectionListener#onMove}/{@code
 * PortalService#handleMove} — un {@code Map<UUID, String>} retient le dernier portail où se
 * trouvait chaque joueur ; l'activation ne se déclenche que sur une vraie transition
 * extérieur → intérieur (ou d'un portail vers un autre), jamais tant qu'il reste dans le même
 * portail à chaque micro-mouvement, et jamais une seconde fois de suite si la téléportation a
 * échoué sans qu'il ait bougé entre-temps.</p>
 */
public final class WorldPortalTeleportListener implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final WorldPortalRegistry registry;
    private final WorldService worldService;
    private final Supplier<RandomSafeArrivalConfig> randomSafeArrivalConfig;
    private final Logger logger;

    private final Map<UUID, String> currentPortalByPlayer = new ConcurrentHashMap<>();

    public WorldPortalTeleportListener(WorldPortalRegistry registry, WorldService worldService,
                                        Supplier<RandomSafeArrivalConfig> randomSafeArrivalConfig, Logger logger) {
        this.registry = registry;
        this.worldService = worldService;
        this.randomSafeArrivalConfig = randomSafeArrivalConfig;
        this.logger = logger;
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || (from.getBlockX() == to.getBlockX() && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ())) {
            return; // ne traite que les changements de bloc, jamais chaque micro-mouvement.
        }
        World world = to.getWorld();
        if (world == null) {
            return;
        }

        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        Optional<WorldPortalDefinition> portalOpt = registry.portalAt(world.getName(), to.getBlockX(), to.getBlockY(), to.getBlockZ())
                .filter(WorldPortalDefinition::enabled);
        String newPortalId = portalOpt.map(WorldPortalDefinition::id).orElse(null);
        String previousPortalId = currentPortalByPlayer.get(playerId);
        if (Objects.equals(newPortalId, previousPortalId)) {
            return;
        }
        if (newPortalId == null) {
            currentPortalByPlayer.remove(playerId);
            return;
        }
        currentPortalByPlayer.put(playerId, newPortalId);
        teleport(player, portalOpt.get());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        currentPortalByPlayer.remove(event.getPlayer().getUniqueId());
    }

    private void teleport(Player player, WorldPortalDefinition portal) {
        Optional<World> destination = worldService.find(portal.destinationWorld());
        if (destination.isEmpty()) {
            player.sendMessage(MM.deserialize(
                    "<red>Le monde de destination de ce portail n'est pas chargé, contacte un administrateur.</red>"));
            logger.warn("Portail simple {} pointe vers un monde non chargé « {} ».", portal.id(), portal.destinationWorld());
            return;
        }
        World world = destination.get();
        Location target = portal.destinationStrategy() == DestinationStrategy.RANDOM_SAFE
                ? resolveRandomSafeLocation(portal, world)
                : world.getSpawnLocation();

        // teleport() synchrone plutôt que teleportAsync() : ce portail simple vise en priorité la
        // robustesse et la testabilité (voir travel.WorldPortalTeleportListenerTest) — contrairement
        // à PortalService, aucun chargement de chunk distant coûteux n'est en jeu ici (spawn ou
        // colonne déjà chargée/générée à la demande par RandomSafeLocationFinder juste au-dessus).
        player.teleport(target);
        player.sendMessage(MM.deserialize("<green>Téléportation réussie.</green>"));
    }

    /**
     * Résout une position aléatoire sûre autour du spawn du monde destination (voir {@link
     * RandomSafeLocationFinder}) ; repli automatique — journalisé — sur le spawn du monde si aucune
     * position sûre n'est trouvée en {@code max-attempts} tentatives (jamais de boucle infinie, et
     * le joueur est toujours téléporté proprement).
     */
    private Location resolveRandomSafeLocation(WorldPortalDefinition portal, World world) {
        RandomSafeArrivalConfig config = randomSafeArrivalConfig.get();
        RandomSafeLocationFinder finder = new RandomSafeLocationFinder(config.minRadius(), config.maxRadius(), config.maxAttempts());
        return finder.find(world, world.getSpawnLocation()).orElseGet(() -> {
            logger.warn(
                    "Portail simple {} (RANDOM_SAFE) : aucune position sûre trouvée en {} tentative(s) (rayon {}-{}) dans « {} », repli sur son spawn.",
                    portal.id(), config.maxAttempts(), config.minRadius(), config.maxRadius(), world.getName());
            return world.getSpawnLocation();
        });
    }
}
