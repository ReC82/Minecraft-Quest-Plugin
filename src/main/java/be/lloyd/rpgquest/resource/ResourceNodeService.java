package be.lloyd.rpgquest.resource;

import be.lloyd.rpgquest.bootstrap.PluginService;
import be.lloyd.rpgquest.database.ResourceNodeRecord;
import be.lloyd.rpgquest.database.ResourceNodeRepository;
import be.lloyd.rpgquest.item.YamlCustomItemRegistry;
import be.lloyd.rpgquest.resource.model.CustomItemDrop;
import be.lloyd.rpgquest.resource.model.ResourceDrop;
import be.lloyd.rpgquest.resource.model.ResourceNodeDefinition;
import be.lloyd.rpgquest.resource.model.VanillaItemDrop;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.function.LongSupplier;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.slf4j.Logger;

/**
 * Possède l'état runtime des nœuds de ressource <b>placés</b> (position →
 * type + cooldown de respawn), séparé de {@link ResourceNodeRegistry} (qui ne
 * connaît que les types). Un nœud est identifié par sa position exacte, jamais
 * par le bloc qui s'y trouve physiquement : deux blocs identiques côte à côte
 * ne sont pas confondus, et un bloc modifié manuellement à cette position est
 * détecté (voir {@link #handleBreak}).
 *
 * <p>{@code clock}/{@code worldResolver}/{@code chunkLoadedChecker} sont
 * injectables (comme le {@code LongSupplier} de {@code CooldownManager}) pour
 * rendre {@link #sweepRespawns} déterministe et testable sans dépendre du
 * comportement de monde/chunk simulé par MockBukkit.</p>
 */
public final class ResourceNodeService implements PluginService {

    private static final long SWEEP_PERIOD_TICKS = 20L * 5; // 5 s : assez fin pour un respawn perçu comme réactif.
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final Plugin plugin;
    private final ResourceNodeRegistry typeRegistry;
    private final ResourceNodeRepository repository;
    private final YamlCustomItemRegistry customItemRegistry;
    private final Logger logger;
    private final LongSupplier clock;
    private final Function<String, World> worldResolver;
    private final ChunkLoadedChecker chunkLoadedChecker;

    private final ConcurrentHashMap<NodeKey, NodeState> nodes = new ConcurrentHashMap<>();
    private BukkitTask sweepTask;

    public ResourceNodeService(Plugin plugin, ResourceNodeRegistry typeRegistry, ResourceNodeRepository repository,
                                YamlCustomItemRegistry customItemRegistry, Logger logger) {
        this(plugin, typeRegistry, repository, customItemRegistry, logger,
                System::currentTimeMillis, Bukkit::getWorld, ChunkLoadedChecker.DEFAULT);
    }

    ResourceNodeService(Plugin plugin, ResourceNodeRegistry typeRegistry, ResourceNodeRepository repository,
                         YamlCustomItemRegistry customItemRegistry, Logger logger, LongSupplier clock,
                         Function<String, World> worldResolver, ChunkLoadedChecker chunkLoadedChecker) {
        this.plugin = plugin;
        this.typeRegistry = typeRegistry;
        this.repository = repository;
        this.customItemRegistry = customItemRegistry;
        this.logger = logger;
        this.clock = clock;
        this.worldResolver = worldResolver;
        this.chunkLoadedChecker = chunkLoadedChecker;
    }

    @Override
    public void start() {
        repository.findAll().thenAccept(records -> Bukkit.getScheduler().runTask(plugin, () -> {
            for (ResourceNodeRecord record : records) {
                NamespacedKey typeId = NamespacedKey.fromString(record.typeId());
                if (typeId == null) {
                    continue;
                }
                Long depletedUntil = record.depletedAt() == null ? null : record.depletedAt().toEpochMilli();
                nodes.put(new NodeKey(record.world(), record.x(), record.y(), record.z()), new NodeState(typeId, depletedUntil));
            }
            logger.info("Nœuds de ressource restaurés depuis la base : {}.", nodes.size());
        })).exceptionally(error -> {
            logger.error("Impossible de charger les nœuds de ressource persistés.", error);
            return null;
        });

        sweepTask = Bukkit.getScheduler().runTaskTimer(
                plugin, () -> sweepRespawns(clock.getAsLong()), SWEEP_PERIOD_TICKS, SWEEP_PERIOD_TICKS);
    }

    @Override
    public void stop() {
        if (sweepTask != null) {
            sweepTask.cancel();
            sweepTask = null;
        }
    }

