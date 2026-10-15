package com.lodygames.rpgquest.database;

import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Journal d'activité générique des événements (issue #57) : une ligne par action de jeu comptable
 * (mob tué, prise de pêche…). Écriture seule, asynchrone ; la lecture se fait par fenêtre dans
 * {@link StatisticsRepository}.
 *
 * <p>Idempotent : {@code event_key} est unique, un retry ou un événement Bukkit rejoué n'insère
 * rien. Ce journal n'est jamais une source de gameplay (aucune récompense, aucune quête ne le lit).</p>
 */
public final class ActivityLogRepository {

    private final DatabaseManager database;
    private final String insert;

    public ActivityLogRepository(DatabaseManager database) {
        this.database = database;
        this.insert = database.dialect().rewrite("INSERT OR IGNORE INTO stat_activity_log "
                + "(event_key, player_uuid, action, target, variant, amount, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)");
    }

    /** Une action comptable. {@code variant} peut être {@code null}. */
    public record Activity(String eventKey, UUID player, String action, String target, String variant, long amount,
                           Instant at) {
    }

    /** Enregistre l'action ; rend {@code false} si sa clé était déjà connue (retry, doublon). */
    public CompletableFuture<Boolean> record(Activity activity) {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(insert)) {
                statement.setString(1, activity.eventKey());
                statement.setString(2, activity.player().toString());
                statement.setString(3, activity.action());
                statement.setString(4, activity.target());
                statement.setString(5, activity.variant());
                statement.setLong(6, activity.amount());
                statement.setString(7, activity.at().toString());
                return statement.executeUpdate() > 0;
            }
        });
    }
}
