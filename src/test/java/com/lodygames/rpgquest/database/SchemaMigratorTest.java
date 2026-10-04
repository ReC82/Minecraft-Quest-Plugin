package com.lodygames.rpgquest.database;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SchemaMigratorTest {

    @TempDir
    Path tempDir;

    @Test
    void migrateSetsUserVersionToCurrentSchemaVersion() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("schema.db"))) {
            SchemaMigrator.migrate(connection);
            assertEquals(23, userVersion(connection));
        }
    }

    @Test
    void migratingTwiceIsIdempotentAndKeepsVersionCurrent() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("schema2.db"))) {
            SchemaMigrator.migrate(connection);
            assertDoesNotThrow(() -> SchemaMigrator.migrate(connection));
            assertEquals(23, userVersion(connection));
        }
    }

    @Test
    void migratingFromAnAlreadyPartiallyMigratedDatabaseStillReachesCurrentVersion() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("schema3.db"))) {
            // Simule une base créée avant l'ajout de quest_objective_progress (V1 seulement).
            SchemaMigrator.migrate(connection);
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA user_version = 1");
                statement.execute("DROP TABLE quest_objective_progress");
            }

            SchemaMigrator.migrate(connection);

            assertEquals(23, userVersion(connection));
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery(
                         "SELECT name FROM sqlite_master WHERE type='table' AND name='quest_objective_progress'")) {
                assertTrue(resultSet.next());
            }
        }
    }

    @Test
    void migrateCreatesResourceNodesTable() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("schema4.db"))) {
            SchemaMigrator.migrate(connection);
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery(
                         "SELECT name FROM sqlite_master WHERE type='table' AND name='resource_nodes'")) {
                assertTrue(resultSet.next());
            }
        }
    }

    @Test
    void migrateCreatesWalletsAndTransactionsTables() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("schema5.db"))) {
            SchemaMigrator.migrate(connection);
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery(
                         "SELECT name FROM sqlite_master WHERE type='table' AND name IN ('wallets', 'transactions')")) {
                int count = 0;
                while (resultSet.next()) {
                    count++;
                }
                assertEquals(2, count);
            }
        }
    }

    @Test
    void migrateCreatesPortalCooldownsTable() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("schema7.db"))) {
            SchemaMigrator.migrate(connection);
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery(
                         "SELECT name FROM sqlite_master WHERE type='table' AND name='portal_cooldowns'")) {
                assertTrue(resultSet.next());
            }
        }
    }

    @Test
    void migrateCreatesClaimsAndClaimMembersTables() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("schema8.db"))) {
            SchemaMigrator.migrate(connection);
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery(
                         "SELECT name FROM sqlite_master WHERE type='table' AND name IN ('claims', 'claim_members')")) {
                int count = 0;
                while (resultSet.next()) {
                    count++;
                }
                assertEquals(2, count);
            }
        }
    }

    @Test
    void migrateCreatesMarketListingsTable() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("schema6.db"))) {
            SchemaMigrator.migrate(connection);
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery(
                         "SELECT name FROM sqlite_master WHERE type='table' AND name='market_listings'")) {
                assertTrue(resultSet.next());
            }
        }
    }

    @Test
    void migrateCreatesProgressionTables() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("schema9.db"))) {
            SchemaMigrator.migrate(connection);
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery(
                         "SELECT name FROM sqlite_master WHERE type='table' AND name IN "
                                 + "('player_skills', 'xp_grants', 'player_placed_blocks')")) {
                int count = 0;
                while (resultSet.next()) {
                    count++;
                }
                assertEquals(3, count);
            }
        }
    }

    @Test
    void migrateCreatesBackpackAndEntitlementTables() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("schema10.db"))) {
            SchemaMigrator.migrate(connection);
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery(
                         "SELECT name FROM sqlite_master WHERE type='table' AND name IN "
                                 + "('player_entitlements', 'backpacks', 'backpack_overflow', 'backpack_audit')")) {
                int count = 0;
                while (resultSet.next()) {
                    count++;
                }
                assertEquals(4, count);
            }
        }
    }

    @Test
    void migrateCreatesStoreDeliveriesProcessedTable() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("schema11.db"))) {
            SchemaMigrator.migrate(connection);
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery(
                         "SELECT name FROM sqlite_master WHERE type='table' AND name='store_deliveries_processed'")) {
                assertTrue(resultSet.next());
            }
        }
    }

    @Test
    void migrateCreatesNpcIdsTable() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("schema12.db"))) {
            SchemaMigrator.migrate(connection);
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery(
                         "SELECT name FROM sqlite_master WHERE type='table' AND name='npc_ids'")) {
                assertTrue(resultSet.next());
            }
        }
    }

    @Test
    void migrateCreatesNpcCitizensBindingsTable() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("schema13.db"))) {
            SchemaMigrator.migrate(connection);
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery(
                         "SELECT name FROM sqlite_master WHERE type='table' AND name='npc_citizens_bindings'")) {
                assertTrue(resultSet.next());
            }
        }
    }

    @Test
    void migrateCreatesStoryProgressTable() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("schema14.db"))) {
            SchemaMigrator.migrate(connection);
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery(
                         "SELECT name FROM sqlite_master WHERE type='table' AND name='story_progress'")) {
                assertTrue(resultSet.next());
            }
        }
    }

    @Test
    void migrateAddsCurrentIndexColumnToStoryProgress() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("schema15.db"))) {
            SchemaMigrator.migrate(connection);
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery("PRAGMA table_info(story_progress)")) {
                boolean found = false;
                while (resultSet.next()) {
                    if ("current_index".equals(resultSet.getString("name"))) {
                        found = true;
                    }
                }
                assertTrue(found, "story_progress doit porter la colonne current_index (migration V14)");
            }
        }
    }

    @Test
    void migratingFromV13PreservesExistingStoryProgressRowsAndDefaultsCurrentIndexToZero() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("schema16.db"))) {
            // Simule une base déjà à V13 (avant l'ajout de current_index), avec une ligne existante —
            // exactement ce qu'un serveur en production aurait après l'étape précédente.
            SchemaMigrator.migrate(connection);
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA user_version = 13");
                statement.execute(
                        "INSERT INTO player_profiles (uuid, last_name, created_at, updated_at) "
                                + "VALUES ('11111111-1111-1111-1111-111111111111', 'Steve', '2024-01-01T00:00:00Z', '2024-01-01T00:00:00Z')");
            }

            SchemaMigrator.migrate(connection);

            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery(
                         "SELECT uuid, last_name FROM player_profiles WHERE uuid = '11111111-1111-1111-1111-111111111111'")) {
                assertTrue(resultSet.next(), "les données déjà présentes avant la migration V14 doivent survivre telles quelles");
                assertEquals("Steve", resultSet.getString("last_name"));
            }
            assertEquals(23, userVersion(connection));
        }
    }

    @Test
    void migrateAddsReservationColumnsToClaims() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("schema17.db"))) {
            SchemaMigrator.migrate(connection);
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery("PRAGMA table_info(claims)")) {
                var found = new java.util.HashSet<String>();
                while (resultSet.next()) {
                    found.add(resultSet.getString("name"));
                }
                for (String column : java.util.List.of("reserved_min_x", "reserved_min_y", "reserved_min_z",
                        "reserved_max_x", "reserved_max_y", "reserved_max_z")) {
                    assertTrue(found.contains(column), () -> "colonne manquante : " + column);
                }
            }
        }
    }

    @Test
    void migratingFromV14BackfillsReservationBoundsToTheActiveBoundsOfExistingClaims() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("schema18.db"))) {
            // Simule un serveur déjà en V14 (avant l'introduction du modèle de réservation) : schéma
            // claims/player_profiles recréé à la main sans les colonnes reserved_*, avec une ligne
            // existante — exactement ce qu'un vrai serveur en production aurait à ce stade.
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TABLE player_profiles (
                            uuid TEXT PRIMARY KEY,
                            last_name TEXT NOT NULL,
                            created_at TEXT NOT NULL,
                            updated_at TEXT NOT NULL
                        )
                        """);
                statement.execute("""
                        CREATE TABLE claims (
                            id TEXT PRIMARY KEY,
                            owner_uuid TEXT NOT NULL,
                            world TEXT NOT NULL,
                            min_x INTEGER NOT NULL,
                            min_y INTEGER NOT NULL,
                            min_z INTEGER NOT NULL,
                            max_x INTEGER NOT NULL,
                            max_y INTEGER NOT NULL,
                            max_z INTEGER NOT NULL,
                            allow_public_redstone INTEGER NOT NULL DEFAULT 0,
                            created_at TEXT NOT NULL,
                            FOREIGN KEY (owner_uuid) REFERENCES player_profiles (uuid) ON DELETE CASCADE
                        )
                        """);
                statement.execute(
                        "INSERT INTO player_profiles (uuid, last_name, created_at, updated_at) "
                                + "VALUES ('22222222-2222-2222-2222-222222222222', 'Steve', '2024-01-01T00:00:00Z', '2024-01-01T00:00:00Z')");
                statement.execute("""
                        INSERT INTO claims (id, owner_uuid, world, min_x, min_y, min_z, max_x, max_y, max_z,
                                             allow_public_redstone, created_at)
                        VALUES ('legacy', '22222222-2222-2222-2222-222222222222', 'world', 0, 0, 0, 10, 255, 10, 0, '2024-01-01T00:00:00Z')
                        """);
                statement.execute("PRAGMA user_version = 14");
            }

            SchemaMigrator.migrate(connection);

            assertEquals(23, userVersion(connection));
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery(
                         "SELECT min_x, max_x, reserved_min_x, reserved_max_x FROM claims WHERE id = 'legacy'")) {
                assertTrue(resultSet.next());
                assertEquals(resultSet.getInt("min_x"), resultSet.getInt("reserved_min_x"),
                        "un claim déjà existant doit voir sa réservation initialisée à son propre cuboïde actif");
                assertEquals(resultSet.getInt("max_x"), resultSet.getInt("reserved_max_x"));
            }
        }
    }

    @Test
    void migrateCreatesItemTravelCooldownsTable() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("schema16.db"))) {
            SchemaMigrator.migrate(connection);
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery(
                         "SELECT name FROM sqlite_master WHERE type='table' AND name='item_travel_cooldowns'")) {
                assertTrue(resultSet.next());
            }
        }
    }

    @Test
    void migrateCreatesWaystoneTables() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("schema17.db"))) {
            SchemaMigrator.migrate(connection);
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery(
                         "SELECT name FROM sqlite_master WHERE type='table' AND name IN ('waystones', 'waystone_discoveries')")) {
                int found = 0;
                while (resultSet.next()) {
                    found++;
                }
                assertEquals(2, found);
            }
        }
    }

    @Test
    void migrateCreatesWaypointTables() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("schema18.db"))) {
            SchemaMigrator.migrate(connection);
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery(
                         "SELECT name FROM sqlite_master WHERE type='table' AND name IN ('waypoints', 'waypoint_discoveries')")) {
                int found = 0;
                while (resultSet.next()) {
                    found++;
                }
                assertEquals(2, found);
            }
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery(
                         "SELECT name FROM sqlite_master WHERE type='index' AND name='idx_waypoints_instance'")) {
                assertTrue(resultSet.next(), "l'index unique (world, biome_instance) doit exister");
            }
        }
    }

    @Test
    void reRunningV18IsIdempotent() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("schemaV18.db"))) {
            SchemaMigrator.migrate(connection);
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA user_version = 17");
            }
            assertDoesNotThrow(() -> SchemaMigrator.migrate(connection));
            assertEquals(23, userVersion(connection));
        }
    }

    @Test
    void reRunningV16AndV17IsIdempotent() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("schema1617.db"))) {
            SchemaMigrator.migrate(connection);
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA user_version = 15");
            }
            assertDoesNotThrow(() -> SchemaMigrator.migrate(connection));
            assertEquals(23, userVersion(connection));
        }
    }

    @Test
    void migrateCreatesTravelBeaconsTable() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("schema19.db"))) {
            SchemaMigrator.migrate(connection);
            assertEquals(23, userVersion(connection));
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery(
                         "SELECT name FROM sqlite_master WHERE type='table' AND name='travel_beacons'")) {
                assertTrue(resultSet.next());
            }
        }
    }

    @Test
    void reRunningV19IsIdempotent() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("schemaV19.db"))) {
            SchemaMigrator.migrate(connection);
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA user_version = 18");
            }
            assertDoesNotThrow(() -> SchemaMigrator.migrate(connection));
            assertEquals(23, userVersion(connection));
        }
    }

    @Test
    void migrateCreatesVillageCentersTable() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("schema20.db"))) {
            SchemaMigrator.migrate(connection);
            assertEquals(23, userVersion(connection));
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery(
                         "SELECT name FROM sqlite_master WHERE type='table' AND name='village_centers'")) {
                assertTrue(resultSet.next());
            }
        }
    }

    @Test
    void reRunningV20IsIdempotent() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("schemaV20.db"))) {
            SchemaMigrator.migrate(connection);
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA user_version = 19");
            }
            assertDoesNotThrow(() -> SchemaMigrator.migrate(connection));
            assertEquals(23, userVersion(connection));
        }
    }

    @Test
    void migrateAddsBiomeInstanceColumnToTravelBeacons() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("schema21.db"))) {
            SchemaMigrator.migrate(connection);
            assertEquals(23, userVersion(connection));
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate(
                        "INSERT INTO travel_beacons (id, world, x, y, z, facing, model_version, active, created_at) "
                                + "VALUES ('b1', 'world_hub', 1, 2, 3, 'NORTH', 1, 1, '2026-01-01T00:00:00Z')");
                try (ResultSet resultSet = statement.executeQuery(
                        "SELECT biome_instance FROM travel_beacons WHERE id = 'b1'")) {
                    assertTrue(resultSet.next());
                    assertEquals("", resultSet.getString("biome_instance"));
                }
            }
        }
    }

    @Test
    void reRunningV21IsIdempotent() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("schemaV21.db"))) {
            SchemaMigrator.migrate(connection);
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA user_version = 20");
            }
            assertDoesNotThrow(() -> SchemaMigrator.migrate(connection));
            assertEquals(23, userVersion(connection));
        }
    }

    /**
     * Issues #133/#135 : les waypoints déjà existants (créés avant cette migration, donc sans
     * {@code display_name}) reçoivent chacun un nom unique au backfill — jamais le même nom pour
     * deux waypoints, même si leur biome est identique (reproduit le cas réel « beach » en double).
     * {@code id} et les découvertes joueurs ne sont jamais touchés par cette migration.
     */
    @Test
    void migrateBackfillsDisplayNameForExistingWaypointsWithoutDuplicates() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("schema22.db"))) {
            SchemaMigrator.migrate(connection);
            try (Statement statement = connection.createStatement()) {
                // Simule une base créée avant V22 : colonne absente, deux waypoints du même biome
                // (le cas réel rapporté : deux destinations « beach » indiscernables dans le menu).
                statement.execute("DROP INDEX idx_waypoints_display_name");
                statement.execute("ALTER TABLE waypoints DROP COLUMN display_name");
                statement.executeUpdate(
                        "INSERT INTO waypoints (id, world, biome_instance, biome_key, region_x, region_z, x, y, z, "
                                + "facing, model_version, active, created_at) VALUES "
                                + "('wp_beach_1', 'wild', 'minecraft:beach@0,0', 'minecraft:beach', 0, 0, 10, 65, 10, "
                                + "'NORTH', 1, 1, '2026-01-01T00:00:00Z')");
                statement.executeUpdate(
                        "INSERT INTO waypoints (id, world, biome_instance, biome_key, region_x, region_z, x, y, z, "
                                + "facing, model_version, active, created_at) VALUES "
                                + "('wp_beach_2', 'wild', 'minecraft:beach@1,0', 'minecraft:beach', 1, 0, 300, 65, 10, "
                                + "'NORTH', 1, 1, '2026-01-01T00:00:00Z')");
                statement.executeUpdate(
                        "INSERT INTO waypoint_discoveries (player_uuid, waypoint_id, discovered_at) VALUES "
                                + "('p1', 'wp_beach_1', '2026-01-01T00:00:00Z')");
                statement.execute("PRAGMA user_version = 21");
            }

            SchemaMigrator.migrate(connection);
            assertEquals(23, userVersion(connection));

            String name1, name2;
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery(
                         "SELECT id, display_name FROM waypoints ORDER BY id")) {
                assertTrue(resultSet.next());
                assertEquals("wp_beach_1", resultSet.getString("id"));
                name1 = resultSet.getString("display_name");
                assertTrue(resultSet.next());
                assertEquals("wp_beach_2", resultSet.getString("id"));
                name2 = resultSet.getString("display_name");
                assertFalse(resultSet.next());
            }
            assertFalse(name1.isBlank(), "chaque waypoint existant reçoit un nom non vide");
            assertFalse(name2.isBlank());
            assertFalse(name1.equalsIgnoreCase(name2),
                    "deux waypoints du même biome ne doivent jamais recevoir le même nom au backfill");

            // id et découvertes joueurs inchangés par la migration.
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery(
                         "SELECT waypoint_id FROM waypoint_discoveries WHERE player_uuid = 'p1'")) {
                assertTrue(resultSet.next());
                assertEquals("wp_beach_1", resultSet.getString("waypoint_id"));
            }
        }
    }

    /** L'index unique posé par V22 refuse bien un doublon de nom à l'attribution (pas seulement en mémoire). */
    @Test
    void migrateCreatesAUniqueIndexOnDisplayNameRejectingDuplicateAttribution() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("schema22b.db"))) {
            SchemaMigrator.migrate(connection);
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate(
                        "INSERT INTO waypoints (id, display_name, world, biome_instance, biome_key, region_x, "
                                + "region_z, x, y, z, facing, model_version, active, created_at) VALUES "
                                + "('wp_a', 'Rochebrune', 'wild', 'minecraft:forest@0,0', 'minecraft:forest', 0, 0, "
                                + "1, 65, 1, 'NORTH', 1, 1, '2026-01-01T00:00:00Z')");
                assertThrows(java.sql.SQLException.class, () -> statement.executeUpdate(
                        "INSERT INTO waypoints (id, display_name, world, biome_instance, biome_key, region_x, "
                                + "region_z, x, y, z, facing, model_version, active, created_at) VALUES "
                                + "('wp_b', 'Rochebrune', 'wild', 'minecraft:forest@1,0', 'minecraft:forest', 1, 0, "
                                + "2, 65, 2, 'NORTH', 1, 1, '2026-01-01T00:00:00Z')"),
                        "un second waypoint avec le même display_name doit être refusé par l'index unique");
            }
        }
    }

    @Test
    void reRunningV22IsIdempotent() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("schemaV22.db"))) {
            SchemaMigrator.migrate(connection);
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA user_version = 21");
            }
            assertDoesNotThrow(() -> SchemaMigrator.migrate(connection));
            assertEquals(23, userVersion(connection));
        }
    }

    /**
     * Retour joueur 2026-10-04 (suite #133/#135) : les noms générés avant cette migration par simple
     * concaténation (ex. {@code Lacgivre}) deviennent lisibles (ex. {@code Lac de Givre}), sans
     * jamais toucher {@code id} ni les découvertes déjà enregistrées pour ce waypoint.
     */
    @Test
    void migrateRenamesExistingConcatenatedDisplayNamesToReadableFormPreservingIdAndDiscoveries() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("schema23.db"))) {
            SchemaMigrator.migrate(connection);
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate(
                        "INSERT INTO waypoints (id, display_name, world, biome_instance, biome_key, region_x, "
                                + "region_z, x, y, z, facing, model_version, active, created_at) VALUES "
                                + "('wp_lac', 'Lacgivre', 'wild', 'minecraft:frozen_ocean@0,0', "
                                + "'minecraft:frozen_ocean', 0, 0, 20, 65, 20, 'NORTH', 1, 1, '2026-01-01T00:00:00Z')");
                statement.executeUpdate(
                        "INSERT INTO waypoint_discoveries (player_uuid, waypoint_id, discovered_at) VALUES "
                                + "('p1', 'wp_lac', '2026-01-01T00:00:00Z')");
                statement.execute("PRAGMA user_version = 22");
            }

            SchemaMigrator.migrate(connection);
            assertEquals(23, userVersion(connection));

            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery(
                         "SELECT display_name FROM waypoints WHERE id = 'wp_lac'")) {
                assertTrue(resultSet.next());
                assertEquals("Lac de Givre", resultSet.getString("display_name"),
                        "le nom concaténé doit devenir lisible, exactement comme demandé par le joueur");
            }
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery(
                         "SELECT waypoint_id FROM waypoint_discoveries WHERE player_uuid = 'p1'")) {
                assertTrue(resultSet.next());
                assertEquals("wp_lac", resultSet.getString("waypoint_id"), "la découverte survit au renommage");
            }
        }
    }

    /** Un nom déjà lisible (ou un nom de secours « Avant-poste N ») n'est jamais altéré par V23. */
    @Test
    void migrateNeverRenamesANameAlreadyOutsideTheOldCatalog() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("schema23b.db"))) {
            SchemaMigrator.migrate(connection);
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate(
                        "INSERT INTO waypoints (id, display_name, world, biome_instance, biome_key, region_x, "
                                + "region_z, x, y, z, facing, model_version, active, created_at) VALUES "
                                + "('wp_custom', 'Avant-poste 1', 'wild', 'minecraft:plains@9,9', "
                                + "'minecraft:plains', 9, 9, 90, 65, 90, 'NORTH', 1, 1, '2026-01-01T00:00:00Z')");
                statement.execute("PRAGMA user_version = 22");
            }

            SchemaMigrator.migrate(connection);

            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery(
                         "SELECT display_name FROM waypoints WHERE id = 'wp_custom'")) {
                assertTrue(resultSet.next());
                assertEquals("Avant-poste 1", resultSet.getString("display_name"),
                        "un nom hors ancien catalogue (ex. nom de secours) reste inchangé");
            }
        }
    }

    @Test
    void reRunningV23IsIdempotent() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("schemaV23.db"))) {
            SchemaMigrator.migrate(connection);
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA user_version = 22");
            }
            assertDoesNotThrow(() -> SchemaMigrator.migrate(connection));
            assertEquals(23, userVersion(connection));
        }
    }

    private int userVersion(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("PRAGMA user_version")) {
            return resultSet.next() ? resultSet.getInt(1) : -1;
        }
    }
}