    public enum CreateOutcome {
        CREATED, UNKNOWN_TYPE, ALREADY_A_NODE
    }

    public record NodeInfo(NamespacedKey typeId, boolean depleted, long remainingRespawnSeconds) {
    }

    /** Enregistre un nouveau nœud à {@code block} et pose le bloc actif du type. Appelé sur le thread principal (commande). */
    public CreateOutcome createNode(Block block, NamespacedKey typeId) {
        NodeKey key = keyOf(block);
        if (nodes.containsKey(key)) {
            return CreateOutcome.ALREADY_A_NODE;
        }
        Optional<ResourceNodeDefinition> type = typeRegistry.find(typeId);
        if (type.isEmpty()) {
            return CreateOutcome.UNKNOWN_TYPE;
        }

        nodes.put(key, new NodeState(typeId, null));
        block.setType(type.get().activeMaterial());
        repository.upsert(new ResourceNodeRecord(key.world(), key.x(), key.y(), key.z(), typeId.toString(), null));
        return CreateOutcome.CREATED;
    }

    /** Retire le suivi du nœud à {@code block} (ne touche pas au bloc physique : laissé à l'administrateur). */
    public boolean removeNode(Block block) {
        NodeKey key = keyOf(block);
        if (nodes.remove(key) == null) {
            return false;
        }
        repository.delete(key.world(), key.x(), key.y(), key.z());
        return true;
    }

    public Optional<NodeInfo> inspectNode(Block block) {
        NodeState state = nodes.get(keyOf(block));
        if (state == null) {
            return Optional.empty();
        }
        long now = clock.getAsLong();
        Long depletedUntil = state.depletedUntilMillis;
        boolean depleted = depletedUntil != null && now < depletedUntil;
        long remaining = depleted ? Math.max(0L, (depletedUntil - now + 999) / 1000) : 0L;
        return Optional.of(new NodeInfo(state.typeId, depleted, remaining));
    }

    /**
     * Traite un {@link BlockBreakEvent} sur un bloc potentiellement suivi comme nœud. Ne fait rien si
     * la position n'est pas un nœud connu (laisse le comportement vanilla intact).
     */
    public void handleBreak(BlockBreakEvent event) {
        if (event.isCancelled()) {
            return;
        }
        Block block = event.getBlock();
        NodeKey key = keyOf(block);
        NodeState state = nodes.get(key);
        if (state == null) {
            return;
        }

        Optional<ResourceNodeDefinition> typeOpt = typeRegistry.find(state.typeId);
        if (typeOpt.isEmpty()) {
            // Le type a été retiré (reload) : on ne fait plus rien de spécial ici, vanilla reprend la main.
            logger.debug("Nœud à {} référence un type inconnu « {} » (retiré ?), ignoré.", key, state.typeId);
            return;
        }
        ResourceNodeDefinition type = typeOpt.get();

        if (block.getType() != type.activeMaterial() && block.getType() != type.depletedMaterial()) {
            // Le bloc physique a été modifié par autre chose (WorldEdit, un joueur...) : on laisse
            // vanilla gérer ce cassage normalement plutôt que de deviner, mais le nœud reste suivi.
            logger.debug("Bloc physique à {} ne correspond plus au type « {} » attendu, ignoré.", key, state.typeId);
            return;
        }

        Player player = event.getPlayer();
        long now = clock.getAsLong();
        Long depletedUntil = state.depletedUntilMillis;
        if (depletedUntil != null && now < depletedUntil) {
            event.setCancelled(true);
            sendMessage(player, "<gray>Ce nœud de ressource est épuisé, revenez plus tard.</gray>");
            return;
        }

        if (!type.anyToolAllowed()) {
            ItemStack tool = player.getInventory().getItemInMainHand();
            if (!type.requiredTools().contains(tool.getType())) {
                event.setCancelled(true);
                sendMessage(player, "<red>Cet outil ne permet pas de récolter ce nœud.</red>");
                return;
            }
        }

        // Anti double-cassage : on marque le nœud épuisé de façon synchrone, avant tout autre
        // traitement (drop, persistance async) — un second BlockBreakEvent sur la même position
        // (même joueur ou un autre) voit déjà l'état épuisé et est annulé par la branche ci-dessus.
        long deadline = now + type.respawnSeconds() * 1000L;
        state.depletedUntilMillis = deadline;

        // On prend le contrôle total du cassage plutôt que de laisser vanilla le traiter : poser le
        // bloc épuisé dans le même handler serait immédiatement écrasé par le post-traitement vanilla
        // (qui mettrait AIR) si l'événement n'était pas annulé.
        event.setCancelled(true);

        Location dropLocation = block.getLocation().add(0.5, 0.5, 0.5);
        block.setType(type.depletedMaterial());
        rollDrop(type).ifPresent(stack -> block.getWorld().dropItemNaturally(dropLocation, stack));

        repository.updateDepletedAt(key.world(), key.x(), key.y(), key.z(), Instant.ofEpochMilli(deadline));
    }

