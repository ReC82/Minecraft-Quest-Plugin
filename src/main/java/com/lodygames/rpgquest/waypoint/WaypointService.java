package com.lodygames.rpgquest.waypoint;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.bootstrap.PluginService;
import com.lodygames.rpgquest.config.TravelConfig;
import com.lodygames.rpgquest.config.TravelConfig.WaypointConfig;
import com.lodygames.rpgquest.database.WaypointRepository;
import com.lodygames.rpgquest.travel.RandomSafeLocationFinder;
import com.lodygames.rpgquest.waypoint.model.BiomeInstanceKey;
import com.lodygames.rpgquest.waypoint.model.Waypoint;
import com.lodygames.rpgquest.waypoint.render.BlockOffset;
import com.lodygames.rpgquest.waypoint.render.WaypointModel;
import com.lodygames.rpgquest.waypoint.render.WaypointModelRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.slf4j.Logger;

/**
 * Moteur de waypoints par instance de biome dans le monde d'exploration (issue #124).
 *
 * <ul>
 *   <li><strong>Génération paresseuse et unique</strong> : quand un joueur entre dans une instance
 *       de biome ({@link WaypointIdentityResolver}) qui n'a pas encore de waypoint, le moteur
 *       cherche <em>une seule fois</em> un emplacement de surface approprié à distance raisonnable
 *       (jamais au pied du joueur), pose la structure et la persiste. Verrou en mémoire
 *       ({@code generating}) + index unique {@code (world, biome_instance)} en base : deux joueurs
 *       entrant simultanément ne créent jamais deux waypoints.</li>
 *   <li><strong>Partagé</strong> : le waypoint appartient au monde, tous les joueurs le voient.</li>
 *   <li><strong>Découverte explicite</strong> : passer à proximité ne découvre rien ; seul un clic
 *       droit sur le <em>bouton</em> (l'interacteur du modèle) valide la découverte, persistée par
 *       UUID joueur.</li>
 *   <li><strong>Rendu versionné</strong> : la logique ne connaît qu'un numéro de version
 *       ({@link WaypointModelRegistry}) ; l'identité du waypoint est indépendante du rendu.</li>
 *   <li><strong>Performance</strong> : aucune recherche terrain par {@code PlayerMoveEvent} — un
 *       throttle par joueur + un cache de la dernière instance + le fait qu'une instance n'est
 *       évaluée qu'une fois.</li>
 * </ul>
 */
public final class WaypointService implements PluginService {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final long[] RETRY_BACKOFF_MILLIS = {30_000L, 120_000L, 600_000L, 3_600_000L};

    private final RPGQuestPlugin plugin;
    private final WaypointRepository repository;
    private final WaypointIdentityResolver identityResolver;
    private final WaypointGenerationPlanner planner;
    private final WaypointModelRegistry modelRegistry;
    private final WaypointPlacementGuard placementGuard;
    private final Supplier<TravelConfig> config;
    private final Logger logger;

    /** id waypoint → waypoint. */
    private final ConcurrentHashMap<String, Waypoint> byId = new ConcurrentHashMap<>();
    /** {@code world + "#" + biomeInstance} → waypoint. */
    private final ConcurrentHashMap<String, Waypoint> byInstance = new ConcurrentHashMap<>();
    /** {@code world:x,y,z} du bloc interacteur → waypoint. */
    private final ConcurrentHashMap<String, Waypoint> byInteractorBlock = new ConcurrentHashMap<>();
    /** {@code world:x,y,z} de tout bloc constitutif protégé. */
    private final Set<String> protectedBlocks = ConcurrentHashMap.newKeySet();
    /** Clés d'instance en cours de génération (verrou de single-flight). */
    private final Set<String> generating = ConcurrentHashMap.newKeySet();
    /** Clé d'instance → epoch ms avant lequel aucun nouvel essai de génération. */
    private final ConcurrentHashMap<String, Long> retryNotBefore = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> retryCount = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<UUID, Set<String>> discoveriesByPlayer = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, String> lastInstanceByPlayer = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> lastCheckByPlayer = new ConcurrentHashMap<>();

