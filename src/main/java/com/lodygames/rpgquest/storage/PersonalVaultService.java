package com.lodygames.rpgquest.storage;

import com.lodygames.rpgquest.backpack.ItemArraySerializer;
import com.lodygames.rpgquest.bootstrap.PluginService;
import com.lodygames.rpgquest.config.PersonalVaultConfig;
import com.lodygames.rpgquest.database.PersonalVaultRepository;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.slf4j.Logger;

/**
 * Socle des coffres personnels (issue #104) : {@code UUID joueur + VaultId → inventaire}, persisté
 * dans {@code personal_vaults}. Le contenu appartient au joueur, jamais à un bloc : le même
 * {@link VaultId} ouvert depuis n'importe quel Hub présente le même contenu.
 *
 * <p>Ce service n'expose <strong>aucune commande</strong> : {@link #open} est l'API interne
 * qu'appellent les coffres physiques des Hubs (#105). Aucun accès distant depuis le Wild. L'accès
 * et la taille de chaque coffre dépendent des droits du joueur ({@link VaultEntitlements}, #106).</p>
 *
 * <p>Garanties : une seule {@link Inventory} vivante par (joueur, coffre) — ouvrir deux fois, ou
 * depuis deux Hubs, réutilise la même ({@link #sessions}) ; le contenu est sérialisé à la
 * fermeture, à la déconnexion et à l'arrêt ; tant que la base n'a pas confirmé une sauvegarde,
 * l'instantané reste en mémoire ({@link #pendingSaves}) et c'est lui que la réouverture suivante
 * présente — jamais une version plus ancienne lue en base. Un contenu illisible n'est jamais
 * écrasé : l'ouverture est refusée et la ligne reste intacte pour une récupération.</p>
 */
public final class PersonalVaultService implements PluginService {

    private static final int MAX_SLOTS = PersonalVaultConfig.MAX_SLOTS;

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final Plugin plugin;
    private final PersonalVaultRepository repository;
    private final VaultEntitlements entitlements;
    private final Logger logger;
    private final PersonalVaultListener listener;

    private final Map<VaultKey, Inventory> sessions = new ConcurrentHashMap<>();
    private final Map<VaultKey, byte[]> pendingSaves = new ConcurrentHashMap<>();

    public PersonalVaultService(Plugin plugin, PersonalVaultRepository repository, VaultEntitlements entitlements,
                                Logger logger) {
        this.plugin = plugin;
        this.repository = repository;
        this.entitlements = entitlements;
        this.logger = logger;
        this.listener = new PersonalVaultListener(this);
    }

    public enum OpenOutcome {
        OPENED, ALREADY_OPEN, PLAYER_OFFLINE, LOCKED, LOAD_FAILED
    }

    @Override
    public void start() {
        Bukkit.getPluginManager().registerEvents(listener, plugin);
    }

    @Override
    public void stop() {
        HandlerList.unregisterAll(listener);
        // Même conception que BackpackService : les sauvegardes partent sur l'exécuteur DB avant que
        // DatabaseService (arrêté après ce service) ne draine sa file et ferme la connexion.
        for (Map.Entry<VaultKey, Inventory> entry : List.copyOf(sessions.entrySet())) {
            Inventory inventory = entry.getValue();
            for (HumanEntity viewer : List.copyOf(inventory.getViewers())) {
                viewer.closeInventory();
            }
            if (sessions.remove(entry.getKey(), inventory)) {
                persist(entry.getKey(), inventory.getContents());
            }
        }
    }

    // ---- Ouverture -----------------------------------------------------------------------

    /**
     * Ouvre le coffre {@code vaultId} du joueur, si ses droits ({@link VaultEntitlements}) le
     * permettent, à la taille qu'ils accordent. À appeler sur le thread principal.
     */
    public CompletableFuture<OpenOutcome> open(Player player, VaultId vaultId) {
        VaultKey key = new VaultKey(player.getUniqueId(), vaultId);
        CompletableFuture<OpenOutcome> result = new CompletableFuture<>();
        entitlements.resolve(key.owner(), vaultId)
                .thenCompose(access -> access.unlocked()
                        ? loadBytes(key).thenApply(bytes -> new Loaded(access, bytes))
                        : CompletableFuture.completedFuture(new Loaded(access, null)))
                .thenAccept(loaded -> Bukkit.getScheduler().runTask(plugin, () -> finishOpen(player, key, loaded, result)))
                .exceptionally(error -> {
                    logger.error("Échec du chargement du coffre personnel {} de {}.", vaultId, key.owner(), error);
                    result.complete(OpenOutcome.LOAD_FAILED);
                    return null;
                });
        return result;
    }

    private void finishOpen(Player player, VaultKey key, Loaded loaded, CompletableFuture<OpenOutcome> result) {
        if (!player.isOnline()) {
            result.complete(OpenOutcome.PLAYER_OFFLINE);
            return;
        }
        if (!loaded.access().unlocked()) {
            // Verrouillé : rien n'est lu ni écrit, le contenu éventuel reste intact en base.
            result.complete(OpenOutcome.LOCKED);
            return;
        }
        Inventory existing = sessions.get(key);
        if (existing != null) {
            showExisting(player, existing);
            result.complete(OpenOutcome.ALREADY_OPEN);
            return;
        }
        Optional<ItemStack[]> contents = decode(key, loaded.bytes());
        if (contents.isEmpty()) {
            result.complete(OpenOutcome.LOAD_FAILED);
            return;
        }
        Inventory inventory = createInventory(key, contents.get(), loaded.access().slots());
        sessions.put(key, inventory);
        player.openInventory(inventory);
        result.complete(OpenOutcome.OPENED);
    }

