package com.lodygames.rpgquest.webapi.store;

import java.time.Instant;
import java.util.UUID;

public record Delivery(
        String id,
        String orderId,
        DeliveryKind kind,
        String productId,
        UUID playerUuid,
        String playerName,
        DeliveryStatus status,
        int attempts,
        Instant createdAt,
        Instant deliveredAt,
        String lastError
) {
}
