package com.lodygames.rpgquest.dialogue.session;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntUnaryOperator;

/**
 * Issue #158 — choisit la réplique affichée parmi celles d'un nœud ({@code text} + {@code
 * variants}) : tirage au hasard, sans jamais répéter <strong>immédiatement</strong> la dernière
 * réplique montrée au même joueur pour ce même nœud (dès que le nœud en a au moins deux).
 *
 * <p>Mémoire volatile, comme les sessions de dialogue : rien n'est persisté, une déconnexion
 * (ou un redémarrage) oublie simplement la dernière réplique. Le pire cas est donc une répétition
 * juste après une reconnexion — sans incidence sur la progression.</p>
 */
final class DialogueLineRotation {

    /** {@code bound -> [0, bound)} ; injectable pour des tests déterministes. */
    private final IntUnaryOperator random;
    private final Map<UUID, Map<String, Integer>> lastShown = new ConcurrentHashMap<>();

    DialogueLineRotation(IntUnaryOperator random) {
        this.random = random;
    }

    /** @return l'index de la réplique à afficher, dans {@code [0, lineCount)} */
    int pick(UUID playerId, String nodeKey, int lineCount) {
        if (lineCount <= 1) {
            return 0;
        }
        Map<String, Integer> perNode = lastShown.computeIfAbsent(playerId, id -> new ConcurrentHashMap<>());
        Integer previous = perNode.get(nodeKey);
        int index;
        if (previous == null || previous >= lineCount) {
            index = random.applyAsInt(lineCount);
        } else {
            // Tirage parmi les lineCount - 1 autres répliques : uniforme, et jamais la précédente.
            index = random.applyAsInt(lineCount - 1);
            if (index >= previous) {
                index++;
            }
        }
        perNode.put(nodeKey, index);
        return index;
    }

    void forget(UUID playerId) {
        lastShown.remove(playerId);
    }

    void clear() {
        lastShown.clear();
    }
}