    /** Coffres déjà persistés pour ce joueur — base des futurs écrans de progression (#106). */
    public CompletableFuture<List<String>> vaultIds(UUID playerId) {
        return repository.vaultIds(playerId);
    }

    /**
     * Ne rouvre pas une vue déjà affichée : {@code openInventory} fermerait d'abord la vue courante,
     * ce qui mettrait fin à la session alors que le même inventaire resterait affiché.
     */
    private void showExisting(Player player, Inventory existing) {
        if (player.isOnline() && player.getOpenInventory().getTopInventory() != existing) {
            player.openInventory(existing);
        }
    }

    private CompletableFuture<byte[]> loadBytes(VaultKey key) {
        byte[] pending = pendingSaves.get(key);
        if (pending != null) {
            return CompletableFuture.completedFuture(pending);
        }
        return repository.find(key.owner(), key.vaultId().value())
                .thenApply(stored -> stored.map(PersonalVaultRepository.StoredVault::contents).orElse(null));
    }

    private Optional<ItemStack[]> decode(VaultKey key, byte[] bytes) {
        if (bytes == null) {
            return Optional.of(new ItemStack[0]);
        }
        try {
            return Optional.of(ItemArraySerializer.deserialize(bytes));
        } catch (IOException e) {
            logger.error("Contenu illisible pour le coffre personnel {} de {} : ouverture refusée, "
                    + "ligne conservée intacte pour récupération.", key.vaultId(), key.owner(), e);
            return Optional.empty();
        }
    }

    private Inventory createInventory(VaultKey key, ItemStack[] contents, int capacity) {
        int slots = sizeFor(contents, capacity);
        PersonalVaultHolder holder = new PersonalVaultHolder(key.owner(), key.vaultId());
        Inventory inventory = Bukkit.createInventory(holder, slots, title(key.vaultId()));
        holder.bind(inventory);
        for (int slot = 0; slot < contents.length && slot < slots; slot++) {
            inventory.setItem(slot, contents[slot]);
        }
        return inventory;
    }

    /**
     * Jamais de troncature : si la capacité a baissé (droit retiré), les rangées qui contiennent
     * encore des objets restent affichées ; seules les rangées vides au-delà disparaissent.
     */
    static int sizeFor(ItemStack[] contents, int capacity) {
        int used = 0;
        for (int slot = contents.length - 1; slot >= 0; slot--) {
            if (contents[slot] != null && !contents[slot].getType().isAir()) {
                used = slot + 1;
                break;
            }
        }
        return Math.min(MAX_SLOTS, Math.max(capacity, roundUpToRow(used)));
    }

    private static int roundUpToRow(int length) {
        return (length + 8) / 9 * 9;
    }

    private Component title(VaultId vaultId) {
        return MM.deserialize("<dark_gray>Coffre personnel <white><id></white></dark_gray>",
                Placeholder.unparsed("id", vaultId.value()));
    }

    // ---- Fermeture / persistance ----------------------------------------------------------

    /** Fermeture du GUI : sauvegarde si plus personne ne le regarde. Idempotent. */
    void handleClose(Inventory inventory, HumanEntity closing) {
        if (!(inventory.getHolder() instanceof PersonalVaultHolder holder)) {
            return;
        }
        boolean stillViewed = inventory.getViewers().stream().anyMatch(viewer -> viewer != closing);
        if (stillViewed) {
            return;
        }
        VaultKey key = new VaultKey(holder.owner(), holder.vaultId());
        if (sessions.remove(key, inventory)) {
            persist(key, inventory.getContents());
        }
    }

    /** Déconnexion : filet de sécurité si la fermeture du GUI n'a pas déjà tout sauvegardé. */
    void handleQuit(UUID playerId) {
        for (Map.Entry<VaultKey, Inventory> entry : List.copyOf(sessions.entrySet())) {
            if (entry.getKey().owner().equals(playerId) && sessions.remove(entry.getKey(), entry.getValue())) {
                persist(entry.getKey(), entry.getValue().getContents());
            }
        }
    }

    private void persist(VaultKey key, ItemStack[] contents) {
        byte[] bytes = ItemArraySerializer.serialize(contents);
        pendingSaves.put(key, bytes);
        repository.save(key.owner(), key.vaultId().value(), ItemArraySerializer.SCHEMA_VERSION, bytes)
                .whenComplete((ignored, error) -> {
                    if (error == null) {
                        pendingSaves.remove(key, bytes);
                    } else {
                        // L'instantané reste en mémoire : la prochaine ouverture le présente et le
                        // ressauvegarde à sa fermeture, rien n'est perdu tant que le serveur tourne.
                        logger.error("Échec de sauvegarde du coffre personnel {} de {}.", key.vaultId(), key.owner(), error);
                    }
                });
    }

    private record VaultKey(UUID owner, VaultId vaultId) {
    }

    private record Loaded(VaultEntitlements.VaultAccess access, byte[] bytes) {
    }
}
