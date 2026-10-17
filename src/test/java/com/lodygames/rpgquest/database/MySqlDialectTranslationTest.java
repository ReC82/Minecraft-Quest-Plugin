package com.lodygames.rpgquest.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Traduction du DML/DDL SQLite canonique vers MariaDB (issue #41) — <strong>hors base</strong>,
 * cible : ce que {@link MySqlDialect} produit pour les repositories et les migrations.
 */
class MySqlDialectTranslationTest {

    private final MySqlDialect dialect = new MySqlDialect();

    // ---- DML : rewrite() -------------------------------------------------------------------

    @Test
    void upsertBecomesOnDuplicateKeyUpdate() {
        String sqlite = """
                INSERT INTO player_variables (player_uuid, variable_key, variable_value) VALUES (?, ?, ?)
                ON CONFLICT (player_uuid, variable_key) DO UPDATE SET variable_value = excluded.variable_value
                """;
        String mysql = dialect.rewrite(sqlite);
        assertTrue(mysql.contains("ON DUPLICATE KEY UPDATE variable_value = VALUES(variable_value)"));
        assertFalse(mysql.contains("ON CONFLICT"));
        assertFalse(mysql.contains("excluded."));
    }

    @Test
    void multiColumnUpsertRewritesEveryAssignment() {
        String sqlite = """
                INSERT INTO story_progress (player_uuid, story_id, state, current_index, updated_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (player_uuid, story_id) DO UPDATE SET
                    state = excluded.state, current_index = excluded.current_index, updated_at = excluded.updated_at
                """;
        String mysql = dialect.rewrite(sqlite);
        assertTrue(mysql.contains("ON DUPLICATE KEY UPDATE"));
        assertTrue(mysql.contains("state = VALUES(state)"));
        assertTrue(mysql.contains("current_index = VALUES(current_index)"));
        assertTrue(mysql.contains("updated_at = VALUES(updated_at)"));
    }

    @Test
    void conflictClauseWithoutSpaceIsHandled() {
        String mysql = dialect.rewrite(
                "INSERT INTO npc_citizens_bindings (citizens_uuid, citizens_numeric_id, npc_id, created_at) "
                        + "VALUES (?, ?, ?, ?) ON CONFLICT(citizens_uuid) DO UPDATE SET "
                        + "citizens_numeric_id = excluded.citizens_numeric_id, npc_id = excluded.npc_id");
        assertTrue(mysql.contains("ON DUPLICATE KEY UPDATE citizens_numeric_id = VALUES(citizens_numeric_id), "
                + "npc_id = VALUES(npc_id)"));
    }

    @Test
    void insertOrIgnoreBecomesInsertIgnore() {
        assertEquals("INSERT IGNORE INTO wallets (player_uuid, balance, updated_at) VALUES (?, 0, ?)",
                dialect.rewrite("INSERT OR IGNORE INTO wallets (player_uuid, balance, updated_at) VALUES (?, 0, ?)"));
    }

    @Test
    void plainStatementsAreUnchanged() {
        String select = "SELECT balance FROM wallets WHERE player_uuid = ?";
        assertEquals(select, dialect.rewrite(select));
        String update = "UPDATE claims SET allow_public_redstone = ? WHERE id = ? AND owner_uuid = ?";
        assertEquals(update, dialect.rewrite(update));
    }

    // ---- DDL : ddl() ---------------------------------------------------------------------

    @Test
    void createTableTranslatesTypesAndAddsInnoDb() {
        String sqlite = """
                CREATE TABLE IF NOT EXISTS player_variables (
                    player_uuid TEXT NOT NULL,
                    variable_key TEXT NOT NULL,
                    variable_value TEXT,
                    PRIMARY KEY (player_uuid, variable_key),
                    FOREIGN KEY (player_uuid) REFERENCES player_profiles (uuid) ON DELETE CASCADE
                )
                """;
        String mysql = dialect.ddl(sqlite);
        assertTrue(mysql.startsWith("CREATE TABLE IF NOT EXISTS player_variables ("));
        assertTrue(mysql.contains("player_uuid VARCHAR(191) NOT NULL"), mysql);
        assertTrue(mysql.contains("variable_key VARCHAR(191) NOT NULL"), mysql);
        assertTrue(mysql.contains("variable_value TEXT"), "colonne nullable non-clé -> reste TEXT");
        assertFalse(mysql.contains("variable_value VARCHAR"));
        assertTrue(mysql.contains("PRIMARY KEY (player_uuid, variable_key)"));
        assertTrue(mysql.contains("FOREIGN KEY (player_uuid) REFERENCES player_profiles (uuid) ON DELETE CASCADE"));
        assertTrue(mysql.contains("ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin"));
        assertFalse(mysql.contains(" TEXT PRIMARY KEY"), "aucun TEXT en clé");
    }

    @Test
    void autoIncrementIntegerBlobAndBooleanColumnsAreTranslated() {
        String sqlite = """
                CREATE TABLE IF NOT EXISTS market_listings (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    seller_uuid TEXT NOT NULL,
                    item_data BLOB NOT NULL,
                    price INTEGER NOT NULL,
                    status TEXT NOT NULL,
                    buyer_uuid TEXT,
                    FOREIGN KEY (seller_uuid) REFERENCES player_profiles (uuid) ON DELETE CASCADE
                )
                """;
        String mysql = dialect.ddl(sqlite);
        assertTrue(mysql.contains("id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY"), mysql);
        assertTrue(mysql.contains("item_data LONGBLOB NOT NULL"));
        assertTrue(mysql.contains("price BIGINT NOT NULL"));
        assertTrue(mysql.contains("seller_uuid VARCHAR(191) NOT NULL"));
        assertTrue(mysql.contains("status VARCHAR(191) NOT NULL"), "colonne indexée NOT NULL -> VARCHAR");
        assertTrue(mysql.contains("buyer_uuid TEXT"), "colonne nullable non-clé -> TEXT");
    }

    @Test
    void inlineTextPrimaryKeyBecomesVarchar() {
        String mysql = dialect.ddl("""
                CREATE TABLE IF NOT EXISTS store_deliveries_processed (
                    delivery_id TEXT PRIMARY KEY,
                    outcome TEXT NOT NULL,
                    detail TEXT,
                    processed_at TEXT NOT NULL
                )
                """);
        assertTrue(mysql.contains("delivery_id VARCHAR(191) PRIMARY KEY"), mysql);
        assertTrue(mysql.contains("detail TEXT"));
    }

    @Test
    void createIndexBecomesAlterTableAddIndex() {
        assertEquals("ALTER TABLE claims ADD INDEX IF NOT EXISTS idx_claims_owner (owner_uuid)",
                dialect.ddl("CREATE INDEX IF NOT EXISTS idx_claims_owner ON claims (owner_uuid)"));
        assertEquals("ALTER TABLE waystones ADD UNIQUE INDEX IF NOT EXISTS idx_waystones_cell (world, cell_x, cell_z)",
                dialect.ddl("CREATE UNIQUE INDEX IF NOT EXISTS idx_waystones_cell ON waystones (world, cell_x, cell_z)"));
        assertEquals("ALTER TABLE waypoints ADD UNIQUE INDEX IF NOT EXISTS idx_waypoints_instance (world, biome_instance)",
                dialect.ddl("CREATE UNIQUE INDEX IF NOT EXISTS idx_waypoints_instance ON waypoints (world, biome_instance)"));
    }

    @Test
    void alterTableAddColumnIntegerBecomesBigint() {
        assertEquals("ALTER TABLE story_progress ADD COLUMN current_index BIGINT NOT NULL DEFAULT 0",
                dialect.ddl("ALTER TABLE story_progress ADD COLUMN current_index INTEGER NOT NULL DEFAULT 0"));
        assertEquals("ALTER TABLE claims ADD COLUMN reserved_min_x BIGINT",
                dialect.ddl("ALTER TABLE claims ADD COLUMN reserved_min_x INTEGER"));
    }

    @Test
    void updateStatementInMigrationIsLeftUnchanged() {
        String update = "UPDATE claims SET reserved_min_x = min_x, reserved_max_x = max_x";
        assertEquals(update, dialect.ddl(update));
    }

    @Test
    void everyRealMigrationTranslatesToPlausibleMariaDbDdl() throws Exception {
        for (java.util.Map.Entry<Integer, java.util.List<String>> entry : realMigrationDdl().entrySet()) {
            for (String sql : entry.getValue()) {
                String upper = sql.toUpperCase(java.util.Locale.ROOT);
                if (upper.startsWith("CREATE TABLE")) {
                    assertTrue(sql.contains("ENGINE=InnoDB"), "V" + entry.getKey() + " : " + sql);
                    assertFalse(upper.matches("(?s).*\\bTEXT\\s+PRIMARY\\s+KEY\\b.*"),
                            "V" + entry.getKey() + " : TEXT en clé -> " + sql);
                    assertFalse(upper.contains("AUTOINCREMENT"), "V" + entry.getKey() + " : " + sql);
                    assertFalse(upper.contains("\"") || sql.contains("[["), "V" + entry.getKey());
                } else if (upper.startsWith("CREATE INDEX") || upper.startsWith("CREATE UNIQUE INDEX")) {
                    org.junit.jupiter.api.Assertions.fail("index non traduit V" + entry.getKey() + " : " + sql);
                }
            }
        }
    }

    // ---- textes libres (issue #354) ------------------------------------------------------

    @Test
    void freeTextNotNullColumnStaysTextWithItsDefault() {
        String mysql = dialect.ddl("""
                CREATE TABLE IF NOT EXISTS building_sites (
                    id TEXT PRIMARY KEY,
                    description TEXT NOT NULL DEFAULT '',
                    created_at TEXT NOT NULL
                )
                """);
        assertTrue(mysql.contains("description TEXT NOT NULL DEFAULT ''"), mysql);
        assertTrue(mysql.contains("created_at VARCHAR(191) NOT NULL"), "texte court -> VARCHAR : " + mysql);
        assertTrue(mysql.contains("id VARCHAR(191) PRIMARY KEY"), mysql);
    }

    @Test
    void freeTextWideningsReproduceTheCanonicalDefinition() {
        java.util.List<String> widenings = dialect.freeTextWidenings();
        assertEquals(MySqlDialect.FREE_TEXT_COLUMNS.size(), widenings.size());
        assertTrue(widenings.contains(
                "ALTER TABLE building_sites MODIFY COLUMN description TEXT NOT NULL DEFAULT ''"), widenings.toString());
        assertTrue(widenings.contains(
                "ALTER TABLE backpack_overflow MODIFY COLUMN reason TEXT NOT NULL"), widenings.toString());
        assertTrue(new SqliteDialect().freeTextWidenings().isEmpty(), "SQLite : aucune instruction");
    }

    /**
     * Balayage du catalogue réel : chaque texte libre déclaré existe, est {@code NOT NULL} hors clé,
     * et sort en {@code TEXT} avec le même {@code DEFAULT} que le DDL canonique ; aucune colonne
     * indexée n'est {@code TEXT} (MariaDB refuserait l'index) ; et toute autre colonne
     * {@code VARCHAR(191)} hors clé est un texte court <em>connu</em> — une nouvelle colonne
     * {@code TEXT NOT NULL} oblige ainsi à choisir son camp ({@link MySqlDialect#FREE_TEXT_COLUMNS}
     * ou {@link #SHORT_TEXT_COLUMNS}).
     */
    @Test
    void everyNotNullTextColumnOfTheCatalogueIsClassified() throws Exception {
        java.util.Map<String, String> columnTypes = new java.util.TreeMap<>();
        java.util.Set<String> keyedOrIndexed = new java.util.TreeSet<>();
        java.util.regex.Pattern addIndex = java.util.regex.Pattern.compile(
                "(?i)^ALTER TABLE (\\S+) ADD (?:UNIQUE )?INDEX IF NOT EXISTS \\S+ \\((.*)\\)$");
        for (java.util.List<String> statements : realMigrationDdl().values()) {
            for (String sql : statements) {
                java.util.regex.Matcher index = addIndex.matcher(sql);
                if (index.matches()) {
                    for (String column : index.group(2).split(",")) {
                        keyedOrIndexed.add(index.group(1) + "." + column.trim());
                    }
                }
                if (!sql.startsWith("CREATE TABLE")) {
                    continue;
                }
                String table = sql.split("\\s+")[5];
                for (String line : sql.split("\n")) {
                    String def = line.trim().replaceAll(",$", "");
                    String upper = def.toUpperCase(java.util.Locale.ROOT);
                    if (upper.startsWith("PRIMARY KEY") || upper.startsWith("FOREIGN KEY")
                            || upper.startsWith("UNIQUE")) {
                        int open = def.indexOf('(');
                        for (String column : def.substring(open + 1, def.indexOf(')', open)).split(",")) {
                            keyedOrIndexed.add(table + "." + column.trim());
                        }
                    } else if (upper.contains(" VARCHAR(191)") || upper.matches("\\S+ TEXT\\b.*")) {
                        String column = def.split("\\s+")[0];
                        columnTypes.put(table + "." + column, def.substring(column.length()).trim());
                        if (upper.contains(" PRIMARY KEY")) {
                            keyedOrIndexed.add(table + "." + column);
                        }
                    }
                }
            }
        }

        for (MySqlDialect.FreeTextColumn free : MySqlDialect.FREE_TEXT_COLUMNS) {
            String key = free.table() + "." + free.column();
            assertTrue(columnTypes.containsKey(key), "texte libre absent du catalogue : " + key);
            assertFalse(keyedOrIndexed.contains(key), "un texte libre ne peut pas être indexé : " + key);
            String expected = "TEXT NOT NULL" + (free.defaultLiteral() == null ? "" : " DEFAULT " + free.defaultLiteral());
            assertEquals(expected, columnTypes.get(key), key);
        }
        java.util.List<String> unclassified = new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, String> column : columnTypes.entrySet()) {
            boolean varchar = column.getValue().startsWith("VARCHAR(191)");
            if (keyedOrIndexed.contains(column.getKey())) {
                assertTrue(varchar, "colonne indexée en TEXT, index refusé par MariaDB : " + column);
            } else if (varchar && !SHORT_TEXT_COLUMNS.contains(column.getKey())) {
                unclassified.add(column.getKey());
            }
        }
        assertEquals(java.util.List.of(), unclassified, "TEXT NOT NULL non classé (texte court ou libre ?)");
    }

    /** Textes {@code NOT NULL} courts et bornés hors clé : horodatages, énumérations, identifiants. */
    private static final java.util.Set<String> SHORT_TEXT_COLUMNS = java.util.Set.of(
            "backpack_audit.created_at", "backpack_audit.event_type", "backpack_overflow.created_at",
            "backpacks.updated_at", "building_baselines.captured_at", "building_baselines.captured_by",
            "building_baselines.world", "building_placement_history.actor",
            "building_placement_history.building_id", "building_placement_history.happened_at",
            "building_placement_history.operation", "building_placement_history.schematic_sha256",
            "building_placement_history.world", "building_placements.building_id",
            "building_placements.placed_at", "building_placements.placed_by", "building_site_ids.created_at",
            "building_sites.created_at", "building_sites.created_by", "building_sites.facing",
            "building_sites.name", "building_sites.status", "city_discoveries.discovered_at",
            "claims.created_at", "claims.world", "dialogue_node_reads.read_at", "encounter_sites.biome_key",
            "encounter_sites.citizens_uuid", "encounter_sites.created_at", "encounter_sites.npc_id",
            "encounter_sites.status", "encounter_sites.template_id", "encounter_sites.updated_at",
            "item_travel_cooldowns.expires_at", "market_listings.created_at",
            "npc_citizens_bindings.created_at", "npc_citizens_bindings.npc_id",
            "npc_citizens_skins.source_kind", "npc_spot_ids.created_at", "npc_spots.created_at",
            "npc_spots.created_by", "npc_spots.name", "npc_spots.updated_at", "npc_spots.world", "npc_citizens_skins.updated_at", "npc_ids.created_at",
            "player_entitlements.granted_at", "player_entitlements.tier", "player_heart_grants.granted_at",
            "player_heart_grants.quest_id", "player_profiles.created_at",
            "player_profiles.last_name", "player_profiles.updated_at", "player_skills.updated_at",
            "portal_cooldowns.expires_at", "quest_progress.state", "quest_progress.updated_at",
            "quest_reward_grants.created_at", "resource_nodes.type_id", "store_deliveries_processed.outcome",
            "store_deliveries_processed.processed_at", "story_progress.state", "story_progress.updated_at",
            "transactions.created_at", "transactions.type", "travel_beacons.created_at",
            "travel_beacons.facing", "travel_beacons.world", "village_centers.created_at",
            "village_centers.name", "village_centers.world", "wallets.updated_at",
            "waypoint_discoveries.discovered_at", "waypoints.biome_key", "waypoints.created_at",
            "waypoints.facing", "waystone_discoveries.discovered_at", "waystones.created_at", "waystones.name",
            "xp_grants.created_at", "stat_period_archives.scope", "stat_period_archives.start_at",
            "stat_period_archives.end_at", "stat_period_archives.frozen_at", "stat_activity_log.event_key",
            "stat_activity_log.player_uuid", "stat_activity_log.target", "stat_period_audit.created_at",
            "reward_entitlements.player_uuid", "reward_entitlements.source", "reward_entitlements.source_ref",
            "reward_entitlements.reward_key", "reward_entitlements.status", "reward_entitlements.created_at",
            "reward_entitlements.updated_at", "periodic_quest_assignments.quest_id",
            "periodic_quest_assignments.status", "periodic_quest_assignments.assigned_at",
            "periodic_quest_assignments.updated_at", "personal_vaults.updated_at");

    /** DDL MariaDB produit par chaque migration réelle (version → instructions exécutées). */
    private java.util.Map<Integer, java.util.List<String>> realMigrationDdl() throws Exception {
        java.util.Map<Integer, java.util.List<String>> byVersion = new java.util.TreeMap<>();
        java.util.List<String> produced = new java.util.ArrayList<>();
        // Connexion factice (proxy) : capture Statement.execute(String), répond "colonne absente"
        // à PRAGMA/information_schema (pour V14/V15) et ignore le reste.
        java.sql.Connection connection = (java.sql.Connection) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {java.sql.Connection.class},
                (proxy, method, args) -> {
                    if ("createStatement".equals(method.getName())) {
                        return java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                                new Class<?>[] {java.sql.Statement.class}, (p2, m2, a2) -> switch (m2.getName()) {
                            case "execute" -> {
                                produced.add((String) a2[0]);
                                yield false;
                            }
                            case "executeQuery" -> emptyResultSet();
                            case "close", "closeOnCompletion" -> null;
                            default -> defaultValue(m2.getReturnType());
                        });
                    }
                    if ("prepareStatement".equals(method.getName())) {
                        return java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                                new Class<?>[] {java.sql.PreparedStatement.class}, (p2, m2, a2) ->
                                        "executeQuery".equals(m2.getName()) ? emptyResultSet()
                                                : defaultValue(m2.getReturnType()));
                    }
                    return defaultValue(method.getReturnType());
                });

        for (SchemaMigration migration : SchemaMigrator.ALL) {
            produced.clear();
            migration.step().apply(connection, dialect);
            byVersion.put(migration.version(), new java.util.ArrayList<>(produced));
        }
        return byVersion;
    }

    private static Object emptyResultSet() {
        return java.lang.reflect.Proxy.newProxyInstance(MySqlDialectTranslationTest.class.getClassLoader(),
                new Class<?>[] {java.sql.ResultSet.class}, (p, m, a) ->
                        "next".equals(m.getName()) ? Boolean.FALSE : defaultValue(m.getReturnType()));
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return Boolean.FALSE;
        }
        if (type == void.class) {
            return null;
        }
        return 0;
    }
}
