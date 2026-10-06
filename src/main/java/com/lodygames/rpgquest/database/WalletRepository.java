package com.lodygames.rpgquest.database;

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

    // ---- Récompenses monétaires de quête : ce qui est DÛ et ce qui est PAYÉ (issue #16) --------
    //
    // Le premier lot ne gardait trace que de ce qui avait déjà été payé. Une récompense due mais
    // non créditée (crash, panne SQL) ne laissait donc AUCUNE trace : invisible, et définitivement
    // perdue. Depuis le schéma V26, une ligne de quest_reward_grants naît PENDING au moment de la
    // complétion et devient PAID au moment du crédit — dans la même transaction que la mise à jour
    // du portefeuille.
    //
    // Ce n'est toujours PAS un second solde : wallets reste la seule source de vérité du solde.
    // Cette table répond à deux questions, et seulement à celles-là : « que reste-t-il à payer ? »
    // et « cette occasion a-t-elle déjà été payée ? ».

    /** Statuts réels d'une récompense monétaire de quête. */
    public enum DebtStatus {
        /** Due, pas encore créditée. Seul statut payable. */
        PENDING,
        /** Créditée au portefeuille, avec sa ligne au journal des transactions. */
        PAID,
        /**
         * Réglée à la main par un administrateur (compensation hors de ce mécanisme). Plus jamais
         * payable : sans ce statut, compenser puis reprendre paierait deux fois.
         */
        SETTLED_MANUALLY
    }

    /**
     * Une récompense monétaire de quête, due ou déjà réglée.
     *
     * <p>{@code amount} est <strong>figé à la complétion</strong>. C'est ce qui rend vraie
     * l'exigence « les montants dus sont conservés même si la définition de quête change ensuite » :
     * le paiement lit toujours cette ligne, jamais la définition courante de la quête.</p>
     */
    public record QuestRewardDebt(String grantId, UUID playerId, String questId, int occurrence,
                                  int rewardIndex, long amount, DebtStatus status, int attempts,
                                  String lastError, String settledReason, String createdAt,
                                  String updatedAt) {
    }

    /**
     * Issue d'une tentative de paiement d'une dette.
     *
     * @param code         {@code PAID} (crédité à l'instant), {@code ALREADY_PAID},
     *                     {@code ALREADY_SETTLED} (réglée à la main), {@code UNKNOWN}
     * @param amount       montant de la ligne (0 si inconnue)
     * @param balanceAfter solde relu dans la même transaction, {@code 0} si rien n'a été crédité
     */
    public record QuestRewardPayment(String code, long amount, long balanceAfter, int occurrence) {

        public boolean paid() {
            return "PAID".equals(code);
        }
    }

    private static final String SELECT_DEBT_COLUMNS =
            "grant_id, player_uuid, quest_id, occurrence, reward_index, amount, status, attempts, "
                    + "last_error, settled_reason, created_at, updated_at";
    private static final String SELECT_DEBT =
            "SELECT " + SELECT_DEBT_COLUMNS + " FROM quest_reward_grants WHERE grant_id = ?";
    private static final String SELECT_PENDING_OF_PLAYER =
            "SELECT " + SELECT_DEBT_COLUMNS + " FROM quest_reward_grants "
                    + "WHERE player_uuid = ? AND status = 'PENDING' ORDER BY created_at, reward_index LIMIT ?";
    private static final String SELECT_PENDING_ALL =
            "SELECT " + SELECT_DEBT_COLUMNS + " FROM quest_reward_grants "
                    + "WHERE status = 'PENDING' ORDER BY created_at, reward_index LIMIT ?";
    private static final String NEXT_OCCURRENCE =
            "SELECT COALESCE(MAX(occurrence), 0) + 1 FROM quest_reward_grants "
                    + "WHERE player_uuid = ? AND quest_id = ?";
    /**
     * {@code INSERT OR IGNORE} et non {@code INSERT} : rejouer l'enregistrement d'une même
     * complétion (même jeton, mêmes index) ne doit pas créer de doublon ni échouer.
     */
    static final String INSERT_DEBT =
            "INSERT OR IGNORE INTO quest_reward_grants "
                    + "(grant_id, player_uuid, quest_id, occurrence, reward_index, amount, status, "
                    + "attempts, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, 'PENDING', 0, ?, ?)";
    private static final String MARK_PAID =
            "UPDATE quest_reward_grants SET status = 'PAID', updated_at = ?, last_error = NULL "
                    + "WHERE grant_id = ? AND status = 'PENDING'";
    private static final String MARK_SETTLED =
            "UPDATE quest_reward_grants SET status = 'SETTLED_MANUALLY', settled_reason = ?, updated_at = ? "
                    + "WHERE grant_id = ? AND status = 'PENDING'";
    private static final String RECORD_FAILURE =
            "UPDATE quest_reward_grants SET attempts = attempts + 1, last_error = ?, updated_at = ? "
                    + "WHERE grant_id = ? AND status = 'PENDING'";

    /**
     * Prochaine occurrence de complétion pour ce joueur et cette quête. Package-private et
     * statique : {@link QuestProgressRepository} l'appelle <strong>dans sa propre transaction</strong>
     * pour enregistrer complétion et dettes d'un seul coup (voir
     * {@link QuestProgressRepository#completeQuestWithMoneyDebts}).
     */
    static int nextQuestRewardOccurrence(Connection connection, UUID uuid, String questId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(NEXT_OCCURRENCE)) {
            statement.setString(1, uuid.toString());
            statement.setString(2, questId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getInt(1) : 1;
            }
        }
    }

    /** Insère une dette PENDING. Voir {@link #nextQuestRewardOccurrence} pour l'usage partagé. */
    static void insertQuestRewardDebt(Connection connection, SqlDialect dialect, String grantId, UUID uuid,
                                       String questId, int occurrence, int rewardIndex, long amount)
            throws SQLException {
        String now = Instant.now().toString();
        try (PreparedStatement statement = connection.prepareStatement(dialect.rewrite(INSERT_DEBT))) {
            statement.setString(1, grantId);
            statement.setString(2, uuid.toString());
            statement.setString(3, questId);
            statement.setInt(4, occurrence);
            statement.setInt(5, rewardIndex);
            statement.setLong(6, amount);
            statement.setString(7, now);
            statement.setString(8, now);
            statement.executeUpdate();
        }
    }

    /**
     * Paie la dette {@code grantId} : <strong>PENDING → PAID, crédit du portefeuille et ligne de
     * journal dans la même transaction</strong>.
     *
     * <p>Le montant crédité <strong>et</strong> le contexte écrit au journal viennent de la LIGNE,
     * jamais de l'appelant : une quête rééditée entre-temps ne peut donc ni augmenter ni réduire une
     * dette déjà née, et la trace reste rattachable à la quête et à l'occasion. Rejouer
     * l'appel sur une dette déjà payée ne recrédite rien ({@code ALREADY_PAID}) ; sur une dette
     * réglée à la main non plus ({@code ALREADY_SETTLED}). Une erreur SQL annule tout : la dette
     * reste {@code PENDING} et donc encore payable.</p>
     */
    public CompletableFuture<QuestRewardPayment> payQuestRewardDebt(String grantId, String type) {
        if (grantId == null || grantId.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "« grantId » est obligatoire : c'est lui qui rend le paiement idempotent."));
        }
        return database.execute(connection -> inTransaction(connection, () -> {
            Optional<QuestRewardDebt> found = readDebt(connection, grantId);
            if (found.isEmpty()) {
                return new QuestRewardPayment("UNKNOWN", 0L, 0L, 0);
            }
            QuestRewardDebt debt = found.get();
            if (debt.status() == DebtStatus.PAID) {
                return new QuestRewardPayment("ALREADY_PAID", debt.amount(),
                        readBalance(connection, debt.playerId()), debt.occurrence());
            }
            if (debt.status() == DebtStatus.SETTLED_MANUALLY) {
                return new QuestRewardPayment("ALREADY_SETTLED", debt.amount(),
                        readBalance(connection, debt.playerId()), debt.occurrence());
            }
            // Le passage PENDING -> PAID est conditionnel (WHERE status = 'PENDING') : deux reprises
            // concurrentes ne peuvent pas toutes les deux l'emporter, donc pas de double crédit.
            if (!markPaid(connection, grantId)) {
                return new QuestRewardPayment("ALREADY_PAID", debt.amount(),
                        readBalance(connection, debt.playerId()), debt.occurrence());
            }
            ensureWallet(connection, debt.playerId());
            long balanceAfter = addExact(readBalance(connection, debt.playerId()), debt.amount());
            writeBalance(connection, debt.playerId(), balanceAfter);
            // Contexte composé depuis la LIGNE, jamais fourni par l'appelant : il porte la quête
            // ET l'occasion, donc une ligne de journal reste rattachable des mois plus tard.
            insertTransaction(connection, debt.playerId(), type,
                    debt.amount(), "quest:" + debt.questId() + "#" + grantId);
            return new QuestRewardPayment("PAID", debt.amount(), balanceAfter, debt.occurrence());
        }));
    }

    /** Dettes encore dues d'un joueur, les plus anciennes d'abord. {@code limit} borné à [1, 100]. */
    public CompletableFuture<List<QuestRewardDebt>> pendingQuestRewardDebts(UUID uuid, int limit) {
        int bounded = Math.max(1, Math.min(100, limit));
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(SELECT_PENDING_OF_PLAYER)) {
                statement.setString(1, uuid.toString());
                statement.setInt(2, bounded);
                return readDebts(statement);
            }
        });
    }

    /** Toutes les dettes encore dues, tous joueurs confondus (vue d'administration). */
    public CompletableFuture<List<QuestRewardDebt>> pendingQuestRewardDebts(int limit) {
        int bounded = Math.max(1, Math.min(100, limit));
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(SELECT_PENDING_ALL)) {
                statement.setInt(1, bounded);
                return readDebts(statement);
            }
        });
    }

    public CompletableFuture<Optional<QuestRewardDebt>> questRewardDebt(String grantId) {
        return database.execute(connection -> readDebt(connection, grantId));
    }

    /**
     * Marque une dette comme <strong>réglée à la main</strong> (compensation administrative hors de
     * ce mécanisme). Ne touche <strong>pas</strong> au portefeuille : c'est précisément le point —
     * l'administrateur a déjà crédité comme il l'entendait, et cette opération empêche la même
     * récompense d'être payée une seconde fois par une reprise.
     *
     * @return {@code true} si une dette {@code PENDING} a bien été réglée ; {@code false} si elle
     *         était déjà payée, déjà réglée, ou inconnue
     */
    public CompletableFuture<Boolean> settleQuestRewardDebtManually(String grantId, String reason) {
        if (reason == null || reason.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "une raison est obligatoire : sans elle, un règlement manuel serait indiscernable d'un oubli."));
        }
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(MARK_SETTLED)) {
                statement.setString(1, reason);
                statement.setString(2, Instant.now().toString());
                statement.setString(3, grantId);
                return statement.executeUpdate() > 0;
            }
        });
    }

    /**
     * Enregistre un échec de paiement sur une dette restée {@code PENDING} : compteur de tentatives
     * et motif. Sert à <strong>borner</strong> les reprises automatiques et à afficher un état réel
     * dans le panel, au lieu de réessayer indéfiniment en silence.
     */
    public CompletableFuture<Void> recordQuestRewardFailure(String grantId, String error) {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(RECORD_FAILURE)) {
                statement.setString(1, error == null ? "(inconnu)" : truncate(error));
                statement.setString(2, Instant.now().toString());
                statement.setString(3, grantId);
                statement.executeUpdate();
            }
            return null;
        });
    }

    private static String truncate(String value) {
        return value.length() <= 200 ? value : value.substring(0, 200) + "…";
    }

    private boolean markPaid(Connection connection, String grantId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(MARK_PAID)) {
            statement.setString(1, Instant.now().toString());
            statement.setString(2, grantId);
            return statement.executeUpdate() > 0;
        }
    }

    private Optional<QuestRewardDebt> readDebt(Connection connection, String grantId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SELECT_DEBT)) {
            statement.setString(1, grantId);
            List<QuestRewardDebt> rows = readDebts(statement);
            return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
        }
    }

    private List<QuestRewardDebt> readDebts(PreparedStatement statement) throws SQLException {
        List<QuestRewardDebt> out = new ArrayList<>();
        try (ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                out.add(new QuestRewardDebt(
                        resultSet.getString("grant_id"),
                        UUID.fromString(resultSet.getString("player_uuid")),
                        resultSet.getString("quest_id"),
                        resultSet.getInt("occurrence"),
                        resultSet.getInt("reward_index"),
                        resultSet.getLong("amount"),
                        parseStatus(resultSet.getString("status")),
                        resultSet.getInt("attempts"),
                        resultSet.getString("last_error"),
                        resultSet.getString("settled_reason"),
                        resultSet.getString("created_at"),
                        resultSet.getString("updated_at")));
            }
        }
        return out;
    }

    /**
     * Un statut inconnu en base est lu comme {@code PAID} et non comme {@code PENDING} : en cas de
     * donnée inattendue, ne rien payer est le choix sûr — l'inverse pourrait créditer à tort.
     */
    private static DebtStatus parseStatus(String raw) {
        if (raw == null) {
            return DebtStatus.PAID;
        }
        try {
            return DebtStatus.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return DebtStatus.PAID;
        }
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