    /**
     * Restaure le bloc actif de chaque nœud épuisé dont le cooldown est passé, mais seulement si son
     * monde existe et son chunk est actuellement chargé (jamais de chargement forcé). Pure logique
     * (pas d'accès Bukkit hors {@code worldResolver}/{@code chunkLoadedChecker} injectés) : appelable
     * directement en test avec une horloge/un monde simulés. @return le nombre de nœuds respawnés.
     */
    int sweepRespawns(long nowMillis) {
        int respawned = 0;
        for (var entry : nodes.entrySet()) {
            NodeKey key = entry.getKey();
            NodeState state = entry.getValue();
            Long depletedUntil = state.depletedUntilMillis;
            if (depletedUntil == null || nowMillis < depletedUntil) {
                continue;
            }

            World world = worldResolver.apply(key.world());
            if (world == null) {
                continue; // monde absent (supprimé/renommé) : le nœud reste suivi, respawn différé indéfiniment.
            }
            int chunkX = key.x() >> 4;
            int chunkZ = key.z() >> 4;
            if (!chunkLoadedChecker.isChunkLoaded(world, chunkX, chunkZ)) {
                continue; // jamais de chargement forcé : on attend que le chunk le soit naturellement.
            }

            Optional<ResourceNodeDefinition> type = typeRegistry.find(state.typeId);
            if (type.isPresent()) {
                Block block = world.getBlockAt(key.x(), key.y(), key.z());
                if (block.getType() == type.get().depletedMaterial()) {
                    block.setType(type.get().activeMaterial());
                }
            }

            state.depletedUntilMillis = null;
            repository.updateDepletedAt(key.world(), key.x(), key.y(), key.z(), null);
            respawned++;
        }
        return respawned;
    }

    private Optional<ItemStack> rollDrop(ResourceNodeDefinition type) {
        List<ResourceDrop> drops = type.drops();
        int roll = ThreadLocalRandom.current().nextInt(type.totalWeight());
        int cumulative = 0;
        ResourceDrop chosen = drops.get(drops.size() - 1);
        for (ResourceDrop drop : drops) {
            cumulative += drop.weight();
            if (roll < cumulative) {
                chosen = drop;
                break;
            }
        }

        int amount = chosen.minAmount() == chosen.maxAmount()
                ? chosen.minAmount()
                : ThreadLocalRandom.current().nextInt(chosen.minAmount(), chosen.maxAmount() + 1);

        return switch (chosen) {
            case CustomItemDrop customItemDrop -> customItemRegistry.create(customItemDrop.itemId(), amount);
            case VanillaItemDrop vanillaItemDrop -> Optional.of(new ItemStack(vanillaItemDrop.material(), amount));
        };
    }

    private void sendMessage(Player player, String miniMessage) {
        if (player != null) {
            player.sendMessage(MM.deserialize(miniMessage));
        }
    }

    private NodeKey keyOf(Block block) {
        return new NodeKey(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
    }

    /** Test uniquement : injecte un nœud directement en mémoire sans passer par {@link #createNode}. */
    void putForTest(NodeKey key, NamespacedKey typeId, Long depletedUntilMillis) {
        nodes.put(key, new NodeState(typeId, depletedUntilMillis));
    }

    /** Test uniquement : lit l'état brut d'un nœud. */
    NodeState getForTest(NodeKey key) {
        return nodes.get(key);
    }

    int trackedCount() {
        return nodes.size();
    }

    record NodeKey(String world, int x, int y, int z) {
    }

    static final class NodeState {
        private final NamespacedKey typeId;
        private volatile Long depletedUntilMillis;

        NodeState(NamespacedKey typeId, Long depletedUntilMillis) {
            this.typeId = typeId;
            this.depletedUntilMillis = depletedUntilMillis;
        }

        NamespacedKey typeId() {
            return typeId;
        }

        Long depletedUntilMillis() {
            return depletedUntilMillis;
        }
    }
}
