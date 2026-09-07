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
            for (String sql : produced) {
                String upper = sql.toUpperCase(java.util.Locale.ROOT);
                if (upper.startsWith("CREATE TABLE")) {
                    assertTrue(sql.contains("ENGINE=InnoDB"), "V" + migration.version() + " : " + sql);
                    assertFalse(upper.matches("(?s).*\\bTEXT\\s+PRIMARY\\s+KEY\\b.*"),
                            "V" + migration.version() + " : TEXT en clé -> " + sql);
                    assertFalse(upper.contains("AUTOINCREMENT"), "V" + migration.version() + " : " + sql);
                    assertFalse(upper.contains("\"") || sql.contains("[["), "V" + migration.version());
                } else if (upper.startsWith("CREATE INDEX") || upper.startsWith("CREATE UNIQUE INDEX")) {
                    org.junit.jupiter.api.Assertions.fail("index non traduit V" + migration.version() + " : " + sql);
                }
            }
        }
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