    public WaypointService(RPGQuestPlugin plugin, WaypointRepository repository,
                            WaypointIdentityResolver identityResolver, WaypointGenerationPlanner planner,
                            WaypointModelRegistry modelRegistry, WaypointPlacementGuard placementGuard,
                            Supplier<TravelConfig> config) {
        this.plugin = plugin;
        this.repository = repository;
        this.identityResolver = identityResolver;
        this.planner = planner;
        this.modelRegistry = modelRegistry;
        this.placementGuard = placementGuard;
        this.config = config;
        this.logger = plugin.getSLF4JLogger();
    }

    @Override
    public void start() {
        repository.loadAll().thenAccept(list -> runSync(() -> {
            for (Waypoint waypoint : list) {
                index(waypoint);
            }
            logger.info("Waypoints chargés : {}.", list.size());
        })).exceptionally(error -> {
            logger.error("Impossible de charger les waypoints persistés.", error);
            return null;
        });
    }

    @Override
    public void stop() {
        byId.clear();
        byInstance.clear();
        byInteractorBlock.clear();
        protectedBlocks.clear();
        generating.clear();
        retryNotBefore.clear();
        retryCount.clear();
        discoveriesByPlayer.clear();
        lastInstanceByPlayer.clear();
        lastCheckByPlayer.clear();
    }

    public Listener listener() {
        return new WaypointListener(this);
    }

    public Listener protectionListener() {
        return new WaypointProtectionListener(this::isProtectedBlock);
    }

    // ---- Cycle de vie joueur -------------------------------------------------------------------

    void handleJoin(Player player) {
        UUID playerId = player.getUniqueId();
        repository.discoveriesFor(playerId)
                .thenAccept(ids -> {
                    Set<String> set = ConcurrentHashMap.newKeySet();
                    set.addAll(ids);
                    discoveriesByPlayer.put(playerId, set);
                })
                .exceptionally(error -> {
                    logger.error("Impossible de charger les découvertes de waypoints de {}", playerId, error);
                    return null;
                });
    }

    void handleQuit(Player player) {
        UUID playerId = player.getUniqueId();
        discoveriesByPlayer.remove(playerId);
        lastInstanceByPlayer.remove(playerId);
        lastCheckByPlayer.remove(playerId);
    }

    /** Après un changement de monde / une téléportation : la prochaine évaluation repart de zéro. */
    void resetPlayerInstance(Player player) {
        lastInstanceByPlayer.remove(player.getUniqueId());
        lastCheckByPlayer.remove(player.getUniqueId());
    }

    // ---- Entrée dans une instance de biome ---------------------------------------------------

    void handleMovement(Player player, Location to) {
        WaypointConfig wc = config.get().waypoint();
        if (!wc.enabled() || to.getWorld() == null) {
            return;
        }
        World world = to.getWorld();
        if (!world.getName().equals(config.get().wildWorld())) {
            return;
        }
        UUID playerId = player.getUniqueId();
        long now = System.currentTimeMillis();
        Long last = lastCheckByPlayer.get(playerId);
        if (last != null && now - last < wc.moveThrottleMillis()) {
            return;
        }
        lastCheckByPlayer.put(playerId, now);

        int bx = to.getBlockX();
        int by = to.getBlockY();
        int bz = to.getBlockZ();
        String biomeKey = biomeKeyAt(world, bx, by, bz);
        BiomeInstanceKey instance =
                identityResolver.resolve(wc.regionSize(), world.getName(), biomeKey, bx, bz);
        String instanceKey = instanceKey(world.getName(), instance.serialize());

        if (instanceKey.equals(lastInstanceByPlayer.get(playerId))) {
            return;
        }
        lastInstanceByPlayer.put(playerId, instanceKey);

        if (byInstance.containsKey(instanceKey)) {
            return; // waypoint déjà présent : rien à générer (la découverte exige le bouton).
        }
        Long notBefore = retryNotBefore.get(instanceKey);
        if (notBefore != null && now < notBefore) {
            return;
        }
        if (!generating.add(instanceKey)) {
            return; // génération déjà en cours pour cette instance.
        }
        try {
            attemptGeneration(world, instance, instanceKey, to, wc);
        } catch (RuntimeException error) {
            generating.remove(instanceKey);
            logger.error("Échec inattendu de génération de waypoint pour {}", instanceKey, error);
        }
    }

