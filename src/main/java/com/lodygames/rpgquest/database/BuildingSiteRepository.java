package com.lodygames.rpgquest.database;

import com.lodygames.rpgquest.building.model.BuildingSite;
import com.lodygames.rpgquest.building.model.Facing;
import com.lodygames.rpgquest.building.model.SiteStatus;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Repository des tables {@code building_sites} et {@code building_site_ids} (issue #213). JDBC pur,
 * aucun type Bukkit — donc testable sur une base SQLite temporaire, sans serveur.
 *
 * <p>La base est la <strong>source de vérité</strong> des emplacements : le service qui les expose
 * en garde un cache mémoire pour répondre sans attendre, mais ce cache est toujours une copie de ce
 * qui est écrit ici. C'est ce qui fait survivre un emplacement à une déconnexion, un
 * {@code /rpgquest reload}, un redémarrage Minecraft et un redémarrage du panel.</p>
 *
 * <p><strong>L'allocation d'identifiant passe par sa propre table.</strong> Même procédé que
 * {@link NpcIdRepository} : une ligne {@code AUTOINCREMENT} par identifiant distribué. Dériver le
 * prochain numéro d'un {@code MAX()} sur {@code building_sites} aurait recyclé les identifiants des
 * emplacements supprimés — or un identifiant recyclé est exactement ce qui fera pointer un futur
 * placement sur le mauvais emplacement.</p>
 */
public final class BuildingSiteRepository {

    private static final String ALLOCATE_ID = "INSERT INTO building_site_ids (created_at) VALUES (?)";

    private static final String INSERT = """
            INSERT INTO building_sites
                (id, name, description, world, x, y, z, facing, status, created_by, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String SELECT_ALL = "SELECT * FROM building_sites";
    private static final String UPDATE_NAME = "UPDATE building_sites SET name = ? WHERE id = ?";
    private static final String UPDATE_DESCRIPTION =
            "UPDATE building_sites SET description = ? WHERE id = ?";
    private static final String UPDATE_FACING = "UPDATE building_sites SET facing = ? WHERE id = ?";
    private static final String UPDATE_STATUS = "UPDATE building_sites SET status = ? WHERE id = ?";
    private static final String DELETE = "DELETE FROM building_sites WHERE id = ?";

    private final DatabaseManager database;

    public BuildingSiteRepository(DatabaseManager database) {
        this.database = database;
    }

    /**
     * Réserve le prochain numéro d'emplacement, <strong>jamais réutilisé</strong>. L'insertion est
     * ce qui arbitre : deux créations simultanées obtiennent deux numéros différents sans verrou
     * applicatif.
     */
    public CompletableFuture<Integer> allocateNumber() {
        return database.execute(connection -> {
            try (PreparedStatement statement =
                         connection.prepareStatement(ALLOCATE_ID, Statement.RETURN_GENERATED_KEYS)) {
                statement.setString(1, Instant.now().toString());
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    keys.next();
                    return keys.getInt(1);
                }
            }
        });
    }

    public CompletableFuture<List<BuildingSite>> loadAll() {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(SELECT_ALL);
                 ResultSet resultSet = statement.executeQuery()) {
                List<BuildingSite> sites = new ArrayList<>();
                while (resultSet.next()) {
                    sites.add(map(resultSet));
                }
                return sites;
            }
        });
    }

    /**
     * Insère un emplacement neuf. Volontairement un {@code INSERT} et non un {@code UPSERT} : un
     * identifiant déjà présent est une anomalie (l'allocateur ne les réutilise pas), et la faire
     * échouer vaut mieux que d'écraser en silence un emplacement existant.
     */
    public CompletableFuture<Void> insert(BuildingSite site) {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(INSERT)) {
                statement.setString(1, site.id());
                statement.setString(2, site.name());
                statement.setString(3, site.description());
                statement.setString(4, site.world());
                statement.setInt(5, site.x());
                statement.setInt(6, site.y());
                statement.setInt(7, site.z());
                statement.setString(8, site.facing().name());
                statement.setString(9, site.status().name());
                statement.setString(10, site.createdBy());
                statement.setString(11, site.createdAt().toString());
                statement.executeUpdate();
                return null;
            }
        });
    }

    public CompletableFuture<Integer> updateName(String id, String name) {
        return update(UPDATE_NAME, name, id);
    }

    public CompletableFuture<Integer> updateDescription(String id, String description) {
        return update(UPDATE_DESCRIPTION, description, id);
    }

    /**
     * Change l'état d'un emplacement (lot « placement » de #213).
     *
     * <p>Écrit le nom de l'énumération, relu par {@link SiteStatus#of(String)} qui est tolérant :
     * c'est ce qui a permis d'ajouter {@code OCCUPIED} sans migration.</p>
     */
    public CompletableFuture<Integer> updateStatus(String id, SiteStatus status) {
        return update(UPDATE_STATUS, status.name(), id);
    }

    public CompletableFuture<Integer> updateFacing(String id, Facing facing) {
        return update(UPDATE_FACING, facing.name(), id);
    }

    /**
     * Supprime un emplacement. Renvoie le nombre de lignes réellement retirées : {@code 0} signifie
     * « il n'y en avait pas », ce qui rend un rejeu inoffensif au lieu d'une erreur.
     */
    public CompletableFuture<Integer> delete(String id) {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(DELETE)) {
                statement.setString(1, id);
                return statement.executeUpdate();
            }
        });
    }

    private CompletableFuture<Integer> update(String sql, String value, String id) {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, value);
                statement.setString(2, id);
                return statement.executeUpdate();
            }
        });
    }

    private BuildingSite map(ResultSet resultSet) throws SQLException {
        return new BuildingSite(
                resultSet.getString("id"),
                resultSet.getString("name"),
                resultSet.getString("description"),
                resultSet.getString("world"),
                resultSet.getInt("x"),
                resultSet.getInt("y"),
                resultSet.getInt("z"),
                // Une orientation illisible retombe sur NORTH plutôt que de faire échouer tout le
                // chargement : perdre un emplacement entier pour un mot mal écrit serait pire.
                Facing.of(resultSet.getString("facing")).orElse(Facing.NORTH),
                SiteStatus.of(resultSet.getString("status")),
                resultSet.getString("created_by"),
                Instant.parse(resultSet.getString("created_at")));
    }
}
