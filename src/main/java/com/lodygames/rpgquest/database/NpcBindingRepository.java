package com.lodygames.rpgquest.database;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Persiste la liaison entre un PNJ Citizens (identifié par son
 * {@code NPC#getUniqueId()}, garanti stable par Citizens lui-même —
 * contrairement à {@code NPC#getId()}, dont la javadoc CitizensAPI précise
 * qu'il n'est pas garanti unique entre sessions) et l'identifiant logique
 * RPGQuest correspondant (ex. {@code guide}). Contrairement au
 * {@code PersistentDataContainer} utilisé pour une entité vanilla ordinaire,
 * cette table survit à un redémarrage même si Citizens recrée une nouvelle
 * entité Bukkit éphémère pour le même PNJ (comportement normal de Citizens).
 * Pure JDBC, aucun type Citizens ni Bukkit ici.
 */
public final class NpcBindingRepository {

    private static final String UPSERT = """
            INSERT INTO npc_citizens_bindings (citizens_uuid, citizens_numeric_id, npc_id, created_at)
            VALUES (?, ?, ?, ?)
            ON CONFLICT(citizens_uuid) DO UPDATE SET citizens_numeric_id = excluded.citizens_numeric_id, npc_id = excluded.npc_id
            """;
    /** Insertion <strong>non destructive</strong> : ne touche rien si le PNJ Citizens est déjà lié. */
    private static final String INSERT_IF_ABSENT = """
            INSERT OR IGNORE INTO npc_citizens_bindings (citizens_uuid, citizens_numeric_id, npc_id, created_at)
            VALUES (?, ?, ?, ?)
            """;
    private static final String DELETE = "DELETE FROM npc_citizens_bindings WHERE citizens_uuid = ?";
    private static final String FIND = "SELECT npc_id FROM npc_citizens_bindings WHERE citizens_uuid = ?";
    private static final String SELECT_ALL = "SELECT citizens_uuid, citizens_numeric_id, npc_id FROM npc_citizens_bindings";

    private final DatabaseManager database;
    private final SqlDialect dialect;

    public NpcBindingRepository(DatabaseManager database) {
        this.database = database;
        this.dialect = database.dialect();
    }

    public CompletableFuture<Void> upsert(UUID citizensUuid, int citizensNumericId, String npcId) {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(dialect.rewrite(UPSERT))) {
                statement.setString(1, citizensUuid.toString());
                statement.setInt(2, citizensNumericId);
                statement.setString(3, npcId);
                statement.setString(4, Instant.now().toString());
                statement.executeUpdate();
            }
            return null;
        });
    }

    /**
     * Crée la liaison {@code citizensUuid → npcId} <strong>uniquement si ce PNJ Citizens n'est pas
     * déjà lié</strong> (clé primaire {@code citizens_uuid}). Atomique au niveau SQL.
     *
     * @return {@code true} si une ligne a été insérée, {@code false} si le PNJ Citizens était déjà lié.
     */
    public CompletableFuture<Boolean> insertIfAbsent(UUID citizensUuid, int citizensNumericId, String npcId) {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(dialect.rewrite(INSERT_IF_ABSENT))) {
                statement.setString(1, citizensUuid.toString());
                statement.setInt(2, citizensNumericId);
                statement.setString(3, npcId);
                statement.setString(4, Instant.now().toString());
                return statement.executeUpdate() == 1;
            }
        });
    }

    public CompletableFuture<Void> delete(UUID citizensUuid) {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(DELETE)) {
                statement.setString(1, citizensUuid.toString());
                statement.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<Optional<String>> find(UUID citizensUuid) {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(FIND)) {
                statement.setString(1, citizensUuid.toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next() ? Optional.of(resultSet.getString("npc_id")) : Optional.<String>empty();
                }
            }
        });
    }

    public CompletableFuture<List<Binding>> loadAll() {
        return database.execute(connection -> {
            List<Binding> bindings = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(SELECT_ALL);
                 ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    bindings.add(new Binding(
                            UUID.fromString(resultSet.getString("citizens_uuid")),
                            resultSet.getInt("citizens_numeric_id"),
                            resultSet.getString("npc_id")));
                }
            }
            return bindings;
        });
    }

    public record Binding(UUID citizensUuid, int citizensNumericId, String npcId) {
    }
}
