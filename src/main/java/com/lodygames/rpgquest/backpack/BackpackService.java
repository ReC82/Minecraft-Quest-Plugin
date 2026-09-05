package com.lodygames.rpgquest.backpack;

import com.lodygames.rpgquest.backpack.model.BackpackSize;
import com.lodygames.rpgquest.bootstrap.PluginService;
import com.lodygames.rpgquest.config.BackpackConfig;
import com.lodygames.rpgquest.database.BackpackRepository;
import com.lodygames.rpgquest.entitlement.EntitlementService;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.slf4j.Logger;

/**
 * Possède l'état runtime des backpacks : un joueur n'a jamais plus d'une
 * {@link Inventory} vivante à la fois pour son backpack, qu'il l'ouvre une
 * ou dix fois de suite ({@link #sessions} est l'unique source de vérité —
 * voir {@link #open}, mission point 7 « ouverture simultanée ») ; le
 * contenu est sérialisé et persisté à la fermeture/déconnexion/arrêt
 * (mission point 8), jamais en tâche de fond périodique.
 *
 * <p>Le palier effectif d'un joueur est résolu via {@link
 * EntitlementService} (avantage {@value #ENTITLEMENT_KEY}), avec la
 * permission de secours {@value #FALLBACK_PERMISSION} comme repli (mission
 * point 5) : {@code backpack} est le premier consommateur concret de
 * l'interface générique d'avantages (mission point 11).</p>
 */
public final class BackpackService implements PluginService {

    public static final String ENTITLEMENT_KEY = "backpack";
    public static final String FALLBACK_PERMISSION = "rpgquest.backpack.free";
    public static final String OPEN_ITEM_KEY_NAME = "backpack-open-item";

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final Plugin plugin;
    private final BackpackRepository repository;
    private final EntitlementService entitlementService;
    private final Supplier<BackpackConfig> config;
    private final Logger logger;
    private final NamespacedKey openItemKey;
    private final BackpackListener listener;

    private final Map<UUID, BackpackSession> sessions = new ConcurrentHashMap<>();

    public BackpackService(Plugin plugin, BackpackRepository repository, EntitlementService entitlementService,
                            Supplier<BackpackConfig> config, Logger logger) {
        this.plugin = plugin;
        this.repository = repository;
        this.entitlementService = entitlementService;
        this.config = config;
        this.logger = logger;
        this.openItemKey = new NamespacedKey(plugin, OPEN_ITEM_KEY_NAME);
        this.listener = new BackpackListener(this);
    }

    @Override
    public void start() {
        Bukkit.getPluginManager().registerEvents(listener, plugin);
    }

