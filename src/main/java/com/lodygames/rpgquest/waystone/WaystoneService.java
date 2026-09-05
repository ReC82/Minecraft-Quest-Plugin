package com.lodygames.rpgquest.waystone;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.bootstrap.PluginService;
import com.lodygames.rpgquest.config.TravelConfig;
import com.lodygames.rpgquest.config.TravelConfig.WaystoneConfig;
import com.lodygames.rpgquest.database.WaystoneRepository;
import com.lodygames.rpgquest.spawn.SpawnService;
import com.lodygames.rpgquest.travel.RandomSafeLocationFinder;
import com.lodygames.rpgquest.waystone.model.Waystone;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.scheduler.BukkitTask;
import org.slf4j.Logger;

/**
 * Système générique de Waystones dans le monde d'exploration (mission « Waystones Wild »).
 *
 * <ul>
 *   <li><strong>Génération paresseuse</strong> : au chargement d'un chunk du monde configuré, les
 *       cellules qu'il touche sont évaluées une fois ({@link WaystoneCellPlanner}, déterministe) ;
 *       si le point candidat tombe dans ce chunk, passe le test d'espacement et trouve une surface
 *       sûre, la structure est posée et persistée. La base est la seule source de vérité : une
 *       cellule déjà en base n'est jamais régénérée (aucun doublon au reload/redémarrage).</li>
 *   <li><strong>Découverte individuelle</strong> : la Waystone est globale physiquement, mais
 *       chaque joueur la « découvre » à son premier clic (persistée : joueur + id + date).</li>
 *   <li><strong>Utilisation</strong> : sur une Waystone déjà découverte, un choix compact
 *       [Retourner au Hub] / [Annuler] ; « Retourner au Hub » canalise ~3 s (annulé sur
 *       mouvement/dégâts) puis téléporte au spawn du Hub. Aucun coût (MVP).</li>
 * </ul>
 */
public final class WaystoneService implements PluginService {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final double MOVEMENT_TOLERANCE_SQUARED = 0.36;

    private final RPGQuestPlugin plugin;
    private final WaystoneRepository repository;
    private final WaystoneCellPlanner planner;
    private final WaystoneStructurePlacer structurePlacer;
    private final SpawnService spawnService;
    private final Supplier<TravelConfig> config;
    private final Logger logger;

    private final Map<String, Waystone> byId = new ConcurrentHashMap<>();
    private final Map<String, Waystone> byBlockKey = new ConcurrentHashMap<>();
    private final Set<String> resolvedCells = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Set<String>> discoveriesByPlayer = new ConcurrentHashMap<>();
    private final Map<UUID, ChannelSession> channeling = new ConcurrentHashMap<>();

    public WaystoneService(RPGQuestPlugin plugin, WaystoneRepository repository, WaystoneCellPlanner planner,
                            WaystoneStructurePlacer structurePlacer, SpawnService spawnService,
                            Supplier<TravelConfig> config) {
        this.plugin = plugin;
        this.repository = repository;
        this.planner = planner;
        this.structurePlacer = structurePlacer;
        this.spawnService = spawnService;
        this.config = config;
        this.logger = plugin.getSLF4JLogger();
    }

    @Override
    public void start() {
        repository.loadAll().thenAccept(list -> plugin.getServer().getScheduler().runTask(plugin, () -> {
            for (Waystone waystone : list) {
                index(waystone);
            }
            logger.info("Waystones chargées : {}.", list.size());
        })).exceptionally(error -> {
            logger.error("Impossible de charger les Waystones persistées.", error);
            return null;
        });
    }

    @Override
    public void stop() {
        for (ChannelSession session : channeling.values()) {
            if (session.task != null) {
                session.task.cancel();
            }
        }
        channeling.clear();
        byId.clear();
        byBlockKey.clear();
        resolvedCells.clear();
        discoveriesByPlayer.clear();
    }

    public Listener listener() {
        return new WaystoneListener(this);
    }

    private void index(Waystone waystone) {
        byId.put(waystone.id(), waystone);
        byBlockKey.put(blockKey(waystone.world(), waystone.x(), waystone.y(), waystone.z()), waystone);
        resolvedCells.add(cellKey(waystone.world(), waystone.cellX(), waystone.cellZ()));
    }

    // ---- Cycle de vie joueur -----------------------------------------------------------------

    void handleJoin(Player player) {
        UUID playerId = player.getUniqueId();
        repository.discoveriesFor(playerId)
                .thenAccept(ids -> {
                    Set<String> set = ConcurrentHashMap.newKeySet();
                    set.addAll(ids);
                    discoveriesByPlayer.put(playerId, set);
                })
                .exceptionally(error -> {
                    logger.error("Impossible de charger les découvertes de Waystones de {}", playerId, error);
                    return null;
                });
    }

