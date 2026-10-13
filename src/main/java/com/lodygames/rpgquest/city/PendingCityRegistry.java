package com.lodygames.rpgquest.city;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Les gestes en attente de l'outil de ville (issue #178), <strong>en mémoire uniquement</strong>.
 *
 * <ul>
 *   <li><strong>Création</strong> : le centre est cliqué, le nom n'est pas encore validé. Rien n'est
 *       écrit tant que le nom n'est pas confirmé ; fermer la fenêtre annule.</li>
 *   <li><strong>Déplacement armé</strong> : un administrateur a demandé (panel ou commande) que son
 *       prochain clic avec l'outil déplace le centre d'une ville précise.</li>
 * </ul>
 *
 * <p>Une seule demande de chaque sorte par joueur ; une nouvelle remplace l'ancienne. L'expiration
 * est évaluée à la lecture, comme pour les emplacements de construction (#227).</p>
 */
public final class PendingCityRegistry {

    public static final Duration CREATE_TTL = Duration.ofSeconds(60);
    public static final Duration MOVE_TTL = Duration.ofSeconds(180);

    private final Clock clock;
    private final Map<String, PendingCity> creates = new ConcurrentHashMap<>();
    private final Map<String, ArmedMove> moves = new ConcurrentHashMap<>();

    public PendingCityRegistry() {
        this(Clock.systemUTC());
    }

    public PendingCityRegistry(Clock clock) {
        this.clock = clock;
    }

    public PendingCity openCreate(String playerId, String world, double x, double y, double z, float yaw) {
        PendingCity pending = new PendingCity(playerId, world, x, y, z, yaw, clock.instant().plus(CREATE_TTL));
        creates.put(playerId, pending);
        return pending;
    }

    /** Retire la création en attente (atomique) : un double clic ne confirme jamais deux fois. */
    public Optional<PendingCity> takeCreate(String playerId) {
        PendingCity pending = creates.remove(playerId);
        if (pending == null || clock.instant().isAfter(pending.expiresAt())) {
            return Optional.empty();
        }
        return Optional.of(pending);
    }

    public boolean hadCreate(String playerId) {
        return creates.containsKey(playerId);
    }

    public void cancelCreate(String playerId) {
        creates.remove(playerId);
    }

    public ArmedMove armMove(String playerId, String cityId, String requestedBy) {
        ArmedMove armed = new ArmedMove(playerId, cityId, requestedBy, clock.instant().plus(MOVE_TTL));
        moves.put(playerId, armed);
        return armed;
    }

    /** Le déplacement armé de ce joueur, retiré au passage (un seul clic l'utilise). */
    public Optional<ArmedMove> takeMove(String playerId) {
        ArmedMove armed = moves.remove(playerId);
        if (armed == null || clock.instant().isAfter(armed.expiresAt())) {
            return Optional.empty();
        }
        return Optional.of(armed);
    }

    public Optional<ArmedMove> peekMove(String playerId) {
        ArmedMove armed = moves.get(playerId);
        if (armed == null || clock.instant().isAfter(armed.expiresAt())) {
            return Optional.empty();
        }
        return Optional.of(armed);
    }

    /** Déplacements armés encore valables, pour l'affichage du panel. */
    public java.util.List<ArmedMove> armedMoves() {
        Instant now = clock.instant();
        return moves.values().stream().filter(m -> !now.isAfter(m.expiresAt())).toList();
    }

    public void cancelMove(String playerId) {
        moves.remove(playerId);
    }

    public void forget(String playerId) {
        creates.remove(playerId);
        moves.remove(playerId);
    }

    /** Centre cliqué, en attente de nom. */
    public record PendingCity(String playerId, String world, double x, double y, double z, float yaw,
                              Instant expiresAt) {

        public String positionLabel() {
            return (int) Math.floor(x) + " / " + (int) Math.floor(y) + " / " + (int) Math.floor(z);
        }
    }

    /** Prochain clic de l'outil = nouveau centre de {@code cityId}. */
    public record ArmedMove(String playerId, String cityId, String requestedBy, Instant expiresAt) {
    }
}
