package com.lodygames.rpgquest.panel.audit;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Journal d'audit append-only du Control Panel. Toute action sensible (et déjà le login/logout en
 * V1) passe par {@link #record}. Deux implémentations : {@link SqliteAuditLog} (production,
 * {@code control-panel.db}) et {@link InMemoryAuditLog} (tests).
 */
public interface AuditLog {

    void append(AuditEntry entry);

    List<AuditEntry> recent(int limit);

    /** Fabrique une entrée horodatée « maintenant » et l'ajoute. */
    default void record(String actor, String action, String target, String result, String details, String requestId) {
        append(new AuditEntry(UUID.randomUUID().toString(), Instant.now(),
                actor, action, target, result, details, requestId));
    }
}
