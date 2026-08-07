package be.lloyd.rpgquest.store;

import be.lloyd.rpgquest.backpack.BackpackService;
import be.lloyd.rpgquest.backpack.model.BackpackSize;
import be.lloyd.rpgquest.bootstrap.PluginService;
import be.lloyd.rpgquest.config.BackpackConfig;
import be.lloyd.rpgquest.config.StoreConfig;
import be.lloyd.rpgquest.database.PlayerProfileRepository;
import be.lloyd.rpgquest.database.StoreDeliveryRepository;
import be.lloyd.rpgquest.entitlement.EntitlementService;
import be.lloyd.rpgquest.store.model.StoreProductDefinition;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.slf4j.Logger;

/**
 * Sonde périodiquement web-api pour les livraisons en attente (mission
 * étape 22, points 5-10) et les applique via les services existants
 * ({@link EntitlementService}, {@link BackpackService}) — jamais un accès
 * direct à web-api pour lire/écrire un avantage, le serveur de jeu reste
 * l'autorité finale (mission, validation).
 *
 * <p>Fonctionne entièrement à partir de l'UUID du joueur, jamais besoin
 * qu'il soit connecté ("joueur hors ligne", mission point 10) :
 * {@link PlayerProfileRepository#findOrCreate} crée son profil au besoin
 * ("UUID inconnu") avant tout octroi. Un octroi déjà présent au palier
 * cible ou supérieur ("produit déjà possédé") est traité comme un succès
 * sans effet, jamais une erreur. Un échec (web-api indisponible, base
 * locale indisponible...) laisse simplement la livraison en attente pour le
 * prochain sondage (mission point 10, "échec temporaire" ; test "reprise
 * après crash") — aucune logique de nouvelle tentative dédiée n'est
 * nécessaire.</p>
 */
public final class StoreDeliveryService implements PluginService {

    private static final long HOUSEKEEPING_INTERVAL_TICKS = 100L; // 5 s
    private static final int DELIVERIES_PER_POLL = 50;

    private final Plugin plugin;
    private final StoreClient client;
    private final StoreProductRegistry productRegistry;
    private final StoreDeliveryRepository processedRepository;
    private final PlayerProfileRepository profileRepository;
    private final EntitlementService entitlementService;
    private final BackpackService backpackService;
    private final Supplier<StoreConfig> config;
    private final Supplier<BackpackConfig> backpackConfig;
    private final Logger logger;
    private final LongSupplier clock;

    private BukkitTask task;
    private volatile long lastPollMillis = Long.MIN_VALUE;
    private volatile boolean pollInProgress = false;

    public StoreDeliveryService(Plugin plugin, StoreClient client, StoreProductRegistry productRegistry,
                                 StoreDeliveryRepository processedRepository, PlayerProfileRepository profileRepository,
                                 EntitlementService entitlementService, BackpackService backpackService,
                                 Supplier<StoreConfig> config, Supplier<BackpackConfig> backpackConfig, Logger logger) {
        this(plugin, client, productRegistry, processedRepository, profileRepository, entitlementService,
                backpackService, config, backpackConfig, logger, System::currentTimeMillis);
    }

    StoreDeliveryService(Plugin plugin, StoreClient client, StoreProductRegistry productRegistry,
                          StoreDeliveryRepository processedRepository, PlayerProfileRepository profileRepository,
                          EntitlementService entitlementService, BackpackService backpackService,
                          Supplier<StoreConfig> config, Supplier<BackpackConfig> backpackConfig, Logger logger,
                          LongSupplier clock) {
        this.plugin = plugin;
        this.client = client;
        this.productRegistry = productRegistry;
        this.processedRepository = processedRepository;
        this.profileRepository = profileRepository;
        this.entitlementService = entitlementService;
        this.backpackService = backpackService;
        this.config = config;
        this.backpackConfig = backpackConfig;
        this.logger = logger;
        this.clock = clock;
    }