    void handleQuit(Player player) {
        discoveriesByPlayer.remove(player.getUniqueId());
        cancelChannel(player.getUniqueId(), null);
    }

    // ---- Génération paresseuse -------------------------------------------------------------------

    void handleChunkLoad(Chunk chunk) {
        World world = chunk.getWorld();
        if (!world.getName().equals(config.get().wildWorld())) {
            return;
        }
        WaystoneConfig wc = config.get().waystone();
        long seed = world.getSeed();
        int baseX = chunk.getX() << 4;
        int baseZ = chunk.getZ() << 4;

        for (long[] cell : cellsTouchedBy(baseX, baseZ, wc.cellSize())) {
            long cellX = cell[0];
            long cellZ = cell[1];
            String cellKey = cellKey(world.getName(), cellX, cellZ);
            if (resolvedCells.contains(cellKey)) {
                continue;
            }
            Optional<WaystoneCellPlanner.Candidate> candidate = planner.planCell(seed, cellX, cellZ, wc);
            if (candidate.isEmpty()) {
                resolvedCells.add(cellKey); // cellule sans Waystone : décision figée pour la session.
                continue;
            }
            WaystoneCellPlanner.Candidate c = candidate.get();
            if (c.blockX() < baseX || c.blockX() >= baseX + 16 || c.blockZ() < baseZ || c.blockZ() >= baseZ + 16) {
                continue; // le point candidat est dans un autre chunk de cette cellule : on le posera là-bas.
            }
            resolvedCells.add(cellKey); // à partir d'ici, cette cellule est traitée quoi qu'il arrive.
            tryGenerate(world, cellX, cellZ, c, wc);
        }
    }

