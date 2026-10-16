package com.lodygames.rpgquest.entitlement.reward;

import com.lodygames.rpgquest.bootstrap.PluginService;
import com.lodygames.rpgquest.database.RewardEntitlementRepository;
import com.lodygames.rpgquest.database.StatisticsRepository;
import com.lodygames.rpgquest.entitlement.EntitlementService;
import com.lodygames.rpgquest.item.delivery.ItemDelivery;
import com.lodygames.rpgquest.stats.StatPeriod;
import com.lodygames.rpgquest.stats.StatisticsDefinition;
import com.lodygames.rpgquest.stats.StatisticsService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Droits à récompense (issue #422, docs/REWARD_ENTITLEMENTS.md) : déclare les droits ouverts par
 * l'archive figée des événements, et les remet <strong>au plus une fois</strong> aux joueurs
 * connectés, par la remise sécurisée commune ({@link ItemDelivery}, #318).
 *
 * <p>Protocole de remise (§5) : place vérifiée sans rien modifier (thread principal) → prise
 * conditionnelle {@code PENDING → DELIVERED} (base) → remise tout ou rien (thread principal) ;
 * échec après la prise → compensation {@code DELIVERED → PENDING}. Une garde mémoire par clé évite
 * deux essais simultanés dans ce processus ; la prise conditionnelle tranche entre processus.</p>
 *
 * <p>{@code entitlements.yml} absent, invalide ou {@code enabled: false} : aucune déclaration depuis
 * les événements, aucune remise, aucune déclaration manuelle. La consultation reste possible.</p>
 */
public final class RewardEntitlementService implements PluginService, Listener, RewardTables {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final long CATALOG_INTERVAL_TICKS = 20L * 60;
    private static final long DELIVERY_INTERVAL_TICKS = 20L * 30;
    private static final long JOIN_DELAY_TICKS = 20L * 3;
    private static final Duration NOTICE_INTERVAL = Duration.ofMinutes(5);
    private static final int PENDING_PER_ROUND = 200;
    static final String UNKNOWN_REWARD = "récompense inconnue du catalogue entitlements.yml";

    /** Résultat d'une déclaration manuelle. */
    public record DeclareOutcome(boolean created, String idempotencyKey) {
    }

    /** Issue d'un essai de remise (tests, journal). */
    enum Attempt {
        DISABLED, BUSY, OFFLINE, UNKNOWN_REWARD, INVENTORY_FULL, ALREADY_TAKEN, DELIVERED, RELEASED, ERROR
    }

    private final Plugin plugin;
    private final Path file;
    private final RewardEntitlementRepository repository;
    private final Supplier<StatisticsDefinition> statistics;
    private final Predicate<String> archived;
    private final StatisticsRepository statisticsRepository;
    private final EntitlementService perks;
    private final BiFunction<RewardDefinition.Item, Logger, Optional<ItemStack>> itemFactory;
    private final Logger logger;
    private final Clock clock;
    private final Executor mainThread;

    private volatile RewardCatalog catalog = RewardCatalog.DISABLED;
    private volatile List<String> lastErrors = List.of();
    private final RewardTableEditor tableEditor;
    private final RewardCatalogEditor catalogEditor;
    private long loadedModified = Long.MIN_VALUE;
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private final Set<String> declaredEvents = ConcurrentHashMap.newKeySet();
    private final Map<String, Instant> lastNotice = new ConcurrentHashMap<>();
    private final List<BukkitTask> tasks = new ArrayList<>();
    private volatile boolean stopped;

    /**
     * @param itemFactory construit la pile d'une ligne du catalogue (matériau ou objet custom) ; vide
     *                    = objet inconnu, le droit reste en attente
     */
    public RewardEntitlementService(Plugin plugin, Path file, RewardEntitlementRepository repository,
                                    StatisticsService statistics, StatisticsRepository statisticsRepository,
                                    EntitlementService perks,
                                    BiFunction<RewardDefinition.Item, Logger, Optional<ItemStack>> itemFactory,
                                    Logger logger) {
        this(plugin, file, repository, statistics::definition, statistics::isArchived, statisticsRepository, perks,
                itemFactory, logger, Clock.systemUTC(), task -> Bukkit.getScheduler().runTask(plugin, task));
    }

    RewardEntitlementService(Plugin plugin, Path file, RewardEntitlementRepository repository,
                             Supplier<StatisticsDefinition> statistics, Predicate<String> archived,
                             StatisticsRepository statisticsRepository,
                             EntitlementService perks,
                             BiFunction<RewardDefinition.Item, Logger, Optional<ItemStack>> itemFactory,
                             Logger logger, Clock clock, Executor mainThread) {
        this.plugin = plugin;
        this.file = file;
        this.repository = repository;
        this.statistics = statistics;
        this.archived = archived;
        this.statisticsRepository = statisticsRepository;
        this.perks = perks;
        this.itemFactory = itemFactory;
        this.logger = logger;
        this.clock = clock;
        this.mainThread = mainThread;
        EntitlementsFile shared = new EntitlementsFile(file, this::reloadNow, logger);
        this.tableEditor = new RewardTableEditor(shared);
        this.catalogEditor = new RewardCatalogEditor(shared);
    }

    @Override
    public void start() {
        stopped = false;
        tasks.add(Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::catalogTick, 1L, CATALOG_INTERVAL_TICKS));
        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, this::deliveryTick, DELIVERY_INTERVAL_TICKS,
                DELIVERY_INTERVAL_TICKS));
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public void stop() {
        stopped = true;
        tasks.forEach(BukkitTask::cancel);
        tasks.clear();
        HandlerList.unregisterAll(this);
    }

    public RewardCatalog catalog() {
        return catalog;
    }

    /** Erreurs du dernier chargement de {@code entitlements.yml} (vide = accepté ou absent). */
    public List<String> lastErrors() {
        return lastErrors;
    }

    public Path file() {
        return file;
    }

    /** Écriture des tables depuis PlugAdmin (issue #177), hors du thread principal. */
    public RewardTableEditor tableEditor() {
        return tableEditor;
    }

    /** Administration du catalogue depuis PlugAdmin (issue #474), hors du thread principal. */
    public RewardCatalogEditor catalogEditor() {
        return catalogEditor;
    }

    @Override
    public boolean enabled() {
        return catalog.enabled();
    }

    @Override
    public boolean rewardEnabled(String rewardKey) {
        return catalog.promisable(rewardKey);
    }

    @Override
    public Optional<RewardTable> table(String id) {
        return catalog.findTable(id);
    }

    @Override
    public String label(String rewardKey) {
        return catalog.find(rewardKey).map(RewardDefinition::label).orElse(rewardKey);
    }

    // ---- catalogue et déclarations depuis les événements (hors thread principal) --------------

    private void catalogTick() {
        if (stopped) {
            return;
        }
        try {
            reloadIfChanged();
            declareFromArchivedEvents().join();
        } catch (RuntimeException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof java.util.concurrent.RejectedExecutionException) {
                return; // base en cours d'arrêt : le prochain démarrage reprendra
            }
            logger.warn("Récompenses : déclaration depuis les événements impossible, nouvel essai dans une minute.",
                    cause);
        }
    }

    /**
     * Relit {@code entitlements.yml} tout de suite, même si sa date n'a pas bougé (issue #177 :
     * écriture depuis PlugAdmin, dont l'appelant doit savoir si elle a été acceptée). Hors du thread
     * principal.
     */
    public synchronized RewardCatalogLoader.Result reloadNow() {
        loadedModified = Long.MIN_VALUE;
        return reloadIfChanged();
    }

    synchronized RewardCatalogLoader.Result reloadIfChanged() {
        long modified;
        try {
            modified = Files.exists(file) ? Files.getLastModifiedTime(file).toMillis() : -1L;
        } catch (IOException e) {
            logger.warn("Récompenses : lecture de {} impossible.", file, e);
            return new RewardCatalogLoader.Result(catalog, List.of("lecture impossible : " + e.getMessage()));
        }
        if (modified == loadedModified) {
            return new RewardCatalogLoader.Result(catalog, List.of());
        }
        loadedModified = modified;
        if (modified < 0) {
            catalog = RewardCatalog.DISABLED;
            lastErrors = List.of();
            return new RewardCatalogLoader.Result(catalog, List.of());
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(Files.readString(file));
        } catch (IOException | InvalidConfigurationException e) {
            logger.warn("Récompenses : {} illisible, droits à récompense désactivés.", file, e);
            catalog = RewardCatalog.DISABLED;
            lastErrors = List.of("fichier illisible : " + e.getMessage());
            return new RewardCatalogLoader.Result(catalog, lastErrors);
        }
        RewardCatalogLoader.Result result = RewardCatalogLoader.load(yaml);
        result.errors().forEach(error -> logger.warn("Récompenses : {} — {}", file.getFileName(), error));
        catalog = result.catalog();
        lastErrors = result.errors();
        logger.info("Récompenses : {} récompense(s) chargée(s) depuis {}, {}{}.", catalog.rewards().size(),
                file.getFileName(), catalog.enabled() ? "activé" : "désactivé",
                result.valid() ? "" : " (fichier invalide ignoré)");
        return result;
    }

    /**
     * Déclare les droits ouverts par chaque événement archivé (§6.1). Idempotent : une clé déjà
     * déclarée n'est jamais recréée ; un événement traité n'est relu qu'au prochain démarrage.
     */
    CompletableFuture<Void> declareFromArchivedEvents() {
        if (!catalog.enabled()) {
            return CompletableFuture.completedFuture(null);
        }
        StatisticsDefinition definition = statistics.get();
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (StatPeriod event : definition.events()) {
            if (declaredEvents.contains(event.id()) || event.state() != StatPeriod.State.PLANNED
                    || !archived.test(event.id())) {
                continue;
            }
            if (event.rules().rewards().isEmpty()) {
                declaredEvents.add(event.id());
                continue;
            }
            Set<UUID> excluded = definition.excludedFrom(event.id());
            chain = chain.thenCompose(ignored -> statisticsRepository.archivedValues(event.id(), event.metrics().get(0)))
                    .thenCompose(values -> {
                        List<RewardEntitlement.Declaration> all = EventRewardRecipients.declarations(event, values,
                                excluded);
                        List<RewardEntitlement.Declaration> kept = promisable(all, "événement " + event.id());
                        if (kept.size() == all.size()) {
                            declaredEvents.add(event.id());
                        } // sinon relu à chaque tour : la récompense réactivée sera déclarée (clés idempotentes)
                        return kept.isEmpty() ? CompletableFuture.completedFuture(0)
                                : repository.declareAll(kept, clock.instant());
                    })
                    .thenAccept(created -> {
                        if (created > 0) {
                            logger.info("Récompenses : {} droit(s) déclaré(s) pour l'événement « {} ».",
                                    created, event.id());
                        }
                    });
        }
        return chain;
    }

    // ---- déclarations et révocations manuelles (PlugAdmin) ------------------------------------

    /**
     * Déclaration administrateur (§6.2) : clé {@code admin:<référence>:<uuid>}. Refusée si la
     * fonction est désactivée, la récompense inconnue ou la référence invalide.
     */
    public CompletableFuture<DeclareOutcome> declareAdmin(UUID player, String rewardKey, String reference) {
        if (!catalog.enabled()) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "droits à récompense désactivés (entitlements.yml : enabled: false)"));
        }
        Optional<RewardDefinition> reward = catalog.find(rewardKey);
        if (reward.isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("récompense inconnue : " + rewardKey));
        }
        if (!reward.get().enabled()) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "récompense désactivée dans le catalogue : " + rewardKey));
        }
        String key = "admin:" + reference + ":" + player;
        if (!RewardEntitlement.KEY_PATTERN.matcher(key).matches()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "référence invalide (lettres, chiffres, _ : . -) : " + reference));
        }
        RewardEntitlement.Declaration declaration = new RewardEntitlement.Declaration(
                key, player, RewardSource.ADMIN, reference, rewardKey, reward.get().label());
        return repository.declare(declaration, clock.instant()).thenApply(created -> new DeclareOutcome(created, key));
    }

    /**
     * Déclarations d'une source du jeu (boss communautaire, #176) : idempotentes par clé, refusées si
     * la fonction est désactivée. Une clé de récompense absente du catalogue est tout de même
     * déclarée : le droit reste en attente avec l'erreur visible, jamais perdu.
     *
     * @return nombre de droits réellement créés
     */
    public CompletableFuture<Integer> declareAll(List<RewardEntitlement.Declaration> declarations) {
        if (!catalog.enabled()) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "droits à récompense désactivés (entitlements.yml : enabled: false)"));
        }
        List<RewardEntitlement.Declaration> promisable = promisable(declarations, "déclaration d'une source du jeu");
        if (promisable.isEmpty()) {
            return CompletableFuture.completedFuture(0);
        }
        return repository.declareAll(promisable, clock.instant());
    }

    /**
     * Retire les droits d'une récompense désactivée (issue #474) : elle n'est plus promise à personne.
     * Les sources le font déjà (tirages, boss) ; ceci garantit la règle pour toutes.
     */
    private List<RewardEntitlement.Declaration> promisable(List<RewardEntitlement.Declaration> declarations,
                                                           String context) {
        List<RewardEntitlement.Declaration> kept = declarations.stream()
                .filter(d -> catalog.promisable(d.rewardKey())).toList();
        if (kept.size() < declarations.size()) {
            logger.info("Récompenses : {} droit(s) non déclaré(s) ({}) — récompense désactivée dans le catalogue.",
                    declarations.size() - kept.size(), context);
        }
        return kept;
    }

    /** Tente tout de suite la remise des droits en attente de ces joueurs (sans attendre le tour de 30 s). */
    @Override
    public void deliverSoon(List<UUID> players) {
        deliverPendingOf(players);
    }

    /** Révocation d'un droit en attente. {@code false} = inconnu, déjà remis ou déjà révoqué. */
    public CompletableFuture<Boolean> revoke(String idempotencyKey, String reason) {
        // Pas de garde mémoire ici : si une remise est en vol, la prise et la révocation sont deux
        // UPDATE conditionnels sur PENDING, la base n'en laisse passer qu'un.
        return repository.revoke(idempotencyKey, reason, clock.instant());
    }

    public CompletableFuture<List<RewardEntitlementRepository.Listed>> list(@Nullable UUID player,
                                                                            @Nullable RewardEntitlementStatus status,
                                                                            int limit) {
        return repository.list(player, status, limit);
    }

    // ---- remise (thread principal) ------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        Bukkit.getScheduler().runTaskLater(plugin, () -> deliverPendingOf(List.of(playerId)), JOIN_DELAY_TICKS);
    }

    private void deliveryTick() {
        List<UUID> online = Bukkit.getOnlinePlayers().stream().map(Player::getUniqueId).toList();
        deliverPendingOf(online);
    }

    /** Lit les droits en attente de ces joueurs (hors thread principal), puis tente chaque remise. */
    CompletableFuture<Void> deliverPendingOf(List<UUID> players) {
        if (stopped || !catalog.enabled() || players.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        return repository.pendingFor(players, PENDING_PER_ROUND)
                .thenAccept(pending -> mainThread.execute(() -> pending.forEach(this::attempt)))
                .exceptionally(error -> {
                    logger.warn("Récompenses : lecture des droits en attente impossible.", error);
                    return null;
                });
    }

    /**
     * Un essai de remise, à appeler sur le thread principal. Le future se termine quand le droit est
     * remis, compensé, ou laissé en attente.
     */
    CompletableFuture<Attempt> attempt(RewardEntitlement entitlement) {
        if (!catalog.enabled()) {
            return CompletableFuture.completedFuture(Attempt.DISABLED);
        }
        String key = entitlement.idempotencyKey();
        if (!inFlight.add(key)) {
            return CompletableFuture.completedFuture(Attempt.BUSY);
        }
        CompletableFuture<Attempt> result;
        try {
            result = attemptGuarded(entitlement);
        } catch (RuntimeException e) {
            result = CompletableFuture.failedFuture(e);
        }
        return result.handle((attempt, error) -> {
            inFlight.remove(key);
            if (error != null) {
                logger.error("Récompenses : remise du droit {} impossible.", key, error);
                return Attempt.ERROR;
            }
            return attempt;
        });
    }

    private CompletableFuture<Attempt> attemptGuarded(RewardEntitlement entitlement) {
        String key = entitlement.idempotencyKey();
        Player player = Bukkit.getPlayer(entitlement.playerId());
        if (player == null || !player.isOnline()) {
            return CompletableFuture.completedFuture(Attempt.OFFLINE);
        }
        Optional<RewardDefinition> reward = catalog.find(entitlement.rewardKey());
        if (reward.isEmpty()) {
            return traceFailure(entitlement, UNKNOWN_REWARD + " (« " + entitlement.rewardKey() + " »)");
        }
        List<ItemStack> items = new ArrayList<>();
        for (RewardDefinition.Item line : reward.get().items()) {
            Optional<ItemStack> stack = itemFactory.apply(line, logger);
            if (stack.isEmpty()) {
                return traceFailure(entitlement, "objet, enchantement ou potion inconnu : " + line.itemId());
            }
            items.add(stack.get());
        }
        int missing = ItemDelivery.missingSlots(player.getInventory(), items);
        if (missing > 0) {
            noticeInventoryFull(player, key, missing);
            return CompletableFuture.completedFuture(Attempt.INVENTORY_FULL);
        }
        return repository.claim(key, clock.instant()).thenCompose(won -> {
            if (!won) {
                return CompletableFuture.completedFuture(Attempt.ALREADY_TAKEN);
            }
            CompletableFuture<Attempt> delivered = new CompletableFuture<>();
            mainThread.execute(() -> {
                try {
                    delivered.complete(handOver(entitlement, reward.get(), items));
                } catch (RuntimeException e) {
                    delivered.completeExceptionally(e);
                }
            });
            return delivered.thenCompose(outcome -> outcome == Attempt.DELIVERED
                    ? grantPerk(entitlement, reward.get()).thenApply(ignored -> outcome)
                    : repository.release(key, "remise interrompue (joueur déconnecté ou inventaire modifié)",
                            clock.instant()).thenApply(ignored -> Attempt.RELEASED));
        });
    }

    /** Thread principal, après la prise : la remise elle-même, tout ou rien. */
    private Attempt handOver(RewardEntitlement entitlement, RewardDefinition reward, List<ItemStack> items) {
        Player player = Bukkit.getPlayer(entitlement.playerId());
        if (player == null || !player.isOnline()) {
            return Attempt.RELEASED;
        }
        if (!items.isEmpty() && !ItemDelivery.deliver(player, items).delivered()) {
            return Attempt.RELEASED;
        }
        player.sendMessage(MM.deserialize("<gold>Récompense reçue :</gold> <white><label></white>",
                Placeholder.unparsed("label", reward.label())));
        logger.info("Récompenses : droit {} remis à {} ({}).", entitlement.idempotencyKey(), player.getName(),
                reward.key());
        return Attempt.DELIVERED;
    }

    /** Avantage persistant éventuel : idempotent (même palier = rien), un échec est tracé sur le droit. */
    private CompletableFuture<Void> grantPerk(RewardEntitlement entitlement, RewardDefinition reward) {
        RewardDefinition.Perk perk = reward.perk();
        if (perk == null) {
            return CompletableFuture.completedFuture(null);
        }
        return perks.currentTier(entitlement.playerId(), perk.key())
                .thenCompose(current -> current.filter(perk.tier()::equals).isPresent()
                        ? CompletableFuture.<Void>completedFuture(null)
                        : perks.grant(entitlement.playerId(), perk.key(), perk.tier(),
                                "reward:" + entitlement.idempotencyKey()))
                .exceptionally(error -> {
                    logger.error("Récompenses : avantage {} du droit {} non accordé.", perk.key(),
                            entitlement.idempotencyKey(), error);
                    repository.note(entitlement.idempotencyKey(), "avantage non accordé : " + error.getMessage(),
                            clock.instant());
                    return null;
                });
    }

    /** Erreur de catalogue : écrite une seule fois tant qu'elle ne change pas (pas une écriture par tick). */
    private CompletableFuture<Attempt> traceFailure(RewardEntitlement entitlement, String error) {
        if (error.equals(entitlement.lastError())) {
            return CompletableFuture.completedFuture(Attempt.UNKNOWN_REWARD);
        }
        logger.warn("Récompenses : droit {} en attente — {}.", entitlement.idempotencyKey(), error);
        return repository.recordFailure(entitlement.idempotencyKey(), error, clock.instant())
                .thenApply(ignored -> Attempt.UNKNOWN_REWARD);
    }

    private void noticeInventoryFull(Player player, String key, int missing) {
        Instant now = clock.instant();
        Instant last = lastNotice.get(key);
        if (last != null && last.plus(NOTICE_INTERVAL).isAfter(now)) {
            return;
        }
        lastNotice.put(key, now);
        player.sendMessage(MM.deserialize("<gold>Une récompense t'attend.</gold>"));
        player.sendMessage(ItemDelivery.inventoryFullMessage(missing));
    }
}