    @Override
    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(
                plugin, (Runnable) this::tick, HOUSEKEEPING_INTERVAL_TICKS, HOUSEKEEPING_INTERVAL_TICKS);
    }

    @Override
    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    /** Force un sondage immédiat, quel que soit l'intervalle configuré (outil de test/admin). */
    public CompletableFuture<Void> triggerNow() {
        return poll();
    }

    private void tick() {
        StoreConfig current = config.get();
        if (!current.enabled()) {
            return;
        }
        long now = clock.getAsLong();
        if (pollInProgress || now - lastPollMillis < current.pollIntervalSeconds() * 1000L) {
            return;
        }
        poll();
    }

    private CompletableFuture<Void> poll() {
        pollInProgress = true;
        return client.fetchPendingDeliveries(DELIVERIES_PER_POLL)
                .thenCompose(this::processAll)
                .whenComplete((ignored, error) -> {
                    if (error != null) {
                        logger.warn("Sondage des livraisons boutique impossible (web-api indisponible ?), "
                                + "nouvelle tentative au prochain sondage.", error);
                    }
                    lastPollMillis = clock.getAsLong();
                    pollInProgress = false;
                });
    }

    private CompletableFuture<Void> processAll(List<PendingDelivery> deliveries) {
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (PendingDelivery delivery : deliveries) {
            chain = chain.thenCompose(ignored -> processOne(delivery));
        }
        return chain;
    }

    private CompletableFuture<Void> processOne(PendingDelivery delivery) {
        CompletableFuture<Void> attempt = processedRepository.isProcessed(delivery.id()).thenCompose(alreadyProcessed -> {
            if (alreadyProcessed) {
                // Filet de sécurité (mission point 7) : déjà octroyé localement, on ré-acquitte au cas
                // où l'accusé précédent n'aurait pas atteint web-api — jamais un second octroi.
                return client.acknowledgeDelivery(delivery.id(), true, "already-processed-locally")
                        .thenApply(ignored -> (Void) null);
            }
            return deliver(delivery);
        });

        // handle+thenCompose plutôt que exceptionally seul : l'accusé « échec » (mission point 10,
        // "échec temporaire") doit être attendu avant de considérer cette livraison traitée pour ce
        // cycle de sondage, jamais laissé en tâche de fond non attendue (source de rejeu prématuré).
        return attempt.handle((ignored, error) -> {
            if (error == null) {
                return CompletableFuture.<Void>completedFuture(null);
            }
            Throwable cause = error.getCause() != null ? error.getCause() : error;
            logger.error("Échec du traitement de la livraison {} (produit {}, joueur {}).",
                    delivery.id(), delivery.productId(), delivery.playerUuid(), cause);
            return client.acknowledgeDelivery(delivery.id(), false, String.valueOf(cause.getMessage()))
                    .exceptionally(ackError -> {
                        logger.warn("Échec de l'accusé de réception (échec) pour {}.", delivery.id(), ackError);
                        return null;
                    })
                    .thenApply(ignoredAck -> (Void) null);
        }).thenCompose(future -> future);
    }

    private CompletableFuture<Void> deliver(PendingDelivery delivery) {
        Optional<StoreProductDefinition> productOpt = productRegistry.find(delivery.productId());
        if (productOpt.isEmpty()) {
            // Catalogue web-api et registre plugin désynchronisés : ne jamais acquitter à l'aveugle un
            // octroi qu'on ne sait pas interpréter — reste en attente pour qu'un admin corrige plutôt
            // que de perdre silencieusement l'achat.
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "Produit boutique inconnu côté plugin : \"" + delivery.productId()
                            + "\" — vérifie plugins/RPGQuest/store-products/."));
        }
        StoreProductDefinition product = productOpt.get();
        String playerName = delivery.playerName() != null ? delivery.playerName() : delivery.playerUuid().toString();

        return profileRepository.findOrCreate(delivery.playerUuid(), playerName).thenCompose(profile -> {
            CompletableFuture<Void> apply = switch (delivery.kind()) {
                case "GRANT" -> applyGrant(delivery.playerUuid(), product);
                case "REVOKE" -> applyRevoke(delivery.playerUuid(), product);
                default -> CompletableFuture.failedFuture(
                        new IllegalStateException("Type de livraison inconnu : \"" + delivery.kind() + "\"."));
            };
            return apply
                    .thenCompose(ignored -> processedRepository.markProcessed(delivery.id(), "delivered", null))
                    .thenCompose(ignored -> client.acknowledgeDelivery(delivery.id(), true, null))
                    .thenApply(ignored -> null);
        });
    }

    private CompletableFuture<Void> applyGrant(UUID playerId, StoreProductDefinition product) {
        return switch (product.grantType()) {
            case BACKPACK_SIZE -> applyBackpackGrant(playerId, product.backpackSize());
            case ENTITLEMENT -> applyEntitlementGrant(playerId, product.entitlementKey(), product.entitlementTier());
        };
    }

    private CompletableFuture<Void> applyBackpackGrant(UUID playerId, BackpackSize targetSize) {
        return entitlementService.currentTier(playerId, BackpackService.ENTITLEMENT_KEY).thenCompose(currentTierOpt -> {
            BackpackSize current = currentTierOpt.map(this::parseBackpackSize).orElse(null);
            if (current != null && current.ordinal() >= targetSize.ordinal()) {
                // Produit déjà possédé, ou palier inférieur à ce que le joueur a déjà (mission point 10) :
                // rien à faire, ni erreur ni double octroi.
                return CompletableFuture.completedFuture(null);
            }
            String reason = "store:" + targetSize;
            return entitlementService.grant(playerId, BackpackService.ENTITLEMENT_KEY, targetSize.name(), reason)
                    .thenCompose(ignored -> backpackService.applySizeChange(playerId, targetSize));
        });
    }

    private CompletableFuture<Void> applyEntitlementGrant(UUID playerId, String key, String tier) {
        return entitlementService.currentTier(playerId, key).thenCompose(currentTierOpt -> {
            if (currentTierOpt.isPresent() && currentTierOpt.get().equals(tier)) {
                return CompletableFuture.completedFuture(null);
            }
            return entitlementService.grant(playerId, key, tier, "store:" + key);
        });
    }

    /**
     * Remboursement (mission point 10). Pour un backpack, le palier retombe sur
     * {@code backpacks.fallback-size} plutôt que sur un calcul dépendant d'une permission par-joueur
     * (impossible à vérifier hors ligne, voir docs/STORE.md pour cette simplification assumée).
     */
    private CompletableFuture<Void> applyRevoke(UUID playerId, StoreProductDefinition product) {
        return switch (product.grantType()) {
            case BACKPACK_SIZE -> entitlementService.revoke(playerId, BackpackService.ENTITLEMENT_KEY, "store-refund")
                    .thenCompose(ignored -> backpackService.applySizeChange(playerId, backpackConfig.get().fallbackSize()));
            case ENTITLEMENT -> entitlementService.revoke(playerId, product.entitlementKey(), "store-refund");
        };
    }

    private BackpackSize parseBackpackSize(String raw) {
        try {
            return BackpackSize.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
