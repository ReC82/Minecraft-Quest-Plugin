package com.lodygames.rpgquest.claim.growth;

import com.lodygames.rpgquest.bootstrap.PluginService;
import com.lodygames.rpgquest.claim.model.Claim;
import com.lodygames.rpgquest.config.ClaimConfig;
import com.lodygames.rpgquest.config.OfflineGrowthConfig;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.GameRules;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.slf4j.Logger;

/**
 * Issue #776 : croissance des plantations des claims sans joueur à proximité, <strong>sans</strong>
 * garder de chunk chargé.
 *
 * <ol>
 *   <li>Au déchargement d'un chunk qui touche un claim d'un monde concerné, l'heure de jeu
 *       ({@link World#getGameTime()}, qui n'avance que serveur allumé et ignore {@code /time set}) est
 *       écrite dans le PDC du chunk — sauvegardée avec ses blocs, donc cohérente après un crash.</li>
 *   <li>Au rechargement, la marque est lue puis <strong>retirée aussitôt</strong> : un second
 *       chargement ne rattrape rien (idempotence). La durée est plafonnée par {@code
 *       max-catch-up-minutes}, convertie en random ticks moyens ({@code randomTickSpeed} du monde).</li>
 *   <li>Le chunk est mis en file ; à chaque tick, au plus un chunk est inventorié (instantané, cuboïdes
 *       de claims seulement) et au plus {@code blocks-per-tick} plantes sont traitées par {@link
 *       PlantGrowthSimulator}. Un chunk redéchargé avant la fin abandonne simplement le reste.</li>
 * </ol>
 *
 * <p>Aucun scan global : seuls les chunks rechargés et marqués sont visités. Un serveur arrêté ne
 * marque rien (pas de croissance pendant l'arrêt, comme en jeu) ; les chunks déchargés avant l'arrêt
 * gardent leur marque et ne rattrapent que le temps de jeu réellement écoulé.</p>
 */
public final class OfflineGrowthService implements PluginService, Listener {

    static final String KEY_NAME = "offline_growth_since";
    /** Au-delà (temps cumulé sur tous ses ticks), le coût d'un chunk est journalisé en INFO. */
    private static final long SLOW_CHUNK_NANOS = 20_000_000L;

    private final Plugin plugin;
    private final Supplier<ClaimConfig> config;
    private final Function<String, List<Claim>> claimsInWorld;
    private final PlantGrowthSimulator simulator;
    private final NamespacedKey sinceKey;
    private final Logger logger;
    private final Deque<ChunkJob> jobs = new ArrayDeque<>();
    private BukkitTask task;
    private long chunksProcessed;
    private long plantsVisited;
    private long blocksChanged;
    private long nanosSpent;

    public OfflineGrowthService(Plugin plugin, Supplier<ClaimConfig> config,
                                Function<String, List<Claim>> claimsInWorld, PlantGrowthSimulator simulator,
                                Logger logger) {
        this.plugin = plugin;
        this.config = config;
        this.claimsInWorld = claimsInWorld;
        this.simulator = simulator;
        this.sinceKey = new NamespacedKey(plugin, KEY_NAME);
        this.logger = logger;
    }

