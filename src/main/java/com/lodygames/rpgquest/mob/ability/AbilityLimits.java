package com.lodygames.rpgquest.mob.ability;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Issue #542 — plafonds serveur des capacités, en plus des plafonds propres à chaque instance
 * ({@code max-alive}, {@code max-alive-per-parent}, {@code max-population}) :
 *
 * <ul>
 *   <li>{@value #MAX_REINFORCEMENTS_PER_SECOND_PER_WORLD} renforts invoqués par seconde et par monde,
 *       tous mobs confondus (refus silencieux au-delà, compteur {@link #refusedCount()}) ;</li>
 *   <li>{@value #MAX_ACTIONS_IN_PROGRESS} actions en cours au total, et une seule par mob (décision Q5 :
 *       les capacités {@code PASSIVE} se cumulent, une seule {@code ACTION} à la fois). Aucune capacité
 *       actuelle n'est une action : ce plafond sert aux suivantes (#544+).</li>
 * </ul>
 */
public final class AbilityLimits {

    public static final int MAX_REINFORCEMENTS_PER_SECOND_PER_WORLD = 3;
    public static final int MAX_ACTIONS_IN_PROGRESS = 32;

    private record Window(long second, int count) {
    }

    private final Map<String, Window> reinforcements = new ConcurrentHashMap<>();
    private final Set<UUID> actionsInProgress = ConcurrentHashMap.newKeySet();
    private long refused;

    /** Réserve une apparition de renfort dans ce monde ; {@code false} = plafond atteint pour cette seconde. */
    public synchronized boolean tryReserveReinforcement(String world, long nowMillis) {
        long second = nowMillis / 1000L;
        Window window = reinforcements.get(world);
        if (window == null || window.second() != second) {
            window = new Window(second, 0);
        }
        if (window.count() >= MAX_REINFORCEMENTS_PER_SECOND_PER_WORLD) {
            refused++;
            return false;
        }
        reinforcements.put(world, new Window(second, window.count() + 1));
        return true;
    }

    /** Commence une action pour ce mob ; {@code false} s'il en a déjà une ou si le plafond global est atteint. */
    public synchronized boolean tryBeginAction(UUID mobId) {
        if (actionsInProgress.contains(mobId) || actionsInProgress.size() >= MAX_ACTIONS_IN_PROGRESS) {
            refused++;
            return false;
        }
        return actionsInProgress.add(mobId);
    }

    public void endAction(UUID mobId) {
        actionsInProgress.remove(mobId);
    }

    public int actionsInProgress() {
        return actionsInProgress.size();
    }

    /** Déclenchements refusés par un plafond serveur depuis le démarrage. */
    public synchronized long refusedCount() {
        return refused;
    }

    public synchronized void clear() {
        reinforcements.clear();
        actionsInProgress.clear();
    }
}
