package com.lodygames.rpgquest.database;

import com.lodygames.rpgquest.building.model.BuildingPlacement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Repository de la table {@code building_placements} (issue #213, lot « placement »). JDBC pur,
 * aucun type Bukkit ni WorldEdit — donc testable sur une base SQLite temporaire, sans serveur.
 *
 * <p>Un placement est un <strong>fait</strong> : « ce bâtiment a été posé ici, tourné comme ça, et
 * voici la sauvegarde de ce qui s'y trouvait avant ». Rien d'autre ne peut le reconstituer, et c'est
 * pour cela que, contrairement à une définition de bâtiment, il vit en base et non dans un
 * fichier.</p>
 *
 * <p><strong>{@code site_id} est la clé primaire</strong>, et c'est une règle métier déguisée en
 * contrainte : un emplacement porte au plus un bâtiment. Un second placement sur le même
 * emplacement est donc rejeté par la base elle-même, pas seulement par le service — ce qui rend un
 * double clic inoffensif même si deux requêtes arrivaient en même temps.</p>
 */
public final class BuildingPlacementRepository {

    private static final String INSERT = """
            INSERT INTO building_placements
                (site_id, building_id, world, anchor_x, anchor_y, anchor_z, rotation,
                 min_x, min_y, min_z, max_x, max_y, max_z,
                 backup_schematic, placed_by, placed_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String SELECT_ALL = "SELECT * FROM building_placements";
    private static final String DELETE = "DELETE FROM building_placements WHERE site_id = ?";

    private final DatabaseManager database;

    public BuildingPlacementRepository(DatabaseManager database) {
        this.database = database;
    }

    public CompletableFuture<List<BuildingPlacement>> loadAll() {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(SELECT_ALL);
                 ResultSet resultSet = statement.executeQuery()) {
                List<BuildingPlacement> placements = new ArrayList<>();
                while (resultSet.next()) {
                    placements.add(map(resultSet));
                }
                return placements;
            }
        });
    }

    /**
     * Enregistre un placement.
     *
     * <p>Volontairement un {@code INSERT} et non un {@code UPSERT} : un emplacement déjà occupé est
     * une anomalie que l'on veut voir échouer, pas écraser en silence. Le service refuse déjà ce
     * cas ; la base est la seconde barrière, celle qui tient même en cas de simultanéité.</p>
     */
    public CompletableFuture<Void> insert(BuildingPlacement placement) {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(INSERT)) {
                statement.setString(1, placement.siteId());
                statement.setString(2, placement.buildingId());
                statement.setString(3, placement.world());
                statement.setInt(4, placement.anchorX());
                statement.setInt(5, placement.anchorY());
                statement.setInt(6, placement.anchorZ());
                statement.setInt(7, placement.rotationDegrees());
                statement.setInt(8, placement.minX());
                statement.setInt(9, placement.minY());
                statement.setInt(10, placement.minZ());
                statement.setInt(11, placement.maxX());
                statement.setInt(12, placement.maxY());
                statement.setInt(13, placement.maxZ());
                statement.setString(14, placement.backupSchematic() == null
                        ? "" : placement.backupSchematic());
                statement.setString(15, placement.placedBy());
                statement.setString(16, placement.placedAt().toString());
                statement.executeUpdate();
                return null;
            }
        });
    }

    /** Retire l'enregistrement. Le nombre de lignes permet de distinguer « retiré » de « absent ». */
    public CompletableFuture<Integer> delete(String siteId) {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(DELETE)) {
                statement.setString(1, siteId);
                return statement.executeUpdate();
            }
        });
    }

    private static BuildingPlacement map(ResultSet row) throws SQLException {
        String backup = row.getString("backup_schematic");
        return new BuildingPlacement(
                row.getString("site_id"),
                row.getString("building_id"),
                row.getString("world"),
                row.getInt("anchor_x"), row.getInt("anchor_y"), row.getInt("anchor_z"),
                row.getInt("rotation"),
                row.getInt("min_x"), row.getInt("min_y"), row.getInt("min_z"),
                row.getInt("max_x"), row.getInt("max_y"), row.getInt("max_z"),
                backup == null || backup.isBlank() ? null : backup,
                row.getString("placed_by"),
                Instant.parse(row.getString("placed_at")));
    }
}
