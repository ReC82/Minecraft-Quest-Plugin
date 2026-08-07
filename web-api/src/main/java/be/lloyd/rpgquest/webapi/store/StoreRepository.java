package be.lloyd.rpgquest.webapi.store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Persistance des commandes/livraisons/événements webhook (mission étape
 * 22). Pure JDBC, aucun type web (pas de {@code HttpExchange} ici).
 */
public final class StoreRepository {

    private static final int MAX_DELIVERY_ATTEMPTS = 10;

    private static final String INSERT_ORDER =
            "INSERT INTO orders (id, product_id, player_uuid, player_name, status, amount_cents, currency, "
                    + "provider_session_id, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    private static final String SELECT_ORDER_BY_ID = "SELECT * FROM orders WHERE id = ?";
    private static final String SELECT_ORDER_BY_SESSION = "SELECT * FROM orders WHERE provider_session_id = ?";
    private static final String SELECT_ORDERS_BY_PLAYER = "SELECT * FROM orders WHERE player_uuid = ? ORDER BY created_at DESC";
    private static final String SELECT_ORDERS_ALL = "SELECT * FROM orders ORDER BY created_at DESC LIMIT ?";
    private static final String UPDATE_ORDER_STATUS = "UPDATE orders SET status = ?, updated_at = ? WHERE id = ?";

    private static final String INSERT_DELIVERY =
            "INSERT INTO deliveries (id, order_id, kind, product_id, player_uuid, player_name, status, attempts, created_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, 'PENDING', 0, ?)";
    private static final String SELECT_DELIVERY_BY_ID = "SELECT * FROM deliveries WHERE id = ?";
    private static final String SELECT_DELIVERIES_PENDING = "SELECT * FROM deliveries WHERE status = 'PENDING' ORDER BY created_at ASC LIMIT ?";
    private static final String SELECT_DELIVERIES_BY_ORDER = "SELECT * FROM deliveries WHERE order_id = ? ORDER BY created_at ASC";
    private static final String UPDATE_DELIVERY_DELIVERED =
            "UPDATE deliveries SET status = 'DELIVERED', delivered_at = ?, attempts = attempts + 1 WHERE id = ?";
    private static final String UPDATE_DELIVERY_FAILED_RETRY =
            "UPDATE deliveries SET attempts = attempts + 1, last_error = ? WHERE id = ?";
    private static final String UPDATE_DELIVERY_FAILED_TERMINAL =
            "UPDATE deliveries SET status = 'FAILED', attempts = attempts + 1, last_error = ? WHERE id = ?";

    private static final String INSERT_WEBHOOK_EVENT =
            "INSERT OR IGNORE INTO webhook_events (event_id, order_id, received_at) VALUES (?, ?, ?)";

    private final StoreDatabase database;

    public StoreRepository(StoreDatabase database) {
        this.database = database;
    }

    // ---- Orders -----------------------------------------------------------------------------

