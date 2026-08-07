package com.lodygames.rpgquest.webapi.store;

import java.time.Instant;
import java.util.UUID;

public record Order(
        String id,
        String productId,
        UUID playerUuid,
        String playerName,
        OrderStatus status,
        long amountCents,
        String currency,
        String providerSessionId,
        Instant createdAt,
        Instant updatedAt
) {
}
