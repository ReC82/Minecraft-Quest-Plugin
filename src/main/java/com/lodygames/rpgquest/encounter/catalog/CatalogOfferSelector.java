package com.lodygames.rpgquest.encounter.catalog;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Choix de la quête qu'un PNJ récurrent propose à un joueur (issue #369). Fonction
 * <strong>pure et déterministe</strong> : aucune horloge, aucun hasard, aucun état — ce qui rend
 * l'offre stable sans rien persister.
 *
 * <ul>
 *   <li><strong>Une à la fois</strong> : si une quête des catalogues du PNJ est déjà en cours, le
 *       PNJ la rappelle et n'en propose pas d'autre.</li>
 *   <li><strong>Éligibilité</strong> : seules les quêtes que le moteur déclare acceptables
 *       maintenant (prérequis, état, non répétable, délai) sont candidates — la règle reste celle
 *       de {@code QuestProgressEngine#availability}, jamais une copie.</li>
 *   <li><strong>Anti-répétition</strong> : les {@code avoidRecent} quêtes terminées le plus
 *       récemment sont écartées tant qu'une autre reste possible.</li>
 *   <li><strong>Stabilité</strong> : le tirage pondéré est graine = (joueur, PNJ, date de la
 *       dernière complétion d'une quête de ces catalogues). Fermer/rouvrir, se reconnecter, changer
 *       d'occurrence du même modèle ou redémarrer le serveur redonne la même offre ; seule une
 *       complétion — une « occasion légitime » — fait tourner la sélection. Le tirage est un
 *       rendez-vous pondéré : retirer ou ajouter une <em>autre</em> quête ne change pas l'offre,
 *       sauf si la nouvelle l'emporte.</li>
 * </ul>
 */
public final class CatalogOfferSelector {

    private CatalogOfferSelector() {
    }

    /** Quête candidate, dans l'ordre des catalogues (doublons déjà fusionnés). */
    public record Candidate(String questId, int weight) {
    }

    /** État d'une candidate pour ce joueur, tel que rendu par le moteur de quêtes. */
    public enum Status { AVAILABLE, ACTIVE, ON_COOLDOWN, UNAVAILABLE }

    public record QuestStatus(Status status, Duration retryAfter) {
    }

    public enum Kind { OFFER, ACTIVE, NOTHING }

    /**
     * @param questId    quête proposée ({@code OFFER}) ou en cours ({@code ACTIVE}), sinon {@code null}
     * @param retryAfter pour {@code NOTHING} : plus court délai avant qu'une quête en recharge
     *                   redevienne possible, ou {@code null} si aucune ne le deviendra d'elle-même
     */
    public record Decision(Kind kind, String questId, Duration retryAfter) {

        public static Decision nothing(Duration retryAfter) {
            return new Decision(Kind.NOTHING, null, retryAfter);
        }
    }

    public static Decision decide(UUID player, String scope, List<Candidate> candidates,
                                  Map<String, QuestStatus> statuses, Map<String, Instant> completedAt,
                                  int avoidRecent) {
        for (Candidate c : candidates) {
            if (statusOf(statuses, c) == Status.ACTIVE) {
                return new Decision(Kind.ACTIVE, c.questId(), null);
            }
        }
        List<Candidate> eligible = candidates.stream()
                .filter(c -> statusOf(statuses, c) == Status.AVAILABLE).toList();
        if (eligible.isEmpty()) {
            Duration soonest = candidates.stream()
                    .map(c -> statuses.get(c.questId()))
                    .filter(s -> s != null && s.status() == Status.ON_COOLDOWN && s.retryAfter() != null)
                    .map(QuestStatus::retryAfter)
                    .min(Comparator.naturalOrder()).orElse(null);
            return Decision.nothing(soonest);
        }
        Set<String> candidateIds = candidates.stream().map(Candidate::questId).collect(Collectors.toSet());
        List<Map.Entry<String, Instant>> completions = completedAt.entrySet().stream()
                .filter(e -> candidateIds.contains(e.getKey()) && e.getValue() != null)
                .sorted(Map.Entry.<String, Instant>comparingByValue().reversed())
                .toList();
        Set<String> recent = completions.stream().limit(Math.max(0, avoidRecent))
                .map(Map.Entry::getKey).collect(Collectors.toSet());
        List<Candidate> pool = eligible.stream().filter(c -> !recent.contains(c.questId())).toList();
        if (pool.isEmpty()) {
            pool = eligible;
        }
        long epoch = completions.isEmpty() ? 0L : completions.get(0).getValue().toEpochMilli();
        return new Decision(Kind.OFFER, pick(player, scope, epoch, pool), null);
    }

    private static Status statusOf(Map<String, QuestStatus> statuses, Candidate c) {
        QuestStatus s = statuses.get(c.questId());
        return s == null ? Status.UNAVAILABLE : s.status();
    }

    /** Rendez-vous pondéré : plus petit {@code -ln(u) / poids}, {@code u} dérivé de la graine. */
    static String pick(UUID player, String scope, long epoch, List<Candidate> pool) {
        String best = null;
        double bestScore = Double.MAX_VALUE;
        for (Candidate c : pool) {
            double u = unit(player + "|" + scope + "|" + epoch + "|" + c.questId());
            double score = -Math.log(u) / c.weight();
            if (score < bestScore) {
                bestScore = score;
                best = c.questId();
            }
        }
        return best;
    }

    /** Valeur dans ]0, 1[ dérivée de {@code seed} (SHA-256), identique sur toute JVM. */
    static double unit(String seed) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(seed.getBytes(StandardCharsets.UTF_8));
            long bits = 0;
            for (int i = 0; i < 8; i++) {
                bits = (bits << 8) | (digest[i] & 0xFF);
            }
            return ((bits >>> 11) + 0.5) / (double) (1L << 53);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponible", e);
        }
    }
}
