package be.lloyd.rpgquest.economy.market;

import be.lloyd.rpgquest.RPGQuestPlugin;
import be.lloyd.rpgquest.bootstrap.PluginService;
import be.lloyd.rpgquest.database.MarketListingRecord;
import be.lloyd.rpgquest.database.MarketRepository;
import be.lloyd.rpgquest.economy.EconomyService;
import be.lloyd.rpgquest.economy.TransactionType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.slf4j.Logger;

/**
 * Marché entre joueurs : vitrine partagée (tous vendeurs confondus) affichée
 * en inventaire paginé, achats/annulations directement au clic. Un joueur
 * met en vente l'objet tenu en main via {@code /market sell <prix>} ;
 * l'objet complet (méta, PDC d'un objet personnalisé compris — voir
 * {@code ItemStack#serializeAsBytes}) est mis en escrow dans
 * {@code market_listings} jusqu'à achat ou annulation, sans dépendre du
 * registre d'objets personnalisés (l'objet sérialisé porte déjà toute son
 * identité).
 *
 * <p><b>Anti-duplication</b> — achat en deux temps distincts, dans cet
 * ordre précis :</p>
 * <ol>
 *     <li>{@link MarketRepository#claim} réserve atomiquement l'offre
 *     (bascule {@code ACTIVE → SOLD} uniquement si elle l'était encore) —
 *     au plus un acheteur peut jamais réserver la même offre ;</li>
 *     <li>le débit de l'acheteur n'est tenté <b>qu'après</b> la réservation
 *     (le prix n'est connu qu'une fois l'offre lue) ; un débit refusé
 *     (fonds insuffisants) réactive l'offre ({@link MarketRepository#reactivate})
 *     plutôt que de la perdre.</li>
 * </ol>
 * Seul un débit réussi entraîne le crédit du vendeur et la remise de
 * l'objet à l'acheteur — jamais l'inverse.
 */
public final class MarketService implements PluginService {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final String TITLE = "<gold>Marché</gold>";
    private static final int LIST_SIZE = 54;
    private static final int PREV_SLOT = 3;
    private static final int PAGE_INDICATOR_SLOT = 4;
    private static final int NEXT_SLOT = 5;
    private static final int CLOSE_SLOT = 8;
    private static final int CONTENT_START_SLOT = 9;
    private static final int[] CONTENT_SLOTS = buildContentSlots();

    private final RPGQuestPlugin plugin;
    private final MarketRepository marketRepository;
    private final EconomyService economyService;
    private final Logger logger;

    private final Map<UUID, MarketSession> sessions = new ConcurrentHashMap<>();

    public MarketService(RPGQuestPlugin plugin, MarketRepository marketRepository, EconomyService economyService) {
        this.plugin = plugin;
        this.marketRepository = marketRepository;
        this.economyService = economyService;
        this.logger = plugin.getSLF4JLogger();
    }

    @Override
    public void start() {
        // Rien à démarrer : chaque offre est lue depuis la base à la demande, aucun cache global.
    }

    @Override
    public void stop() {
        for (UUID playerId : sessions.keySet()) {
            Player player = plugin.getServer().getPlayer(playerId);
            if (player != null) {
                player.closeInventory();
            }
        }
        sessions.clear();
    }

    /** Listener Bukkit unique (clic/drag/fermeture/déconnexion) à enregistrer via {@code PlayerListenerService}. */
    public Listener listener() {
        return new MarketListener(this);
    }

    /** {@code /market} : ouvre la vitrine (toutes offres actives, triées par ancienneté), première page. */
    public void open(Player player) {
        showList(player, 0);
    }

    /** {@code /market sell <prix>} : met en vente l'objet tenu en main. */
    public void sell(Player player, long price) {
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.getType().isAir() || held.getAmount() <= 0) {
            player.sendMessage(MM.deserialize("<red>Tu dois tenir en main l'objet à vendre.</red>"));
            return;
        }

        byte[] itemData = held.clone().serializeAsBytes();
        player.getInventory().setItemInMainHand(null);
        UUID sellerId = player.getUniqueId();

