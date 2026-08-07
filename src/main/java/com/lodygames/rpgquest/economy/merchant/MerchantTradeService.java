package com.lodygames.rpgquest.economy.merchant;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.bootstrap.PluginService;
import com.lodygames.rpgquest.economy.EconomyService;
import com.lodygames.rpgquest.economy.TransactionType;
import com.lodygames.rpgquest.economy.merchant.model.MerchantDefinition;
import com.lodygames.rpgquest.economy.merchant.model.MerchantOffer;
import com.lodygames.rpgquest.economy.merchant.model.OfferItemKind;
import com.lodygames.rpgquest.economy.merchant.model.TradeDirection;
import com.lodygames.rpgquest.item.YamlCustomItemRegistry;
import com.lodygames.rpgquest.quest.progress.QuestProgressEngine;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.slf4j.Logger;

/**
 * Orchestre l'ouverture d'une vitrine de marchand et l'exécution des
 * échanges. Un marchand n'est <b>jamais</b> ouvert directement par un clic
 * sur une entité PNJ (ce serait un second système d'identification de PNJ en
 * parallèle de celui des quêtes/dialogues) : la seule porte d'entrée est
 * l'action de dialogue {@code OPEN_MERCHANT} (voir {@code
 * DialogueSessionEngine}) — un marchand est donc toujours atteint via un
 * nœud de dialogue existant, qui peut lui-même poser ses propres conditions
 * (permission, variable, quête...).
 *
 * <p><b>Anti-duplication</b> : {@link #sellToPlayer} débite <em>avant</em> de
 * donner l'objet (un débit refusé ne donne jamais rien) ; {@link
 * #buyFromPlayer} retire l'objet de l'inventaire de façon synchrone, avant
 * même de lancer le crédit asynchrone (un retrait déjà appliqué ne peut pas
 * être rejoué par un second clic pendant que le premier crédit est encore en
 * vol — voir docs/ECONOMY.md).</p>
 */
public final class MerchantTradeService implements PluginService {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final int MIN_ROWS = 3;
    private static final int MAX_ROWS = 6;

    private final RPGQuestPlugin plugin;
    private final YamlMerchantRegistry merchantRegistry;
    private final EconomyService economyService;
    private final YamlCustomItemRegistry customItemRegistry;
    private final QuestProgressEngine questProgressEngine;
    private final Logger logger;

    private final Map<UUID, MerchantSession> sessions = new ConcurrentHashMap<>();

    public MerchantTradeService(RPGQuestPlugin plugin, YamlMerchantRegistry merchantRegistry, EconomyService economyService,
                                 YamlCustomItemRegistry customItemRegistry, QuestProgressEngine questProgressEngine) {
        this.plugin = plugin;
        this.merchantRegistry = merchantRegistry;
        this.economyService = economyService;
        this.customItemRegistry = customItemRegistry;
        this.questProgressEngine = questProgressEngine;
        this.logger = plugin.getSLF4JLogger();
    }

    @Override
    public void start() {
        // Rien à démarrer : le chargement des définitions est un service séparé (YamlMerchantRegistry).
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
        return new MerchantShopListener(this);
    }

    /** Point d'entrée unique, appelé depuis {@code DialogueSessionEngine} lors de l'action {@code OPEN_MERCHANT}. */
    public void openShop(Player player, NamespacedKey merchantId) {
        Optional<MerchantDefinition> merchantOpt = merchantRegistry.find(merchantId);
        if (merchantOpt.isEmpty()) {
            player.sendMessage(MM.deserialize(
                    "<red>Marchand introuvable :</red> <white><id></white>",
                    Placeholder.unparsed("id", merchantId.toString())));
            return;
        }
        render(player, merchantOpt.get());
    }

    void handleClose(Player player) {
        sessions.remove(player.getUniqueId());
    }

    void handleQuit(Player player) {
        sessions.remove(player.getUniqueId());
    }

    void handleClick(Player player, int slot) {
        MerchantSession session = sessions.get(player.getUniqueId());
        if (session == null) {
            return;
        }
        Optional<MerchantDefinition> merchantOpt = merchantRegistry.find(session.merchantId());
        if (merchantOpt.isEmpty()) {
            // Marchand supprimé par un /merchant reload pendant que la vitrine était ouverte.
            player.closeInventory();
            return;
        }
        MerchantDefinition merchant = merchantOpt.get();
        int contentSlots = contentSlotCount(merchant);
        if (slot < 0 || slot >= contentSlots || slot >= merchant.offers().size()) {
            return;
        }
        checkRequirementsAndTrade(player, merchant.offers().get(slot));
    }

