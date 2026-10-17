package com.lodygames.rpgquest.storage;

import com.lodygames.rpgquest.bootstrap.PluginService;
import com.lodygames.rpgquest.database.VaultAccessPointRepository;
import com.lodygames.rpgquest.database.VaultAccessPointRepository.StoredAccessPoint;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Container;
import org.bukkit.block.data.type.Chest;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.Plugin;
import org.slf4j.Logger;

/**
 * Coffres physiques des Hubs (issue #105) : registre {@code monde + position → VaultId}, persisté
 * dans {@code vault_access_points} et gardé en cache mémoire (lecture sur le thread principal à
 * chaque clic, jamais de SQL par événement).
 *
 * <p>Un point d'accès n'est qu'une <strong>porte</strong> : ouvrir le bloc ouvre le coffre
 * personnel {@link VaultId} de <em>celui qui clique</em> ({@link PersonalVaultService#open}). Le
 * contenu reste attaché à (UUID joueur, {@code vault_id}) ; lier, relier ou délier une position ne
 * lit ni n'écrit jamais {@code personal_vaults}. Plusieurs positions (un coffre par Hub) peuvent
 * viser le même {@code vault_id}.</p>
 *
 * <p>Seule la position déclarée par un administrateur ({@code /rpgadmin vault bind}) compte : un
 * coffre posé, copié ou déplacé par un joueur reste un coffre vanilla. Les liaisons sont refusées
 * dans le Wild et le monde des claims (pas d'accès distant au stockage personnel).</p>
 */
public final class VaultAccessPointService implements PluginService {

    /** Blocs pouvant servir de point d'accès : ceux qu'un joueur s'attend à « ouvrir ». */
    static final Set<Material> SUPPORTED_BLOCKS =
            Set.of(Material.CHEST, Material.TRAPPED_CHEST, Material.BARREL, Material.ENDER_CHEST);

    private final Plugin plugin;
    private final VaultAccessPointRepository repository;
    private final PersonalVaultService vaults;
    private final Supplier<Set<String>> forbiddenWorlds;
    private final Logger logger;
    private final VaultAccessListener accessListener;
    private final VaultAccessProtectionListener protectionListener;

    private final Map<BlockKey, VaultId> points = new ConcurrentHashMap<>();
    private volatile boolean loaded;

    public VaultAccessPointService(Plugin plugin, VaultAccessPointRepository repository, PersonalVaultService vaults,
                                   Supplier<Set<String>> forbiddenWorlds, Logger logger) {
        this.plugin = plugin;
        this.repository = repository;
        this.vaults = vaults;
        this.forbiddenWorlds = forbiddenWorlds;
        this.logger = logger;
        this.accessListener = new VaultAccessListener(this);
        this.protectionListener = new VaultAccessProtectionListener(this::isAccessPoint);
    }

    public enum BindOutcome {
        BOUND, REBOUND, ALREADY_BOUND, UNSUPPORTED_BLOCK, NOT_EMPTY, FORBIDDEN_WORLD, NOT_LOADED, FAILED
    }

    public enum UnbindOutcome {
        UNBOUND, NOT_BOUND, FAILED
    }

    /** Point d'accès tel qu'affiché par {@code /rpgadmin vault list}. */
    public record AccessPoint(String world, int x, int y, int z, VaultId vaultId) {
    }

    @Override
    public void start() {
        Bukkit.getPluginManager().registerEvents(accessListener, plugin);
        Bukkit.getPluginManager().registerEvents(protectionListener, plugin);
        reload();
    }

    @Override
    public void stop() {
        HandlerList.unregisterAll(accessListener);
        HandlerList.unregisterAll(protectionListener);
    }

    /** Recharge le cache depuis la base ; complété sur le thread principal. */
    CompletableFuture<Void> reload() {
        CompletableFuture<Void> done = new CompletableFuture<>();
        repository.findAll().thenAccept(stored -> Bukkit.getScheduler().runTask(plugin, () -> {
            points.clear();
            for (StoredAccessPoint point : stored) {
                try {
                    points.put(new BlockKey(point.world(), point.x(), point.y(), point.z()), VaultId.of(point.vaultId()));
                } catch (IllegalArgumentException invalid) {
                    logger.warn("Point d'accès de coffre ignoré ({} {} {} {}) : {}", point.world(), point.x(),
                            point.y(), point.z(), invalid.getMessage());
                }
            }
            loaded = true;
            done.complete(null);
        })).exceptionally(error -> {
            logger.error("Chargement des coffres physiques des Hubs impossible : ces blocs se comportent "
                    + "comme des coffres vanilla jusqu'au prochain démarrage.", error);
            done.completeExceptionally(error);
            return null;
        });
        return done;
    }

    // ---- Lecture (thread principal, cache seul) ----------------------------------------------

    boolean isLoaded() {
        return loaded;
    }

    public Optional<VaultId> vaultAt(Block block) {
        return Optional.ofNullable(points.get(BlockKey.of(block)));
    }

    public boolean isAccessPoint(Block block) {
        return points.containsKey(BlockKey.of(block));
    }

