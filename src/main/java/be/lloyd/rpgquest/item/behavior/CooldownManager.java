package be.lloyd.rpgquest.item.behavior;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Cooldowns indexés par (UUID joueur, identifiant de capacité), comme
 * demandé par la mission — jamais par le seul id de l'objet, pour permettre
 * à plusieurs objets de partager une capacité (même {@code abilityId}) ou à
 * un même objet d'exposer plusieurs capacités indépendantes.
 *
 * <p>{@code clock} est injectable (au lieu de coder {@code
 * System.currentTimeMillis()} en dur) pour rendre les tests déterministes
 * sans dépendre du temps réel ni de {@code Thread.sleep}.</p>
 */
public final class CooldownManager {

    private record Key(UUID playerId, String abilityId) {
    }

    private final Map<Key, Long> expiryByKey = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    public CooldownManager() {
        this(System::currentTimeMillis);
    }

    public CooldownManager(LongSupplier clock) {
        this.clock = clock;
    }

    /** {@code true} si la capacité est prête. Nettoie l'entrée au passage si elle est expirée (rule 5). */
    public boolean isReady(UUID playerId, String abilityId) {
        Key key = new Key(playerId, abilityId);
        Long expiry = expiryByKey.get(key);
        if (expiry == null) {
            return true;
        }
        if (clock.getAsLong() >= expiry) {
            expiryByKey.remove(key);
            return true;
        }
        return false;
    }

    /** Démarre (ou réinitialise) le cooldown d'une capacité pour ce joueur. */
    public void start(UUID playerId, String abilityId, long durationMillis) {
        if (durationMillis <= 0) {
            return;
        }
        expiryByKey.put(new Key(playerId, abilityId), clock.getAsLong() + durationMillis);
    }

    /** Temps restant en millisecondes, {@code 0} si la capacité est prête. */
    public long remainingMillis(UUID playerId, String abilityId) {
        Long expiry = expiryByKey.get(new Key(playerId, abilityId));
        if (expiry == null) {
            return 0L;
        }
        return Math.max(0L, expiry - clock.getAsLong());
    }

    /** Efface tous les cooldowns d'un joueur (déconnexion) — évite d'accumuler des entrées mortes indéfiniment. */
    public void clear(UUID playerId) {
        expiryByKey.keySet().removeIf(key -> key.playerId().equals(playerId));
    }

    /** Purge active de toutes les entrées expirées, indépendamment d'un accès. @return le nombre d'entrées supprimées. */
    public int purgeExpired() {
        long now = clock.getAsLong();
        int before = expiryByKey.size();
        expiryByKey.values().removeIf(expiry -> now >= expiry);
        return before - expiryByKey.size();
    }

    /** Nombre d'entrées actuellement suivies (y compris expirées non encore purgées) — utilisé par les tests. */
    public int trackedCount() {
        return expiryByKey.size();
    }
}
