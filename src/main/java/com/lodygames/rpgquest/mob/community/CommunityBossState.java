package com.lodygames.rpgquest.mob.community;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Issue #175 — instantané immuable de l'état persistant du scheduler (pas une table : un fichier YAML
 * de données, voir {@link CommunityBossStateStore}).
 *
 * @param nextDueMillis     prochaine échéance, {@code null} tant que le scheduler n'a jamais été armé
 * @param lastSpawnMillis   dernière apparition réussie (0 = jamais)
 * @param lastAttemptMillis dernière tentative, réussie ou non (0 = jamais)
 * @param lastOutcome       résultat lisible de la dernière tentative (affiché dans PlugAdmin)
 * @param instances         verrous des boss actifs, y compris ceux restés dans un chunk déchargé
 * @param retired           entités retirées depuis PlugAdmin alors que leur chunk était déchargé :
 *                          supprimées dès que ce chunk se recharge, jamais réadoptées
 */
public record CommunityBossState(Long nextDueMillis, long lastSpawnMillis, long lastAttemptMillis,
                                 String lastOutcome, List<CommunityBossInstance> instances, Set<UUID> retired) {

    public CommunityBossState {
        lastOutcome = lastOutcome == null ? "" : lastOutcome;
        instances = instances == null ? List.of() : List.copyOf(instances);
        retired = retired == null ? Set.of() : Set.copyOf(retired);
    }

    public static CommunityBossState empty() {
        return new CommunityBossState(null, 0L, 0L, "", List.of(), Set.of());
    }
}
