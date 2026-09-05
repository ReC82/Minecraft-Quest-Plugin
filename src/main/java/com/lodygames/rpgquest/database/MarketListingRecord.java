package com.lodygames.rpgquest.database;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Ligne de {@code market_listings}. Pure JDBC, aucun type Bukkit :
 * {@code itemData} est l'objet sérialisé tel quel
 * ({@code ItemStack#serializeAsBytes()}), désérialisé uniquement côté
 * {@code economy.market.MarketService}. {@code sellerName} n'est renseigné
 * que par les requêtes qui joignent {@code player_profiles} (les requêtes
 * ciblant une seule ligne par id n'en ont pas besoin).
 */
public record MarketListingRecord(
        long id,
        UUID sellerUuid,
        Optional<String> sellerName,
        byte[] itemData,
        long price,
        String status,
        Instant createdAt,
        Instant resolvedAt,
        Optional<UUID> buyerUuid
) {
}
