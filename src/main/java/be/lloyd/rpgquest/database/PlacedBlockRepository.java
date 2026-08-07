package be.lloyd.rpgquest.database;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Repository for the {@code player_placed_blocks} table (mission étape 19,
 * anti-farm point 7). Pure JDBC. Positions identifiées par
 * {@code "world:x:y:z"} plutôt qu'un type dédié : même convention que {@code
 * ResourceNodeService.NodeKey}, mais ici la position est le seul état à
 * suivre (pas de type/cooldown associé), une simple clé texte suffit et
 * évite un aller-retour supplémentaire entre {@code progression} et
 * {@code database}.
 */
public final class PlacedBlockRepository {

    private static final String SELECT_ALL = "SELECT world, x, y, z FROM player_placed_blocks";
    private static final String INSERT = "INSERT OR IGNORE INTO player_placed_blocks (world, x, y, z) VALUES (?, ?, ?, ?)";
    private static final String DELETE = "DELETE FROM player_placed_blocks WHERE world = ? AND x = ? AND y = ? AND z = ?";

    private final DatabaseManager database;

    public PlacedBlockRepository(DatabaseManager database) {
        this.database = database;
    }

    /** Toutes les positions suivies, sous forme {@code "world:x:y:z"} — chargées une fois au démarrage. */
    public CompletableFuture<Set<String>> findAll() {
        return database.execute(connection -> {
            Set<String> keys = new HashSet<>();
            try (PreparedStatement statement = connection.prepareStatement(SELECT_ALL);
                 ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    keys.add(key(resultSet.getString("world"), resultSet.getInt("x"),
                            resultSet.getInt("y"), resultSet.getInt("z")));
                }
            }
            return keys;
        });
    }

    public CompletableFuture<Void> markPlaced(String world, int x, int y, int z) {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(INSERT)) {
                statement.setString(1, world);
                statement.setInt(2, x);
                statement.setInt(3, y);
                statement.setInt(4, z);
                statement.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<Void> clear(String world, int x, int y, int z) {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(DELETE)) {
                statement.setString(1, world);
                statement.setInt(2, x);
                statement.setInt(3, y);
                statement.setInt(4, z);
                statement.executeUpdate();
            }
            return null;
        });
    }

    public static String key(String world, int x, int y, int z) {
        return world + ":" + x + ":" + y + ":" + z;
    }
}
