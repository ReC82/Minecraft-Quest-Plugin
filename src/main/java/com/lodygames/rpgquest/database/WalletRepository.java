package com.lodygames.rpgquest.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
    private final SqlDialect dialect;

    public WalletRepository(DatabaseManager database) {
        this.database = database;
        this.dialect = database.dialect();
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

    /**
     * Résultat d'un crédit de récompense monétaire de quête (issue #16).
     *
     * @param credited     {@code true} si CE crédit vient d'avoir lieu ; {@code false} si cette
     *                     occurrence avait déjà été payée (rien n'a été modifié)
     * @param amount       montant de l'occurrence — celui qui vient d'être crédité, ou celui qui
     *                     l'avait été la première fois
     * @param balanceAfter solde relu dans la même transaction, donc jamais une estimation
     * @param occurrence   numéro de la complétion payée pour ce joueur et cette quête (1 = la 1re)
     */
    public record QuestRewardGrant(boolean credited, long amount, long balanceAfter, int occurrence) {
    }

    private static final String SELECT_GRANT =
            "SELECT occurrence, amount FROM quest_reward_grants WHERE grant_id = ?";
    private static final String NEXT_OCCURRENCE =
            "SELECT COALESCE(MAX(occurrence), 0) + 1 FROM quest_reward_grants "
                    + "WHERE player_uuid = ? AND quest_id = ?";
    private static final String CLAIM_GRANT =
            "INSERT OR IGNORE INTO quest_reward_grants "
                    + "(grant_id, player_uuid, quest_id, occurrence, amount, created_at) VALUES (?, ?, ?, ?, ?, ?)";

    /**
     * Crédite <strong>une seule fois</strong> la récompense monétaire d'une occurrence de
     * complétion de quête (issue #16).
     *
     * <p>Toute la garantie tient dans un seul fait : la réservation de {@code grantId} et la
     * mise à jour du portefeuille vivent dans la <strong>même transaction SQL</strong>. Il n'existe
     * donc aucun instant où l'argent serait crédité sans trace (un retry doublerait le paiement) ni
     * tracé sans être crédité (le joueur perdrait sa récompense). Rejouer l'appel avec le même
     * {@code grantId} ne recrédite rien et retourne {@code credited=false} ; en cas d'erreur SQL,
     * la transaction est annulée en entier et un nouvel appel peut encore payer.</p>
     *
     * <p>Cette table n'est <strong>pas</strong> un second solde : {@code wallets} reste la seule
     * source de vérité. {@code quest_reward_grants} ne répond qu'à « cette occasion a-t-elle déjà
     * été payée ? ».</p>
     */
    public CompletableFuture<QuestRewardGrant> creditQuestReward(UUID uuid, String questId, String grantId,
                                                                 long amount, String type, String context) {
        if (amount <= 0) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("« amount » doit être strictement positif : " + amount));
        }
        if (grantId == null || grantId.isBlank()) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("« grantId » est obligatoire : c'est lui qui rend le crédit idempotent."));
        }
        return database.execute(connection -> inTransaction(connection, () -> {
            ensureWallet(connection, uuid);
            int occurrence = nextOccurrence(connection, uuid, questId);
            if (!claimGrant(connection, grantId, uuid, questId, occurrence, amount)) {
                // Déjà payé : on relit la ligne existante plutôt que de supposer que le montant
                // demandé aujourd'hui est celui qui a été crédité (une quête a pu être rééditée
                // entre-temps — le journal doit refléter ce qui s'est réellement passé).
                QuestRewardGrant existing = readGrant(connection, grantId);
                return new QuestRewardGrant(false, existing.amount(), readBalance(connection, uuid),
                        existing.occurrence());
            }
            long balance = readBalance(connection, uuid);
            long balanceAfter = addExact(balance, amount);
            writeBalance(connection, uuid, balanceAfter);
            insertTransaction(connection, uuid, type, amount, context);
            return new QuestRewardGrant(true, amount, balanceAfter, occurrence);
        }));
    }

    private int nextOccurrence(Connection connection, UUID uuid, String questId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(NEXT_OCCURRENCE)) {
            statement.setString(1, uuid.toString());
            statement.setString(2, questId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getInt(1) : 1;
            }
        }
    }

    private boolean claimGrant(Connection connection, String grantId, UUID uuid, String questId,
                                int occurrence, long amount) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(dialect.rewrite(CLAIM_GRANT))) {
            statement.setString(1, grantId);
            statement.setString(2, uuid.toString());
            statement.setString(3, questId);
            statement.setInt(4, occurrence);
            statement.setLong(5, amount);
            statement.setString(6, Instant.now().toString());
            return statement.executeUpdate() > 0;
        }
    }

    private QuestRewardGrant readGrant(Connection connection, String grantId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SELECT_GRANT)) {
            statement.setString(1, grantId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return new QuestRewardGrant(false, resultSet.getLong("amount"), 0L,
                            resultSet.getInt("occurrence"));
                }
            }
        }
        // Inatteignable en pratique : claimGrant n'a pas inséré, donc la ligne existe. Ne jamais
        // lever ici pour autant — une exception annulerait la transaction et laisserait croire à un
        // échec de paiement alors que rien n'était dû.
        return new QuestRewardGrant(false, 0L, 0L, 0);
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
        try (PreparedStatement statement = connection.prepareStatement(dialect.rewrite(ENSURE_WALLET))) {
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

    /**
     * Une ligne du journal des transactions (issue #140). {@code amount} est <strong>signé</strong> :
     * négatif pour un débit, positif pour un crédit — exactement comme en base, pour ne pas
     * réinterpréter la donnée en la lisant.
     */
    public record LedgerEntry(String type, long amount, String context, String createdAt) {
    }

    private static final String SELECT_HISTORY =
            "SELECT type, amount, context, created_at FROM transactions WHERE player_uuid = ? "
                    + "ORDER BY id DESC LIMIT ?";

    /**
     * Dernières transactions d'un joueur, les plus récentes d'abord (issue #140).
     *
     * <p>Le journal existait déjà — chaque crédit et chaque débit y écrivent une ligne dans la
     * <strong>même transaction SQL</strong> que l'écriture du solde — mais <strong>rien ne le
     * lisait</strong> : la traçabilité était écrite sans être consultable. C'est ce que cette
     * lecture corrige, et c'est ce qui permet à l'administration de montrer d'où vient un solde.</p>
     *
     * <p>Lecture seule, bornée, asynchrone comme le reste du dépôt : jamais de SQL sur le thread
     * principal.</p>
     */
    public CompletableFuture<List<LedgerEntry>> history(UUID uuid, int limit) {
        int bounded = Math.max(1, Math.min(100, limit));
        return database.execute(connection -> {
            List<LedgerEntry> out = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(SELECT_HISTORY)) {
                statement.setString(1, uuid.toString());
                statement.setInt(2, bounded);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        out.add(new LedgerEntry(rows.getString("type"), rows.getLong("amount"),
                                rows.getString("context"), rows.getString("created_at")));
                    }
                }
            }
            return List.copyOf(out);
        });
    }
}
