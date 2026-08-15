package com.lodygames.rpgquest.database;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Applies schema migrations in order, tracked via SQLite's {@code PRAGMA
 * user_version}. Running {@link #migrate(Connection)} on an already
 * up-to-date database is a no-op.
 */
public final class SchemaMigrator {

    private static final int CURRENT_VERSION = 12;

    private SchemaMigrator() {
    }

    public static void migrate(Connection connection) throws SQLException {
        int startingVersion = currentVersion(connection);
        int version = startingVersion;

        if (version < 1) {
            applyV1(connection);
            version = 1;
        }
        if (version < 2) {
            applyV2(connection);
            version = 2;
        }
        if (version < 3) {
            applyV3(connection);
            version = 3;
        }
        if (version < 4) {
            applyV4(connection);
            version = 4;
        }
        if (version < 5) {
            applyV5(connection);
            version = 5;
        }
        if (version < 6) {
            applyV6(connection);
            version = 6;
        }
        if (version < 7) {
            applyV7(connection);
            version = 7;
        }
        if (version < 8) {
            applyV8(connection);
            version = 8;
        }
        if (version < 9) {
            applyV9(connection);
            version = 9;
        }
        if (version < 10) {
            applyV10(connection);
            version = 10;
        }
        if (version < 11) {
            applyV11(connection);
            version = 11;
        }
        if (version < 12) {
            applyV12(connection);
            version = 12;
        }

        if (version != startingVersion) {
            setVersion(connection, version);
        }
    }

    private static int currentVersion(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("PRAGMA user_version")) {
            return resultSet.next() ? resultSet.getInt(1) : 0;
        }
    }

    private static void setVersion(Connection connection, int version) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA user_version = " + version);
        }
    }

    private static void applyV1(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS player_profiles (
                        uuid TEXT PRIMARY KEY,
                        last_name TEXT NOT NULL,
                        created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL
                    )
                    """);

            statement.execute("""
                    CREATE TABLE IF NOT EXISTS player_variables (
                        player_uuid TEXT NOT NULL,
                        variable_key TEXT NOT NULL,
                        variable_value TEXT,
                        PRIMARY KEY (player_uuid, variable_key),
                        FOREIGN KEY (player_uuid) REFERENCES player_profiles (uuid) ON DELETE CASCADE
                    )
                    """);

            // Préparée pour une étape ultérieure : non exploitée pour l'instant.
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS quest_progress (
                        player_uuid TEXT NOT NULL,
                        quest_id TEXT NOT NULL,
                        state TEXT NOT NULL,
                        progress_data TEXT,
                        updated_at TEXT NOT NULL,
                        PRIMARY KEY (player_uuid, quest_id),
                        FOREIGN KEY (player_uuid) REFERENCES player_profiles (uuid) ON DELETE CASCADE
                    )
                    """);
        }
    }

    private static void applyV2(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS quest_objective_progress (
                        player_uuid TEXT NOT NULL,
                        quest_id TEXT NOT NULL,
                        step_id TEXT NOT NULL,
                        objective_index INTEGER NOT NULL,
                        progress INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY (player_uuid, quest_id, step_id, objective_index),
                        FOREIGN KEY (player_uuid) REFERENCES player_profiles (uuid) ON DELETE CASCADE
                    )
                    """);
        }
    }

    private static void applyV3(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            // Positions par joueur inutile ici : un nœud appartient au monde, pas à un joueur.
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS resource_nodes (
                        world TEXT NOT NULL,
                        x INTEGER NOT NULL,
                        y INTEGER NOT NULL,
                        z INTEGER NOT NULL,
                        type_id TEXT NOT NULL,
                        depleted_at TEXT,
                        PRIMARY KEY (world, x, y, z)
                    )
                    """);
        }
    }

    private static void applyV4(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS wallets (
                        player_uuid TEXT PRIMARY KEY,
                        balance INTEGER NOT NULL DEFAULT 0,
                        updated_at TEXT NOT NULL,
                        FOREIGN KEY (player_uuid) REFERENCES player_profiles (uuid) ON DELETE CASCADE
                    )
                    """);

            statement.execute("""
                    CREATE TABLE IF NOT EXISTS transactions (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        player_uuid TEXT NOT NULL,
                        type TEXT NOT NULL,
                        amount INTEGER NOT NULL,
                        context TEXT,
                        created_at TEXT NOT NULL,
                        FOREIGN KEY (player_uuid) REFERENCES player_profiles (uuid) ON DELETE CASCADE
                    )
                    """);
        }
    }

    private static void applyV5(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            // item_data : ItemStack#serializeAsBytes(), l'objet complet (méta, PDC d'un objet
            // personnalisé compris) plutôt qu'une référence recomposée à la remise.
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS market_listings (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        seller_uuid TEXT NOT NULL,
                        item_data BLOB NOT NULL,
                        price INTEGER NOT NULL,
                        status TEXT NOT NULL,
                        created_at TEXT NOT NULL,
                        resolved_at TEXT,
                        buyer_uuid TEXT,
                        FOREIGN KEY (seller_uuid) REFERENCES player_profiles (uuid) ON DELETE CASCADE
                    )
                    """);
            statement.execute("""
                    CREATE INDEX IF NOT EXISTS idx_market_listings_status ON market_listings (status)
                    """);
        }
    }

    private static void applyV6(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            // Un cooldown de portail doit survivre à une reconnexion (mission étape 16) : persisté ici,
            // rechargé en mémoire à la connexion par travel.PortalService (jamais consulté en base
            // depuis PlayerMoveEvent, trop fréquent pour une requête asynchrone par événement).
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS portal_cooldowns (
                        player_uuid TEXT NOT NULL,
                        portal_id TEXT NOT NULL,
                        expires_at TEXT NOT NULL,
                        PRIMARY KEY (player_uuid, portal_id),
                        FOREIGN KEY (player_uuid) REFERENCES player_profiles (uuid) ON DELETE CASCADE
                    )
                    """);
        }
    }

    private static void applyV7(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS claims (
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
            statement.execute("""
                    CREATE INDEX IF NOT EXISTS idx_claims_owner ON claims (owner_uuid)
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS claim_members (
                        claim_id TEXT NOT NULL,
                        member_uuid TEXT NOT NULL,
                        PRIMARY KEY (claim_id, member_uuid),
                        FOREIGN KEY (claim_id) REFERENCES claims (id) ON DELETE CASCADE,
                        FOREIGN KEY (member_uuid) REFERENCES player_profiles (uuid) ON DELETE CASCADE
                    )
                    """);
        }
    }

    private static void applyV8(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            // total_xp seul : le niveau n'est jamais persisté (toujours recalculé via
            // ProgressionCurve#levelForTotalXp), aucun risque de divergence niveau/XP.
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS player_skills (
                        player_uuid TEXT NOT NULL,
                        skill TEXT NOT NULL,
                        total_xp INTEGER NOT NULL DEFAULT 0,
                        updated_at TEXT NOT NULL,
                        PRIMARY KEY (player_uuid, skill),
                        FOREIGN KEY (player_uuid) REFERENCES player_profiles (uuid) ON DELETE CASCADE
                    )
                    """);

            // Un octroi d'XP est identifié par (joueur, compétence, id d'événement) : la même action
            // de jeu (mort de mob, bloc miné...) ne peut jamais récompenser deux fois la même
            // compétence (mission étape 19, point 5).
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS xp_grants (
                        player_uuid TEXT NOT NULL,
                        skill TEXT NOT NULL,
                        event_id TEXT NOT NULL,
                        amount INTEGER NOT NULL,
                        reason TEXT,
                        created_at TEXT NOT NULL,
                        PRIMARY KEY (player_uuid, skill, event_id),
                        FOREIGN KEY (player_uuid) REFERENCES player_profiles (uuid) ON DELETE CASCADE
                    )
                    """);
            statement.execute("""
                    CREATE INDEX IF NOT EXISTS idx_xp_grants_player ON xp_grants (player_uuid)
                    """);

            // Anti-farm (mission point 7) : une position posée par un joueur n'accorde jamais d'XP de
            // minage. Aucune clé étrangère vers player_profiles : un bloc survit à la suppression du
            // profil de son poseur (le monde reste inchangé).
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS player_placed_blocks (
                        world TEXT NOT NULL,
                        x INTEGER NOT NULL,
                        y INTEGER NOT NULL,
                        z INTEGER NOT NULL,
                        PRIMARY KEY (world, x, y, z)
                    )
                    """);
        }
    }

    private static void applyV9(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            // Avantage générique (mission étape 20, point 11) : le backpack est le premier
            // consommateur concret, d'autres avantages futurs réutiliseront cette même table sans
            // migration supplémentaire (entitlement_key est une simple chaîne libre).
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS player_entitlements (
                        player_uuid TEXT NOT NULL,
                        entitlement_key TEXT NOT NULL,
                        tier TEXT NOT NULL,
                        granted_at TEXT NOT NULL,
                        reason TEXT,
                        PRIMARY KEY (player_uuid, entitlement_key),
                        FOREIGN KEY (player_uuid) REFERENCES player_profiles (uuid) ON DELETE CASCADE
                    )
                    """);

            // contents : ItemStack[] sérialisé maison (voir backpack.ItemArraySerializer),
            // schema_version distinct de PRAGMA user_version : permet de migrer le format binaire
            // sans toucher au schéma SQL (mission point 6, "stocke... de manière sûre et versionnée").
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS backpacks (
                        player_uuid TEXT PRIMARY KEY,
                        schema_version INTEGER NOT NULL,
                        contents BLOB NOT NULL,
                        updated_at TEXT NOT NULL,
                        FOREIGN KEY (player_uuid) REFERENCES player_profiles (uuid) ON DELETE CASCADE
                    )
                    """);

            // Boîte de récupération (mission point 9) : objets qui ne rentraient plus après une
            // réduction de taille, ou tout contenu qu'une anomalie empêche de restaurer directement
            // (ex. bloc sérialisé illisible) — jamais perdus silencieusement, toujours réclamables.
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS backpack_overflow (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        player_uuid TEXT NOT NULL,
                        schema_version INTEGER NOT NULL,
                        contents BLOB NOT NULL,
                        reason TEXT NOT NULL,
                        created_at TEXT NOT NULL,
                        claimed_at TEXT,
                        FOREIGN KEY (player_uuid) REFERENCES player_profiles (uuid) ON DELETE CASCADE
                    )
                    """);
            statement.execute("""
                    CREATE INDEX IF NOT EXISTS idx_backpack_overflow_player ON backpack_overflow (player_uuid)
                    """);

            // Journal d'anomalies (mission, validation "toute anomalie crée une entrée de
            // récupération ou d'audit") : append-only, même esprit que la table transactions de
            // WalletRepository mais pour des événements structurels plutôt que financiers.
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS backpack_audit (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        player_uuid TEXT,
                        event_type TEXT NOT NULL,
                        detail TEXT,
                        created_at TEXT NOT NULL
                    )
                    """);
        }
    }

    private static void applyV10(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            // Filet de sécurité d'idempotence côté serveur de jeu (mission étape 22, points 7-8) :
            // web-api acquitte déjà les livraisons de façon idempotente, mais si l'accusé de
            // réception échoue à repartir après un octroi réussi (crash pile après), le prochain
            // sondage renverrait la même livraison — cette table empêche un second octroi local.
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS store_deliveries_processed (
                        delivery_id TEXT PRIMARY KEY,
                        outcome TEXT NOT NULL,
                        detail TEXT,
                        processed_at TEXT NOT NULL
                    )
                    """);
        }
    }

    private static void applyV11(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            // Allocateur d'identifiants de PNJ (voir com.lodygames.rpgquest.npc.NpcIdentityService) :
            // une ligne par identifiant "npc_<n>" auto-généré, jamais réutilisé (id AUTOINCREMENT).
            // Sert uniquement de secours quand un administrateur ne fournit pas d'id explicite à
            // /rpgadmin npc tag ; l'identité elle-même vit dans le PersistentDataContainer de
            // l'entité, jamais dans cette table (pas de lien entité <-> ligne à maintenir ici).
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS npc_ids (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        created_at TEXT NOT NULL
                    )
                    """);
        }
    }

    private static void applyV12(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            // Liaison persistante PNJ Citizens <-> identifiant logique RPGQuest (voir
            // com.lodygames.rpgquest.npc.NpcIdentityService / NpcBindingRepository). Nécessaire car
            // Citizens recrée une nouvelle entité Bukkit éphémère à chaque (re)spawn — un
            // PersistentDataContainer posé sur cette entité ne survit donc jamais à un redémarrage.
            // citizens_uuid = NPC#getUniqueId(), garanti stable par Citizens lui-même (contrairement
            // à NPC#getId(), documenté par Citizens comme non garanti unique entre sessions).
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS npc_citizens_bindings (
                        citizens_uuid TEXT PRIMARY KEY,
                        citizens_numeric_id INTEGER NOT NULL,
                        npc_id TEXT NOT NULL,
                        created_at TEXT NOT NULL
                    )
                    """);
        }
    }
}