    // ---- Rendu ------------------------------------------------------------

    private void render(Player player, MerchantDefinition merchant) {
        sessions.put(player.getUniqueId(), new MerchantSession(merchant.id()));

        int size = inventorySize(merchant.offers().size());
        MerchantShopInventoryHolder holder = new MerchantShopInventoryHolder();
        Inventory inventory = Bukkit.createInventory(holder, size, MM.deserialize(merchant.title()));
        holder.bind(inventory);

        int contentSlots = contentSlotCount(merchant);
        List<MerchantOffer> offers = merchant.offers();
        for (int i = 0; i < offers.size() && i < contentSlots; i++) {
            ItemStack icon = buildIcon(offers.get(i));
            if (icon != null) {
                inventory.setItem(i, icon);
            }
        }

        player.openInventory(inventory);
    }

    private int inventorySize(int offerCount) {
        int rows = (int) Math.ceil((offerCount + 9) / 9.0);
        rows = Math.max(MIN_ROWS, Math.min(MAX_ROWS, rows));
        return rows * 9;
    }

    private int contentSlotCount(MerchantDefinition merchant) {
        return inventorySize(merchant.offers().size()) - 9;
    }

    private ItemStack buildIcon(MerchantOffer offer) {
        ItemStack stack = createOfferStack(offer);
        if (stack == null) {
            logger.warn("Offre de marchand ignorée (objet introuvable) : {}", describeItem(offer));
            return null;
        }
        ItemMeta meta = stack.getItemMeta();
        List<Component> lore = new ArrayList<>();
        lore.add(offer.direction() == TradeDirection.SELL_TO_PLAYER
                ? MM.deserialize("<yellow>Achat</yellow> <gray>: clic pour acheter</gray>")
                : MM.deserialize("<yellow>Vente</yellow> <gray>: clic pour vendre</gray>"));
        lore.add(MM.deserialize("<white>Quantité :</white> <gray><amount></gray>",
                Placeholder.unparsed("amount", String.valueOf(offer.quantity()))));
        lore.add(MM.deserialize("<white>Prix :</white> <gold><price> pièce(s)</gold>",
                Placeholder.unparsed("price", String.valueOf(offer.price()))));
        if (offer.requiredPermission() != null) {
            lore.add(MM.deserialize("<gray>Requiert la permission <permission></gray>",
                    Placeholder.unparsed("permission", offer.requiredPermission())));
        }
        if (offer.requiredQuestId() != null) {
            lore.add(MM.deserialize("<gray>Requiert la quête <quest> (<state>)</gray>",
                    Placeholder.unparsed("quest", offer.requiredQuestId().toString()),
                    Placeholder.unparsed("state", offer.requiredQuestState().name())));
        }
        if (offer.requiredLevel() != null) {
            lore.add(MM.deserialize("<gray>Requiert le niveau <level></gray>",
                    Placeholder.unparsed("level", String.valueOf(offer.requiredLevel()))));
        }
        meta.lore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    // ---- Conditions ---------------------------------------------------------

    private void checkRequirementsAndTrade(Player player, MerchantOffer offer) {
        if (offer.requiredPermission() != null && !player.hasPermission(offer.requiredPermission())) {
            sendDenied(player, "<red>Il te manque la permission requise pour cette offre.</red>");
            return;
        }
        if (offer.requiredLevel() != null && player.getLevel() < offer.requiredLevel()) {
            sendDenied(player, "<red>Il te manque le niveau requis pour cette offre.</red>");
            return;
        }
        if (offer.requiredQuestId() != null) {
            questProgressEngine.stateOf(player.getUniqueId(), offer.requiredQuestId()).thenAccept(state ->
                    runOnMainThread(() -> {
                        if (state != offer.requiredQuestState()) {
                            sendDenied(player, "<red>Cette offre nécessite une progression de quête différente.</red>");
                        } else {
                            executeTrade(player, offer);
                        }
                    })).exceptionally(error -> {
                logger.error("Échec de la vérification de quête pour une offre de marchand ({})", player.getUniqueId(), error);
                return null;
            });
            return;
        }
        executeTrade(player, offer);
    }

    private void sendDenied(Player player, String message) {
        player.sendMessage(MM.deserialize(message));
    }

    // ---- Échange --------------------------------------------------------------

    private void executeTrade(Player player, MerchantOffer offer) {
        switch (offer.direction()) {
            case SELL_TO_PLAYER -> sellToPlayer(player, offer);
            case BUY_FROM_PLAYER -> buyFromPlayer(player, offer);
        }
    }

    /** Le marchand vend : débit d'abord (atomique côté base), objet donné seulement si le débit a réussi. */
    private void sellToPlayer(Player player, MerchantOffer offer) {
        if (offer.price() == 0) {
            giveOfferItem(player, offer);
            return;
        }
        economyService.debit(player.getUniqueId(), offer.price(), TransactionType.MERCHANT_BUY, describeItem(offer))
                .thenAccept(success -> runOnMainThread(() -> {
                    if (!success) {
                        player.sendMessage(MM.deserialize("<red>Fonds insuffisants.</red>"));
                        return;
                    }
                    giveOfferItem(player, offer);
                    player.sendMessage(MM.deserialize(
                            "<green>Achat effectué :</green> <white><amount>x <item></white> <gray>(-<price>)</gray>",
                            Placeholder.unparsed("amount", String.valueOf(offer.quantity())),
                            Placeholder.unparsed("item", describeItem(offer)),
                            Placeholder.unparsed("price", String.valueOf(offer.price()))));
                }))
                .exceptionally(error -> {
                    logger.error("Échec du débit lors d'un achat marchand pour {}", player.getUniqueId(), error);
                    runOnMainThread(() -> player.sendMessage(MM.deserialize("<red>Une erreur est survenue, réessaie.</red>")));
                    return null;
                });
    }

    /**
     * Le marchand achète : le retrait de l'objet est synchrone (même thread
     * d'événement que le clic), donc déjà appliqué avant même que le crédit
     * asynchrone ne démarre — un second clic pendant que le premier crédit
     * est en vol revoit un inventaire déjà réduit et échoue naturellement au
     * contrôle de stock, sans dupliquer l'objet ni la monnaie.
     */
    private void buyFromPlayer(Player player, MerchantOffer offer) {
        ItemStack template = createOfferStack(offer);
        if (template == null) {
            player.sendMessage(MM.deserialize("<red>Cette offre n'est plus disponible.</red>"));
            return;
        }
        if (!player.getInventory().containsAtLeast(template, offer.quantity())) {
            player.sendMessage(MM.deserialize("<red>Tu n'as pas assez de <item> (<amount> requis).</red>",
                    Placeholder.unparsed("item", describeItem(offer)),
                    Placeholder.unparsed("amount", String.valueOf(offer.quantity()))));
            return;
        }
        player.getInventory().removeItem(template);

        if (offer.price() == 0) {
            player.sendMessage(MM.deserialize("<green>Vendu :</green> <white><amount>x <item></white>",
                    Placeholder.unparsed("amount", String.valueOf(offer.quantity())),
                    Placeholder.unparsed("item", describeItem(offer))));
            return;
        }
        economyService.credit(player.getUniqueId(), offer.price(), TransactionType.MERCHANT_SELL, describeItem(offer))
                .thenAccept(v -> runOnMainThread(() -> player.sendMessage(MM.deserialize(
                        "<green>Vendu :</green> <white><amount>x <item></white> <gray>(+<price>)</gray>",
                        Placeholder.unparsed("amount", String.valueOf(offer.quantity())),
                        Placeholder.unparsed("item", describeItem(offer)),
                        Placeholder.unparsed("price", String.valueOf(offer.price()))))))
                .exceptionally(error -> {
                    logger.error("Échec du crédit après une vente à un marchand pour {} : objet rendu.",
                            player.getUniqueId(), error);
                    runOnMainThread(() -> giveOfferItem(player, offer));
                    return null;
                });
    }

    private void giveOfferItem(Player player, MerchantOffer offer) {
        ItemStack stack = createOfferStack(offer);
        if (stack == null) {
            player.sendMessage(MM.deserialize("<red>Cette offre n'est plus disponible.</red>"));
            return;
        }
        player.getInventory().addItem(stack).values()
                .forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
    }

    private ItemStack createOfferStack(MerchantOffer offer) {
        return switch (offer.itemKind()) {
            case VANILLA -> new ItemStack(offer.vanillaMaterial(), offer.quantity());
            case CUSTOM -> customItemRegistry.create(offer.customItemId(), offer.quantity()).orElse(null);
        };
    }

    private String describeItem(MerchantOffer offer) {
        return offer.itemKind() == OfferItemKind.VANILLA
                ? offer.vanillaMaterial().name()
                : offer.customItemId().toString();
    }

    private void runOnMainThread(Runnable task) {
        plugin.getServer().getScheduler().runTask(plugin, task);
    }
}
