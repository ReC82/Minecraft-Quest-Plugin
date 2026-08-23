package com.lodygames.rpgquest.travel;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.config.RandomSafeArrivalConfig;
import com.lodygames.rpgquest.travel.model.DestinationStrategy;
import com.lodygames.rpgquest.travel.model.WorldPortalDefinition;
import com.lodygames.rpgquest.world.WorldService;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
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
 *
 * <p><strong>Répit d'arrivée</strong> (bug constaté en test réel) : cette anti-boucle ne protège
 * que contre le <em>même</em> portail répété ; elle ne protège pas un joueur qui vient d'être
 * placé quelque part par autre chose qu'un pas volontaire (connexion, {@code /tp} d'un
 * administrateur, ou l'atterrissage d'un <em>autre</em> portail) et dont la position se trouve
 * être à l'intérieur d'une zone d'activation — dans ce cas, le tout premier {@code
 * PlayerMoveEvent} de règlement (chute, micro-mouvement client) déclenchait une téléportation
 * silencieuse et immédiate, avec l'illusion d'un état persistant par joueur puisque ça se
 * reproduit à chaque connexion tant qu'il revient à cette position. {@link #arrivalGrace} accorde
 * {@value #ARRIVAL_GRACE_TICKS} ticks d'immunité après {@link PlayerJoinEvent}/{@link
 * PlayerTeleportEvent} (donc après une connexion, un {@code /tp} externe, ou l'atterrissage d'un
 * autre portail) avant qu'un portail simple puisse se déclencher automatiquement — le temps
 * qu'un joueur (ou un administrateur) remarque et s'écarte d'une zone mal placée, sans désactiver
 * l'usage volontaire du portail au-delà de ce court répit.</p>
 *
 * <p><strong>Limite connue de ce répit, découverte en investiguant plus loin (voir le rapport de
 * la session « diagnostic WorldPortal »)</strong> : il ne corrige pas une zone réellement mal
 * positionnée qui engloberait un point d'arrivée du Hub — il ne fait que <em>retarder</em> le
 * déclenchement de {@value #ARRIVAL_GRACE_TICKS} ticks (2 s), ce qui correspond exactement au délai
 * « après 1-2 secondes » rapporté depuis VeryGames. {@code onMove} ne remet jamais à jour {@link
 * #currentPortalByPlayer} pendant le répit (retour anticipé avant toute lecture du registre) ; si
 * la zone couvre réellement la position d'arrivée, le tout premier {@code PlayerMoveEvent} de
 * règlement après l'expiration du répit voit donc {@code previousPortalId == null} (jamais
 * enregistré) et déclenche une téléportation « neuve », immédiate. Ce n'est <strong>pas encore
 * corrigé ici</strong> (mission : outils de diagnostic d'abord) — voir {@code TpTraceLogger}
 * (champ {@code grace=}) et {@code /rpgadmin worldportal here}/{@code debug} pour confirmer ou
 * infirmer cette hypothèse sur le serveur réel avant tout correctif.</p>
 */
public final class WorldPortalTeleportListener implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final long ARRIVAL_GRACE_TICKS = 40L; // 2 s.

    private final RPGQuestPlugin plugin;
    private final WorldPortalRegistry registry;
    private final WorldService worldService;
    private final Supplier<RandomSafeArrivalConfig> randomSafeArrivalConfig;
    private final Logger logger;

    private final Map<UUID, String> currentPortalByPlayer = new ConcurrentHashMap<>();
    private final Set<UUID> arrivalGrace = ConcurrentHashMap.newKeySet();
    /** TODO(debug bug TP hub) : marqueur de ré-entrance — voir {@link #onTeleport}. */
    private final Set<UUID> selfInitiatedTeleport = ConcurrentHashMap.newKeySet();

    public WorldPortalTeleportListener(RPGQuestPlugin plugin, WorldPortalRegistry registry, WorldService worldService,
                                        Supplier<RandomSafeArrivalConfig> randomSafeArrivalConfig, Logger logger) {
        this.plugin = plugin;
        this.registry = registry;
        this.worldService = worldService;
        this.randomSafeArrivalConfig = randomSafeArrivalConfig;
        this.logger = logger;
    }

    /** Durée du répit d'arrivée, en ticks — exposé pour {@code /rpgadmin worldportal info} (diagnostic, voir mission). */
    public static long arrivalGraceTicks() {
        return ARRIVAL_GRACE_TICKS;
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
        if (arrivalGrace.contains(playerId)) {
            return; // vient d'arriver (connexion/téléportation) : voir le répit documenté ci-dessus.
        }
        Optional<WorldPortalDefinition> portalOpt = registry.portalAt(world.getName(), to.getBlockX(), to.getBlockY(), to.getBlockZ())
                .filter(WorldPortalDefinition::enabled);
        String newPortalId = portalOpt.map(WorldPortalDefinition::id).orElse(null);
        String previousPortalId = currentPortalByPlayer.get(playerId);
        if (Objects.equals(newPortalId, previousPortalId)) {
            return;
        }
        if (newPortalId == null) {
            currentPortalByPlayer.remove(playerId);
            TpTraceLogger.log(logger, "portal_exit", playerId, player.getName(), previousPortalId,
                    world.getName(), to.getBlockX(), to.getBlockY(), to.getBlockZ(),
                    false, true, null, null, portalLocationString(from), null);
            return;
        }
        currentPortalByPlayer.put(playerId, newPortalId);
        TpTraceLogger.log(logger, "portal_enter", playerId, player.getName(), newPortalId,
                world.getName(), to.getBlockX(), to.getBlockY(), to.getBlockZ(),
                true, previousPortalId != null, null, null, portalLocationString(from), null);
        teleport(player, portalOpt.get());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Location loc = player.getLocation();
        boolean inside = loc.getWorld() != null
                && registry.portalAt(loc.getWorld().getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()).isPresent();
        TpTraceLogger.log(logger, "player_join", player.getUniqueId(), player.getName(), null,
                loc.getWorld() != null ? loc.getWorld().getName() : "?", loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(),
                inside, null, ARRIVAL_GRACE_TICKS, null, null, null);
        grantArrivalGrace(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        Location loc = player.getLocation();
        World world = loc.getWorld();
        boolean inside = world != null
                && registry.portalAt(world.getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()).isPresent();
        TpTraceLogger.log(logger, "world_change", player.getUniqueId(), player.getName(), null,
                world != null ? world.getName() : "?", loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(),
                inside, null, arrivalGrace.contains(player.getUniqueId()) ? "active" : null, null,
                event.getFrom().getName(), null);
    }

    /**
     * TODO(debug bug TP hub) : {@code event=external_teleport} peut aussi apparaître pour une
     * téléportation déclenchée par {@link #teleport(Player, WorldPortalDefinition)} lui-même — voir
     * {@link #selfInitiatedTeleport}, qui supprime le doublon quand la ré-entrance est détectable de
     * façon fiable (appel synchrone). {@code PortalService#finishTeleport} utilise {@code
     * teleportAsync}, dont l'événement peut être différé au tick suivant : pas de garde-fou partagé
     * entre les deux classes pour cette raison (complexité jugée disproportionnée pour une
     * instrumentation temporaire) — corréler par {@code uuid=}/l'horodatage avec la ligne {@code
     * teleport_start}/{@code teleport_success} adjacente en cas de doute.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        grantArrivalGrace(playerId);
        if (selfInitiatedTeleport.contains(playerId)) {
            return; // notre propre teleport() plus bas : déjà tracé par teleport_start/teleport_success.
        }
        Location to = event.getTo();
        World world = to != null ? to.getWorld() : null;
        TpTraceLogger.log(logger, "external_teleport", playerId, player.getName(), null,
                world != null ? world.getName() : "?",
                to != null ? to.getBlockX() : 0, to != null ? to.getBlockY() : 0, to != null ? to.getBlockZ() : 0,
                null, null, ARRIVAL_GRACE_TICKS, null,
                portalLocationString(event.getFrom()), portalLocationString(to));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        currentPortalByPlayer.remove(playerId);
        arrivalGrace.remove(playerId);
        selfInitiatedTeleport.remove(playerId);
    }

    private void grantArrivalGrace(UUID playerId) {
        arrivalGrace.add(playerId);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> arrivalGrace.remove(playerId), ARRIVAL_GRACE_TICKS);
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
        Location source = player.getLocation();
        Location target = portal.destinationStrategy() == DestinationStrategy.RANDOM_SAFE
                ? resolveRandomSafeLocation(portal, world)
                : world.getSpawnLocation();

        UUID playerId = player.getUniqueId();
        TpTraceLogger.log(logger, "teleport_start", playerId, player.getName(), portal.id(),
                world.getName(), target.getBlockX(), target.getBlockY(), target.getBlockZ(),
                null, null, null, null, portalLocationString(source), portalLocationString(target));

        // teleport() synchrone plutôt que teleportAsync() : ce portail simple vise en priorité la
        // robustesse et la testabilité (voir travel.WorldPortalTeleportListenerTest) — contrairement
        // à PortalService, aucun chargement de chunk distant coûteux n'est en jeu ici (spawn ou
        // colonne déjà chargée/générée à la demande par RandomSafeLocationFinder juste au-dessus).
        selfInitiatedTeleport.add(playerId);
        boolean success;
        try {
            success = player.teleport(target);
        } finally {
            selfInitiatedTeleport.remove(playerId);
        }

        TpTraceLogger.log(logger, success ? "teleport_success" : "teleport_failed", playerId, player.getName(), portal.id(),
                world.getName(), target.getBlockX(), target.getBlockY(), target.getBlockZ(),
                null, null, null, null, portalLocationString(source), portalLocationString(target));

        if (!success) {
            logger.warn("player.teleport() a renvoyé false pour le portail simple {} (joueur {}) — "
                    + "un autre plugin a probablement annulé la téléportation.", portal.id(), player.getName());
        }
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

    private static String portalLocationString(Location location) {
        if (location == null || location.getWorld() == null) {
            return null;
        }
        return location.getWorld().getName() + ":" + location.getBlockX() + "," + location.getBlockY() + "," + location.getBlockZ();
    }
}