    public List<AccessPoint> all() {
        List<AccessPoint> list = new ArrayList<>();
        points.forEach((key, vaultId) -> list.add(new AccessPoint(key.world(), key.x(), key.y(), key.z(), vaultId)));
        list.sort(Comparator.comparing((AccessPoint p) -> p.vaultId().value())
                .thenComparing(AccessPoint::world).thenComparingInt(AccessPoint::x)
                .thenComparingInt(AccessPoint::y).thenComparingInt(AccessPoint::z));
        return list;
    }

    /** Ouvre, pour CE joueur, le coffre personnel derrière le point d'accès. */
    CompletableFuture<PersonalVaultService.OpenOutcome> open(Player player, VaultId vaultId) {
        return vaults.open(player, vaultId);
    }

    // ---- Administration -------------------------------------------------------------------------

    /**
     * Lie le bloc (et l'autre moitié d'un coffre double) à {@code vaultId}. À appeler sur le thread
     * principal ; le cache n'est mis à jour qu'après confirmation de la base.
     */
    public CompletableFuture<BindOutcome> bind(Block block, VaultId vaultId, UUID admin) {
        if (!loaded) {
            return CompletableFuture.completedFuture(BindOutcome.NOT_LOADED);
        }
        if (!SUPPORTED_BLOCKS.contains(block.getType())) {
            return CompletableFuture.completedFuture(BindOutcome.UNSUPPORTED_BLOCK);
        }
        if (forbiddenWorlds.get().contains(block.getWorld().getName())) {
            return CompletableFuture.completedFuture(BindOutcome.FORBIDDEN_WORLD);
        }
        // Le contenu physique du bloc n'est plus jamais montré une fois lié : refuser plutôt que de
        // laisser des objets invisibles (et les joueurs croire qu'ils sont dans leur coffre).
        if (block.getState() instanceof Container container && !container.getInventory().isEmpty()) {
            return CompletableFuture.completedFuture(BindOutcome.NOT_EMPTY);
        }
        List<BlockKey> keys = new ArrayList<>();
        keys.add(BlockKey.of(block));
        partnerOf(block).ifPresent(partner -> keys.add(BlockKey.of(partner)));

        VaultId previous = points.get(keys.get(0));
        if (vaultId.equals(previous) && keys.stream().allMatch(k -> vaultId.equals(points.get(k)))) {
            return CompletableFuture.completedFuture(BindOutcome.ALREADY_BOUND);
        }
        BindOutcome success = previous == null ? BindOutcome.BOUND : BindOutcome.REBOUND;
        CompletableFuture<?>[] writes = keys.stream()
                .map(k -> repository.save(new StoredAccessPoint(k.world(), k.x(), k.y(), k.z(), vaultId.value()), admin))
                .toArray(CompletableFuture[]::new);
        return onMainThread(CompletableFuture.allOf(writes), () -> {
            keys.forEach(k -> points.put(k, vaultId));
            return success;
        }, BindOutcome.FAILED, "liaison");
    }

    /** Délie une position (et l'autre moitié liée d'un coffre double, si le bloc existe encore). */
    public CompletableFuture<UnbindOutcome> unbind(Block block) {
        List<BlockKey> keys = new ArrayList<>();
        keys.add(BlockKey.of(block));
        partnerOf(block).map(BlockKey::of).filter(points::containsKey).ifPresent(keys::add);
        if (!points.containsKey(keys.get(0))) {
            return CompletableFuture.completedFuture(UnbindOutcome.NOT_BOUND);
        }
        CompletableFuture<?>[] deletes = keys.stream()
                .map(k -> repository.delete(k.world(), k.x(), k.y(), k.z()))
                .toArray(CompletableFuture[]::new);
        return onMainThread(CompletableFuture.allOf(deletes), () -> {
            keys.forEach(points::remove);
            return UnbindOutcome.UNBOUND;
        }, UnbindOutcome.FAILED, "suppression");
    }

    private <T> CompletableFuture<T> onMainThread(CompletableFuture<?> write, Supplier<T> onSuccess,
                                                  T onFailure, String operation) {
        CompletableFuture<T> result = new CompletableFuture<>();
        write.whenComplete((ignored, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (error != null) {
                logger.error("Échec de la {} d'un point d'accès de coffre personnel.", operation, error);
                result.complete(onFailure);
            } else {
                result.complete(onSuccess.get());
            }
        }));
        return result;
    }

    /**
     * Autre moitié d'un coffre double, déduite des seules données de bloc (type LEFT/RIGHT et
     * orientation), comme le jeu : LEFT → à droite de l'orientation, RIGHT → à gauche.
     */
    static Optional<Block> partnerOf(Block block) {
        if (!(block.getBlockData() instanceof Chest chest) || chest.getType() == Chest.Type.SINGLE) {
            return Optional.empty();
        }
        BlockFace facing = chest.getFacing();
        BlockFace side = chest.getType() == Chest.Type.LEFT ? clockwise(facing) : clockwise(clockwise(clockwise(facing)));
        Block partner = block.getRelative(side);
        return partner.getType() == block.getType() ? Optional.of(partner) : Optional.empty();
    }

    private static BlockFace clockwise(BlockFace face) {
        return switch (face) {
            case NORTH -> BlockFace.EAST;
            case EAST -> BlockFace.SOUTH;
            case SOUTH -> BlockFace.WEST;
            case WEST -> BlockFace.NORTH;
            default -> face;
        };
    }

    record BlockKey(String world, int x, int y, int z) {
        static BlockKey of(Block block) {
            return new BlockKey(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
        }
    }
}
