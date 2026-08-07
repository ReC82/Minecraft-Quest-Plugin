package be.lloyd.rpgquest.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Repository for the {@code wallets}/{@code transactions} tables. Pure JDBC,
 * no Bukkit/Paper types. Every balance-changing operation runs as a single
 * explicit JDBC transaction (read balance, write balance, log transaction,
 * commit) inside one {@link DatabaseManager#execute} call — since the
 * database executor is single-threaded and FIFO, this is also the only
 * place two concurrent requests for the same wallet can ever interleave,
 * and an explicit transaction still protects against a mid-operation crash
 * leaving a wallet debited without its matching transaction row (or vice
 * versa).
 */
public final class WalletRepository {

    private static final String SELECT_BALANCE = "SELECT balance FROM wallets WHERE player_uuid = ?";
    private static final String ENSURE_WALLET =
            "INSERT OR IGNORE INTO wallets (player_uuid, balance, updated_at) VALUES (?, 0, ?)";
    private static final String UPDATE_BALANCE =
            "UPDATE wallets SET balance = ?, updated_at = ? WHERE player_uuid = ?";
    private static final String INSERT_TRANSACTION =
            "INSERT INTO transactions (player_uuid, type, amount, context, created_at) VALUES (?, ?, ?, ?, ?)";

    private final DatabaseManager database;

    public WalletRepository(DatabaseManager database) {
        this.database = database;
    }

    /** Solde actuel, {@code 0} si le joueur n'a encore aucun portefeuille (jamais créé tant que rien ne l'a touché). */
    public CompletableFuture<Long> balance(UUID uuid) {
        return database.execute(connection -> readBalance(connection, uuid));
    }

    /**
     * Débite {@code amount} si le solde est suffisant. Retourne {@code false}
     * (sans rien modifier) si le solde est insuffisant — jamais de solde
     * négatif possible par ce chemin.
     */
    public CompletableFuture<Boolean> debit(UUID uuid, long amount, String type, String context) {
        if (amount <= 0) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("« amount » doit être strictement positif : " + amount));
        }
        return database.execute(connection -> inTransaction(connection, () -> {
            ensureWallet(connection, uuid);
            long balance = readBalance(connection, uuid);
            if (balance < amount) {
                return false;
            }
            writeBalance(connection, uuid, balance - amount);
            insertTransaction(connection, uuid, type, -amount, context);
            return true;
        }));
    }

    /** Crédite {@code amount} (toujours strictement positif). */
    public CompletableFuture<Void> credit(UUID uuid, long amount, String type, String context) {
        if (amount <= 0) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("« amount » doit être strictement positif : " + amount));
        }
        return database.execute(connection -> inTransaction(connection, () -> {
            ensureWallet(connection, uuid);
            long balance = readBalance(connection, uuid);
            writeBalance(connection, uuid, addExact(balance, amount));
            insertTransaction(connection, uuid, type, amount, context);
            return null;
        }));
    }

    /**
     * Transfert atomique entre deux joueurs : soit le débit et le crédit
     * réussissent tous les deux (une seule transaction SQL), soit aucun des
     * deux n'a lieu. Retourne {@code false} si {@code from} n'a pas assez de
     * fonds.
     */
    public CompletableFuture<Boolean> pay(UUID from, UUID to, long amount, String context) {
        if (amount <= 0) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("« amount » doit être strictement positif : " + amount));
        }
        return database.execute(connection -> inTransaction(connection, () -> {
            ensureWallet(connection, from);
            ensureWallet(connection, to);
            long fromBalance = readBalance(connection, from);
            if (fromBalance < amount) {
                return false;
            }
            long toBalance = readBalance(connection, to);
            writeBalance(connection, from, fromBalance - amount);
            writeBalance(connection, to, addExact(toBalance, amount));
            insertTransaction(connection, from, "PAYMENT_SENT", -amount, context);
            insertTransaction(connection, to, "PAYMENT_RECEIVED", amount, context);
            return true;
        }));
    }

    /** Fixe le solde à une valeur exacte (outil admin), jamais négatif. */
    public CompletableFuture<Void> setBalance(UUID uuid, long amount, String type, String context) {
        if (amount < 0) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("« amount » ne peut pas être négatif : " + amount));
        }
        return database.execute(connection -> inTransaction(connection, () -> {
            ensureWallet(connection, uuid);
            long balance = readBalance(connection, uuid);
            writeBalance(connection, uuid, amount);
            insertTransaction(connection, uuid, type, amount - balance, context);
            return null;
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

    private long readBalance(Connection connection, UUID uuid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SELECT_BALANCE)) {
            statement.setString(1, uuid.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getLong("balance") : 0L;
            }
        }
    }

    private void ensureWallet(Connection connection, UUID uuid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(ENSURE_WALLET)) {
            statement.setString(1, uuid.toString());
            statement.setString(2, Instant.now().toString());
            statement.executeUpdate();
        }
    }

    private void writeBalance(Connection connection, UUID uuid, long newBalance) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(UPDATE_BALANCE)) {
            statement.setLong(1, newBalance);
            statement.setString(2, Instant.now().toString());
            statement.setString(3, uuid.toString());
            statement.executeUpdate();
        }
    }

    private void insertTransaction(Connection connection, UUID uuid, String type, long amount, String context)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT_TRANSACTION)) {
            statement.setString(1, uuid.toString());
            statement.setString(2, type);
            statement.setLong(3, amount);
            statement.setString(4, context);
            statement.setString(5, Instant.now().toString());
            statement.executeUpdate();
        }
    }

    private long addExact(long a, long b) throws SQLException {
        try {
            return Math.addExact(a, b);
        } catch (ArithmeticException e) {
            throw new SQLException("Dépassement de capacité du solde (montant trop élevé).", e);
        }
    }
}
