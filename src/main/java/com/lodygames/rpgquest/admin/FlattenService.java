package com.lodygames.rpgquest.admin;

import com.lodygames.rpgquest.bootstrap.PluginService;
import com.lodygames.rpgquest.config.AdminFlattenConfig;
import com.lodygames.rpgquest.config.ConfigService;
import com.lodygames.rpgquest.config.FlattenShape;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.slf4j.Logger;

/**
 * Cœur de {@code /rpgadmin flatten} : aplatit une zone (carrée ou circulaire)
 * centrée sur l'administrateur, en traitant un nombre borné de blocs par
 * tick (jamais tout d'un coup) pour ne jamais geler le serveur, avec
 * aperçu/estimation avant exécution, confirmation à délai d'expiration,
 * annulation, et un unique niveau d'annulation (« undo ») par joueur.
 *
 * <p>{@code clock} est injectable (comme {@code CooldownManager}) pour
 * rendre l'expiration de la confirmation testable sans dépendre du temps
 * réel.</p>
 */
public final class FlattenService implements PluginService {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final long PROGRESS_REPORT_PERIOD_TICKS = 20L; // ~1s : évite le spam, reste réactif.

    private final Plugin plugin;
    private final ConfigService configService;
    private final Logger logger;
    private final LongSupplier clock;

    private final Map<UUID, PendingPreview> pending = new ConcurrentHashMap<>();
    private final Map<UUID, ActiveOperation> active = new ConcurrentHashMap<>();
    private final Map<UUID, UndoRecord> undoData = new ConcurrentHashMap<>();

    public FlattenService(Plugin plugin, ConfigService configService, Logger logger) {
        this(plugin, configService, logger, System::currentTimeMillis);
    }

    FlattenService(Plugin plugin, ConfigService configService, Logger logger, LongSupplier clock) {
        this.plugin = plugin;
        this.configService = configService;
        this.logger = logger;
        this.clock = clock;
    }

    @Override
    public void start() {
        // Rien à démarrer : les tâches batch sont créées à la demande (confirm()).
    }

    @Override
    public void stop() {
        for (ActiveOperation operation : active.values()) {
            operation.task.cancel();
        }
        active.clear();
        pending.clear();
    }

    public enum PreviewOutcome {
        CREATED, INVALID_RADIUS, INVALID_HEIGHT, FORBIDDEN_WORLD, ALREADY_PENDING, ALREADY_ACTIVE
    }

    public record PreviewResult(PreviewOutcome outcome, FlattenEstimate estimate) {
        static PreviewResult of(PreviewOutcome outcome) {
            return new PreviewResult(outcome, null);
        }
    }

    public enum ConfirmOutcome {
        STARTED, NO_PENDING, EXPIRED, ALREADY_ACTIVE
    }

    public enum CancelOutcome {
        CANCELLED_PENDING, CANCELLED_ACTIVE, NOTHING_TO_CANCEL
    }

    public enum UndoOutcome {
        UNDONE, NOTHING_TO_UNDO, OPERATION_IN_PROGRESS
    }

    /** Calcule un aperçu sans toucher au monde. Remplace tout aperçu en attente non confirmé. */
    public PreviewResult preview(Player player, int radius, Integer explicitY) {
        UUID playerId = player.getUniqueId();
        if (active.containsKey(playerId)) {
            return PreviewResult.of(PreviewOutcome.ALREADY_ACTIVE);
        }

        AdminFlattenConfig config = configService.current().adminFlatten();
        if (radius <= 0 || radius > config.maxRadius()) {
            return PreviewResult.of(PreviewOutcome.INVALID_RADIUS);
        }

        World world = player.getWorld();
        if (config.forbiddenWorlds().contains(world.getName())) {
            return PreviewResult.of(PreviewOutcome.FORBIDDEN_WORLD);
        }

        int y = explicitY != null ? explicitY : player.getLocation().getBlockY() - 1;
        if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
            return PreviewResult.of(PreviewOutcome.INVALID_HEIGHT);
        }

        List<int[]> columns = columnOffsets(config.defaultShape(), radius);
        long estimate = (long) columns.size() * (config.subLayerDepth() + 1L + config.clearAboveHeight());

