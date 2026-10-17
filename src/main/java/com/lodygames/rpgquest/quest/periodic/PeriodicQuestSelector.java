package com.lodygames.rpgquest.quest.periodic;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Issue #15 — choisit les quêtes d'un joueur pour une période. Fonction pure et
 * <strong>déterministe</strong> : mêmes entrées (joueur, période, réserve, quêtes de la période
 * précédente), même résultat. Deux calculs concurrents pour une même période proposent donc la même
 * sélection, et l'insertion idempotente en base fait le reste.
 *
 * <p>Anti-répétition : les quêtes de la période précédente ne sont reprises que si la réserve ne
 * suffit pas à en proposer assez de nouvelles.</p>
 */
public final class PeriodicQuestSelector {

    private PeriodicQuestSelector() {
    }

    /**
     * @param pool     identifiants des modèles disponibles pour cette période (ordre indifférent)
     * @param previous quêtes attribuées à la période précédente (à éviter si possible)
     * @param count    nombre de quêtes voulues (borné par la taille de la réserve)
     * @return au plus {@code count} identifiants distincts, dans l'ordre d'affichage
     */
    public static List<String> select(UUID playerId, String periodType, String periodKey, Collection<String> pool,
                                      Set<String> previous, int count) {
        List<String> sorted = pool.stream().distinct().sorted(Comparator.naturalOrder()).toList();
        Random random = new Random(seed(playerId, periodType, periodKey));
        List<String> fresh = new ArrayList<>();
        List<String> repeated = new ArrayList<>();
        for (String id : sorted) {
            (previous.contains(id) ? repeated : fresh).add(id);
        }
        java.util.Collections.shuffle(fresh, random);
        java.util.Collections.shuffle(repeated, random);
        List<String> chosen = new ArrayList<>(fresh);
        chosen.addAll(repeated);
        return List.copyOf(chosen.subList(0, Math.min(Math.max(count, 0), chosen.size())));
    }

    private static long seed(UUID playerId, String periodType, String periodKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    (playerId + "|" + periodType + "|" + periodKey).getBytes(StandardCharsets.UTF_8));
            long seed = 0;
            for (int i = 0; i < Long.BYTES; i++) {
                seed = (seed << 8) | (digest[i] & 0xFF);
            }
            return seed;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponible", e);
        }
    }
}
