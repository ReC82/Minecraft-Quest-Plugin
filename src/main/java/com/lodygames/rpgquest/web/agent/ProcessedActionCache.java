package com.lodygames.rpgquest.web.agent;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Garde d'idempotence de l'agent (issue #51, phase 10). Retient les {@code action_id} déjà traités
 * et leur résultat pendant une durée bornée, afin qu'une action ré-livrée par PlugAdmin (réponse
 * HTTP de résultat perdue, retry) ne soit <strong>jamais ré-exécutée</strong> : l'agent renvoie
 * simplement le résultat mémorisé.
 *
 * <p>Stratégie : cache mémoire borné (taille + TTL). Sur redémarrage du plugin, le cache est vide,
 * mais PlugAdmin ne re-livre une action que tant qu'aucun résultat terminal n'est arrivé ; le
 * risque résiduel (résultat perdu <em>pendant</em> un redémarrage) est documenté et acceptable
 * pour le MVP — la seule action ouverte, {@code player.variable.get}, est de toute façon une
 * lecture pure sans effet de bord.</p>
 *
 * <p>Non thread-safe : appelé uniquement depuis l'unique boucle de polling de l'agent.</p>
 */
public final class ProcessedActionCache {

    private final Duration ttl;
    private final int maxEntries;
    private final Map<String, Entry> entries;

    private record Entry(AgentActionOutcome outcome, Instant storedAt) {
    }

    public ProcessedActionCache() {
        this(Duration.ofMinutes(30), 500);
    }

    public ProcessedActionCache(Duration ttl, int maxEntries) {
        this.ttl = ttl;
        this.maxEntries = Math.max(16, maxEntries);
        this.entries = new LinkedHashMap<>(64, 0.75f, false);
    }

    /** Résultat déjà calculé pour cette action, si connu et non expiré. */
    public Optional<AgentActionOutcome> lookup(String actionId, Instant now) {
        Entry entry = entries.get(actionId);
        if (entry == null) {
            return Optional.empty();
        }
        if (now.isAfter(entry.storedAt().plus(ttl))) {
            entries.remove(actionId);
            return Optional.empty();
        }
        return Optional.of(entry.outcome());
    }

    /** Mémorise le résultat d'une action fraîchement traitée. */
    public void remember(String actionId, AgentActionOutcome outcome, Instant now) {
        purgeExpired(now);
        if (entries.size() >= maxEntries) {
            var iterator = entries.keySet().iterator();
            if (iterator.hasNext()) {
                iterator.next();
                iterator.remove();
            }
        }
        entries.put(actionId, new Entry(outcome, now));
    }

    public int size() {
        return entries.size();
    }

    private void purgeExpired(Instant now) {
        entries.entrySet().removeIf(e -> now.isAfter(e.getValue().storedAt().plus(ttl)));
    }
}
