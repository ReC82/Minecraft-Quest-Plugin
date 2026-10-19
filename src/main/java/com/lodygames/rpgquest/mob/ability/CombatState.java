package com.lodygames.rpgquest.mob.ability;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;

/**
 * Issue #542 — « en combat » (décision Q4 de #541) : le mob vise un joueur valide, du même monde, à
 * portée, <strong>et</strong> un coup a été échangé entre eux (donné ou reçu) il y a moins de
 * {@link #COMBAT_TIMEOUT_MILLIS}. État en mémoire seulement, par UUID de mob, retiré à la mort, au
 * retrait du monde et à l'arrêt du plugin ({@link AbilityEngine}). Les capacités actuelles ne
 * l'exigent pas (comportement inchangé) ; les futures capacités offensives le demanderont.
 */
public final class CombatState {

    public static final long COMBAT_TIMEOUT_MILLIS = 10_000L;

    private record Exchange(UUID playerId, long atMillis) {
    }

    private final Map<UUID, Exchange> lastExchange = new ConcurrentHashMap<>();

    /** Un coup a été donné ou reçu entre ce mob et ce joueur. */
    public void recordExchange(UUID mobId, UUID playerId, long nowMillis) {
        lastExchange.put(mobId, new Exchange(playerId, nowMillis));
    }

    /**
     * @param rangeBlocks portée de la capacité qui demande
     * @param validTarget filtre des cibles ({@link AbilityWorldPolicy#isValidTarget})
     */
    public boolean isInCombat(LivingEntity entity, double rangeBlocks, long nowMillis, Predicate<Player> validTarget) {
        if (!(entity instanceof Mob mob) || !(mob.getTarget() instanceof Player target) || !validTarget.test(target)) {
            return false;
        }
        Exchange exchange = lastExchange.get(entity.getUniqueId());
        if (exchange == null || !exchange.playerId().equals(target.getUniqueId())
                || nowMillis - exchange.atMillis() >= COMBAT_TIMEOUT_MILLIS) {
            return false;
        }
        return target.getWorld().equals(entity.getWorld())
                && target.getLocation().distanceSquared(entity.getLocation()) <= rangeBlocks * rangeBlocks;
    }

    public void forget(UUID mobId) {
        lastExchange.remove(mobId);
    }

    public void clear() {
        lastExchange.clear();
    }

    /** Entrées en mémoire (diagnostic, tests de nettoyage). */
    public int size() {
        return lastExchange.size();
    }
}
