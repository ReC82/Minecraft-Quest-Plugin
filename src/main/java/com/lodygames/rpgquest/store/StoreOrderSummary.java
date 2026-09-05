package com.lodygames.rpgquest.store;

/** Résumé d'une commande, tel que renvoyé par {@code GET /api/store/orders} (historique admin, mission point 11). */
public record StoreOrderSummary(String id, String productId, String playerUuid, String playerName, String status,
                                 long amountCents, String currency, String createdAt) {
}
