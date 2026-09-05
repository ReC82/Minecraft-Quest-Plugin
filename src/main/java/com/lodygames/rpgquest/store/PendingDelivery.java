package com.lodygames.rpgquest.store;

import java.util.UUID;

/** Livraison en attente telle que renvoyée par {@code GET /api/store/deliveries/pending}. */
public record PendingDelivery(String id, String orderId, String kind, String productId, UUID playerUuid, String playerName, int attempts) {
}
