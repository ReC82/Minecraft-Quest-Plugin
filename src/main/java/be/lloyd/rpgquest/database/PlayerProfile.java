package be.lloyd.rpgquest.database;

import java.time.Instant;
import java.util.UUID;

public record PlayerProfile(UUID uuid, String lastName, Instant createdAt, Instant updatedAt) {
}
