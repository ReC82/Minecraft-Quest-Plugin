package com.lodygames.rpgquest.panel.audit;

import java.time.Instant;

/**
 * Une entrée du journal d'audit du Control Panel (issue #37, exigence 5 : mécanisme central posé
 * dès le socle, même si peu d'actions en V1).
 *
 * @param id        identifiant unique (ULID/UUID)
 * @param ts        horodatage UTC
 * @param actor     utilisateur (ou {@code anonymous} pour un échec de login)
 * @param action    ex. {@code login.success}, {@code logout}, plus tard {@code actions/quest-complete}
 * @param target    cible/environnement, ex. {@code env=dev player=Rondoudou9000}
 * @param result    ex. {@code OK}, {@code DENIED}, {@code ERROR:bridge_unreachable}
 * @param details   contexte court, JAMAIS de secret
 * @param requestId corrélation avec les logs HTTP
 */
public record AuditEntry(String id, Instant ts, String actor, String action,
                         String target, String result, String details, String requestId) {
}