    @Override
    public void stop() {
        HandlerList.unregisterAll(listener);
        // Force la fermeture de tout backpack encore ouvert : InventoryCloseEvent se déclenche de
        // façon synchrone sur le thread principal, donc la sauvegarde qu'il enclenche est déjà en
        // file sur l'exécuteur DB avant que ce stop() ne rende la main — DatabaseService (démarré
        // avant, donc arrêté après, LIFO) draine cette file avant de fermer la connexion (mission
        // point 8, « sauvegarde atomique... à l'arrêt »).
        for (UUID playerId : List.copyOf(sessions.keySet())) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                player.closeInventory();
            } else {
                sessions.remove(playerId);
            }
        }
    }

    public Listener listener() {
        return listener;
    }

    public NamespacedKey openItemKey() {
        return openItemKey;
    }

    public enum OpenOutcome {
        OPENED, ALREADY_OPEN, NO_ACCESS
    }

    /** Objet PDC-tagué (mission point 4) : jamais identifié par son matériau/nom, seule la PDC compte. */
    public ItemStack createOpenItem() {
        ItemStack item = new ItemStack(config.get().openItemMaterial());
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(openItemKey, PersistentDataType.BYTE, (byte) 1);
        meta.displayName(MM.deserialize("<gold><bold>Sac à dos</bold></gold>"));
        item.setItemMeta(meta);
        return item;
    }

    public boolean isOpenItem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return false;
        }
        Byte tag = item.getItemMeta().getPersistentDataContainer().get(openItemKey, PersistentDataType.BYTE);
        return tag != null && tag == 1;
    }

    /** Objet jamais autorisé à l'intérieur d'un backpack : imbrication (mission point 7) ou liste explicite. */
    public boolean isForbidden(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        if (isOpenItem(item)) {
            return true;
        }
        return config.get().forbiddenMaterials().contains(item.getType());
    }

    // ---- Ouverture -----------------------------------------------------------------------

    public CompletableFuture<OpenOutcome> open(Player player) {
        UUID playerId = player.getUniqueId();
        BackpackSession existing = sessions.get(playerId);
        if (existing != null) {
            player.openInventory(existing.inventory());
            return CompletableFuture.completedFuture(OpenOutcome.ALREADY_OPEN);
        }

        CompletableFuture<OpenOutcome> result = new CompletableFuture<>();
        effectiveSize(player).thenAccept(sizeOpt -> {
            if (sizeOpt.isEmpty()) {
                result.complete(OpenOutcome.NO_ACCESS);
                return;
            }
            BackpackSize size = sizeOpt.get();
            repository.find(playerId).thenAccept(stored -> runOnMainThread(() -> {
                BackpackSession raced = sessions.get(playerId);
                if (raced != null) {
                    // Une autre tentative d'ouverture a fini en premier (mission point 7,
                    // "ouverture simultanée") : jamais une seconde instance vivante, on réutilise.
                    if (player.isOnline()) {
                        player.openInventory(raced.inventory());
                    }
                    result.complete(OpenOutcome.ALREADY_OPEN);
                    return;
                }

                int totalSlots = config.get().rowsFor(size) * 9;
                ItemStack[] contents = decodeOrRecover(playerId, stored, totalSlots);
                BackpackInventoryHolder holder = new BackpackInventoryHolder();
                Inventory inventory = Bukkit.createInventory(holder, totalSlots, title(size));
                holder.bind(inventory);
                inventory.setContents(contents);
                sessions.put(playerId, new BackpackSession(inventory, size));
                if (player.isOnline()) {
                    player.openInventory(inventory);
                }
                result.complete(OpenOutcome.OPENED);
            })).exceptionally(error -> {
                logger.error("Échec du chargement du backpack de {}.", playerId, error);
                result.completeExceptionally(error);
                return null;
            });
        }).exceptionally(error -> {
            logger.error("Échec de la résolution du palier de backpack de {}.", playerId, error);
            result.completeExceptionally(error);
            return null;
        });
        return result;
    }

    private Component title(BackpackSize size) {
        return MM.deserialize("<dark_gray>Sac à dos <white><size></white></dark_gray>",
                Placeholder.unparsed("size", size.name()));
    }

    public CompletableFuture<Optional<BackpackSize>> effectiveSize(Player player) {
        return entitlementService.currentTier(player.getUniqueId(), ENTITLEMENT_KEY).thenApply(tierOpt -> {
            if (tierOpt.isPresent()) {
                try {
                    return Optional.of(BackpackSize.valueOf(tierOpt.get()));
                } catch (IllegalArgumentException e) {
                    logger.warn("Palier de backpack inconnu pour {} : \"{}\", ignoré.", player.getUniqueId(), tierOpt.get());
                }
            }
            if (player.hasPermission(FALLBACK_PERMISSION)) {
                return Optional.of(config.get().fallbackSize());
            }
            return Optional.<BackpackSize>empty();
        });
    }

    // ---- Fermeture / persistance ----------------------------------------------------------

    /** Appelé par {@link BackpackListener} sur fermeture du GUI ou déconnexion. Idempotent. */
    void handleClose(UUID playerId) {
        BackpackSession session = sessions.remove(playerId);
        if (session == null) {
            return;
        }
        byte[] bytes = ItemArraySerializer.serialize(session.inventory().getContents());
        repository.save(playerId, ItemArraySerializer.SCHEMA_VERSION, bytes)
                .exceptionally(error -> {
                    logger.error("Échec de sauvegarde du backpack de {} à la fermeture.", playerId, error);
                    return null;
                });
    }

    // ---- Redimensionnement (octroi/retrait d'avantage) -------------------------------------

    /**
     * Applique un nouveau palier (mission points 9/10) : agrandir ne perd jamais rien ; réduire
     * compacte le contenu existant dans les cases disponibles et bascule le surplus dans la boîte
     * de récupération, jamais silencieusement perdu. Ferme d'abord le backpack s'il est ouvert
     * (sauvegarde sous l'ancienne taille avant de le redimensionner en base).
     */
    public CompletableFuture<Void> applySizeChange(UUID playerId, BackpackSize newSize) {
        BackpackSession openSession = sessions.get(playerId);
        Player player = openSession == null ? null : Bukkit.getPlayer(playerId);
        if (player != null) {
            return runOnMainThreadFuture(player::closeInventory).thenCompose(ignored -> resize(playerId, newSize));
        }
        return resize(playerId, newSize);
    }

    private CompletableFuture<Void> resize(UUID playerId, BackpackSize newSize) {
        int newTotalSlots = config.get().rowsFor(newSize) * 9;
        return repository.find(playerId).thenCompose(stored -> {
            ItemStack[] oldContents = stored.isEmpty() ? new ItemStack[0] : safeDeserialize(playerId, stored.get().contents());
            ResizeResult result = compact(oldContents, newTotalSlots);
            byte[] newBytes = ItemArraySerializer.serialize(result.contents());
            byte[] overflowBytes = result.overflow() == null ? null : ItemArraySerializer.serialize(result.overflow());
            String reason = "resize_to_" + newSize;
            return repository.applyResize(playerId, ItemArraySerializer.SCHEMA_VERSION, newBytes, overflowBytes, reason);
        });
    }

    /** Compacte les objets non vides en début de tableau ; ceux qui dépassent la nouvelle taille débordent. */
    private ResizeResult compact(ItemStack[] oldContents, int newTotalSlots) {
        ItemStack[] newContents = new ItemStack[newTotalSlots];
        List<ItemStack> overflow = new ArrayList<>();
        int index = 0;
        for (ItemStack item : oldContents) {
            if (item == null || item.getType().isAir()) {
                continue;
            }
            if (index < newTotalSlots) {
                newContents[index++] = item;
            } else {
                overflow.add(item);
            }
        }
        return new ResizeResult(newContents, overflow.isEmpty() ? null : overflow.toArray(new ItemStack[0]));
    }

    private ItemStack[] decodeOrRecover(UUID playerId, Optional<BackpackRepository.StoredBackpack> stored, int totalSlots) {
        if (stored.isEmpty()) {
            return new ItemStack[totalSlots];
        }
        ItemStack[] decoded = safeDeserialize(playerId, stored.get().contents());
        if (decoded.length == totalSlots) {
            return decoded;
        }
        // Taille en base différente de la taille effective actuelle (ex. avantage modifié hors de
        // applySizeChange) : on adapte défensivement plutôt que de planter ou tronquer silencieusement.
        ResizeResult result = compact(decoded, totalSlots);
        byte[] newBytes = ItemArraySerializer.serialize(result.contents());
        byte[] overflowBytes = result.overflow() == null ? null : ItemArraySerializer.serialize(result.overflow());
        repository.applyResize(playerId, ItemArraySerializer.SCHEMA_VERSION, newBytes, overflowBytes, "size_mismatch_on_load")
                .exceptionally(error -> {
                    logger.error("Échec de l'adaptation de taille au chargement pour {}.", playerId, error);
                    return null;
                });
        return result.contents();
    }

    private ItemStack[] safeDeserialize(UUID playerId, byte[] bytes) {
        try {
            return ItemArraySerializer.deserialize(bytes);
        } catch (IOException e) {
            logger.error("Contenu de backpack illisible pour {} : traité comme une anomalie, rien n'est perdu.", playerId, e);
            repository.logAudit(playerId, "CORRUPT_CONTENTS", String.valueOf(e.getMessage()))
                    .exceptionally(logError -> null);
            repository.applyResize(playerId, ItemArraySerializer.SCHEMA_VERSION,
                            ItemArraySerializer.serialize(new ItemStack[0]), bytes, "corrupt_contents_on_load")
                    .exceptionally(logError -> null);
            return new ItemStack[0];
        }
    }

    // ---- Boîte de récupération --------------------------------------------------------------

    public CompletableFuture<List<BackpackRepository.OverflowEntry>> unclaimedOverflow(UUID playerId) {
        return repository.findUnclaimedOverflow(playerId);
    }

    public CompletableFuture<Boolean> claimOverflow(long overflowId) {
        return repository.markOverflowClaimed(overflowId);
    }

    // ---- Utilitaires -----------------------------------------------------------------------

    private void runOnMainThread(Runnable task) {
        Bukkit.getScheduler().runTask(plugin, task);
    }

    private CompletableFuture<Void> runOnMainThreadFuture(Runnable task) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                task.run();
                future.complete(null);
            } catch (RuntimeException e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    record BackpackSession(Inventory inventory, BackpackSize size) {
    }

    private record ResizeResult(ItemStack[] contents, ItemStack[] overflow) {
    }
}
