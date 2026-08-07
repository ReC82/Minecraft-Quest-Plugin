package com.lodygames.rpgquest.database;

import java.time.Instant;

/**
 * Une position de nœud de ressource persistée. {@code depletedAt} est
 * {@code null} quand le nœud est actif (jamais récolté, ou déjà respawné) ;
 * sinon c'est l'instant auquel il redevient récoltable.
 */
public record ResourceNodeRecord(String world, int x, int y, int z, String typeId, Instant depletedAt) {
}
