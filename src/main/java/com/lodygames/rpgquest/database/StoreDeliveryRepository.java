package com.lodygames.rpgquest.database;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

/**
 * Filet de sécurité d'idempotence local pour les livraisons de la boutique
 * (mission étape 22, table {@code store_deliveries_processed}, migration
 * V10). {@code web-api} acquitte déjà les livraisons de façon idempotente ;
 * cette table protège contre le cas où l'octroi local a réussi mais
 * l'accusé de réception vers web-api a échoué à repartir (panne réseau
 * juste après un octroi) — sans elle, le prochain sondage retraiterait la
 * même livraison et octroierait deux fois.
 */
public final class StoreDeliveryRepository {

    private static final String SELECT_PROCESSED = "SELECT 1 FROM store_deliveries_processed WHERE delivery_id = ?";
    private static final String INSERT_PROCESSED =
            "INSERT OR IGNORE INTO store_deliveries_processed (delivery_id, outcome, detail, processed_at) VALUES (?, ?, ?, ?)";

    private final DatabaseManager database;

    public StoreDeliveryRepository(DatabaseManager database) {
        this.database = database;
    }

    public CompletableFuture<Boolean> isProcessed(String deliveryId) {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(SELECT_PROCESSED)) {
                statement.setString(1, deliveryId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next();
                }
            }
        });
    }

    public CompletableFuture<Void> markProcessed(String deliveryId, String outcome, String detail) {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(INSERT_PROCESSED)) {
                statement.setString(1, deliveryId);
                statement.setString(2, outcome);
                statement.setString(3, detail);
                statement.setString(4, Instant.now().toString());
                statement.executeUpdate();
            }
            return null;
        });
    }
}