    /** Partie synchrone : recherche terrain (une seule fois par instance), puis persistance async. */
    private void attemptGeneration(World world, BiomeInstanceKey instance, String instanceKey,
                                   Location entry, WaypointConfig wc) {
        long seed = instanceKey.hashCode();
        List<WaypointGenerationPlanner.Candidate> candidates = planner.candidates(
                entry.getBlockX(), entry.getBlockZ(), seed,
                wc.minDistance(), wc.maxDistance(), wc.candidateAttempts());

        WaypointModel model = modelRegistry.current();
        for (WaypointGenerationPlanner.Candidate candidate : candidates) {
            int cx = candidate.blockX();
            int cz = candidate.blockZ();

            // Le candidat doit rester DANS la même instance de biome (même biome + même tuile).
            if (!biomeKeyAt(world, cx, world.getHighestBlockYAt(cx, cz), cz).equals(instance.biomeKey())
                    || !identityResolver.resolve(wc.regionSize(), world.getName(),
                            instance.biomeKey(), cx, cz).equals(instance)) {
                continue;
            }
            Optional<Location> safe = RandomSafeLocationFinder.findAtColumn(world, cx, cz);
            if (safe.isEmpty()) {
                continue;
            }
            int anchorX = safe.get().getBlockX();
            int anchorY = safe.get().getBlockY();
            int anchorZ = safe.get().getBlockZ();
            BlockFace facing = facingToward(entry, anchorX, anchorZ);

            if (!areaFreeForWaypoint(world, anchorX, anchorY, anchorZ, facing, model)) {
                continue;
            }
            if (violatesSpacing(world.getName(), anchorX, anchorZ, wc.minimumSpacing())) {
                continue;
            }

            Waypoint waypoint = new Waypoint(instance.waypointId(), world.getName(),
                    instance.serialize(), instance.biomeKey(), instance.regionX(), instance.regionZ(),
                    anchorX, anchorY, anchorZ, facing.name(), model.version(), true, Instant.now());
            persist(world, waypoint, instanceKey, model);
            return;
        }

        // Aucun emplacement valable : retry borné, jamais de boucle lourde.
        generating.remove(instanceKey);
        int attempt = retryCount.merge(instanceKey, 1, Integer::sum);
        long backoff = RETRY_BACKOFF_MILLIS[Math.min(attempt - 1, RETRY_BACKOFF_MILLIS.length - 1)];
        retryNotBefore.put(instanceKey, System.currentTimeMillis() + backoff);
        logger.info("Aucun emplacement de waypoint trouvé pour {} (essai {}), nouvel essai dans {} s.",
                instanceKey, attempt, backoff / 1000);
    }

    private void persist(World world, Waypoint waypoint, String instanceKey, WaypointModel model) {
        repository.insertIfAbsent(waypoint).thenAccept(inserted -> runSync(() -> {
            generating.remove(instanceKey);
            retryCount.remove(instanceKey);
            retryNotBefore.remove(instanceKey);
            if (inserted) {
                model.place(world, waypoint.x(), waypoint.y(), waypoint.z(),
                        BlockFace.valueOf(waypoint.facing()));
                index(waypoint);
                logger.info("Waypoint « {} » généré en {} ({},{},{}) [biome {}, modèle v{}].",
                        waypoint.id(), world.getName(), waypoint.x(), waypoint.y(), waypoint.z(),
                        waypoint.biomeKey(), waypoint.modelVersion());
            } else {
                // Course perdue (autre nœud / ligne préexistante) : réaligner l'état mémoire.
                repository.findByInstance(waypoint.world(), waypoint.biomeInstance())
                        .thenAccept(opt -> runSync(() -> opt.ifPresent(this::index)))
                        .exceptionally(error -> {
                            logger.error("Impossible de recharger le waypoint {}", waypoint.id(), error);
                            return null;
                        });
            }
        })).exceptionally(error -> {
            generating.remove(instanceKey);
            logger.error("Impossible de persister le waypoint {}", waypoint.id(), error);
            return null;
        });
    }