        PendingPreview preview = new PendingPreview(
                world.getName(), player.getLocation().getBlockX(), player.getLocation().getBlockZ(), y,
                config.defaultShape(), radius, columns, clock.getAsLong() + config.confirmationTimeoutSeconds() * 1000L);
        pending.put(playerId, preview);

        return new PreviewResult(PreviewOutcome.CREATED,
                new FlattenEstimate(config.defaultShape(), radius, y, columns.size(), estimate));
    }

    public ConfirmOutcome confirm(Player player) {
        UUID playerId = player.getUniqueId();
        if (active.containsKey(playerId)) {
            return ConfirmOutcome.ALREADY_ACTIVE;
        }
        PendingPreview preview = pending.get(playerId);
        if (preview == null) {
            return ConfirmOutcome.NO_PENDING;
        }
        if (clock.getAsLong() >= preview.expiresAtMillis) {
            pending.remove(playerId);
            return ConfirmOutcome.EXPIRED;
        }
        pending.remove(playerId);

        World world = Bukkit.getWorld(preview.world);
        if (world == null) {
            return ConfirmOutcome.NO_PENDING; // monde disparu entre l'aperçu et la confirmation.
        }

        ActiveOperation operation = new ActiveOperation(playerId, world, preview);
        operation.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> processTick(operation), 1L, 1L);
        active.put(playerId, operation);
        return ConfirmOutcome.STARTED;
    }

    public CancelOutcome cancel(Player player) {
        UUID playerId = player.getUniqueId();
        if (pending.remove(playerId) != null) {
            return CancelOutcome.CANCELLED_PENDING;
        }
        ActiveOperation operation = active.remove(playerId);
        if (operation != null) {
            operation.task.cancel();
            finalizeUndoRecord(operation);
            return CancelOutcome.CANCELLED_ACTIVE;
        }
        return CancelOutcome.NOTHING_TO_CANCEL;
    }

    public UndoOutcome undo(Player player) {
        UUID playerId = player.getUniqueId();
        if (active.containsKey(playerId)) {
            return UndoOutcome.OPERATION_IN_PROGRESS;
        }
        UndoRecord record = undoData.remove(playerId);
        if (record == null || record.entries.isEmpty()) {
            return UndoOutcome.NOTHING_TO_UNDO;
        }
        World world = Bukkit.getWorld(record.world);
        if (world == null) {
            return UndoOutcome.NOTHING_TO_UNDO; // monde disparu depuis l'opération : rien à restaurer.
        }
        // Ordre inverse : restaure dans l'ordre inverse d'écriture, sans conséquence pratique ici
        // (positions toutes distinctes) mais garde le principe "annuler = dérouler à l'envers".
        for (int i = record.entries.size() - 1; i >= 0; i--) {
            UndoEntry entry = record.entries.get(i);
            world.getBlockAt(entry.x, entry.y, entry.z).setBlockData(entry.previous, false);
        }
        return UndoOutcome.UNDONE;
    }

    boolean hasActiveOperation(UUID playerId) {
        return active.containsKey(playerId);
    }

    boolean hasPendingPreview(UUID playerId) {
        return pending.containsKey(playerId);
    }

    private void processTick(ActiveOperation operation) {
        AdminFlattenConfig config = configService.current().adminFlatten();
        int budget = config.blocksPerTick();
        int used = 0;
        while (used < budget && operation.columnIndex < operation.columns.size()) {
            int[] offset = operation.columns.get(operation.columnIndex++);
            used += flattenColumn(operation, config, operation.centerX + offset[0], operation.centerZ + offset[1]);
        }

        boolean finished = operation.columnIndex >= operation.columns.size();
        operation.ticksSinceReport++;
        if (finished || operation.ticksSinceReport >= PROGRESS_REPORT_PERIOD_TICKS) {
            operation.ticksSinceReport = 0;
            reportProgress(operation);
        }

        if (finished) {
            operation.task.cancel();
            active.remove(operation.playerId);
            finalizeUndoRecord(operation);
            Player player = Bukkit.getPlayer(operation.playerId);
            if (player != null && player.isOnline()) {
                player.sendMessage(MM.deserialize("<green>Aplatissement terminé (<count> colonnes).</green>",
                        Placeholder.unparsed("count", String.valueOf(operation.columns.size()))));
            }
        }
    }

    private int flattenColumn(ActiveOperation operation, AdminFlattenConfig config, int x, int z) {
        World world = operation.world;
        int y = operation.y;
        int writes = 0;

        int clearTop = Math.min(y + config.clearAboveHeight(), world.getMaxHeight() - 1);
        for (int cy = y + 1; cy <= clearTop; cy++) {
            writes += setBlockIfChanged(operation, world, x, cy, z, Material.AIR);
        }

        int subBottom = Math.max(y - config.subLayerDepth(), world.getMinHeight());
        for (int cy = y - 1; cy >= subBottom; cy--) {
            writes += setBlockIfChanged(operation, world, x, cy, z, config.subLayerMaterial());
        }

        writes += setBlockIfChanged(operation, world, x, y, z, config.topLayerMaterial());
        return writes;
    }

    private int setBlockIfChanged(ActiveOperation operation, World world, int x, int y, int z, Material material) {
        Block block = world.getBlockAt(x, y, z);
        if (block.getType() == material) {
            return 0;
        }
        operation.undoEntries.add(new UndoEntry(x, y, z, block.getBlockData()));
        block.setType(material, false); // pas de mise à jour physique : évite les cascades (sable, redstone...).
        return 1;
    }

    private void finalizeUndoRecord(ActiveOperation operation) {
        if (operation.undoEntries.isEmpty()) {
            return;
        }
        undoData.put(operation.playerId, new UndoRecord(operation.world.getName(), operation.undoEntries));
    }

    private void reportProgress(ActiveOperation operation) {
        Player player = Bukkit.getPlayer(operation.playerId);
        if (player == null || !player.isOnline()) {
            return;
        }
        int percent = operation.columns.isEmpty() ? 100
                : (int) (100L * operation.columnIndex / operation.columns.size());
        player.sendActionBar(MM.deserialize(
                "<yellow>Aplatissement :</yellow> <white><percent>%</white> <gray>(<done>/<total> colonnes)</gray>",
                Placeholder.unparsed("percent", String.valueOf(percent)),
                Placeholder.unparsed("done", String.valueOf(operation.columnIndex)),
                Placeholder.unparsed("total", String.valueOf(operation.columns.size()))));
    }

    /** Décalages (dx, dz) réellement concernés par la forme, calculés une seule fois par aperçu. */
    private List<int[]> columnOffsets(FlattenShape shape, int radius) {
        List<int[]> offsets = new ArrayList<>();
        long radiusSquared = (long) radius * radius;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (shape == FlattenShape.CIRCLE && (long) dx * dx + (long) dz * dz > radiusSquared) {
                    continue;
                }
                offsets.add(new int[]{dx, dz});
            }
        }
        return offsets;
    }

    private record UndoEntry(int x, int y, int z, BlockData previous) {
    }

    private record UndoRecord(String world, List<UndoEntry> entries) {
    }

    private static final class PendingPreview {
        private final String world;
        private final int centerX;
        private final int centerZ;
        private final int y;
        private final FlattenShape shape;
        private final int radius;
        private final List<int[]> columns;
        private final long expiresAtMillis;

        private PendingPreview(String world, int centerX, int centerZ, int y, FlattenShape shape, int radius,
                                List<int[]> columns, long expiresAtMillis) {
            this.world = world;
            this.centerX = centerX;
            this.centerZ = centerZ;
            this.y = y;
            this.shape = shape;
            this.radius = radius;
            this.columns = columns;
            this.expiresAtMillis = expiresAtMillis;
        }
    }

    private static final class ActiveOperation {
        private final UUID playerId;
        private final World world;
        private final int centerX;
        private final int centerZ;
        private final int y;
        private final List<int[]> columns;
        private final List<UndoEntry> undoEntries = new ArrayList<>();
        private int columnIndex;
        private long ticksSinceReport;
        private BukkitTask task;

        private ActiveOperation(UUID playerId, World world, PendingPreview preview) {
            this.playerId = playerId;
            this.world = world;
            this.centerX = preview.centerX;
            this.centerZ = preview.centerZ;
            this.y = preview.y;
            this.columns = preview.columns;
        }
    }
}