    public CompletableFuture<Void> createOrder(Order order) {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(INSERT_ORDER)) {
                statement.setString(1, order.id());
                statement.setString(2, order.productId());
                statement.setString(3, order.playerUuid().toString());
                statement.setString(4, order.playerName());
                statement.setString(5, order.status().name());
                statement.setLong(6, order.amountCents());
                statement.setString(7, order.currency());
                statement.setString(8, order.providerSessionId());
                statement.setString(9, order.createdAt().toString());
                statement.setString(10, order.updatedAt().toString());
                statement.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<Optional<Order>> findOrder(String orderId) {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(SELECT_ORDER_BY_ID)) {
                statement.setString(1, orderId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next() ? Optional.of(mapOrder(resultSet)) : Optional.empty();
                }
            }
        });
    }

    public CompletableFuture<Optional<Order>> findOrderBySessionId(String sessionId) {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(SELECT_ORDER_BY_SESSION)) {
                statement.setString(1, sessionId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next() ? Optional.of(mapOrder(resultSet)) : Optional.empty();
                }
            }
        });
    }

    public CompletableFuture<List<Order>> findOrdersByPlayer(UUID playerUuid) {
        return database.execute(connection -> {
            List<Order> orders = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(SELECT_ORDERS_BY_PLAYER)) {
                statement.setString(1, playerUuid.toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        orders.add(mapOrder(resultSet));
                    }
                }
            }
            return orders;
        });
    }

    public CompletableFuture<List<Order>> allOrders(int limit) {
        return database.execute(connection -> {
            List<Order> orders = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(SELECT_ORDERS_ALL)) {
                statement.setInt(1, limit);
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        orders.add(mapOrder(resultSet));
                    }
                }
            }
            return orders;
        });
    }

    public CompletableFuture<Void> updateOrderStatus(String orderId, OrderStatus status) {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(UPDATE_ORDER_STATUS)) {
                statement.setString(1, status.name());
                statement.setString(2, Instant.now().toString());
                statement.setString(3, orderId);
                statement.executeUpdate();
            }
            return null;
        });
    }

    // ---- Deliveries -------------------------------------------------------------------------

    public CompletableFuture<Void> enqueueDelivery(Delivery delivery) {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(INSERT_DELIVERY)) {
                statement.setString(1, delivery.id());
                statement.setString(2, delivery.orderId());
                statement.setString(3, delivery.kind().name());
                statement.setString(4, delivery.productId());
                statement.setString(5, delivery.playerUuid().toString());
                statement.setString(6, delivery.playerName());
                statement.setString(7, delivery.createdAt().toString());
                statement.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<List<Delivery>> pendingDeliveries(int limit) {
        return database.execute(connection -> {
            List<Delivery> deliveries = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(SELECT_DELIVERIES_PENDING)) {
                statement.setInt(1, limit);
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        deliveries.add(mapDelivery(resultSet));
                    }
                }
            }
            return deliveries;
        });
    }

    public CompletableFuture<List<Delivery>> deliveriesForOrder(String orderId) {
        return database.execute(connection -> {
            List<Delivery> deliveries = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(SELECT_DELIVERIES_BY_ORDER)) {
                statement.setString(1, orderId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        deliveries.add(mapDelivery(resultSet));
                    }
                }
            }
            return deliveries;
        });
    }

    /**
     * Acquitte une livraison. Idempotent (mission point 7, test "livraison répétée") : acquitter
     * une livraison déjà {@code DELIVERED} ou déjà {@code FAILED} (terminale) ne fait rien et
     * retourne {@link AckOutcome#ALREADY_ACKNOWLEDGED}, jamais une erreur. Un échec temporaire
     * (mission point 10) incrémente les tentatives et laisse la livraison {@code PENDING} pour
     * qu'elle soit réessayée au prochain sondage — sauf au-delà de {@value #MAX_DELIVERY_ATTEMPTS}
     * tentatives, où elle passe {@code FAILED} de façon terminale (visible dans l'historique admin).
     */
    public CompletableFuture<AckOutcome> acknowledgeDelivery(String deliveryId, boolean delivered, String detail) {
        return database.execute(connection -> {
            Delivery current = findDeliverySync(connection, deliveryId);
            if (current == null) {
                return AckOutcome.UNKNOWN_DELIVERY;
            }
            if (current.status() != DeliveryStatus.PENDING) {
                return AckOutcome.ALREADY_ACKNOWLEDGED;
            }

            if (delivered) {
                try (PreparedStatement statement = connection.prepareStatement(UPDATE_DELIVERY_DELIVERED)) {
                    statement.setString(1, Instant.now().toString());
                    statement.setString(2, deliveryId);
                    statement.executeUpdate();
                }
                return AckOutcome.RECORDED;
            }

            boolean terminal = current.attempts() + 1 >= MAX_DELIVERY_ATTEMPTS;
            try (PreparedStatement statement = connection.prepareStatement(
                    terminal ? UPDATE_DELIVERY_FAILED_TERMINAL : UPDATE_DELIVERY_FAILED_RETRY)) {
                statement.setString(1, detail);
                statement.setString(2, deliveryId);
                statement.executeUpdate();
            }
            return AckOutcome.RECORDED;
        });
    }

    private Delivery findDeliverySync(Connection connection, String deliveryId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SELECT_DELIVERY_BY_ID)) {
            statement.setString(1, deliveryId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? mapDelivery(resultSet) : null;
            }
        }
    }

    public enum AckOutcome {
        RECORDED, ALREADY_ACKNOWLEDGED, UNKNOWN_DELIVERY
    }

    // ---- Webhook idempotency ------------------------------------------------------------------

    /** {@code true} si cet événement n'avait jamais été vu (à traiter), {@code false} si c'est un rejeu. */
    public CompletableFuture<Boolean> recordWebhookEventIfNew(String eventId, String orderId) {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(INSERT_WEBHOOK_EVENT)) {
                statement.setString(1, eventId);
                statement.setString(2, orderId);
                statement.setString(3, Instant.now().toString());
                return statement.executeUpdate() > 0;
            }
        });
    }

    // ---- Mapping ------------------------------------------------------------------------------

    private Order mapOrder(ResultSet resultSet) throws SQLException {
        return new Order(
                resultSet.getString("id"),
                resultSet.getString("product_id"),
                UUID.fromString(resultSet.getString("player_uuid")),
                resultSet.getString("player_name"),
                OrderStatus.valueOf(resultSet.getString("status")),
                resultSet.getLong("amount_cents"),
                resultSet.getString("currency"),
                resultSet.getString("provider_session_id"),
                Instant.parse(resultSet.getString("created_at")),
                Instant.parse(resultSet.getString("updated_at")));
    }

    private Delivery mapDelivery(ResultSet resultSet) throws SQLException {
        String deliveredAtRaw = resultSet.getString("delivered_at");
        return new Delivery(
                resultSet.getString("id"),
                resultSet.getString("order_id"),
                DeliveryKind.valueOf(resultSet.getString("kind")),
                resultSet.getString("product_id"),
                UUID.fromString(resultSet.getString("player_uuid")),
                resultSet.getString("player_name"),
                DeliveryStatus.valueOf(resultSet.getString("status")),
                resultSet.getInt("attempts"),
                Instant.parse(resultSet.getString("created_at")),
                deliveredAtRaw == null ? null : Instant.parse(deliveredAtRaw),
                resultSet.getString("last_error"));
    }
}
