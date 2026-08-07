package be.lloyd.rpgquest.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Repository for {@code market_listings}. Pure JDBC, no Bukkit/Paper types.
 * {@link #claim}/{@link #cancel}/{@link #reactivate} each run as a single
 * explicit JDBC transaction (read-then-conditionally-write) inside one
 * {@link DatabaseManager#execute} call — same discipline as
 * {@link WalletRepository} — so a listing can never be claimed twice nor
 * cancelled by anyone but its seller.
 */
public final class MarketRepository {

    private static final String INSERT =
            "INSERT INTO market_listings (seller_uuid, item_data, price, status, created_at) VALUES (?, ?, ?, 'ACTIVE', ?)";
    private static final String SELECT_ACTIVE = """
            SELECT m.id, m.seller_uuid, p.last_name AS seller_name, m.item_data, m.price, m.status,
                   m.created_at, m.resolved_at, m.buyer_uuid
            FROM market_listings m
            JOIN player_profiles p ON p.uuid = m.seller_uuid
            WHERE m.status = 'ACTIVE'
            ORDER BY m.created_at ASC
            """;
    private static final String SELECT_MINE_ACTIVE = """
            SELECT m.id, m.seller_uuid, p.last_name AS seller_name, m.item_data, m.price, m.status,
                   m.created_at, m.resolved_at, m.buyer_uuid
            FROM market_listings m
            JOIN player_profiles p ON p.uuid = m.seller_uuid
            WHERE m.status = 'ACTIVE' AND m.seller_uuid = ?
            ORDER BY m.created_at ASC
            """;
    private static final String SELECT_BY_ID_FOR_UPDATE =
            "SELECT id, seller_uuid, item_data, price, status, created_at, resolved_at, buyer_uuid "
                    + "FROM market_listings WHERE id = ?";
    private static final String MARK_SOLD =
            "UPDATE market_listings SET status = 'SOLD', buyer_uuid = ?, resolved_at = ? WHERE id = ? AND status = 'ACTIVE'";
    private static final String MARK_CANCELLED =
            "UPDATE market_listings SET status = 'CANCELLED', resolved_at = ? WHERE id = ? AND seller_uuid = ? AND status = 'ACTIVE'";
    private static final String REACTIVATE =
            "UPDATE market_listings SET status = 'ACTIVE', buyer_uuid = NULL, resolved_at = NULL "
                    + "WHERE id = ? AND status = 'SOLD' AND buyer_uuid = ?";

    private final DatabaseManager database;

    public MarketRepository(DatabaseManager database) {
        this.database = database;
    }

    public CompletableFuture<Long> createListing(UUID sellerUuid, byte[] itemData, long price) {
        return database.execute(connection -> {
            try (PreparedStatement statement =
                         connection.prepareStatement(INSERT, Statement.RETURN_GENERATED_KEYS)) {
                statement.setString(1, sellerUuid.toString());
                statement.setBytes(2, itemData);
                statement.setLong(3, price);
                statement.setString(4, Instant.now().toString());
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    keys.next();
                    return keys.getLong(1);
                }
            }
        });
    }

    public CompletableFuture<List<MarketListingRecord>> activeListings() {
        return database.execute(connection -> query(connection, SELECT_ACTIVE, null));
    }

    public CompletableFuture<List<MarketListingRecord>> myActiveListings(UUID sellerUuid) {
        return database.execute(connection -> query(connection, SELECT_MINE_ACTIVE, sellerUuid));
    }

    /**
     * Réserve atomiquement l'offre {@code id} pour {@code buyerUuid} (marque
     * {@code SOLD}) si et seulement si elle est encore {@code ACTIVE}.
     * Retourne l'offre telle qu'elle était au moment de la réservation
     * (utilisée par l'appelant pour créditer le vendeur et remettre
     * l'objet), ou vide si elle n'était déjà plus disponible.
     */
    public CompletableFuture<Optional<MarketListingRecord>> claim(long id, UUID buyerUuid) {
        return database.execute(connection -> inTransaction(connection, () -> {
            Optional<MarketListingRecord> listing = selectById(connection, id);
            if (listing.isEmpty() || !"ACTIVE".equals(listing.get().status())) {
                return Optional.empty();
            }
            try (PreparedStatement statement = connection.prepareStatement(MARK_SOLD)) {
                statement.setString(1, buyerUuid.toString());
                statement.setString(2, Instant.now().toString());
                statement.setLong(3, id);
                int updated = statement.executeUpdate();
                return updated == 1 ? listing : Optional.<MarketListingRecord>empty();
            }
        }));
    }

    /**
     * Défait une réservation en cas d'échec du débit de l'acheteur (fonds
     * insuffisants découverts après la réservation, l'offre n'ayant pas de
     * prix connu avant lecture) — remet l'offre à disposition des autres
     * joueurs. Ne touche à rien si {@code buyerUuid} n'est plus le
     * réservataire courant (défense en profondeur, ne devrait jamais
     * arriver : une seule réservation à la fois par offre).
     */
    public CompletableFuture<Void> reactivate(long id, UUID buyerUuid) {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(REACTIVATE)) {
                statement.setLong(1, id);
                statement.setString(2, buyerUuid.toString());
                statement.executeUpdate();
            }
            return null;
        });
    }

    /**
     * Annule l'offre {@code id} si elle appartient bien à {@code sellerUuid}
     * et est encore {@code ACTIVE}. Retourne l'offre annulée (pour que
     * l'appelant restitue l'objet), ou vide si rien ne correspond (offre
     * inconnue, pas la sienne, déjà vendue/annulée).
     */
    public CompletableFuture<Optional<MarketListingRecord>> cancel(long id, UUID sellerUuid) {
        return database.execute(connection -> inTransaction(connection, () -> {
            Optional<MarketListingRecord> listing = selectById(connection, id);
            if (listing.isEmpty() || !"ACTIVE".equals(listing.get().status())
                    || !listing.get().sellerUuid().equals(sellerUuid)) {
                return Optional.empty();
            }
            try (PreparedStatement statement = connection.prepareStatement(MARK_CANCELLED)) {
                statement.setString(1, Instant.now().toString());
                statement.setLong(2, id);
                statement.setString(3, sellerUuid.toString());
                int updated = statement.executeUpdate();
                return updated == 1 ? listing : Optional.<MarketListingRecord>empty();
            }
        }));
    }

    @FunctionalInterface
    private interface SqlAction<T> {
        T run() throws SQLException;
    }

    private <T> T inTransaction(Connection connection, SqlAction<T> action) throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            T result = action.run();
            connection.commit();
            return result;
        } catch (SQLException | RuntimeException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    private Optional<MarketListingRecord> selectById(Connection connection, long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SELECT_BY_ID_FOR_UPDATE)) {
            statement.setLong(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(new MarketListingRecord(
                        resultSet.getLong("id"),
                        UUID.fromString(resultSet.getString("seller_uuid")),
                        Optional.empty(),
                        resultSet.getBytes("item_data"),
                        resultSet.getLong("price"),
                        resultSet.getString("status"),
                        Instant.parse(resultSet.getString("created_at")),
                        parseNullableInstant(resultSet.getString("resolved_at")),
                        parseNullableUuid(resultSet.getString("buyer_uuid"))));
            }
        }
    }

    private List<MarketListingRecord> query(Connection connection, String sql, UUID sellerFilter) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            if (sellerFilter != null) {
                statement.setString(1, sellerFilter.toString());
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                List<MarketListingRecord> listings = new ArrayList<>();
                while (resultSet.next()) {
                    listings.add(new MarketListingRecord(
                            resultSet.getLong("id"),
                            UUID.fromString(resultSet.getString("seller_uuid")),
                            Optional.of(resultSet.getString("seller_name")),
                            resultSet.getBytes("item_data"),
                            resultSet.getLong("price"),
                            resultSet.getString("status"),
                            Instant.parse(resultSet.getString("created_at")),
                            parseNullableInstant(resultSet.getString("resolved_at")),
                            parseNullableUuid(resultSet.getString("buyer_uuid"))));
                }
                return listings;
            }
        }
    }

    private Instant parseNullableInstant(String raw) {
        return raw == null ? null : Instant.parse(raw);
    }

    private Optional<UUID> parseNullableUuid(String raw) {
        return raw == null ? Optional.empty() : Optional.of(UUID.fromString(raw));
    }
}