    // ---- Découverte -----------------------------------------------------------------------------

    /** @return {@code true} si {@code block} est l'interacteur d'un waypoint (l'événement doit alors être annulé). */
    boolean handleInteract(Player player, Block block) {
        Waypoint waypoint = byInteractorBlock.get(blockKey(block.getWorld().getName(),
                block.getX(), block.getY(), block.getZ()));
        if (waypoint == null || !waypoint.active()) {
            return false;
        }
        UUID playerId = player.getUniqueId();
        Set<String> discovered = discoveriesByPlayer.computeIfAbsent(playerId, k -> ConcurrentHashMap.newKeySet());
        if (discovered.contains(waypoint.id())) {
            return true; // déjà découvert : on consomme le clic, sans spam ni ré-écriture.
        }
        repository.recordDiscovery(playerId, waypoint.id(), Instant.now())
                .thenAccept(isNew -> runSync(() -> {
                    discovered.add(waypoint.id());
                    Player online = plugin.getServer().getPlayer(playerId);
                    if (online != null && online.isOnline() && isNew) {
                        online.sendMessage(MM.deserialize(
                                "<gold>Waypoint découvert</gold> <gray>—</gray> <white><biome></white>",
                                Placeholder.parsed("biome", prettyBiome(waypoint.biomeKey()))));
                        online.playSound(online.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.7f, 1.5f);
                    }
                })).exceptionally(error -> {
                    logger.error("Impossible d'enregistrer la découverte de {} par {}", waypoint.id(), playerId, error);
                    return null;
                });
        return true;
    }

    // ---- Protection ---------------------------------------------------------------------------

    /** {@code true} si (x, y, z) est un bloc constitutif d'un waypoint (interdit de casser/déplacer/etc.). */
    public boolean isProtectedBlock(String world, int x, int y, int z) {
        return protectedBlocks.contains(blockKey(world, x, y, z));
    }

    // ---- Lecture (Control Panel / diagnostics / tests) --------------------------------------

    public List<Waypoint> all() {
        return List.copyOf(byId.values());
    }

