package com.lodygames.rpgquest.database;

import com.lodygames.rpgquest.building.model.BuildingBaseline;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Repository de la table {@code building_baselines} (issue #234) : le <strong>terrain d'origine</strong>
 * d'un emplacement, conservé à part des sauvegardes de compensation.
 *
 * <p>JDBC pur, aucun type Bukkit ni WorldEdit — donc exécutable sur une base SQLite temporaire, sans
 * serveur.</p>
 *
 * <p><strong>Plusieurs lignes par emplacement, et aucune contrainte d'unicité sur {@code site_id}</strong> :
 * c'est l'objet même de la table. Voir {@link BuildingBaseline} pour la raison — une tour occupe
 * plus de place qu'une hutte, et une baseline prise sur l'emprise de la hutte ne suffirait pas à
 * rendre le terrain d'origine.</p>
 *
 * <p>Il n'y a <strong>pas</strong> de méthode de mise à jour, et c'est délibéré : une baseline qui
 * pourrait être réécrite ne serait plus une baseline. Seule
 * {@link #deleteAllForSite(String)} existe, pour le jour où un emplacement est supprimé — pas pour
 * « rafraîchir » l'origine.</p>
 */
public final class BuildingBaselineRepository {

    private static final String INSERT = """
            INSERT INTO building_baselines
                (site_id, schematic, world, min_x, min_y, min_z, max_x, max_y, max_z,
                 captured_by, captured_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String SELECT_ALL =
            "SELECT * FROM building_baselines ORDER BY site_id, id";
    private static final String DELETE_SITE = "DELETE FROM building_baselines WHERE site_id = ?";

    private final DatabaseManager database;

    public BuildingBaselineRepository(DatabaseManager database) {
        this.database = database;
    }

    /** Tous les fragments, du plus ancien au plus récent par emplacement. */
    public CompletableFuture<List<BuildingBaseline>> loadAll() {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(SELECT_ALL);
                 ResultSet resultSet = statement.executeQuery()) {
                List<BuildingBaseline> baselines = new ArrayList<>();
                while (resultSet.next()) {
                    baselines.add(map(resultSet));
                }
                return baselines;
            }
        });
    }

    /**
     * Enregistre un fragment et renvoie la ligne telle qu'elle existe désormais, identifiant
     * compris.
     *
     * <p>L'identifiant généré est relu plutôt que supposé : c'est lui qui fixe l'ordre de
     * restauration, et un ordre deviné ne serait pas reproductible.</p>
     */
    public CompletableFuture<BuildingBaseline> insert(BuildingBaseline baseline) {
        return database.execute(connection -> {
            try (PreparedStatement statement =
                         connection.prepareStatement(INSERT, Statement.RETURN_GENERATED_KEYS)) {
                statement.setString(1, baseline.siteId());
                statement.setString(2, baseline.schematic());
                statement.setString(3, baseline.world());
                statement.setInt(4, baseline.minX());
                statement.setInt(5, baseline.minY());
                statement.setInt(6, baseline.minZ());
                statement.setInt(7, baseline.maxX());
                statement.setInt(8, baseline.maxY());
                statement.setInt(9, baseline.maxZ());
                statement.setString(10, baseline.capturedBy());
                statement.setString(11, baseline.capturedAt().toString());
                statement.executeUpdate();
                long id = baseline.id();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    if (keys.next()) {
                        id = keys.getLong(1);
                    }
                }
                return new BuildingBaseline(id, baseline.siteId(), baseline.schematic(),
                        baseline.world(), baseline.minX(), baseline.minY(), baseline.minZ(),
                        baseline.maxX(), baseline.maxY(), baseline.maxZ(),
                        baseline.capturedBy(), baseline.capturedAt());
            }
        });
    }

    /**
     * Oublie le terrain d'origine d'un emplacement. Renvoie le nombre de fragments retirés.
     *
     * <p>À n'appeler que lorsque l'emplacement lui-même disparaît. Tant qu'il existe, sa baseline
     * doit survivre à toutes les expérimentations — c'est exactement ce qu'elle sert à garantir.</p>
     */
    public CompletableFuture<Integer> deleteAllForSite(String siteId) {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(DELETE_SITE)) {
                statement.setString(1, siteId);
                return statement.executeUpdate();
            }
        });
    }

    private static BuildingBaseline map(ResultSet row) throws SQLException {
        return new BuildingBaseline(
                row.getLong("id"),
                row.getString("site_id"),
                row.getString("schematic"),
                row.getString("world"),
                row.getInt("min_x"), row.getInt("min_y"), row.getInt("min_z"),
                row.getInt("max_x"), row.getInt("max_y"), row.getInt("max_z"),
                row.getString("captured_by"),
                Instant.parse(row.getString("captured_at")));
    }
}