    private void tryGenerate(World world, long cellX, long cellZ, WaystoneCellPlanner.Candidate c, WaystoneConfig wc) {
        if (violatesSpacing(world.getName(), c.blockX(), c.blockZ(), wc.minimumSpacing())) {
            return;
        }
        Optional<Location> spot = findSafeSurface(world, c.blockX(), c.blockZ(), wc.safeAttempts());
        if (spot.isEmpty()) {
            return;
        }
        Location safe = spot.get();
        int topX = safe.getBlockX();
        int topY = safe.getBlockY();
        int topZ = safe.getBlockZ();
        String id = "ws_" + cellX + "_" + cellZ;
        Waystone waystone = new Waystone(id, world.getName(), topX, topY, topZ, cellX, cellZ, c.name(), Instant.now());

        repository.insertIfAbsent(waystone).thenAccept(inserted -> plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (inserted) {
                structurePlacer.place(world, topX, topY, topZ);
                index(waystone);
                logger.info("Waystone « {} » générée en {} ({},{},{}).", c.name(), world.getName(), topX, topY, topZ);
            }
        })).exceptionally(error -> {
            logger.error("Impossible de persister la Waystone {}", id, error);
            return null;
        });
    }

    boolean violatesSpacing(String world, int x, int z, int minSpacing) {
        long minSq = (long) minSpacing * minSpacing;
        for (Waystone existing : byId.values()) {
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

    Optional<Location> findSafeSurface(World world, int x, int z, int attempts) {
        Optional<Location> direct = RandomSafeLocationFinder.findAtColumn(world, x, z);
        if (direct.isPresent()) {
            return direct;
        }
        int radius = 1;
        int tried = 1;
        while (tried < attempts) {
            for (int dx = -radius; dx <= radius && tried < attempts; dx++) {
                for (int dz = -radius; dz <= radius && tried < attempts; dz++) {
                    if (Math.abs(dx) != radius && Math.abs(dz) != radius) {
                        continue; // uniquement l'anneau extérieur de ce rayon.
                    }
                    tried++;
                    Optional<Location> spot = RandomSafeLocationFinder.findAtColumn(world, x + dx * 2, z + dz * 2);
                    if (spot.isPresent()) {
                        return spot;
                    }
                }
            }
            radius++;
        }
        return Optional.empty();
    }

    // ---- Interaction --------------------------------------------------------------------------

    Optional<Waystone> waystoneAtBlock(String world, int x, int y, int z) {
        return Optional.ofNullable(byBlockKey.get(blockKey(world, x, y, z)));
    }

    void handleClick(Player player, Waystone waystone) {
        UUID playerId = player.getUniqueId();
        Set<String> discovered = discoveriesByPlayer.computeIfAbsent(playerId, k -> ConcurrentHashMap.newKeySet());
        if (!discovered.contains(waystone.id())) {
            repository.recordDiscovery(playerId, waystone.id(), Instant.now())
                    .thenAccept(isNew -> plugin.getServer().getScheduler().runTask(plugin, () -> {
                        discovered.add(waystone.id());
                        Player online = plugin.getServer().getPlayer(playerId);
                        if (online != null && isNew) {
                            online.sendMessage(MM.deserialize(
                                    "<gold>Pierre de voyage découverte :</gold> <white><name></white>",
                                    Placeholder.parsed("name", waystone.name())));
                        }
                    })).exceptionally(error -> {
                        logger.error("Impossible d'enregistrer la découverte de {} par {}", waystone.id(), playerId, error);
                        return null;
                    });
            return;
        }
        sendTravelChoice(player, waystone);
    }

    private void sendTravelChoice(Player player, Waystone waystone) {
        UUID playerId = player.getUniqueId();
        Component message = MM.deserialize("<aqua><name></aqua> <gray>—</gray> ",
                        Placeholder.parsed("name", waystone.name()))
                .append(MM.deserialize("<green>[Retourner au Hub]</green>")
                        .clickEvent(ClickEvent.callback(audience -> {
                            Player online = plugin.getServer().getPlayer(playerId);
                            if (online != null && online.isOnline()) {
                                beginReturnChannel(online);
                            }
                        })))
                .append(Component.text("  "))
                .append(MM.deserialize("<red>[Annuler]</red>")
                        .clickEvent(ClickEvent.callback(audience -> { })));
        player.sendMessage(message);
    }

    // ---- Canalisation ----------------------------------------------------------------------------

    void beginReturnChannel(Player player) {
        UUID playerId = player.getUniqueId();
        if (channeling.containsKey(playerId)) {
            return;
        }
        if (spawnService.resolve().isEmpty()) {
            player.sendMessage(MM.deserialize("<red>Destination indisponible, contacte un administrateur.</red>"));
            return;
        }
        int totalTicks = Math.max(1, config.get().waystone().channelSeconds()) * 20;
        ChannelSession session = new ChannelSession(playerId, player.getLocation().clone(), totalTicks);
        channeling.put(playerId, session);
        session.task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> tick(session), 1L, 1L);
    }

    private void tick(ChannelSession session) {
        Player player = plugin.getServer().getPlayer(session.playerId);
        if (player == null || !player.isOnline()) {
            cancelChannel(session.playerId, null);
            return;
        }
        Location current = player.getLocation();
        World startWorld = session.start.getWorld();
        if (startWorld == null || !startWorld.equals(current.getWorld())
                || current.distanceSquared(session.start) > MOVEMENT_TOLERANCE_SQUARED) {
            cancelChannel(session.playerId, "<red>Voyage annulé : tu as bougé.</red>");
            return;
        }
        session.elapsed++;
        int percent = (int) (100L * session.elapsed / session.total);
        player.sendActionBar(MM.deserialize("<yellow>Retour au Hub :</yellow> <white><p>%</white>",
                Placeholder.unparsed("p", String.valueOf(percent))));
        if (session.elapsed >= session.total) {
            complete(session);
        }
    }

    private void complete(ChannelSession session) {
        channeling.remove(session.playerId);
        if (session.task != null) {
            session.task.cancel();
        }
        Player player = plugin.getServer().getPlayer(session.playerId);
        if (player == null || !player.isOnline()) {
            return;
        }
        player.sendActionBar(Component.empty());
        spawnService.resolve().ifPresentOrElse(
                target -> {
                    player.teleportAsync(target);
                    player.sendMessage(MM.deserialize("<green>Retour au Hub réussi.</green>"));
                },
                () -> player.sendMessage(MM.deserialize("<red>Destination indisponible, contacte un administrateur.</red>")));
    }

    void handleDamage(Player player) {
        cancelChannel(player.getUniqueId(), "<red>Voyage annulé : tu as subi des dégâts.</red>");
    }

    boolean isChanneling(UUID playerId) {
        return channeling.containsKey(playerId);
    }

    private void cancelChannel(UUID playerId, String message) {
        ChannelSession session = channeling.remove(playerId);
        if (session == null) {
            return;
        }
        if (session.task != null) {
            session.task.cancel();
        }
        Player player = plugin.getServer().getPlayer(playerId);
        if (player != null) {
            player.sendActionBar(Component.empty());
            if (message != null) {
                player.sendMessage(MM.deserialize(message));
            }
        }
    }

    // ---- API admin/debug -----------------------------------------------------------------------

    public List<Waystone> all() {
        return List.copyOf(byId.values());
    }

    public Optional<Waystone> byId(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    /** La Waystone de la cellule où se trouve {@code location}, s'il y en a une. */
    public Optional<Waystone> waystoneInCellOf(Location location) {
        if (location.getWorld() == null) {
            return Optional.empty();
        }
        long cellSize = config.get().waystone().cellSize();
        long cellX = planner.cellOf(location.getBlockX(), cellSize);
        long cellZ = planner.cellOf(location.getBlockZ(), cellSize);
        return byId.values().stream()
                .filter(w -> w.world().equals(location.getWorld().getName()) && w.cellX() == cellX && w.cellZ() == cellZ)
                .findFirst();
    }

    /**
     * Force la génération d'une Waystone dans la cellule de {@code location} (outil de test
     * {@code /rpgadmin waystone generatehere}) : ignore le tirage de probabilité, mais respecte
     * toujours l'unicité par cellule et la recherche de surface sûre.
     */
    public CompletableFuture<Optional<Waystone>> generateAt(Location location) {
        World world = location.getWorld();
        if (world == null) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        WaystoneConfig wc = config.get().waystone();
        long cellX = planner.cellOf(location.getBlockX(), wc.cellSize());
        long cellZ = planner.cellOf(location.getBlockZ(), wc.cellSize());
        Optional<Waystone> existing = byId.values().stream()
                .filter(w -> w.world().equals(world.getName()) && w.cellX() == cellX && w.cellZ() == cellZ)
                .findFirst();
        if (existing.isPresent()) {
            return CompletableFuture.completedFuture(existing);
        }
        Optional<Location> spot = findSafeSurface(world, location.getBlockX(), location.getBlockZ(), wc.safeAttempts());
        if (spot.isEmpty()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        Location safe = spot.get();
        String id = "ws_" + cellX + "_" + cellZ;
        Waystone waystone = new Waystone(id, world.getName(), safe.getBlockX(), safe.getBlockY(), safe.getBlockZ(),
                cellX, cellZ, planner.planCell(world.getSeed(), cellX, cellZ, wc)
                        .map(WaystoneCellPlanner.Candidate::name).orElse("Pierre de voyage"), Instant.now());
        return repository.insertIfAbsent(waystone).thenApply(inserted -> {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                resolvedCells.add(cellKey(world.getName(), cellX, cellZ));
                if (inserted) {
                    structurePlacer.place(world, waystone.x(), waystone.y(), waystone.z());
                    index(waystone);
                }
            });
            return inserted ? Optional.of(waystone) : Optional.empty();
        });
    }

    /**
     * Nombre de Waystones découvertes par ce joueur. Lecture pure (aucune écriture) — utilisé par le
     * preview du reset admin « nouveau joueur » pour annoncer ce qui serait effacé de
     * {@code waystone_discoveries} sans rien supprimer.
     */
    public CompletableFuture<Integer> discoveryCount(UUID playerId) {
        return repository.discoveriesFor(playerId).thenApply(Set::size);
    }

    public CompletableFuture<Integer> resetDiscoveries(UUID playerId) {
        return repository.deleteDiscoveries(playerId).thenApply(count -> {
            Set<String> cached = discoveriesByPlayer.get(playerId);
            if (cached != null) {
                cached.clear();
            }
            return count;
        });
    }

    // ---- Utilitaires -------------------------------------------------------------------------

    private static Iterable<long[]> cellsTouchedBy(int baseX, int baseZ, long cellSize) {
        List<long[]> cells = new ArrayList<>(4);
        for (int cx : new int[] {baseX, baseX + 15}) {
            for (int cz : new int[] {baseZ, baseZ + 15}) {
                long cellX = Math.floorDiv((long) cx, cellSize);
                long cellZ = Math.floorDiv((long) cz, cellSize);
                boolean known = cells.stream().anyMatch(p -> p[0] == cellX && p[1] == cellZ);
                if (!known) {
                    cells.add(new long[] {cellX, cellZ});
                }
            }
        }
        return cells;
    }

    private static String cellKey(String world, long cellX, long cellZ) {
        return world + "#" + cellX + "#" + cellZ;
    }

    private static String blockKey(String world, int x, int y, int z) {
        return world + ":" + x + "," + y + "," + z;
    }

    private static final class ChannelSession {
        private final UUID playerId;
        private final Location start;
        private final int total;
        private int elapsed;
        private BukkitTask task;

        private ChannelSession(UUID playerId, Location start, int total) {
            this.playerId = playerId;
            this.start = start;
            this.total = total;
        }
    }
}