    public Optional<Waypoint> byId(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    public CompletableFuture<Integer> discoveryCount(String waypointId) {
        return repository.discoveryCountForWaypoint(waypointId);
    }

    // ---- Interne ----------------------------------------------------------------------------

    private void index(Waypoint waypoint) {
        byId.put(waypoint.id(), waypoint);
        byInstance.put(instanceKey(waypoint.world(), waypoint.biomeInstance()), waypoint);
        BlockFace facing = parseFacing(waypoint.facing());
        WaypointModel model = modelRegistry.resolveOrCurrent(waypoint.modelVersion());

        BlockOffset interactor = model.interactor(facing);
        byInteractorBlock.put(blockKey(waypoint.world(),
                waypoint.x() + interactor.dx(), waypoint.y() + interactor.dy(), waypoint.z() + interactor.dz()),
                waypoint);
        for (BlockOffset offset : model.protectedBlocks(facing)) {
            protectedBlocks.add(blockKey(waypoint.world(),
                    waypoint.x() + offset.dx(), waypoint.y() + offset.dy(), waypoint.z() + offset.dz()));
        }
    }

    boolean violatesSpacing(String world, int x, int z, int minSpacing) {
        if (minSpacing <= 0) {
            return false;
        }
        long minSq = (long) minSpacing * minSpacing;
        for (Waypoint existing : byId.values()) {
            if (!existing.world().equals(world)) {
                continue;
            }
            long dx = existing.x() - x;
            long dz = existing.z() - z;
            if (dx * dx + dz * dz < minSq) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code true} si la zone du modèle est libre : chaque bloc constitutif est actuellement
     * « naturel » (remplaçable) et le système a le droit d'y construire (hors claim). Ne détruit
     * jamais une construction joueur pour poser un waypoint.
     */
    private boolean areaFreeForWaypoint(World world, int anchorX, int anchorY, int anchorZ,
                                        BlockFace facing, WaypointModel model) {
        for (BlockOffset offset : model.protectedBlocks(facing)) {
            int x = anchorX + offset.dx();
            int y = anchorY + offset.dy();
            int z = anchorZ + offset.dz();
            if (!placementGuard.isBuildable(world, x, y, z)) {
                return false;
            }
            Block block = world.getBlockAt(x, y, z);
            // Le sol sous l'ancre (dy == -1) a le droit d'être solide/naturel ; tout le reste doit
            // être vide ou une végétation/neige remplaçable.
            if (offset.dy() == -1) {
                if (!block.getType().isSolid() && !isNaturallyReplaceable(block.getType().name())) {
                    return false;
                }
            } else if (!isNaturallyReplaceable(block.getType().name())) {
                return false;
            }
        }
        // Dégagement au-dessus (non protégé, mais ne doit pas écraser une construction).
        for (int[] top : new int[][] {{0, 2, 0}, {facing.getModX(), 2, facing.getModZ()}}) {
            Block block = world.getBlockAt(anchorX + top[0], anchorY + top[1], anchorZ + top[2]);
            if (!isNaturallyReplaceable(block.getType().name())) {
                return false;
            }
        }
        return true;
    }

    private static boolean isNaturallyReplaceable(String materialName) {
        if (materialName.equals("AIR") || materialName.equals("CAVE_AIR") || materialName.equals("VOID_AIR")
                || materialName.equals("WATER") || materialName.equals("SNOW") || materialName.equals("SHORT_GRASS")
                || materialName.equals("TALL_GRASS") || materialName.equals("FERN") || materialName.equals("LARGE_FERN")
                || materialName.equals("DEAD_BUSH") || materialName.equals("VINE") || materialName.equals("SEAGRASS")) {
            return true;
        }
        return materialName.endsWith("_SAPLING") || materialName.endsWith("_FLOWER") || materialName.endsWith("_MUSHROOM")
                || materialName.endsWith("_LEAVES");
    }

    private String biomeKeyAt(World world, int x, int y, int z) {
        try {
            return world.getBiome(x, y, z).getKey().toString();
        } catch (RuntimeException error) {
            return "minecraft:the_void";
        }
    }

    private static BlockFace facingToward(Location from, int anchorX, int anchorZ) {
        double dx = from.getX() - (anchorX + 0.5);
        double dz = from.getZ() - (anchorZ + 0.5);
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx >= 0 ? BlockFace.EAST : BlockFace.WEST;
        }
        return dz >= 0 ? BlockFace.SOUTH : BlockFace.NORTH;
    }

    private static BlockFace parseFacing(String raw) {
        try {
            BlockFace face = BlockFace.valueOf(raw);
            return switch (face) {
                case NORTH, SOUTH, EAST, WEST -> face;
                default -> BlockFace.NORTH;
            };
        } catch (IllegalArgumentException error) {
            return BlockFace.NORTH;
        }
    }

    private static String prettyBiome(String biomeKey) {
        int colon = biomeKey.indexOf(':');
        String name = colon >= 0 ? biomeKey.substring(colon + 1) : biomeKey;
        return name.replace('_', ' ');
    }

    private static String instanceKey(String world, String biomeInstance) {
        return world + "#" + biomeInstance;
    }

    private static String blockKey(String world, int x, int y, int z) {
        return world + ":" + x + "," + y + "," + z;
    }

    private void runSync(Runnable runnable) {
        if (plugin.getServer().isPrimaryThread()) {
            runnable.run();
        } else {
            plugin.getServer().getScheduler().runTask(plugin, runnable);
        }
    }
}
