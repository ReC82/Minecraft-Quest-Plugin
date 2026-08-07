package be.lloyd.rpgquest.webapi.store;

import be.lloyd.rpgquest.webapi.json.Json;
import be.lloyd.rpgquest.webapi.json.JsonParseException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Orchestre achat, webhook et livraisons (mission étape 22). Le serveur de
 * jeu (plugin) reste l'autorité finale pour les avantages eux-mêmes — cette
 * classe ne fait qu'enqueue des livraisons {@link DeliveryKind#GRANT}/
 * {@link DeliveryKind#REVOKE}, jamais n'accorde ou ne retire directement
 * quoi que ce soit (web-api n'a de toute façon aucun accès à data.db).
 */
public final class StoreService {

    private final ProductCatalog catalog;
    private final StoreRepository repository;
    private final PaymentProvider paymentProvider;
    private final String webhookSecret;

    public StoreService(ProductCatalog catalog, StoreRepository repository, PaymentProvider paymentProvider,
                         String webhookSecret) {
        this.catalog = catalog;
        this.repository = repository;
        this.paymentProvider = paymentProvider;
        this.webhookSecret = webhookSecret;
    }

    public ProductCatalog catalog() {
        return catalog;
    }

    // ---- Achat --------------------------------------------------------------------------------

    /** Aucune donnée de carte bancaire n'est jamais demandée ici (mission point 4) : seuls produit + UUID joueur. */
    public CompletableFuture<CheckoutResult> checkout(String productId, String rawPlayerUuid, String playerName) {
        Optional<Product> productOpt = catalog.find(productId);
        if (productOpt.isEmpty()) {
            return CompletableFuture.completedFuture(CheckoutResult.unknownProduct());
        }
        UUID playerUuid;
        try {
            playerUuid = UUID.fromString(rawPlayerUuid);
        } catch (IllegalArgumentException | NullPointerException e) {
            return CompletableFuture.completedFuture(CheckoutResult.invalidPlayerUuid());
        }

        Product product = productOpt.get();
        String orderId = UUID.randomUUID().toString();
        String sessionId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        Order order = new Order(orderId, product.id(), playerUuid, playerName, OrderStatus.PENDING,
                product.priceCents(), product.currency(), sessionId, now, now);

        return repository.createOrder(order).thenApply(ignored -> {
            paymentProvider.registerSession(sessionId, orderId);
            return CheckoutResult.created(paymentProvider.payPageUrl(sessionId));
        });
    }

    public CompletableFuture<Optional<Order>> orderForSession(String sessionId) {
        return repository.findOrderBySessionId(sessionId);
    }

    /** Déclenché par la page de paiement sandbox ("Payer") : simule le prestataire confirmant le paiement. */
    public CompletableFuture<Boolean> confirmPayment(String sessionId) {
        return simulateProviderOutcome(sessionId, true);
    }

    /** Déclenché par la page de paiement sandbox ("Simuler un échec"). */
    public CompletableFuture<Boolean> failPayment(String sessionId) {
        return simulateProviderOutcome(sessionId, false);
    }

    private CompletableFuture<Boolean> simulateProviderOutcome(String sessionId, boolean succeeded) {
        Optional<String> orderIdOpt = paymentProvider.orderIdForSession(sessionId);
        if (orderIdOpt.isEmpty()) {
            return CompletableFuture.completedFuture(false);
        }
        return repository.findOrder(orderIdOpt.get()).thenCompose(orderOpt -> orderOpt.isEmpty()
                ? CompletableFuture.completedFuture(false)
                : paymentProvider.dispatchWebhook(orderOpt.get(), succeeded).thenApply(ignored -> true));
    }

    // ---- Webhook ------------------------------------------------------------------------------

    public enum WebhookOutcome {
        PROCESSED, DUPLICATE, INVALID_SIGNATURE, UNKNOWN_ORDER, MALFORMED
    }

    /**
     * Traite une notification du prestataire (mission point 9, signature vérifiée d'abord — test
     * "signature invalide"). Idempotent par {@code eventId} (mission, test "webhook répété") : un
     * événement déjà vu ne modifie plus rien et ne relivre jamais deux fois.
     */
    public CompletableFuture<WebhookOutcome> handleWebhook(byte[] payload, String signatureHeader) {
        if (!WebhookSigner.isValid(webhookSecret, payload, signatureHeader)) {
            return CompletableFuture.completedFuture(WebhookOutcome.INVALID_SIGNATURE);
        }

        Map<String, Object> event;
        try {
            Object parsed = Json.parse(new String(payload, StandardCharsets.UTF_8));
            if (!(parsed instanceof Map<?, ?> raw)) {
                return CompletableFuture.completedFuture(WebhookOutcome.MALFORMED);
            }
            event = castStringKeyed(raw);
        } catch (JsonParseException e) {
            return CompletableFuture.completedFuture(WebhookOutcome.MALFORMED);
        }

        Object eventIdRaw = event.get("eventId");
        Object orderIdRaw = event.get("orderId");
        Object statusRaw = event.get("status");
        if (!(eventIdRaw instanceof String eventId) || !(orderIdRaw instanceof String orderId)
                || !(statusRaw instanceof String status)) {
            return CompletableFuture.completedFuture(WebhookOutcome.MALFORMED);
        }

        return repository.recordWebhookEventIfNew(eventId, orderId).thenCompose(isNew -> {
            if (!isNew) {
                return CompletableFuture.completedFuture(WebhookOutcome.DUPLICATE);
            }
            return repository.findOrder(orderId).thenCompose(orderOpt -> {
                if (orderOpt.isEmpty()) {
                    return CompletableFuture.completedFuture(WebhookOutcome.UNKNOWN_ORDER);
                }
                Order order = orderOpt.get();
                if ("paid".equals(status)) {
                    return repository.updateOrderStatus(orderId, OrderStatus.PAID)
                            .thenCompose(ignored -> enqueueDelivery(order, DeliveryKind.GRANT))
                            .thenApply(ignored -> WebhookOutcome.PROCESSED);
                }
                return repository.updateOrderStatus(orderId, OrderStatus.FAILED)
                        .thenApply(ignored -> WebhookOutcome.PROCESSED);
            });
        });
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castStringKeyed(Map<?, ?> raw) {
        return (Map<String, Object>) raw;
    }

    // ---- Remboursement / révocation ------------------------------------------------------------

    public enum RefundOutcome {
        REFUNDED, UNKNOWN_ORDER, INVALID_STATE
    }

    /** Simule un remboursement (mission point 10) : bascule la commande et enqueue une révocation. */
    public CompletableFuture<RefundOutcome> refund(String orderId) {
        return repository.findOrder(orderId).thenCompose(orderOpt -> {
            if (orderOpt.isEmpty()) {
                return CompletableFuture.completedFuture(RefundOutcome.UNKNOWN_ORDER);
            }
            Order order = orderOpt.get();
            if (order.status() != OrderStatus.PAID) {
                return CompletableFuture.completedFuture(RefundOutcome.INVALID_STATE);
            }
            return repository.updateOrderStatus(orderId, OrderStatus.REFUNDED)
                    .thenCompose(ignored -> enqueueDelivery(order, DeliveryKind.REVOKE))
                    .thenApply(ignored -> RefundOutcome.REFUNDED);
        });
    }

    private CompletableFuture<Void> enqueueDelivery(Order order, DeliveryKind kind) {
        Delivery delivery = new Delivery(UUID.randomUUID().toString(), order.id(), kind, order.productId(),
                order.playerUuid(), order.playerName(), DeliveryStatus.PENDING, 0, Instant.now(), null, null);
        return repository.enqueueDelivery(delivery);
    }

    // ---- Livraisons (sondées par le serveur de jeu) --------------------------------------------

    public CompletableFuture<List<Delivery>> pendingDeliveries(int limit) {
        return repository.pendingDeliveries(limit);
    }

    public CompletableFuture<StoreRepository.AckOutcome> acknowledgeDelivery(String deliveryId, boolean delivered, String detail) {
        return repository.acknowledgeDelivery(deliveryId, delivered, detail);
    }

    // ---- Historique admin -----------------------------------------------------------------------

    public CompletableFuture<List<Order>> ordersForPlayer(UUID playerUuid) {
        return repository.findOrdersByPlayer(playerUuid);
    }

    public CompletableFuture<List<Order>> allOrders(int limit) {
        return repository.allOrders(limit);
    }

    public CompletableFuture<List<Delivery>> deliveriesForOrder(String orderId) {
        return repository.deliveriesForOrder(orderId);
    }
}