        marketRepository.createListing(sellerId, itemData, price).thenAccept(id -> runOnMainThread(() ->
                player.sendMessage(MM.deserialize(
                        "<green>Objet mis en vente</green> <gray>(#<id>, <price> pièce(s))</gray>",
                        Placeholder.unparsed("id", String.valueOf(id)),
                        Placeholder.unparsed("price", String.valueOf(price))))))
                .exceptionally(error -> {
                    logger.error("Échec de la mise en vente pour {} : objet restitué.", sellerId, error);
                    runOnMainThread(() -> giveItem(player, itemData));
                    return null;
                });
    }

    /** {@code /market cancel <id>} : alternative texte au clic sur sa propre offre dans la vitrine. */
    public void cancel(Player player, long listingId) {
        cancelListing(player, listingId);
    }

    void handleClose(Player player) {
        sessions.remove(player.getUniqueId());
    }

    void handleQuit(Player player) {
        sessions.remove(player.getUniqueId());
    }

    void handleClick(Player player, int slot) {
        MarketSession session = sessions.get(player.getUniqueId());
        if (session == null) {
            return;
        }
        if (slot == PREV_SLOT) {
            showList(player, session.page() - 1);
            return;
        }
        if (slot == NEXT_SLOT) {
            showList(player, session.page() + 1);
            return;
        }
        if (slot == CLOSE_SLOT) {
            player.closeInventory();
            return;
        }

        int contentIndex = contentIndexOf(slot);
        if (contentIndex < 0 || contentIndex >= session.pageListings().size()) {
            return;
        }
        MarketListingRecord listing = session.pageListings().get(contentIndex);
        if (listing.sellerUuid().equals(player.getUniqueId())) {
            cancelListing(player, listing.id());
        } else {
            buyListing(player, listing);
        }
    }

    // ---- Rendu ------------------------------------------------------------

    private void showList(Player player, int requestedPage) {
        UUID playerId = player.getUniqueId();
        marketRepository.activeListings().thenAccept(listings -> runOnMainThread(() -> {
            int pageCount = MarketPagination.pageCount(listings.size());
            int page = MarketPagination.clampPage(requestedPage, pageCount);
            List<MarketListingRecord> pageItems = MarketPagination.pageOf(listings, page);

            sessions.put(playerId, new MarketSession(page, pageItems));

            MarketInventoryHolder holder = new MarketInventoryHolder();
            Inventory inventory = Bukkit.createInventory(holder, LIST_SIZE, MM.deserialize(TITLE));
            holder.bind(inventory);

            renderChrome(inventory, page, pageCount);
            for (int i = 0; i < pageItems.size(); i++) {
                inventory.setItem(CONTENT_SLOTS[i], buildIcon(playerId, pageItems.get(i)));
            }
            player.openInventory(inventory);
        })).exceptionally(error -> {
            logger.error("Impossible de construire la vitrine du marché pour {}", playerId, error);
            return null;
        });
    }

    private void renderChrome(Inventory inventory, int page, int pageCount) {
        if (page > 0) {
            inventory.setItem(PREV_SLOT, navIcon(Material.ARROW, "« Page précédente"));
        }
        inventory.setItem(PAGE_INDICATOR_SLOT, pageIndicatorIcon(page, pageCount));
        if (page < pageCount - 1) {
            inventory.setItem(NEXT_SLOT, navIcon(Material.ARROW, "Page suivante »"));
        }
        inventory.setItem(CLOSE_SLOT, navIcon(Material.BARRIER, "Fermer"));
    }

    private ItemStack navIcon(Material material, String label) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(MM.deserialize("<yellow><label></yellow>", Placeholder.unparsed("label", label)));
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack pageIndicatorIcon(int page, int pageCount) {
        ItemStack stack = new ItemStack(Material.PAPER);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(MM.deserialize("<gray>Page <current>/<total></gray>",
                Placeholder.unparsed("current", String.valueOf(page + 1)),
                Placeholder.unparsed("total", String.valueOf(pageCount))));
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack buildIcon(UUID viewerId, MarketListingRecord listing) {
        ItemStack stack = ItemStack.deserializeBytes(listing.itemData()).clone();
        ItemMeta meta = stack.getItemMeta();

        List<Component> lore = new ArrayList<>(meta.hasLore() ? meta.lore() : List.of());
        if (!lore.isEmpty()) {
            lore.add(Component.empty());
        }
        lore.add(MM.deserialize("<white>Vendeur :</white> <gray><seller></gray>",
                Placeholder.unparsed("seller", listing.sellerName().orElse(listing.sellerUuid().toString()))));
        lore.add(MM.deserialize("<white>Prix :</white> <gold><price> pièce(s)</gold>",
                Placeholder.unparsed("price", String.valueOf(listing.price()))));
        lore.add(listing.sellerUuid().equals(viewerId)
                ? MM.deserialize("<yellow>Clique pour annuler et récupérer l'objet</yellow>")
                : MM.deserialize("<yellow>Clique pour acheter</yellow>"));
        meta.lore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    private int contentIndexOf(int slot) {
        if (slot < CONTENT_START_SLOT || slot >= CONTENT_START_SLOT + CONTENT_SLOTS.length) {
            return -1;
        }
        return slot - CONTENT_START_SLOT;
    }

    // ---- Achat / annulation ------------------------------------------------

    private void buyListing(Player player, MarketListingRecord cached) {
        UUID buyerId = player.getUniqueId();
        marketRepository.claim(cached.id(), buyerId).thenCompose(claimedOpt -> {
            if (claimedOpt.isEmpty()) {
                runOnMainThread(() -> player.sendMessage(MM.deserialize("<red>Cette offre n'est plus disponible.</red>")));
                return CompletableFuture.completedFuture(null);
            }
            MarketListingRecord claimed = claimedOpt.get();
            String context = "listing:" + claimed.id();

            return economyService.debit(buyerId, claimed.price(), TransactionType.MARKET_BUY, context)
                    .thenCompose(debited -> {
                        if (!debited) {
                            return marketRepository.reactivate(claimed.id(), buyerId).thenRun(() ->
                                    runOnMainThread(() -> player.sendMessage(MM.deserialize("<red>Fonds insuffisants.</red>"))));
                        }
                        return economyService.credit(claimed.sellerUuid(), claimed.price(), TransactionType.MARKET_SELL, context)
                                .thenRun(() -> runOnMainThread(() -> {
                                    giveItem(player, claimed.itemData());
                                    player.sendMessage(MM.deserialize(
                                            "<green>Achat effectué</green> <gray>(-<price>)</gray>",
                                            Placeholder.unparsed("price", String.valueOf(claimed.price()))));
                                    refreshIfSessionStillOpen(player);
                                }));
                    });
        }).exceptionally(error -> {
            logger.error("Échec d'un achat sur le marché pour {}", buyerId, error);
            runOnMainThread(() -> player.sendMessage(MM.deserialize("<red>Une erreur est survenue, réessaie.</red>")));
            return null;
        });
    }

    private void cancelListing(Player player, long listingId) {
        UUID sellerId = player.getUniqueId();
        marketRepository.cancel(listingId, sellerId).thenAccept(cancelledOpt -> runOnMainThread(() -> {
            if (cancelledOpt.isEmpty()) {
                player.sendMessage(MM.deserialize("<red>Offre introuvable, déjà résolue, ou pas la tienne.</red>"));
                return;
            }
            giveItem(player, cancelledOpt.get().itemData());
            player.sendMessage(MM.deserialize("<yellow>Offre annulée, objet restitué.</yellow>"));
            refreshIfSessionStillOpen(player);
        })).exceptionally(error -> {
            logger.error("Échec de l'annulation d'une offre du marché pour {}", sellerId, error);
            return null;
        });
    }

    private void refreshIfSessionStillOpen(Player player) {
        MarketSession current = sessions.get(player.getUniqueId());
        if (current != null) {
            showList(player, current.page());
        }
    }

    private void giveItem(Player player, byte[] itemData) {
        ItemStack stack = ItemStack.deserializeBytes(itemData);
        player.getInventory().addItem(stack).values()
                .forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
    }

    private void runOnMainThread(Runnable task) {
        plugin.getServer().getScheduler().runTask(plugin, task);
    }

    private static int[] buildContentSlots() {
        int[] slots = new int[MarketPagination.PAGE_SIZE];
        for (int i = 0; i < slots.length; i++) {
            slots[i] = CONTENT_START_SLOT + i;
        }
        return slots;
    }
}
