package com.lodygames.rpgquest.economy.merchant;

import com.lodygames.rpgquest.economy.CurrencyName;
import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.bootstrap.PluginService;
import com.lodygames.rpgquest.economy.EconomyService;
import com.lodygames.rpgquest.economy.TransactionType;
import com.lodygames.rpgquest.economy.merchant.model.MerchantDefinition;
import com.lodygames.rpgquest.economy.merchant.model.MerchantOffer;
import com.lodygames.rpgquest.economy.merchant.model.OfferItemKind;
import com.lodygames.rpgquest.economy.merchant.model.TradeDirection;
import com.lodygames.rpgquest.economy.price.PriceCatalog;
import com.lodygames.rpgquest.economy.price.PriceCatalogService;
import com.lodygames.rpgquest.economy.price.PriceDefinition;
import com.lodygames.rpgquest.economy.price.PriceDefinition.PriceItem;
import com.lodygames.rpgquest.economy.price.PriceType;
import com.lodygames.rpgquest.item.YamlCustomItemRegistry;
import com.lodygames.rpgquest.item.delivery.ItemDelivery;
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
import org.bukkit.Material;
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
 *
 * <p><b>Prix du catalogue</b> (issue #336) : une offre {@code price-ref} prend sens,
 * objet, quantité et prix dans {@link PriceCatalogService}. Prix absent ou
 * désactivé = offre « indisponible », jamais un montant par défaut. Une
 * fabrication ({@code CRAFT}) débite <em>d'abord</em> les pièces, puis prend les
 * ingrédients et remet l'objet sur le thread principal ; si les ingrédients ont
 * disparu entre-temps (second clic, dépôt), si la place manque ou si le joueur
 * est parti, seules les pièces sont remboursées — aucun objet n'est jamais
 * pris sans être compensé.</p>
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
    private final PriceCatalogService priceCatalog;
    private final Logger logger;

    private final Map<UUID, MerchantSession> sessions = new ConcurrentHashMap<>();

    public MerchantTradeService(RPGQuestPlugin plugin, YamlMerchantRegistry merchantRegistry, EconomyService economyService,
                                 YamlCustomItemRegistry customItemRegistry, QuestProgressEngine questProgressEngine) {
        this(plugin, merchantRegistry, economyService, customItemRegistry, questProgressEngine, null);
    }

    /** @param priceCatalog catalogue des prix (issue #336) ; {@code null} = toute offre liée est indisponible */
    public MerchantTradeService(RPGQuestPlugin plugin, YamlMerchantRegistry merchantRegistry, EconomyService economyService,
                                 YamlCustomItemRegistry customItemRegistry, QuestProgressEngine questProgressEngine,
                                 PriceCatalogService priceCatalog) {
        this.priceCatalog = priceCatalog;
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
        MerchantOffer offer = merchant.offers().get(slot);
        if (offer.linked() && session.priceGeneration() != priceGeneration()) {
            pricesChanged(player, merchant);
            return;
        }
        checkRequirementsAndTrade(player, offer, session.priceGeneration());
    }

    /** Les prix ont été modifiés depuis l'affichage : rien n'est échangé, la vitrine est réaffichée. */
    private void pricesChanged(Player player, MerchantDefinition merchant) {
        player.sendMessage(MM.deserialize("<yellow>Les prix viennent de changer :</yellow> <gray>la vitrine est "
                + "mise à jour, rien n'a été échangé. Vérifie le prix puis clique de nouveau.</gray>"));
        render(player, merchant);
    }

    // ---- Rendu ------------------------------------------------------------

    private void render(Player player, MerchantDefinition merchant) {
        long generation = priceGeneration();
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
        // Après l'ouverture : fermer l'ancienne vitrine (réaffichage) retire la session précédente.
        sessions.put(player.getUniqueId(), new MerchantSession(merchant.id(), generation));
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
        if (offer.linked()) {
            return buildLinkedIcon(offer);
        }
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
        lore.add(MM.deserialize("<white>Prix :</white> <gold><price> " + CurrencyName.get() + "</gold>",
                Placeholder.unparsed("price", String.valueOf(offer.price()))));
        addRequirementLore(offer, lore);
        meta.lore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    /**
     * Icône d'une offre liée (issue #336) : le prix du catalogue, ou une barrière « indisponible »
     * qui garde la place de l'offre (les index de clic restent ceux du fichier du marchand).
     */
    private ItemStack buildLinkedIcon(MerchantOffer offer) {
        Optional<PriceDefinition> price = usablePrice(offer);
        ItemStack stack = price.map(p -> createStack(p.item())).orElse(null);
        if (stack == null) {
            ItemStack barrier = new ItemStack(Material.BARRIER);
            ItemMeta meta = barrier.getItemMeta();
            meta.displayName(MM.deserialize("<red>Offre indisponible</red>"));
            meta.lore(List.of(MM.deserialize("<gray>Aucun prix actif pour cette offre.</gray>")));
            barrier.setItemMeta(meta);
            if (price.isEmpty()) {
                logger.warn("Offre de marchand indisponible : prix « {} » absent ou désactivé dans prices.yml.",
                        offer.priceRef());
            } else {
                logger.warn("Offre de marchand indisponible : objet introuvable pour le prix « {} ».", offer.priceRef());
            }
            return barrier;
        }
        PriceDefinition p = price.get();
        ItemMeta meta = stack.getItemMeta();
        List<Component> lore = new ArrayList<>();
        switch (p.type()) {
            case SELL_TO_PLAYER -> lore.add(MM.deserialize("<yellow>Achat</yellow> <gray>: clic pour acheter</gray>"));
            case BUY_FROM_PLAYER -> lore.add(MM.deserialize("<yellow>Vente</yellow> <gray>: clic pour vendre</gray>"));
            case CRAFT -> lore.add(MM.deserialize("<yellow>Fabrication</yellow> <gray>: clic pour fabriquer</gray>"));
        }
        lore.add(MM.deserialize("<white>Quantité :</white> <gray><amount></gray>",
                Placeholder.unparsed("amount", String.valueOf(p.item().amount()))));
        if (p.type() == PriceType.CRAFT) {
            lore.add(MM.deserialize("<white>Ingrédients :</white>"));
            for (PriceItem ingredient : p.ingredients()) {
                lore.add(MM.deserialize("<gray> - <amount> × <item></gray>",
                        Placeholder.unparsed("amount", String.valueOf(ingredient.amount())),
                        Placeholder.unparsed("item", ingredient.itemId())));
            }
            lore.add(MM.deserialize("<white>Coût :</white> <gold><price> " + CurrencyName.get() + "</gold>",
                    Placeholder.unparsed("price", String.valueOf(p.coins()))));
        } else {
            lore.add(MM.deserialize("<white>Prix :</white> <gold><price> " + CurrencyName.get() + "</gold>",
                    Placeholder.unparsed("price", String.valueOf(p.coins()))));
        }
        addRequirementLore(offer, lore);
        meta.lore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    private void addRequirementLore(MerchantOffer offer, List<Component> lore) {
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
    }

    // ---- Conditions ---------------------------------------------------------

    private void checkRequirementsAndTrade(Player player, MerchantOffer offer, long generation) {
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
                            executeTrade(player, offer, generation);
                        }
                    })).exceptionally(error -> {
                logger.error("Échec de la vérification de quête pour une offre de marchand ({})", player.getUniqueId(), error);
                return null;
            });
            return;
        }
        executeTrade(player, offer, generation);
    }

    private void sendDenied(Player player, String message) {
        player.sendMessage(MM.deserialize(message));
    }

    // ---- Échange --------------------------------------------------------------

    private void executeTrade(Player player, MerchantOffer offer, long generation) {
        if (offer.linked()) {
            executeLinkedTrade(player, offer, generation);
            return;
        }
        switch (offer.direction()) {
            case SELL_TO_PLAYER -> sellToPlayer(player, offer, describeItem(offer));
            case BUY_FROM_PLAYER -> buyFromPlayer(player, offer, describeItem(offer));
        }
    }

    /**
     * Offre liée (issue #336) : prix relu dans la génération affichée — si le catalogue a changé
     * pendant la vérification de quête, rien n'est échangé. La trace de transaction porte la clé et
     * la version du prix appliqué.
     */
    private void executeLinkedTrade(Player player, MerchantOffer offer, long generation) {
        if (generation != priceGeneration()) {
            player.sendMessage(MM.deserialize("<yellow>Les prix viennent de changer :</yellow> <gray>rien n'a été "
                    + "échangé, rouvre la boutique.</gray>"));
            return;
        }
        Optional<PriceDefinition> priceOpt = usablePrice(offer);
        if (priceOpt.isEmpty()) {
            player.sendMessage(MM.deserialize("<red>Cette offre est indisponible :</red> <gray>aucun prix actif.</gray>"));
            return;
        }
        PriceDefinition price = priceOpt.get();
        String context = "prix:" + price.key() + "@" + price.version() + " " + price.item();
        if (price.type() == PriceType.CRAFT) {
            craft(player, price, context);
            return;
        }
        MerchantOffer inline = inlineOffer(price);
        if (inline == null) {
            player.sendMessage(MM.deserialize("<red>Cette offre n'est plus disponible.</red>"));
            return;
        }
        if (price.type() == PriceType.SELL_TO_PLAYER) {
            sellToPlayer(player, inline, context);
        } else {
            buyFromPlayer(player, inline, context);
        }
    }

    /** Vente / rachat du catalogue sous la forme historique, pour réutiliser les mêmes garanties. */
    private MerchantOffer inlineOffer(PriceDefinition price) {
        TradeDirection direction = price.type() == PriceType.SELL_TO_PLAYER
                ? TradeDirection.SELL_TO_PLAYER : TradeDirection.BUY_FROM_PLAYER;
        if (price.item().custom()) {
            NamespacedKey id = NamespacedKey.fromString(price.item().itemId());
            return id == null ? null : new MerchantOffer(direction, OfferItemKind.CUSTOM, null, id,
                    price.item().amount(), price.coins(), null, null, null, null);
        }
        Material material = Material.matchMaterial(price.item().itemId());
        return material == null ? null : new MerchantOffer(direction, OfferItemKind.VANILLA, material, null,
                price.item().amount(), price.coins(), null, null, null, null);
    }

    // ---- Fabrication (issue #336) -----------------------------------------------

    /**
     * Fabrication : contrôles (ingrédients exacts, place pour le résultat une fois les ingrédients
     * pris), débit des pièces, puis — sur le thread principal — nouvelle vérification, retrait des
     * ingrédients et remise du résultat en une seule passe synchrone. Un échec après le débit ne
     * rembourse que les pièces : les ingrédients n'ont pas encore été touchés.
     */
    private void craft(Player player, PriceDefinition price, String context) {
        ItemStack result = createStack(price.item());
        List<ItemStack> ingredients = new ArrayList<>();
        for (PriceItem ingredient : price.ingredients()) {
            ingredients.add(createStack(ingredient));
        }
        if (result == null || ingredients.contains(null)) {
            player.sendMessage(MM.deserialize("<red>Cette offre n'est plus disponible.</red>"));
            return;
        }
        if (!craftPossible(player, price, ingredients, result)) {
            return;
        }
        if (price.coins() == 0) {
            completeCraft(player, price, ingredients, result);
            return;
        }
        economyService.debit(player.getUniqueId(), price.coins(), TransactionType.ARTISAN_CRAFT, context)
                .thenAccept(success -> runOnMainThread(() -> {
                    if (!success) {
                        player.sendMessage(MM.deserialize("<red>Fonds insuffisants.</red>"));
                        return;
                    }
                    if (!player.isOnline() || !completeCraft(player, price, ingredients, result)) {
                        refundCraft(player, price, context);
                    }
                }))
                .exceptionally(error -> {
                    logger.error("Échec du débit lors d'une fabrication pour {}", player.getUniqueId(), error);
                    runOnMainThread(() -> player.sendMessage(MM.deserialize("<red>Une erreur est survenue, réessaie.</red>")));
                    return null;
                });
    }

    /** Ingrédients présents et place suffisante une fois ceux-ci pris ; sinon message au joueur. */
    private boolean craftPossible(Player player, PriceDefinition price, List<ItemStack> ingredients, ItemStack result) {
        for (int i = 0; i < ingredients.size(); i++) {
            PriceItem wanted = price.ingredients().get(i);
            if (!player.getInventory().containsAtLeast(ingredients.get(i), wanted.amount())) {
                player.sendMessage(MM.deserialize("<red>Il te manque des ingrédients :</red> <white><amount> × "
                                + "<item></white> <gray>requis.</gray>",
                        Placeholder.unparsed("amount", String.valueOf(wanted.amount())),
                        Placeholder.unparsed("item", wanted.itemId())));
                return false;
            }
        }
        Inventory simulated = Bukkit.createInventory(null, 36);
        ItemStack[] contents = player.getInventory().getStorageContents();
        for (int i = 0; i < contents.length && i < simulated.getSize(); i++) {
            simulated.setItem(i, contents[i] == null ? null : contents[i].clone());
        }
        ingredients.forEach(ingredient -> simulated.removeItem(ingredient.clone()));
        int missing = ItemDelivery.missingSlots(simulated, List.of(result));
        if (missing > 0) {
            player.sendMessage(ItemDelivery.inventoryFullMessage(missing));
            return false;
        }
        return true;
    }

    /** Thread principal : revérifie, retire les ingrédients puis remet le résultat. Faux = rien n'a été pris. */
    private boolean completeCraft(Player player, PriceDefinition price, List<ItemStack> ingredients, ItemStack result) {
        if (!craftPossible(player, price, ingredients, result)) {
            return false;
        }
        ItemStack[] snapshot = cloneContents(player.getInventory().getStorageContents());
        ingredients.forEach(ingredient -> player.getInventory().removeItem(ingredient.clone()));
        ItemDelivery.Result delivery = ItemDelivery.deliver(player, result);
        if (!delivery.delivered()) {
            // Ne devrait pas arriver après la simulation : retour exact à l'inventaire d'avant.
            player.getInventory().setStorageContents(snapshot);
            player.sendMessage(ItemDelivery.inventoryFullMessage(delivery.missingSlots()));
            return false;
        }
        player.sendMessage(MM.deserialize(
                "<green>Fabrication réussie :</green> <white><amount>x <item></white> <gray>(-<price>)</gray>",
                Placeholder.unparsed("amount", String.valueOf(price.item().amount())),
                Placeholder.unparsed("item", price.item().itemId()),
                Placeholder.unparsed("price", String.valueOf(price.coins()))));
        return true;
    }

    private void refundCraft(Player player, PriceDefinition price, String context) {
        UUID playerId = player.getUniqueId();
        economyService.credit(playerId, price.coins(), TransactionType.ARTISAN_CRAFT, "remboursement:" + context)
                .thenRun(() -> runOnMainThread(() -> player.sendMessage(MM.deserialize(
                        "<yellow>Fabrication annulée et remboursée</yellow> <gray>(+<price>) : aucun ingrédient n'a été "
                                + "pris.</gray>",
                        Placeholder.unparsed("price", String.valueOf(price.coins()))))))
                .exceptionally(error -> {
                    logger.error("Remboursement impossible après une fabrication non remise : {} doit {} à {}",
                            context, price.coins(), playerId, error);
                    return null;
                });
    }

    private static ItemStack[] cloneContents(ItemStack[] contents) {
        ItemStack[] copy = new ItemStack[contents.length];
        for (int i = 0; i < contents.length; i++) {
            copy[i] = contents[i] == null ? null : contents[i].clone();
        }
        return copy;
    }

    private Optional<PriceDefinition> usablePrice(MerchantOffer offer) {
        PriceCatalog catalog = priceCatalog == null ? PriceCatalog.EMPTY : priceCatalog.catalog();
        return catalog.usable(offer.priceRef());
    }

    private long priceGeneration() {
        return priceCatalog == null ? 0 : priceCatalog.generation();
    }

    /** Objet d'un prix du catalogue, avec sa quantité ; {@code null} si l'objet n'existe pas sur ce serveur. */
    private ItemStack createStack(PriceItem item) {
        if (item.custom()) {
            NamespacedKey id = NamespacedKey.fromString(item.itemId());
            return id == null ? null : customItemRegistry.create(id, item.amount()).orElse(null);
        }
        Material material = Material.matchMaterial(item.itemId());
        return material == null || !material.isItem() ? null : new ItemStack(material, item.amount());
    }

    /**
     * Le marchand vend : place vérifiée, puis débit (atomique côté base), objet remis seulement si
     * le débit a réussi. Issue #318 : un objet qui ne tient plus au retour du débit (inventaire
     * rempli entre-temps, joueur déconnecté) est <strong>remboursé</strong>, jamais jeté au sol.
     */
    private void sellToPlayer(Player player, MerchantOffer offer, String context) {
        ItemStack stack = createOfferStack(offer);
        if (stack == null) {
            player.sendMessage(MM.deserialize("<red>Cette offre n'est plus disponible.</red>"));
            return;
        }
        int missingSlots = ItemDelivery.missingSlots(player.getInventory(), List.of(stack));
        if (missingSlots > 0) {
            player.sendMessage(ItemDelivery.inventoryFullMessage(missingSlots));
            return;
        }
        if (offer.price() == 0) {
            deliverOfferItem(player, stack);
            return;
        }
        economyService.debit(player.getUniqueId(), offer.price(), TransactionType.MERCHANT_BUY, context)
                .thenAccept(success -> runOnMainThread(() -> {
                    if (!success) {
                        player.sendMessage(MM.deserialize("<red>Fonds insuffisants.</red>"));
                        return;
                    }
                    if (!player.isOnline() || !deliverOfferItem(player, stack)) {
                        refund(player, offer, context);
                        return;
                    }
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

    private boolean deliverOfferItem(Player player, ItemStack stack) {
        ItemDelivery.Result delivery = ItemDelivery.deliver(player, stack);
        if (!delivery.delivered()) {
            player.sendMessage(ItemDelivery.inventoryFullMessage(delivery.missingSlots()));
        }
        return delivery.delivered();
    }

    /** Achat débité mais objet non remis : le prix revient au joueur, une seule fois, avec trace. */
    private void refund(Player player, MerchantOffer offer, String context) {
        UUID playerId = player.getUniqueId();
        economyService.credit(playerId, offer.price(), TransactionType.MERCHANT_SELL, "remboursement:" + context)
                .thenRun(() -> runOnMainThread(() -> player.sendMessage(MM.deserialize(
                        "<yellow>Achat annulé et remboursé</yellow> <gray>(+<price>) : l'objet ne tenait pas dans ton inventaire.</gray>",
                        Placeholder.unparsed("price", String.valueOf(offer.price()))))))
                .exceptionally(error -> {
                    logger.error("Remboursement impossible après un achat marchand non remis : {} doit {} à {}",
                            describeItem(offer), offer.price(), playerId, error);
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
    private void buyFromPlayer(Player player, MerchantOffer offer, String context) {
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
        economyService.credit(player.getUniqueId(), offer.price(), TransactionType.MERCHANT_SELL, context)
                .thenAccept(v -> runOnMainThread(() -> player.sendMessage(MM.deserialize(
                        "<green>Vendu :</green> <white><amount>x <item></white> <gray>(+<price>)</gray>",
                        Placeholder.unparsed("amount", String.valueOf(offer.quantity())),
                        Placeholder.unparsed("item", describeItem(offer)),
                        Placeholder.unparsed("price", String.valueOf(offer.price()))))))
                .exceptionally(error -> {
                    logger.error("Échec du crédit après une vente à un marchand pour {} : objet rendu.",
                            player.getUniqueId(), error);
                    runOnMainThread(() -> restoreOfferItem(player, offer));
                    return null;
                });
    }

    /** Restitution de l'objet du joueur quand le crédit de sa vente a échoué (voir {@link ItemDelivery#restoreOrDrop}). */
    private void restoreOfferItem(Player player, MerchantOffer offer) {
        ItemStack stack = createOfferStack(offer);
        if (stack == null) {
            logger.error("Objet de l'offre {} introuvable : restitution impossible pour {}",
                    describeItem(offer), player.getUniqueId());
            return;
        }
        ItemDelivery.restoreOrDrop(player, List.of(stack), logger, "vente marchand non créditée");
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
