package com.lodygames.rpgquest.database;

import com.lodygames.rpgquest.waypoint.model.WaypointNameCatalog;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Catalogue des migrations de schéma RPGQuest, dans l'ordre (issue #40).
 *
 * <p>Le suivi de version ({@link SchemaHistory}) et la boucle d'application
 * ({@link SchemaMigrationRunner}) sont des classes séparées, ce qui rend le mécanisme portable
 * (SQLite <em>et</em> MariaDB) sans conditionnelle dispersée.</p>
 *
 * <p>Chaque instruction DDL est écrite ici en <strong>SQLite canonique</strong> et passée par
 * {@link SqlDialect#ddl(String)} : pour {@link SqliteDialect} c'est l'identité (SQL
 * <strong>strictement inchangé</strong>, une base {@code data.db} existante fonctionne exactement
 * comme avant) ; {@link MySqlDialect} traduit les types, l'auto-incrément, le moteur InnoDB et les
 * index. V14/V15 utilisent en plus {@link SqlDialect#columnExists} pour un {@code ALTER} idempotent.</p>
 */
public final class SchemaMigrator {

    /** Version de schéma attendue par ce build. */
    public static final int CURRENT_VERSION = 26;

    /** Toutes les migrations connues, dans l'ordre croissant de version. */
    public static final List<SchemaMigration> ALL = List.of(
            new SchemaMigration(1, "player_profiles, player_variables, quest_progress", SchemaMigrator::applyV1),
            new SchemaMigration(2, "quest_objective_progress", SchemaMigrator::applyV2),
            new SchemaMigration(3, "resource_nodes", SchemaMigrator::applyV3),
            new SchemaMigration(4, "wallets, transactions", SchemaMigrator::applyV4),
            new SchemaMigration(5, "market_listings", SchemaMigrator::applyV5),
            new SchemaMigration(6, "portal_cooldowns", SchemaMigrator::applyV6),
            new SchemaMigration(7, "claims, claim_members", SchemaMigrator::applyV7),
            new SchemaMigration(8, "player_skills, xp_grants, player_placed_blocks", SchemaMigrator::applyV8),
            new SchemaMigration(9, "player_entitlements, backpacks, backpack_overflow, backpack_audit", SchemaMigrator::applyV9),
            new SchemaMigration(10, "store_deliveries_processed", SchemaMigrator::applyV10),
            new SchemaMigration(11, "npc_ids", SchemaMigrator::applyV11),
            new SchemaMigration(12, "npc_citizens_bindings", SchemaMigrator::applyV12),
            new SchemaMigration(13, "story_progress", SchemaMigrator::applyV13),
            new SchemaMigration(14, "story_progress.current_index", SchemaMigrator::applyV14),
            new SchemaMigration(15, "claims land reservation columns", SchemaMigrator::applyV15),
            new SchemaMigration(16, "item_travel_cooldowns", SchemaMigrator::applyV16),
            new SchemaMigration(17, "waystones, waystone_discoveries", SchemaMigrator::applyV17),
            new SchemaMigration(18, "waypoints, waypoint_discoveries", SchemaMigrator::applyV18),
            new SchemaMigration(19, "travel_beacons", SchemaMigrator::applyV19),
            new SchemaMigration(20, "village_centers", SchemaMigrator::applyV20),
            new SchemaMigration(21, "travel_beacons.biome_instance", SchemaMigrator::applyV21),
            new SchemaMigration(22, "waypoints.display_name", SchemaMigrator::applyV22),
            new SchemaMigration(23, "waypoints.display_name noms lisibles", SchemaMigrator::applyV23),
            new SchemaMigration(24, "dialogue_node_reads", SchemaMigrator::applyV24),
            new SchemaMigration(25, "quest_reward_grants", SchemaMigrator::applyV25),
            new SchemaMigration(26, "quest_reward_grants.status (dettes récupérables)", SchemaMigrator::applyV26));

    private SchemaMigrator() {
    }

    /**
     * Applique les migrations SQLite en attente via {@code PRAGMA user_version} — API historique,
     * conservée pour la compatibilité (tests, appels directs sur une {@link Connection} SQLite).
     * Rejouée sur une base à jour : no-op.
     */
    public static void migrate(Connection connection) throws SQLException {
        new SchemaMigrationRunner(ALL, new PragmaUserVersionHistory(), new SqliteDialect()).run(connection);
    }

    // --------------------------------------------------------------------------------------------
    //  Étapes de migration — SQL inchangé depuis l'origine du projet.
    // --------------------------------------------------------------------------------------------

    private static void applyV1(Connection connection, SqlDialect dialect) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(dialect.ddl("""
                    CREATE TABLE IF NOT EXISTS player_profiles (
                        uuid TEXT PRIMARY KEY,
                        last_name TEXT NOT NULL,
                        created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL
                    )
                    """));

            statement.execute(dialect.ddl("""
                    CREATE TABLE IF NOT EXISTS player_variables (
                        player_uuid TEXT NOT NULL,
                        variable_key TEXT NOT NULL,
                        variable_value TEXT,
                        PRIMARY KEY (player_uuid, variable_key),
                        FOREIGN KEY (player_uuid) REFERENCES player_profiles (uuid) ON DELETE CASCADE
                    )
                    """));

            // Préparée pour une étape ultérieure : non exploitée pour l'instant.
            statement.execute(dialect.ddl("""
                    CREATE TABLE IF NOT EXISTS quest_progress (
                        player_uuid TEXT NOT NULL,
                        quest_id TEXT NOT NULL,
                        state TEXT NOT NULL,
                        progress_data TEXT,
                        updated_at TEXT NOT NULL,
                        PRIMARY KEY (player_uuid, quest_id),
                        FOREIGN KEY (player_uuid) REFERENCES player_profiles (uuid) ON DELETE CASCADE
                    )
                    """));
        }
    }

    private static void applyV2(Connection connection, SqlDialect dialect) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(dialect.ddl("""
                    CREATE TABLE IF NOT EXISTS quest_objective_progress (
                        player_uuid TEXT NOT NULL,
                        quest_id TEXT NOT NULL,
                        step_id TEXT NOT NULL,
                        objective_index INTEGER NOT NULL,
                        progress INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY (player_uuid, quest_id, step_id, objective_index),
                        FOREIGN KEY (player_uuid) REFERENCES player_profiles (uuid) ON DELETE CASCADE
                    )
                    """));
        }
    }

    private static void applyV3(Connection connection, SqlDialect dialect) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            // Positions par joueur inutile ici : un nœud appartient au monde, pas à un joueur.
            statement.execute(dialect.ddl("""
                    CREATE TABLE IF NOT EXISTS resource_nodes (
                        world TEXT NOT NULL,
                        x INTEGER NOT NULL,
                        y INTEGER NOT NULL,
                        z INTEGER NOT NULL,
                        type_id TEXT NOT NULL,
                        depleted_at TEXT,
                        PRIMARY KEY (world, x, y, z)
                    )
                    """));
        }
    }

    private static void applyV4(Connection connection, SqlDialect dialect) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(dialect.ddl("""
                    CREATE TABLE IF NOT EXISTS wallets (
                        player_uuid TEXT PRIMARY KEY,
                        balance INTEGER NOT NULL DEFAULT 0,
                        updated_at TEXT NOT NULL,
                        FOREIGN KEY (player_uuid) REFERENCES player_profiles (uuid) ON DELETE CASCADE
                    )
                    """));

            statement.execute(dialect.ddl("""
                    CREATE TABLE IF NOT EXISTS transactions (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        player_uuid TEXT NOT NULL,
                        type TEXT NOT NULL,
                        amount INTEGER NOT NULL,
                        context TEXT,
                        created_at TEXT NOT NULL,
                        FOREIGN KEY (player_uuid) REFERENCES player_profiles (uuid) ON DELETE CASCADE
                    )
                    """));
        }
    }

    private static void applyV5(Connection connection, SqlDialect dialect) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            // item_data : ItemStack#serializeAsBytes(), l'objet complet (méta, PDC d'un objet
            // personnalisé compris) plutôt qu'une référence recomposée à la remise.
            statement.execute(dialect.ddl("""
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
                    """));
            statement.execute(dialect.ddl("""
                    CREATE INDEX IF NOT EXISTS idx_market_listings_status ON market_listings (status)
                    """));
        }
    }

    private static void applyV6(Connection connection, SqlDialect dialect) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            // Un cooldown de portail doit survivre à une reconnexion (mission étape 16) : persisté ici,
            // rechargé en mémoire à la connexion par travel.PortalService (jamais consulté en base
            // depuis PlayerMoveEvent, trop fréquent pour une requête asynchrone par événement).
            statement.execute(dialect.ddl("""
                    CREATE TABLE IF NOT EXISTS portal_cooldowns (
                        player_uuid TEXT NOT NULL,
                        portal_id TEXT NOT NULL,
                        expires_at TEXT NOT NULL,
                        PRIMARY KEY (player_uuid, portal_id),
                        FOREIGN KEY (player_uuid) REFERENCES player_profiles (uuid) ON DELETE CASCADE
                    )
                    """));
        }
    }

    private static void applyV7(Connection connection, SqlDialect dialect) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(dialect.ddl("""
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
                    """));
            statement.execute(dialect.ddl("""
                    CREATE INDEX IF NOT EXISTS idx_claims_owner ON claims (owner_uuid)
                    """));
            statement.execute(dialect.ddl("""
                    CREATE TABLE IF NOT EXISTS claim_members (
                        claim_id TEXT NOT NULL,
                        member_uuid TEXT NOT NULL,
                        PRIMARY KEY (claim_id, member_uuid),
                        FOREIGN KEY (claim_id) REFERENCES claims (id) ON DELETE CASCADE,
                        FOREIGN KEY (member_uuid) REFERENCES player_profiles (uuid) ON DELETE CASCADE
                    )
                    """));
        }
    }

    private static void applyV8(Connection connection, SqlDialect dialect) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            // total_xp seul : le niveau n'est jamais persisté (toujours recalculé via
            // ProgressionCurve#levelForTotalXp), aucun risque de divergence niveau/XP.
            statement.execute(dialect.ddl("""
                    CREATE TABLE IF NOT EXISTS player_skills (
                        player_uuid TEXT NOT NULL,
                        skill TEXT NOT NULL,
                        total_xp INTEGER NOT NULL DEFAULT 0,
                        updated_at TEXT NOT NULL,
                        PRIMARY KEY (player_uuid, skill),
                        FOREIGN KEY (player_uuid) REFERENCES player_profiles (uuid) ON DELETE CASCADE
                    )
                    """));

            // Un octroi d'XP est identifié par (joueur, compétence, id d'événement) : la même action
            // de jeu (mort de mob, bloc miné...) ne peut jamais récompenser deux fois la même
            // compétence (mission étape 19, point 5).
            statement.execute(dialect.ddl("""
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
                    """));
            statement.execute(dialect.ddl("""
                    CREATE INDEX IF NOT EXISTS idx_xp_grants_player ON xp_grants (player_uuid)
                    """));

            // Anti-farm (mission point 7) : une position posée par un joueur n'accorde jamais d'XP de
            // minage. Aucune clé étrangère vers player_profiles : un bloc survit à la suppression du
            // profil de son poseur (le monde reste inchangé).
            statement.execute(dialect.ddl("""
                    CREATE TABLE IF NOT EXISTS player_placed_blocks (
                        world TEXT NOT NULL,
                        x INTEGER NOT NULL,
                        y INTEGER NOT NULL,
                        z INTEGER NOT NULL,
                        PRIMARY KEY (world, x, y, z)
                    )
                    """));
        }
    }

    private static void applyV9(Connection connection, SqlDialect dialect) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            // Avantage générique (mission étape 20, point 11) : le backpack est le premier
            // consommateur concret, d'autres avantages futurs réutiliseront cette même table sans
            // migration supplémentaire (entitlement_key est une simple chaîne libre).
            statement.execute(dialect.ddl("""
                    CREATE TABLE IF NOT EXISTS player_entitlements (
                        player_uuid TEXT NOT NULL,
                        entitlement_key TEXT NOT NULL,
                        tier TEXT NOT NULL,
                        granted_at TEXT NOT NULL,
                        reason TEXT,
                        PRIMARY KEY (player_uuid, entitlement_key),
                        FOREIGN KEY (player_uuid) REFERENCES player_profiles (uuid) ON DELETE CASCADE
                    )
                    """));

            // contents : ItemStack[] sérialisé maison (voir backpack.ItemArraySerializer),
            // schema_version distinct de PRAGMA user_version : permet de migrer le format binaire
            // sans toucher au schéma SQL (mission point 6, "stocke... de manière sûre et versionnée").
            statement.execute(dialect.ddl("""
                    CREATE TABLE IF NOT EXISTS backpacks (
                        player_uuid TEXT PRIMARY KEY,
                        schema_version INTEGER NOT NULL,
                        contents BLOB NOT NULL,
                        updated_at TEXT NOT NULL,
                        FOREIGN KEY (player_uuid) REFERENCES player_profiles (uuid) ON DELETE CASCADE
                    )
                    """));

            // Boîte de récupération (mission point 9) : objets qui ne rentraient plus après une
            // réduction de taille, ou tout contenu qu'une anomalie empêche de restaurer directement
            // (ex. bloc sérialisé illisible) — jamais perdus silencieusement, toujours réclamables.
            statement.execute(dialect.ddl("""
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
                    """));
            statement.execute(dialect.ddl("""
                    CREATE INDEX IF NOT EXISTS idx_backpack_overflow_player ON backpack_overflow (player_uuid)
                    """));

            // Journal d'anomalies (mission, validation "toute anomalie crée une entrée de
            // récupération ou d'audit") : append-only, même esprit que la table transactions de
            // WalletRepository mais pour des événements structurels plutôt que financiers.
            statement.execute(dialect.ddl("""
                    CREATE TABLE IF NOT EXISTS backpack_audit (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        player_uuid TEXT,
                        event_type TEXT NOT NULL,
                        detail TEXT,
                        created_at TEXT NOT NULL
                    )
                    """));
        }
    }

    private static void applyV10(Connection connection, SqlDialect dialect) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            // Filet de sécurité d'idempotence côté serveur de jeu (mission étape 22, points 7-8) :
            // web-api acquitte déjà les livraisons de façon idempotente, mais si l'accusé de
            // réception échoue à repartir après un octroi réussi (crash pile après), le prochain
            // sondage renverrait la même livraison — cette table empêche un second octroi local.
            statement.execute(dialect.ddl("""
                    CREATE TABLE IF NOT EXISTS store_deliveries_processed (
                        delivery_id TEXT PRIMARY KEY,
                        outcome TEXT NOT NULL,
                        detail TEXT,
                        processed_at TEXT NOT NULL
                    )
                    """));
        }
    }

    private static void applyV11(Connection connection, SqlDialect dialect) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            // Allocateur d'identifiants de PNJ (voir com.lodygames.rpgquest.npc.NpcIdentityService) :
            // une ligne par identifiant "npc_<n>" auto-généré, jamais réutilisé (id AUTOINCREMENT).
            // Sert uniquement de secours quand un administrateur ne fournit pas d'id explicite à
            // /rpgadmin npc tag ; l'identité elle-même vit dans le PersistentDataContainer de
            // l'entité, jamais dans cette table (pas de lien entité <-> ligne à maintenir ici).
            statement.execute(dialect.ddl("""
                    CREATE TABLE IF NOT EXISTS npc_ids (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        created_at TEXT NOT NULL
                    )
                    """));
        }
    }

    private static void applyV12(Connection connection, SqlDialect dialect) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            // Liaison persistante PNJ Citizens <-> identifiant logique RPGQuest (voir
            // com.lodygames.rpgquest.npc.NpcIdentityService / NpcBindingRepository). Nécessaire car
            // Citizens recrée une nouvelle entité Bukkit éphémère à chaque (re)spawn — un
            // PersistentDataContainer posé sur cette entité ne survit donc jamais à un redémarrage.
            // citizens_uuid = NPC#getUniqueId(), garanti stable par Citizens lui-même (contrairement
            // à NPC#getId(), documenté par Citizens comme non garanti unique entre sessions).
            statement.execute(dialect.ddl("""
                    CREATE TABLE IF NOT EXISTS npc_citizens_bindings (
                        citizens_uuid TEXT PRIMARY KEY,
                        citizens_numeric_id INTEGER NOT NULL,
                        npc_id TEXT NOT NULL,
                        created_at TEXT NOT NULL
                    )
                    """));
        }
    }

    private static void applyV13(Connection connection, SqlDialect dialect) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            // Progression Story (voir com.lodygames.rpgquest.story.StoryService) : un conteneur
            // logique de quest_progress existantes, jamais couplé à ces lignes — état minimal
            // NOT_STARTED (absence de ligne)/ACTIVE/COMPLETED par joueur+story, même convention que
            // quest_progress (mission storyline étape 1).
            statement.execute(dialect.ddl("""
                    CREATE TABLE IF NOT EXISTS story_progress (
                        player_uuid TEXT NOT NULL,
                        story_id TEXT NOT NULL,
                        state TEXT NOT NULL,
                        updated_at TEXT NOT NULL,
                        PRIMARY KEY (player_uuid, story_id),
                        FOREIGN KEY (player_uuid) REFERENCES player_profiles (uuid) ON DELETE CASCADE
                    )
                    """));
        }
    }

    private static void applyV14(Connection connection, SqlDialect dialect) throws SQLException {
        // Position courante dans StoryDefinition#questIds() (voir com.lodygames.rpgquest.story
        // .StoryService) : quelle quête de la story est actuellement suivie pour ce joueur.
        // ALTER TABLE ... ADD COLUMN avec DEFAULT s'applique aussi aux lignes existantes (aucune
        // ligne story_progress ne peut exister avant cette étape sans avoir déjà index 0, puisque
        // seule /rpgadmin story start en créait jusqu'ici, toujours à l'index de départ).
        //
        // Contrairement à CREATE TABLE IF NOT EXISTS (idempotent nativement), ALTER TABLE ADD COLUMN
        // échoue si la colonne existe déjà — un re-run de cette étape (ex. historique de version
        // corrompu/remis à zéro, ou toute future migration qui rejoue les étapes depuis une version
        // antérieure) planterait sinon avec "duplicate column name". Vérification explicite d'abord.
        if (dialect.columnExists(connection, "story_progress", "current_index")) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute(dialect.ddl("ALTER TABLE story_progress ADD COLUMN current_index INTEGER NOT NULL DEFAULT 0"));
        }
    }

    private static void applyV15(Connection connection, SqlDialect dialect) throws SQLException {
        // Réservation foncière (voir com.lodygames.rpgquest.claim.model.Claim/ClaimTier) : cuboïde
        // englobant, toujours >= le cuboïde actif (min/max_x/y/z), qui empêche tout AUTRE claim de
        // chevaucher cet espace même avant une éventuelle extension future (mission « premier claim
        // 5x5 + réservation 100x100 »). Les claims déjà existants (créés avant cette étape, ex.
        // /claim create à la baguette) n'ont aucune réservation supplémentaire : leur réservation est
        // initialisée à leur propre cuboïde actif — comportement inchangé pour eux, même défaut que
        // Claim#Claim(9 bornes, members, flags) côté Java.
        if (dialect.columnExists(connection, "claims", "reserved_min_x")) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute(dialect.ddl("ALTER TABLE claims ADD COLUMN reserved_min_x INTEGER"));
            statement.execute(dialect.ddl("ALTER TABLE claims ADD COLUMN reserved_min_y INTEGER"));
            statement.execute(dialect.ddl("ALTER TABLE claims ADD COLUMN reserved_min_z INTEGER"));
            statement.execute(dialect.ddl("ALTER TABLE claims ADD COLUMN reserved_max_x INTEGER"));
            statement.execute(dialect.ddl("ALTER TABLE claims ADD COLUMN reserved_max_y INTEGER"));
            statement.execute(dialect.ddl("ALTER TABLE claims ADD COLUMN reserved_max_z INTEGER"));
            statement.execute(dialect.ddl("""
                    UPDATE claims SET reserved_min_x = min_x, reserved_min_y = min_y, reserved_min_z = min_z,
                                       reserved_max_x = max_x, reserved_max_y = max_y, reserved_max_z = max_z
                    """));
        }
    }

    private static void applyV16(Connection connection, SqlDialect dialect) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            // Cooldown de voyage par objet (mission « Rune de rappel ») : doit survivre à une
            // reconnexion/redémarrage — persisté ici, rechargé en mémoire à la connexion par
            // travel.ItemTravelService (jamais consulté en base à chaque clic droit). Même forme que
            // portal_cooldowns (V6), mais indexé par id d'objet plutôt que par id de portail : un
            // objet dont la définition n'applique aucun cooldown n'écrit jamais dans cette table.
            statement.execute(dialect.ddl("""
                    CREATE TABLE IF NOT EXISTS item_travel_cooldowns (
                        player_uuid TEXT NOT NULL,
                        item_id TEXT NOT NULL,
                        expires_at TEXT NOT NULL,
                        PRIMARY KEY (player_uuid, item_id),
                        FOREIGN KEY (player_uuid) REFERENCES player_profiles (uuid) ON DELETE CASCADE
                    )
                    """));
        }
    }

    private static void applyV17(Connection connection, SqlDialect dialect) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            // Waystones générées (mission « Waystones Wild ») : la base est la seule source de vérité
            // — une cellule dont l'id est déjà présent n'est jamais régénérée (pas de doublon au
            // reload/restart), et la structure physique n'est jamais consultée pour décider si une
            // Waystone existe. name : libellé lisible affiché à la découverte. La persistance est
            // prête pour un futur « Hub → Waystone découverte » sans nouvelle migration (x/y/z/world
            // suffisent comme destination).
            statement.execute(dialect.ddl("""
                    CREATE TABLE IF NOT EXISTS waystones (
                        id TEXT PRIMARY KEY,
                        world TEXT NOT NULL,
                        x INTEGER NOT NULL,
                        y INTEGER NOT NULL,
                        z INTEGER NOT NULL,
                        cell_x INTEGER NOT NULL,
                        cell_z INTEGER NOT NULL,
                        name TEXT NOT NULL,
                        created_at TEXT NOT NULL
                    )
                    """));
            statement.execute(dialect.ddl("""
                    CREATE UNIQUE INDEX IF NOT EXISTS idx_waystones_cell ON waystones (world, cell_x, cell_z)
                    """));

            // Découverte individuelle par joueur : la Waystone est globale physiquement, mais chaque
            // joueur la « découvre » à son premier clic (persistance player UUID + waystoneId +
            // discoveredAt). Aucune clé étrangère vers waystones : une découverte survit à une
            // éventuelle suppression/regénération d'id (jamais faite automatiquement).
            statement.execute(dialect.ddl("""
                    CREATE TABLE IF NOT EXISTS waystone_discoveries (
                        player_uuid TEXT NOT NULL,
                        waystone_id TEXT NOT NULL,
                        discovered_at TEXT NOT NULL,
                        PRIMARY KEY (player_uuid, waystone_id),
                        FOREIGN KEY (player_uuid) REFERENCES player_profiles (uuid) ON DELETE CASCADE
                    )
                    """));
        }
    }

    private static void applyV18(Connection connection, SqlDialect dialect) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            // Waypoints par instance de biome (issue #124), distincts des Waystones (V17, réseau de
            // voyage sur grille). La base est la seule source de vérité : l'index unique
            // (world, biome_instance) garantit qu'une instance de biome n'a jamais deux waypoints,
            // même si deux joueurs entrent dans la zone en même temps (INSERT OR IGNORE côté
            // repository). biome_instance = identité métier stable de la zone
            // (BiomeInstanceKey#serialize(), ex. "minecraft:forest@3,-1") ; biome_key/region_x/
            // region_z en sont les composantes dénormalisées pour la lecture Control Panel. x/y/z =
            // ancre de la structure (colonne de surface), pas l'interacteur : celui-ci se déduit du
            // modèle. model_version = version du rendu (WaypointModelRegistry) — changer le rendu ne
            // change jamais id ni biome_instance. active : waypoint désactivé = ignoré.
            statement.execute(dialect.ddl("""
                    CREATE TABLE IF NOT EXISTS waypoints (
                        id TEXT PRIMARY KEY,
                        world TEXT NOT NULL,
                        biome_instance TEXT NOT NULL,
                        biome_key TEXT NOT NULL,
                        region_x INTEGER NOT NULL,
                        region_z INTEGER NOT NULL,
                        x INTEGER NOT NULL,
                        y INTEGER NOT NULL,
                        z INTEGER NOT NULL,
                        facing TEXT NOT NULL,
                        model_version INTEGER NOT NULL,
                        active INTEGER NOT NULL DEFAULT 1,
                        created_at TEXT NOT NULL
                    )
                    """));
            statement.execute(dialect.ddl("""
                    CREATE UNIQUE INDEX IF NOT EXISTS idx_waypoints_instance ON waypoints (world, biome_instance)
                    """));

            // Découverte individuelle par joueur : le waypoint est global physiquement, mais chaque
            // joueur ne le « découvre » qu'à une interaction explicite avec le bouton (persistance
            // player UUID + waypointId + discoveredAt). Aucune clé étrangère vers waypoints : une
            // découverte survit à une éventuelle suppression/régénération d'id.
            statement.execute(dialect.ddl("""
                    CREATE TABLE IF NOT EXISTS waypoint_discoveries (
                        player_uuid TEXT NOT NULL,
                        waypoint_id TEXT NOT NULL,
                        discovered_at TEXT NOT NULL,
                        PRIMARY KEY (player_uuid, waypoint_id),
                        FOREIGN KEY (player_uuid) REFERENCES player_profiles (uuid) ON DELETE CASCADE
                    )
                    """));
        }
    }

    private static void applyV19(Connection connection, SqlDialect dialect) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            // Bornes du réseau de voyage (issues #132/#150) : point d'accès au menu de voyage,
            // jamais une destination en soi — table strictement séparée de waypoints/waystones
            // (ne jamais fusionner les identités). x/y/z = ancre (colonne de surface), pas
            // l'interacteur, comme pour waypoints. active : borne désactivée = bouton inerte.
            statement.execute(dialect.ddl("""
                    CREATE TABLE IF NOT EXISTS travel_beacons (
                        id TEXT PRIMARY KEY,
                        world TEXT NOT NULL,
                        x INTEGER NOT NULL,
                        y INTEGER NOT NULL,
                        z INTEGER NOT NULL,
                        facing TEXT NOT NULL,
                        model_version INTEGER NOT NULL,
                        active INTEGER NOT NULL DEFAULT 1,
                        created_at TEXT NOT NULL
                    )
                    """));
        }
    }

    private static void applyV20(Connection connection, SqlDialect dialect) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            // Centres de village administrables (issue #151), destination de la catégorie
            // « Villages » du menu de voyage. id = seule identité stable (jamais recalculée depuis
            // la position) : déplacer/renommer/désactiver un centre réutilise le même id, sans
            // jamais casser une référence existante. Indépendant du monde Hub lui-même : plusieurs
            // centres peuvent coexister dans le même monde.
            statement.execute(dialect.ddl("""
                    CREATE TABLE IF NOT EXISTS village_centers (
                        id TEXT PRIMARY KEY,
                        name TEXT NOT NULL,
                        world TEXT NOT NULL,
                        x REAL NOT NULL,
                        y REAL NOT NULL,
                        z REAL NOT NULL,
                        yaw REAL NOT NULL,
                        pitch REAL NOT NULL,
                        active INTEGER NOT NULL DEFAULT 1,
                        created_at TEXT NOT NULL
                    )
                    """));
        }
    }

    private static void applyV21(Connection connection, SqlDialect dialect) throws SQLException {
        // Appariement automatique borne+waypoint par instance de biome du Hub (issue #149) : ""
        // (défaut) pour une borne placée à la main (#132, jamais liée à une instance précise),
        // sinon BiomeInstanceKey#serialize() de l'instance à laquelle la borne est appariée — sert
        // uniquement à l'idempotence de l'appariement, jamais une fusion avec la table waypoints.
        if (dialect.columnExists(connection, "travel_beacons", "biome_instance")) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute(dialect.ddl("ALTER TABLE travel_beacons ADD COLUMN biome_instance TEXT NOT NULL DEFAULT ''"));
        }
    }

    /**
     * Nom d'affichage humain, unique et persistant par waypoint (issues #133/#135) — le biome reste
     * une métadonnée secondaire, jamais le nom principal (plusieurs waypoints du même biome
     * affichaient jusqu'ici le même libellé, ex. « beach » en double, sans moyen de les distinguer
     * dans le menu de voyage). Rempli ici pour les waypoints déjà existants (identifiant et
     * découvertes joueurs inchangés) à partir du même catalogue statique
     * ({@link com.lodygames.rpgquest.waypoint.model.WaypointNameCatalog}) que l'attribution à la
     * génération — traitement des lignes dans un ordre stable (par {@code id}) pour un résultat
     * déterministe et reproductible en cas de ré-exécution manuelle sur un clone de la base.
     */
    private static void applyV22(Connection connection, SqlDialect dialect) throws SQLException {
        if (dialect.columnExists(connection, "waypoints", "display_name")) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute(dialect.ddl("ALTER TABLE waypoints ADD COLUMN display_name TEXT NOT NULL DEFAULT ''"));
        }

        WaypointNameCatalog catalog = WaypointNameCatalog.loadBundled();
        Set<String> usedNormalized = new HashSet<>();
        List<String> ids = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT id FROM waypoints ORDER BY id")) {
            while (resultSet.next()) {
                ids.add(resultSet.getString("id"));
            }
        }
        try (PreparedStatement update = connection.prepareStatement("UPDATE waypoints SET display_name = ? WHERE id = ?")) {
            for (String id : ids) {
                String displayName = catalog.reserveName(id.hashCode(), usedNormalized);
                update.setString(1, displayName);
                update.setString(2, id);
                update.executeUpdate();
            }
        }

        try (Statement statement = connection.createStatement()) {
            statement.execute(dialect.ddl(
                    "CREATE UNIQUE INDEX IF NOT EXISTS idx_waypoints_display_name ON waypoints (display_name)"));
        }
    }

    /**
     * Correspondance figée ancien nom généré (concaténation sans séparateur, ex. {@code Lacgivre})
     * -> nouveau nom lisible (ex. {@code Lac de Givre}) -- retour joueur 2026-10-04, suite #133/#135.
     * Clé normalisée via {@link WaypointNameCatalog#normalize} : jamais une égalité de chaîne brute,
     * insensible à la casse/aux accents comme {@code idx_waypoints_display_name}. Un waypoint dont
     * le nom actuel ne correspond à aucune clé (ex. nom de secours {@code "Avant-poste N"}, ou déjà
     * un nom lisible) n'est jamais modifié par cette migration.
     */
    private static final Map<String, String> RENAMED_DISPLAY_NAMES = Map.ofEntries(
            Map.entry("Pierrerive", "Pierre de Rive"),
            Map.entry("Collinevert", "Colline Verte"),
            Map.entry("Collinefontaine", "Colline de Fontaine"),
            Map.entry("Closbelle", "Beau Clos"),
            Map.entry("Tremblesoir", "Tremble de Soir"),
            Map.entry("Grandbrune", "Grande Brune"),
            Map.entry("Frênemiel", "Frêne de Miel"),
            Map.entry("Ventrive", "Vent de Rive"),
            Map.entry("Frênebrune", "Frêne Brun"),
            Map.entry("Chênechamp", "Chêne de Champ"),
            Map.entry("Collinenoix", "Colline de Noix"),
            Map.entry("Bruyèrebois", "Bruyère de Bois"),
            Map.entry("Erableval", "Érable de Val"),
            Map.entry("Falaisenoir", "Falaise Noire"),
            Map.entry("Chênesoir", "Chêne de Soir"),
            Map.entry("Chêneclair", "Chêne Clair"),
            Map.entry("Jardinrive", "Jardin de Rive"),
            Map.entry("Lacgris", "Lac Gris"),
            Map.entry("Valgivre", "Val de Givre"),
            Map.entry("Trembleor", "Tremble d'Or"),
            Map.entry("Closvert", "Clos Vert"),
            Map.entry("Rochedoré", "Roche Dorée"),
            Map.entry("Pierremiel", "Pierre de Miel"),
            Map.entry("Fontainebois", "Fontaine de Bois"),
            Map.entry("Ventfort", "Vent Fort"),
            Map.entry("Rochefort", "Roche Forte"),
            Map.entry("Closgris", "Clos Gris"),
            Map.entry("Noyerfort", "Noyer Fort"),
            Map.entry("Lacfleur", "Lac de Fleur"),
            Map.entry("Aulnemont", "Aulne de Mont"),
            Map.entry("Landeroc", "Lande de Roc"),
            Map.entry("Montaube", "Mont d'Aube"),
            Map.entry("Fontainerive", "Fontaine de Rive"),
            Map.entry("Frênebelle", "Beau Frêne"),
            Map.entry("Combevent", "Combe de Vent"),
            Map.entry("Jardingivre", "Jardin de Givre"),
            Map.entry("Ventgivre", "Vent de Givre"),
            Map.entry("Rivagenoix", "Rivage de Noix"),
            Map.entry("Hautegris", "Haute Grise"),
            Map.entry("Collinemiel", "Colline de Miel"),
            Map.entry("Landeombre", "Lande d'Ombre"),
            Map.entry("Frêneaube", "Frêne d'Aube"),
            Map.entry("Chêneroc", "Chêne de Roc"),
            Map.entry("Clospierre", "Clos de Pierre"),
            Map.entry("Brumenoir", "Brume Noire"),
            Map.entry("Brumefort", "Brume Forte"),
            Map.entry("Montpierre", "Mont de Pierre"),
            Map.entry("Petitmiel", "Petit Miel"),
            Map.entry("Combenoix", "Combe de Noix"),
            Map.entry("Montnoix", "Mont de Noix"),
            Map.entry("Étangblanc", "Étang Blanc"),
            Map.entry("Préfontaine", "Pré de Fontaine"),
            Map.entry("Clairclair", "Clair Matin"),
            Map.entry("Rivagefleur", "Rivage de Fleur"),
            Map.entry("Étangfeu", "Étang de Feu"),
            Map.entry("Ventfontaine", "Vent de Fontaine"),
            Map.entry("Brumeroc", "Brume de Roc"),
            Map.entry("Closfontaine", "Clos de Fontaine"),
            Map.entry("Étangnoir", "Étang Noir"),
            Map.entry("Aulneombre", "Aulne d'Ombre"),
            Map.entry("Prairienoir", "Prairie Noire"),
            Map.entry("Landefort", "Lande Forte"),
            Map.entry("Prairiefleur", "Prairie de Fleur"),
            Map.entry("Prairievert", "Prairie Verte"),
            Map.entry("Ormemiel", "Orme de Miel"),
            Map.entry("Prairierive", "Prairie de Rive"),
            Map.entry("Sourcenoir", "Source Noire"),
            Map.entry("Closvent", "Clos de Vent"),
            Map.entry("Falaiseval", "Falaise de Val"),
            Map.entry("Présoir", "Pré de Soir"),
            Map.entry("Collinefort", "Colline Forte"),
            Map.entry("Petitval", "Petit Val"),
            Map.entry("Noyerpierre", "Noyer de Pierre"),
            Map.entry("Rivageombre", "Rivage d'Ombre"),
            Map.entry("Granddoré", "Grande Dorée"),
            Map.entry("Rivagebelle", "Beau Rivage"),
            Map.entry("Lacgivre", "Lac de Givre"),
            Map.entry("Grandor", "Grand Or"),
            Map.entry("Aulnevent", "Aulne de Vent"),
            Map.entry("Frênemont", "Frêne de Mont"),
            Map.entry("Clairbrune", "Claire Brune"),
            Map.entry("Saulebrune", "Saule Brun"),
            Map.entry("Falaisegivre", "Falaise de Givre"),
            Map.entry("Rivagevert", "Rivage Vert"),
            Map.entry("Erablemiel", "Érable de Miel"),
            Map.entry("Prairiefort", "Prairie Forte"),
            Map.entry("Rivagepierre", "Rivage de Pierre"),
            Map.entry("Roncegris", "Ronce Grise"),
            Map.entry("Sourcebrune", "Source Brune"),
            Map.entry("Prairiesoir", "Prairie de Soir"),
            Map.entry("Genêtor", "Genêt d'Or"),
            Map.entry("Bassesource", "Basse Source"),
            Map.entry("Trembledoré", "Tremble Doré"),
            Map.entry("Aulnesource", "Aulne de Source"),
            Map.entry("Noyermurmure", "Noyer de Murmure"),
            Map.entry("Bruyèreor", "Bruyère d'Or"),
            Map.entry("Tremblebrune", "Tremble Brun"),
            Map.entry("Collinegris", "Colline Grise"),
            Map.entry("Falaisebelle", "Belle Falaise"),
            Map.entry("Saulevert", "Saule Vert"),
            Map.entry("Étangvent", "Étang de Vent"),
            Map.entry("Hautemurmure", "Haut Murmure"),
            Map.entry("Petitgivre", "Petit Givre"),
            Map.entry("Saulepierre", "Saule de Pierre"),
            Map.entry("Erableor", "Érable d'Or"),
            Map.entry("Prédoré", "Pré Doré"),
            Map.entry("Noyergris", "Noyer Gris"),
            Map.entry("Erableombre", "Érable d'Ombre"),
            Map.entry("Lacpierre", "Lac de Pierre"),
            Map.entry("Closnoix", "Clos de Noix"),
            Map.entry("Lacbrune", "Lac Brun"),
            Map.entry("Montlune", "Mont de Lune"),
            Map.entry("Bassefort", "Basse Forte"),
            Map.entry("Collinebelle", "Belle Colline"),
            Map.entry("Clairgris", "Claire Grise"),
            Map.entry("Rivagefontaine", "Rivage de Fontaine"),
            Map.entry("Boisbois", "Bois de Bois"),
            Map.entry("Sourceor", "Source d'Or"),
            Map.entry("Boisblanc", "Bois Blanc"),
            Map.entry("Étangvert", "Étang Vert"),
            Map.entry("Chênenoix", "Chêne de Noix"),
            Map.entry("Erablemurmure", "Érable de Murmure"),
            Map.entry("Clairdoré", "Claire Dorée"),
            Map.entry("Boisfeu", "Bois de Feu"),
            Map.entry("Brumeblanc", "Brume Blanche"),
            Map.entry("Moulinrive", "Moulin de Rive"),
            Map.entry("Valpierre", "Val de Pierre"),
            Map.entry("Frêneroc", "Frêne de Roc"),
            Map.entry("Genêtlune", "Genêt de Lune"),
            Map.entry("Boisvent", "Bois de Vent"),
            Map.entry("Montblanc", "Mont Blanc"),
            Map.entry("Montvert", "Mont Vert"),
            Map.entry("Pontbois", "Pont de Bois"),
            Map.entry("Rivagelune", "Rivage de Lune"),
            Map.entry("Collinedoré", "Colline Dorée"),
            Map.entry("Boisval", "Bois de Val"),
            Map.entry("Prairieombre", "Prairie d'Ombre"),
            Map.entry("Sourcegivre", "Source de Givre"),
            Map.entry("Pierrevert", "Pierre Verte"),
            Map.entry("Bassegivre", "Bas Givre"),
            Map.entry("Pontclair", "Pont Clair"),
            Map.entry("Prairieval", "Prairie de Val"),
            Map.entry("Bruyèrebelle", "Belle Bruyère"),
            Map.entry("Moulinfort", "Moulin Fort"),
            Map.entry("Maraischamp", "Marais de Champ"),
            Map.entry("Rochemiel", "Roche de Miel"),
            Map.entry("Combeclair", "Combe Claire"),
            Map.entry("Petitbrune", "Petite Brune"),
            Map.entry("Falaisevent", "Falaise de Vent"),
            Map.entry("Noyerval", "Noyer de Val"),
            Map.entry("Clairgivre", "Clair Givre"),
            Map.entry("Tremblefort", "Tremble Fort"),
            Map.entry("Montfeu", "Mont de Feu"),
            Map.entry("Combegris", "Combe Grise"),
            Map.entry("Tremblenoix", "Tremble de Noix"),
            Map.entry("Boisbelle", "Beau Bois"),
            Map.entry("Frêneombre", "Frêne d'Ombre"),
            Map.entry("Hautesource", "Haute Source"),
            Map.entry("Bassemiel", "Bas Miel"),
            Map.entry("Aulnelune", "Aulne de Lune"),
            Map.entry("Lacblanc", "Lac Blanc"),
            Map.entry("Brumegivre", "Brume de Givre"),
            Map.entry("Moulinaube", "Moulin d'Aube"),
            Map.entry("Moulinval", "Moulin de Val"),
            Map.entry("Combepierre", "Combe de Pierre"),
            Map.entry("Rivagegivre", "Rivage de Givre"),
            Map.entry("Genêtombre", "Genêt d'Ombre"),
            Map.entry("Genêtfontaine", "Genêt de Fontaine"),
            Map.entry("Sourceroc", "Source de Roc"),
            Map.entry("Vergerfort", "Verger Fort"),
            Map.entry("Boisor", "Bois d'Or"),
            Map.entry("Clairfeu", "Clair Feu"),
            Map.entry("Ventaube", "Vent d'Aube"),
            Map.entry("Fontaineclair", "Fontaine Claire"),
            Map.entry("Erablefort", "Érable Fort"),
            Map.entry("Noyermiel", "Noyer de Miel"),
            Map.entry("Vergerclair", "Verger Clair"),
            Map.entry("Maraisval", "Marais de Val"),
            Map.entry("Moulinbois", "Moulin de Bois"),
            Map.entry("Pierrelune", "Pierre de Lune"),
            Map.entry("Bruyèregivre", "Bruyère de Givre"),
            Map.entry("Pierreval", "Pierre de Val"),
            Map.entry("Frênenoir", "Frêne Noir"),
            Map.entry("Lacombre", "Lac d'Ombre"),
            Map.entry("Grandombre", "Grande Ombre"),
            Map.entry("Étangfontaine", "Étang de Fontaine"),
            Map.entry("Genêtfeu", "Genêt de Feu"),
            Map.entry("Clairaube", "Claire Aube"),
            Map.entry("Rocherive", "Roche de Rive"),
            Map.entry("Collinefleur", "Colline de Fleur"),
            Map.entry("Maraismurmure", "Marais de Murmure"),
            Map.entry("Rivageblanc", "Rivage Blanc"),
            Map.entry("Collinevent", "Colline de Vent"),
            Map.entry("Hautegivre", "Haut Givre"),
            Map.entry("Sourcenoix", "Source de Noix"),
            Map.entry("Bruyèremurmure", "Bruyère de Murmure"),
            Map.entry("Jardinor", "Jardin d'Or"),
            Map.entry("Genêtgivre", "Genêt de Givre"),
            Map.entry("Boisfontaine", "Bois de Fontaine"),
            Map.entry("Rivagechamp", "Rivage de Champ"),
            Map.entry("Maraisgris", "Marais Gris"),
            Map.entry("Roncesource", "Ronce de Source"),
            Map.entry("Ventclair", "Vent Clair"),
            Map.entry("Falaisepierre", "Falaise de Pierre"),
            Map.entry("Pontombre", "Pont d'Ombre"),
            Map.entry("Saulesoir", "Saule de Soir"),
            Map.entry("Erablegris", "Érable Gris"),
            Map.entry("Clairor", "Clair Or"),
            Map.entry("Pontgivre", "Pont de Givre"),
            Map.entry("Prairiemont", "Prairie de Mont"),
            Map.entry("Aulneblanc", "Aulne Blanc"),
            Map.entry("Ronceclair", "Ronce Claire"),
            Map.entry("Saulemiel", "Saule de Miel"),
            Map.entry("Vergerbrune", "Verger Brun"),
            Map.entry("Falaisegris", "Falaise Grise"),
            Map.entry("Lacdoré", "Lac Doré"),
            Map.entry("Préfeu", "Pré de Feu"),
            Map.entry("Genêtblanc", "Genêt Blanc"),
            Map.entry("Genêtfort", "Genêt Fort"),
            Map.entry("Lacor", "Lac d'Or")
    );

    private static void applyV23(Connection connection, SqlDialect dialect) throws SQLException {
        Map<String, String> byNormalizedOldName = new HashMap<>();
        for (Map.Entry<String, String> entry : RENAMED_DISPLAY_NAMES.entrySet()) {
            byNormalizedOldName.put(WaypointNameCatalog.normalize(entry.getKey()), entry.getValue());
        }

        List<String[]> toRename = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT id, display_name FROM waypoints")) {
            while (resultSet.next()) {
                String id = resultSet.getString("id");
                String currentName = resultSet.getString("display_name");
                String newName = byNormalizedOldName.get(WaypointNameCatalog.normalize(currentName));
                if (newName != null) {
                    toRename.add(new String[] {id, newName});
                }
            }
        }
        try (PreparedStatement update = connection.prepareStatement("UPDATE waypoints SET display_name = ? WHERE id = ?")) {
            for (String[] row : toRename) {
                update.setString(1, row[1]);
                update.setString(2, row[0]);
                update.executeUpdate();
            }
        }
    }

    private static void applyV24(Connection connection, SqlDialect dialect) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            // Nœuds de dialogue RÉELLEMENT présentés à un joueur (issue #12). Une ligne = « ce
            // joueur a vu ce nœud de ce dialogue ». L'absence de ligne vaut « jamais lu » : rien
            // n'est pré-rempli, et ouvrir un PNJ n'écrit qu'une ligne — celle du nœud de départ.
            //
            // Identité par UUID : un changement de pseudo ne perd pas la lecture. La clé primaire
            // composite rend l'écriture idempotente, donc relire deux fois le même nœud n'ajoute
            // rien et ne peut pas produire de doublon.
            statement.execute(dialect.ddl("""
                    CREATE TABLE IF NOT EXISTS dialogue_node_reads (
                        player_uuid TEXT NOT NULL,
                        dialogue_id TEXT NOT NULL,
                        node_id TEXT NOT NULL,
                        read_at TEXT NOT NULL,
                        PRIMARY KEY (player_uuid, dialogue_id, node_id)
                    )
                    """));
            // Lecture d'un joueur pour un dialogue donné : c'est l'accès du service de signal,
            // fait par joueur et par dialogue, jamais un balayage global.
            statement.execute(dialect.ddl(
                    "CREATE INDEX IF NOT EXISTS idx_dialogue_node_reads_player "
                            + "ON dialogue_node_reads (player_uuid, dialogue_id)"));
        }
    }

    private static void applyV25(Connection connection, SqlDialect dialect) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            // Récompenses MONÉTAIRES de quête réellement créditées (issue #16). Une ligne = « cette
            // occurrence de complétion a été payée », et c'est tout : cette table ne contient
            // AUCUN solde. Le solde reste dans « wallets », seule source de vérité — ici on ne
            // garde que de quoi répondre « a-t-on déjà payé ceci ? » et « quand, combien ».
            //
            // grant_id est fourni par l'appelant et porte la clé primaire : c'est lui qui rend le
            // crédit IDEMPOTENT. Un INSERT OR IGNORE qui n'insère rien signifie « déjà payé », et
            // comme l'insertion et la mise à jour du portefeuille vivent dans la MÊME transaction
            // SQL, il n'existe aucune fenêtre où l'argent serait crédité sans trace, ni tracé sans
            // être crédité — donc ni double paiement sur retry, ni paiement perdu sur panne.
            //
            // occurrence numérote les complétions successives d'une même quête par un même joueur
            // (quêtes répétables) : la 2e complétion est une occurrence distincte, légitimement
            // payée à nouveau. Identité par UUID : un changement de pseudo ne perd pas l'historique.
            //
            // Volontairement SANS clé étrangère vers player_profiles, contrairement à transactions :
            // un ON DELETE CASCADE rendrait un profil supprimé puis recréé payable une seconde fois
            // pour les mêmes occurrences. Une ligne orpheline ne coûte rien ; un double paiement, si.
            statement.execute(dialect.ddl("""
                    CREATE TABLE IF NOT EXISTS quest_reward_grants (
                        grant_id TEXT PRIMARY KEY,
                        player_uuid TEXT NOT NULL,
                        quest_id TEXT NOT NULL,
                        occurrence INTEGER NOT NULL,
                        amount INTEGER NOT NULL,
                        created_at TEXT NOT NULL
                    )
                    """));
            // Accès réel : « quelles occurrences ce joueur a-t-il déjà pour cette quête ? », fait
            // par joueur et par quête au moment de payer — jamais un balayage global.
            statement.execute(dialect.ddl(
                    "CREATE INDEX IF NOT EXISTS idx_quest_reward_grants_player_quest "
                            + "ON quest_reward_grants (player_uuid, quest_id)"));
        }
    }

    private static void applyV26(Connection connection, SqlDialect dialect) throws SQLException {
        // Récupération des récompenses monétaires (issue #16, second lot). V25 ne gardait trace que
        // de ce qui avait DÉJÀ été payé : une récompense due mais non créditée (crash, panne SQL)
        // ne laissait AUCUNE trace et devenait invisible, donc définitivement perdue. Cette
        // migration transforme la table en « ce qui est dû ET ce qui est payé », sans jamais
        // devenir un second solde : le montant d'un dû est figé ici, le solde reste dans wallets.
        if (dialect.columnExists(connection, "quest_reward_grants", "status")) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            // DEFAULT 'PAID' : toute ligne déjà présente correspond, par construction de V25, à un
            // crédit RÉELLEMENT effectué (la ligne n'était écrite que dans la transaction qui
            // créditait). Les marquer payées est donc un constat, pas une supposition — et surtout
            // cela n'invente AUCUNE dette rétroactive.
            statement.execute(dialect.ddl(
                    "ALTER TABLE quest_reward_grants ADD COLUMN status TEXT NOT NULL DEFAULT 'PAID'"));
            // reward_index : une même complétion peut porter PLUSIEURS récompenses monétaires.
            // Sans cet index, elles partageaient la même identité de paiement et la clé primaire
            // rejetait toutes sauf la première — défaut mesuré (100 + 30 payait 100).
            statement.execute(dialect.ddl(
                    "ALTER TABLE quest_reward_grants ADD COLUMN reward_index INTEGER NOT NULL DEFAULT 0"));
            statement.execute(dialect.ddl(
                    "ALTER TABLE quest_reward_grants ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0"));
            statement.execute(dialect.ddl("ALTER TABLE quest_reward_grants ADD COLUMN last_error TEXT"));
            statement.execute(dialect.ddl("ALTER TABLE quest_reward_grants ADD COLUMN updated_at TEXT"));
            // Raison d'un règlement MANUEL (compensation admin) : une dette réglée à la main ne
            // doit plus jamais être payable, sinon le joueur serait payé deux fois.
            statement.execute(dialect.ddl("ALTER TABLE quest_reward_grants ADD COLUMN settled_reason TEXT"));
        }
        // Accès réel de la récupération : « que reste-t-il à payer ? », par statut — jamais un
        // balayage de toute la table.
        try (Statement statement = connection.createStatement()) {
            statement.execute(dialect.ddl(
                    "CREATE INDEX IF NOT EXISTS idx_quest_reward_grants_status "
                            + "ON quest_reward_grants (status)"));
        }
    }
}
