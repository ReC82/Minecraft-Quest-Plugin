package com.lodygames.rpgquest.database;

import com.lodygames.rpgquest.entitlement.reward.RewardEntitlement;
import com.lodygames.rpgquest.entitlement.reward.RewardEntitlementStatus;
import com.lodygames.rpgquest.entitlement.reward.RewardSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.jetbrains.annotations.Nullable;

/**
 * Table {@code reward_entitlements} (issue #422, schéma V41) : les droits à récompense.
 *
 * <p>Toute transition est un {@code UPDATE} <strong>conditionnel</strong> sur le statut courant ;
 * le booléen renvoyé dit si <em>cet</em> appel a gagné. C'est ce qui empêche deux remises du même
 * droit, entre deux sessions d'un joueur comme entre deux processus partageant une base MariaDB
 * (docs/REWARD_ENTITLEMENTS.md §4-5).</p>
 */
public final class RewardEntitlementRepository {

    /** Longueur conservée d'un message d'erreur ou d'un motif (traçabilité, pas un journal). */
    static final int MAX_TEXT = 500;

    private static final String COLUMNS = "idempotency_key, player_uuid, source, source_ref, reward_key, label, "
            + "status, attempts, last_error, revoke_reason, created_at, updated_at, delivered_at, revoked_at";
    private static final String INSERT = "INSERT OR IGNORE INTO reward_entitlements (idempotency_key, player_uuid, "
            + "source, source_ref, reward_key, label, status, attempts, created_at, updated_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, 'PENDING', 0, ?, ?)";
    private static final String CLAIM = "UPDATE reward_entitlements SET status = 'DELIVERED', delivered_at = ?, "
            + "updated_at = ?, last_error = NULL WHERE idempotency_key = ? AND status = 'PENDING'";
    private static final String RELEASE = "UPDATE reward_entitlements SET status = 'PENDING', delivered_at = NULL, "
            + "attempts = attempts + 1, last_error = ?, updated_at = ? "
            + "WHERE idempotency_key = ? AND status = 'DELIVERED'";
    private static final String FAILURE = "UPDATE reward_entitlements SET attempts = attempts + 1, last_error = ?, "
            + "updated_at = ? WHERE idempotency_key = ? AND status = 'PENDING'";
    private static final String NOTE = "UPDATE reward_entitlements SET last_error = ?, updated_at = ? "
            + "WHERE idempotency_key = ?";
    private static final String REVOKE = "UPDATE reward_entitlements SET status = 'REVOKED', revoked_at = ?, "
            + "revoke_reason = ?, updated_at = ? WHERE idempotency_key = ? AND status = 'PENDING'";

    private final DatabaseManager database;

    public RewardEntitlementRepository(DatabaseManager database) {
        this.database = database;
    }

    /** Déclare un droit. {@code true} s'il vient d'être créé, {@code false} si la clé existait déjà. */
    public CompletableFuture<Boolean> declare(RewardEntitlement.Declaration declaration, Instant now) {
        return database.execute(connection -> insert(connection, declaration, now));
    }

    /** Déclare plusieurs droits sur une seule connexion ; renvoie le nombre de droits réellement créés. */
    public CompletableFuture<Integer> declareAll(List<RewardEntitlement.Declaration> declarations, Instant now) {
        return database.execute(connection -> {
            int created = 0;
            for (RewardEntitlement.Declaration declaration : declarations) {
                if (insert(connection, declaration, now)) {
                    created++;
                }
            }
            return created;
        });
    }

