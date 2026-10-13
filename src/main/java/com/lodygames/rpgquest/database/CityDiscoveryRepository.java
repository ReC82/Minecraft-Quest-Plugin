package com.lodygames.rpgquest.database;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Découvertes de villes (issue #178), table {@code city_discoveries} (migration V33). JDBC pur.
 *
 * <p>La clé primaire (joueur, ville) fait de la <strong>première</strong> découverte un fait unique
 * au niveau de la base : {@link #insert} renvoie {@code true} une seule fois par couple, même si deux
 * événements arrivent en même temps, après une mort, une reconnexion ou un redémarrage.</p>
 */
public final class CityDiscoveryRepository {

    private static final String INSERT =
            "INSERT OR IGNORE INTO city_discoveries (player_uuid, city_id, discovered_at) VALUES (?, ?, ?)";
    private static final String SELECT_FOR_PLAYER =
            "SELECT city_id FROM city_discoveries WHERE player_uuid = ?";
    private static final String COUNT_BY_CITY =
            "SELECT city_id, COUNT(*) AS n FROM city_discoveries GROUP BY city_id";

    private final DatabaseManager database;
    private final SqlDialect dialect;

    public CityDiscoveryRepository(DatabaseManager database) {
        this.database = database;
        this.dialect = database.dialect();
    }

    /** Identifiants des villes déjà découvertes par ce joueur. */
    public CompletableFuture<Set<String>> discoveredBy(UUID playerId) {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(SELECT_FOR_PLAYER)) {
                statement.setString(1, playerId.toString());
                Set<String> ids = new HashSet<>();
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        ids.add(rs.getString("city_id"));
                    }
                }
                return ids;
            }
        });
    }

    /** {@code true} si cette découverte est nouvelle (ligne réellement insérée). */
    public CompletableFuture<Boolean> insert(UUID playerId, String cityId, Instant at) {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(dialect.rewrite(INSERT))) {
                statement.setString(1, playerId.toString());
                statement.setString(2, cityId);
                statement.setString(3, at.toString());
                return statement.executeUpdate() == 1;
            }
        });
    }

    /** Nombre de joueurs ayant découvert chaque ville (pour le panel). */
    public CompletableFuture<Map<String, Integer>> countByCity() {
        return database.execute(connection -> {
            Map<String, Integer> counts = new HashMap<>();
            try (PreparedStatement statement = connection.prepareStatement(COUNT_BY_CITY);
                 ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    counts.put(rs.getString("city_id"), rs.getInt("n"));
                }
            }
            return counts;
        });
    }
}