    @Override
    public void start() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::processBatch, 1L, 1L);
    }

    @Override
    public void stop() {
        HandlerList.unregisterAll(this);
        if (task != null) {
            task.cancel();
            task = null;
        }
        jobs.clear(); // marques déjà consommées : le reste est perdu, jamais rejoué en double
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnload(ChunkUnloadEvent event) {
        Chunk chunk = event.getChunk();
        if (!appliesTo(chunk.getWorld()) || claimsTouching(chunk).isEmpty()) {
            return;
        }
        chunk.getPersistentDataContainer().set(sinceKey, PersistentDataType.LONG, chunk.getWorld().getGameTime());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        Chunk chunk = event.getChunk();
        PersistentDataContainer pdc = chunk.getPersistentDataContainer();
        Long since = pdc.get(sinceKey, PersistentDataType.LONG);
        if (since == null) {
            return;
        }
        pdc.remove(sinceKey); // consommée une seule fois, même si le rattrapage est ensuite ignoré
        World world = chunk.getWorld();
        if (!appliesTo(world)) {
            return;
        }
        long elapsed = Math.min(world.getGameTime() - since, config.get().offlineGrowth().maxCatchUpTicks());
        Integer speed = world.getGameRuleValue(GameRules.RANDOM_TICK_SPEED);
        List<Claim> claims = claimsTouching(chunk);
        if (elapsed <= 0 || speed == null || speed <= 0 || claims.isEmpty()) {
            return;
        }
        jobs.add(new ChunkJob(world, chunk.getX(), chunk.getZ(), claims,
                PlantGrowthSimulator.expectedRandomTicks(elapsed, speed)));
    }

    /** Un tick : au plus un inventaire de chunk et {@code blocks-per-tick} plantes. */
    void processBatch() {
        int budget = config.get().offlineGrowth().blocksPerTick();
        boolean scanned = false;
        while (budget > 0 && !jobs.isEmpty()) {
            ChunkJob job = jobs.peek();
            if (!job.world.isChunkLoaded(job.chunkX, job.chunkZ)) {
                jobs.poll(); // redéchargé entre-temps : sa nouvelle marque repart de maintenant
                continue;
            }
            long start = System.nanoTime();
            if (job.plants == null) {
                if (scanned) {
                    return;
                }
                job.plants = scan(job);
                scanned = true;
            }
            while (budget > 0 && job.next < job.plants.size()) {
                PlantPosition position = job.plants.get(job.next++);
                Block block = job.world.getBlockAt(position.x, position.y, position.z);
                if (PlantGrowthSimulator.PLANTS.contains(block.getType())) {
                    job.changed += simulator.simulate(block, position.claim, job.randomTicks);
                }
                budget--;
            }
            job.nanos += System.nanoTime() - start;
            if (job.next < job.plants.size()) {
                return;
            }
            jobs.poll();
            finish(job);
        }
    }

    private void finish(ChunkJob job) {
        chunksProcessed++;
        plantsVisited += job.plants.size();
        blocksChanged += job.changed;
        nanosSpent += job.nanos;
        String message = "Croissance hors ligne : chunk {} ({}, {}) — {} plante(s), {} bloc(s) modifié(s), {} µs.";
        Object[] args = {job.world.getName(), job.chunkX, job.chunkZ, job.plants.size(), job.changed, job.nanos / 1000};
        if (job.nanos > SLOW_CHUNK_NANOS) {
            logger.info(message, args); // visible sans mode debug : coût à surveiller
        } else {
            logger.debug(message, args);
        }
    }

    /** Inventaire des plantes dans les cuboïdes de claims du chunk, sur un instantané (lecture seule). */
    private List<PlantPosition> scan(ChunkJob job) {
        ChunkSnapshot snapshot = job.world.getChunkAt(job.chunkX, job.chunkZ).getChunkSnapshot(false, false, false);
        int baseX = job.chunkX << 4;
        int baseZ = job.chunkZ << 4;
        List<PlantPosition> plants = new ArrayList<>();
        for (Claim claim : job.claims) {
            int minY = Math.max(claim.minY(), job.world.getMinHeight());
            int maxY = Math.min(claim.maxY(), job.world.getMaxHeight() - 1);
            for (int x = Math.max(claim.minX(), baseX); x <= Math.min(claim.maxX(), baseX + 15); x++) {
                for (int z = Math.max(claim.minZ(), baseZ); z <= Math.min(claim.maxZ(), baseZ + 15); z++) {
                    for (int y = minY; y <= maxY; y++) {
                        if (PlantGrowthSimulator.PLANTS.contains(snapshot.getBlockType(x - baseX, y, z - baseZ))) {
                            plants.add(new PlantPosition(x, y, z, claim));
                        }
                    }
                }
            }
        }
        return plants;
    }

    private boolean appliesTo(World world) {
        ClaimConfig claims = config.get();
        OfflineGrowthConfig growth = claims.offlineGrowth();
        return growth.appliesTo(world.getName(), claims.world());
    }

    private List<Claim> claimsTouching(Chunk chunk) {
        int minX = chunk.getX() << 4;
        int minZ = chunk.getZ() << 4;
        return claimsInWorld.apply(chunk.getWorld().getName()).stream()
                .filter(claim -> claim.maxX() >= minX && claim.minX() <= minX + 15
                        && claim.maxZ() >= minZ && claim.minZ() <= minZ + 15)
                .toList();
    }

    /** Chunks en attente de rattrapage (diagnostic et tests). */
    public int pendingChunks() {
        return jobs.size();
    }

    /** Coût cumulé depuis le démarrage : chunks, plantes visitées, blocs modifiés, temps total. */
    public Stats stats() {
        return new Stats(chunksProcessed, plantsVisited, blocksChanged, nanosSpent);
    }

    public record Stats(long chunks, long plants, long blocksChanged, long nanos) {
    }

    private record PlantPosition(int x, int y, int z, Claim claim) {
    }

    private static final class ChunkJob {
        private final World world;
        private final int chunkX;
        private final int chunkZ;
        private final List<Claim> claims;
        private final double randomTicks;
        private List<PlantPosition> plants;
        private int next;
        private int changed;
        private long nanos;

        private ChunkJob(World world, int chunkX, int chunkZ, List<Claim> claims, double randomTicks) {
            this.world = world;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.claims = claims;
            this.randomTicks = randomTicks;
        }
    }
}