    private boolean insert(Connection connection, RewardEntitlement.Declaration declaration, Instant now)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(database.dialect().rewrite(INSERT))) {
            statement.setString(1, declaration.idempotencyKey());
            statement.setString(2, declaration.playerId().toString());
            statement.setString(3, declaration.source().name());
            statement.setString(4, declaration.sourceRef());
            statement.setString(5, declaration.rewardKey());
            statement.setString(6, declaration.label());
            statement.setString(7, now.toString());
            statement.setString(8, now.toString());
            return statement.executeUpdate() > 0;
        }
    }

    public CompletableFuture<Optional<RewardEntitlement>> find(String idempotencyKey) {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT " + COLUMNS + " FROM reward_entitlements WHERE idempotency_key = ?")) {
                statement.setString(1, idempotencyKey);
                List<RewardEntitlement> rows = read(statement);
                return rows.isEmpty() ? Optional.<RewardEntitlement>empty() : Optional.of(rows.get(0));
            }
        });
    }

    /** Droits en attente de ces joueurs, les plus anciens d'abord. */
    public CompletableFuture<List<RewardEntitlement>> pendingFor(Collection<UUID> players, int limit) {
        if (players.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }
        String sql = "SELECT " + COLUMNS + " FROM reward_entitlements WHERE status = 'PENDING' AND player_uuid IN ("
                + String.join(", ", java.util.Collections.nCopies(players.size(), "?"))
                + ") ORDER BY created_at ASC, idempotency_key ASC LIMIT ?";
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                int index = 1;
                for (UUID player : players) {
                    statement.setString(index++, player.toString());
                }
                statement.setInt(index, limit);
                return read(statement);
            }
        });
    }

    /** Une ligne de la liste de traçabilité : le droit et le dernier pseudo connu du joueur. */
    public record Listed(RewardEntitlement entitlement, @Nullable String playerName) {
    }

    /** Liste de traçabilité (PlugAdmin), la plus récemment modifiée d'abord. Filtres facultatifs. */
    public CompletableFuture<List<Listed>> list(@Nullable UUID player, @Nullable RewardEntitlementStatus status,
                                                int limit) {
        StringBuilder sql = new StringBuilder("SELECT " + prefixed("r.") + ", p.last_name AS player_name "
                + "FROM reward_entitlements r LEFT JOIN player_profiles p ON p.uuid = r.player_uuid WHERE 1 = 1");
        if (player != null) {
            sql.append(" AND r.player_uuid = ?");
        }
        if (status != null) {
            sql.append(" AND r.status = ?");
        }
        sql.append(" ORDER BY r.updated_at DESC, r.idempotency_key ASC LIMIT ?");
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
                int index = 1;
                if (player != null) {
                    statement.setString(index++, player.toString());
                }
                if (status != null) {
                    statement.setString(index++, status.name());
                }
                statement.setInt(index, limit);
                List<Listed> rows = new ArrayList<>();
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        rows.add(new Listed(row(resultSet), resultSet.getString("player_name")));
                    }
                }
                return rows;
            }
        });
    }

    private static String prefixed(String alias) {
        return alias + COLUMNS.replace(", ", ", " + alias);
    }

    /** Prise du droit avant remise : {@code PENDING → DELIVERED}. {@code false} = déjà pris ou révoqué. */
    public CompletableFuture<Boolean> claim(String idempotencyKey, Instant now) {
        return update(CLAIM, now.toString(), now.toString(), idempotencyKey);
    }

    /** Compensation d'une remise qui n'a pas pu avoir lieu après la prise : {@code DELIVERED → PENDING}. */
    public CompletableFuture<Boolean> release(String idempotencyKey, String error, Instant now) {
        return update(RELEASE, truncate(error), now.toString(), idempotencyKey);
    }

    /** Échec avant la prise (récompense inconnue…) : le droit reste en attente, l'erreur est tracée. */
    public CompletableFuture<Boolean> recordFailure(String idempotencyKey, String error, Instant now) {
        return update(FAILURE, truncate(error), now.toString(), idempotencyKey);
    }

    /** Note de traçabilité sans changement de statut (avantage non accordé après une remise…). */
    public CompletableFuture<Boolean> note(String idempotencyKey, String text, Instant now) {
        return update(NOTE, truncate(text), now.toString(), idempotencyKey);
    }

    /** Révocation administrateur : {@code PENDING → REVOKED} seulement. */
    public CompletableFuture<Boolean> revoke(String idempotencyKey, String reason, Instant now) {
        return update(REVOKE, now.toString(), truncate(reason), now.toString(), idempotencyKey);
    }

    private CompletableFuture<Boolean> update(String sql, String... parameters) {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                for (int i = 0; i < parameters.length; i++) {
                    statement.setString(i + 1, parameters[i]);
                }
                return statement.executeUpdate() == 1;
            }
        });
    }

    private static List<RewardEntitlement> read(PreparedStatement statement) throws SQLException {
        List<RewardEntitlement> rows = new ArrayList<>();
        try (ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                rows.add(row(resultSet));
            }
        }
        return rows;
    }

    private static RewardEntitlement row(ResultSet resultSet) throws SQLException {
        return new RewardEntitlement(
                resultSet.getString("idempotency_key"),
                UUID.fromString(resultSet.getString("player_uuid")),
                RewardSource.valueOf(resultSet.getString("source")),
                resultSet.getString("source_ref"),
                resultSet.getString("reward_key"),
                resultSet.getString("label"),
                RewardEntitlementStatus.valueOf(resultSet.getString("status")),
                resultSet.getInt("attempts"),
                resultSet.getString("last_error"),
                resultSet.getString("revoke_reason"),
                Instant.parse(resultSet.getString("created_at")),
                Instant.parse(resultSet.getString("updated_at")),
                instant(resultSet.getString("delivered_at")),
                instant(resultSet.getString("revoked_at")));
    }

    private static @Nullable Instant instant(@Nullable String raw) {
        return raw == null ? null : Instant.parse(raw);
    }

    private static @Nullable String truncate(@Nullable String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= MAX_TEXT ? text : text.substring(0, MAX_TEXT);
    }
}
