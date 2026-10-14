package com.lodygames.rpgquest.database.migration;

import com.lodygames.rpgquest.database.SchemaMigrator;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Bases SQLite de test pour l'outil de migration (issue #42) : schéma réel du plugin
 * ({@link SchemaMigrator#migrate}) et jeu de données synthétique couvrant les domaines métier —
 * jamais de données réelles de joueurs.
 */
public final class MigrationFixtures {

    public static final String ALICE = "0b6f2f8e-3c55-4d2a-9a4e-1f0c7a5e9b01";
    public static final String BOB = "7d1c9e44-82aa-4b6f-b1d3-5e2f6a7c8d02";
    public static final String CAROL = "c3a2b1d0-1111-4222-8333-944455556603";

    private MigrationFixtures() {
    }

    public static Connection open(Path file) throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
        }
        return connection;
    }

    /** Base au schéma courant, sans aucune ligne. */
    public static Path emptyDatabase(Path file) throws SQLException {
        try (Connection connection = open(file)) {
            SchemaMigrator.migrate(connection);
        }
        return file;
    }

    /**
     * Jeu de données « réaliste » : joueurs, quêtes et objectifs, story, variables, claims et
     * membres, économie (portefeuilles, transactions, annonce avec BLOB), réels (centres de village),
     * texte Unicode, NULL vs chaîne vide, et une séquence AUTOINCREMENT avec trou
     * ({@code npc_ids} : 3 attribués, le dernier supprimé — le 3 ne doit jamais être réattribué).
     */
    public static Path realisticDatabase(Path file) throws SQLException {
        emptyDatabase(file);
        try (Connection connection = open(file); Statement statement = connection.createStatement()) {
            for (String[] player : new String[][] {{ALICE, "Alice"}, {BOB, "Bob_l'Éclaireur"}, {CAROL, "Carol"}}) {
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO player_profiles (uuid, last_name, created_at, updated_at) VALUES (?, ?, ?, ?)")) {
                    insert.setString(1, player[0]);
                    insert.setString(2, player[1]);
                    insert.setString(3, "2026-09-01T10:00:00Z");
                    insert.setString(4, "2026-10-01T12:34:56.789Z");
                    insert.executeUpdate();
                }
            }
            statement.execute("INSERT INTO player_variables VALUES ('" + ALICE + "', 'unlock.mine', 'true')");
            statement.execute("INSERT INTO player_variables VALUES ('" + ALICE + "', 'Unlock.Mine', 'casse distincte')");
            statement.execute("INSERT INTO player_variables VALUES ('" + BOB + "', 'note', NULL)");
            statement.execute("INSERT INTO player_variables VALUES ('" + BOB + "', 'vide', '')");
            statement.execute("INSERT INTO quest_progress VALUES ('" + ALICE + "', 'crystal_hunt', 'ACTIVE', "
                    + "'{\"step\":\"cave\",\"🗺\":\"carte\"}', '2026-10-02T08:00:00Z')");
            statement.execute("INSERT INTO quest_progress VALUES ('" + BOB + "', 'crystal_hunt', 'COMPLETED', NULL, "
                    + "'2026-10-03T08:00:00Z')");
            statement.execute("INSERT INTO quest_objective_progress VALUES ('" + ALICE + "', 'crystal_hunt', 'cave', 0, 3)");
            statement.execute("INSERT INTO quest_objective_progress VALUES ('" + ALICE + "', 'crystal_hunt', 'cave', 1, 0)");
            statement.execute("INSERT INTO story_progress (player_uuid, story_id, state, updated_at, current_index) "
                    + "VALUES ('" + ALICE + "', 'lily_memories', 'ACTIVE', '2026-10-04T09:00:00Z', 2)");
            statement.execute("INSERT INTO claims (id, owner_uuid, world, min_x, min_y, min_z, max_x, max_y, max_z, "
                    + "allow_public_redstone, created_at) VALUES ('claim_1', '" + ALICE + "', 'world', -10, 60, -10, "
                    + "10, 90, 10, 1, '2026-10-05T00:00:00Z')");
            statement.execute("INSERT INTO claim_members VALUES ('claim_1', '" + BOB + "')");
            statement.execute("INSERT INTO wallets VALUES ('" + ALICE + "', 1500, '2026-10-06T00:00:00Z')");
            statement.execute("INSERT INTO wallets VALUES ('" + BOB + "', 0, '2026-10-06T00:00:00Z')");
            for (int i = 0; i < 1200; i++) {
                statement.execute("INSERT INTO transactions (player_uuid, type, amount, context, created_at) VALUES ('"
                        + (i % 2 == 0 ? ALICE : BOB) + "', 'QUEST_REWARD', " + (i * 7 - 300) + ", "
                        + (i % 3 == 0 ? "NULL" : "'quête n°" + i + "'") + ", '2026-10-06T00:00:" + (i % 60) + "Z')");
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO market_listings (seller_uuid, item_data, price, status, created_at) "
                            + "VALUES (?, ?, ?, 'OPEN', '2026-10-07T00:00:00Z')")) {
                insert.setString(1, CAROL);
                insert.setBytes(2, new byte[] {0, 1, 2, (byte) 0xFF, (byte) 0x80, 0, 42});
                insert.setLong(3, 9_000_000_000L);
                insert.executeUpdate();
            }
            statement.execute("INSERT INTO village_centers (id, name, world, x, y, z, yaw, pitch, active, created_at) "
                    + "VALUES ('hub', 'Hub', 'world', 0.5, 64.0, -0.5, 179.99, -12.25, 1, '2026-10-08T00:00:00Z')");
            statement.execute("INSERT INTO village_centers (id, name, world, x, y, z, yaw, pitch, active, created_at) "
                    + "VALUES ('port', 'Port « Azur »', 'world', 1024.125, 63.0, 0.1, 0, 0, 0, '2026-10-08T00:00:00Z')");
            for (int i = 0; i < 3; i++) {
                statement.execute("INSERT INTO npc_ids (created_at) VALUES ('2026-10-09T00:00:0" + i + "Z')");
            }
            statement.execute("DELETE FROM npc_ids WHERE id = 3");
        }
        return file;
    }
}
