package com.lodygames.rpgquest.database;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Assumptions;

/**
 * Support des tests d'intégration MariaDB (issue #41).
 *
 * <p>Les tests réels contre un serveur MariaDB sont <strong>optionnels</strong> : ils ne
 * s'exécutent que si les variables d'environnement suivantes sont définies, sinon ils sont
 * <em>ignorés</em> (jamais en échec). Ainsi {@code ./gradlew test} passe sur une machine sans
 * MariaDB, et l'on peut valider pour de vrai en chargeant la config locale :
 *
 * <pre>
 *   RPGQUEST_DB_HOST  RPGQUEST_DB_PORT  RPGQUEST_DB_NAME  RPGQUEST_DB_USER  RPGQUEST_DB_PASSWORD
 * </pre>
 *
 * <p>Le mot de passe n'est jamais imprimé. Chaque classe de test <strong>vide</strong> les tables
 * RPGQuest de la base de test avant de commencer (base de test dédiée, jamais de prod).</p>
 */
final class MariaDbTestSupport {

    /** Tables du schéma RPGQuest, ordre inverse des dépendances FK pour le DROP. */
    static final List<String> RPGQUEST_TABLES = List.of(
            "waypoint_discoveries", "waypoints",
            "waystone_discoveries", "waystones", "item_travel_cooldowns", "story_progress",
            "npc_citizens_bindings", "npc_ids", "store_deliveries_processed",
            "backpack_audit", "backpack_overflow", "backpacks", "player_entitlements",
            "player_placed_blocks", "xp_grants", "player_skills", "claim_members", "claims",
            "portal_cooldowns", "market_listings", "transactions", "wallets", "resource_nodes",
            "quest_objective_progress", "quest_progress", "player_variables", "player_profiles",
            "rpgquest_schema_migrations");

    private MariaDbTestSupport() {
    }

    static boolean available() {
        for (String key : List.of("RPGQUEST_DB_HOST", "RPGQUEST_DB_PORT", "RPGQUEST_DB_NAME",
                "RPGQUEST_DB_USER", "RPGQUEST_DB_PASSWORD")) {
            String v = System.getenv(key);
            if (v == null || v.isBlank()) {
                return false;
            }
        }
        return true;
    }

    static void assumeAvailable() {
        Assumptions.assumeTrue(available(),
                "Test d'intégration MariaDB ignoré : variables RPGQUEST_DB_* non définies.");
    }

    static DatabaseSettings.MySqlSettings settings() {
        return new DatabaseSettings.MySqlSettings(
                System.getenv("RPGQUEST_DB_HOST"),
                Integer.parseInt(System.getenv("RPGQUEST_DB_PORT")),
                System.getenv("RPGQUEST_DB_NAME"),
                System.getenv("RPGQUEST_DB_USER"),
                "RPGQUEST_DB_PASSWORD",
                "disable",
                new DatabaseSettings.PoolSettings(1, 4, 10_000L, 600_000L, 0L));
    }

    /** Moteur MariaDB configuré depuis l'environnement de test. */
    static MySqlDatabaseEngine engine() {
        return new MySqlDatabaseEngine(settings(), System::getenv);
    }

    /** Supprime toutes les tables RPGQuest de la base de test (idempotent, FK ignorées). */
    static void wipe() throws SQLException {
        MySqlDatabaseEngine engine = engine();
        engine.start();
        try {
            Connection connection = engine.borrow();
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET FOREIGN_KEY_CHECKS = 0");
                for (String table : RPGQUEST_TABLES) {
                    statement.execute("DROP TABLE IF EXISTS " + table);
                }
                statement.execute("SET FOREIGN_KEY_CHECKS = 1");
            } finally {
                engine.release(connection);
            }
        } finally {
            engine.close();
        }
    }
}
