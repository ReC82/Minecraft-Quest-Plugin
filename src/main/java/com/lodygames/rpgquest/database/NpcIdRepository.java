package com.lodygames.rpgquest.database;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

/**
 * Allocates sequential, never-reused NPC identifiers ({@code npc_ids.id}) for
 * {@link com.lodygames.rpgquest.npc.NpcIdentityService} — used only when an
 * administrator tags an entity without providing an explicit id. Pure JDBC,
 * no Bukkit/Paper types.
 */
public final class NpcIdRepository {

    private static final String INSERT = "INSERT INTO npc_ids (created_at) VALUES (?)";

    private final DatabaseManager database;

    public NpcIdRepository(DatabaseManager database) {
        this.database = database;
    }

    public CompletableFuture<Integer> allocateId() {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(INSERT, Statement.RETURN_GENERATED_KEYS)) {
                statement.setString(1, Instant.now().toString());
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    keys.next();
                    return keys.getInt(1);
                }
            }
        });
    }
}
