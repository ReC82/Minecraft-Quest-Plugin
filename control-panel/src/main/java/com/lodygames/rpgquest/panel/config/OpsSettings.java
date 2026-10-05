package com.lodygames.rpgquest.panel.config;

import com.lodygames.rpgquest.panel.ops.RestartService;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Bloc de configuration « exploitation serveur » (issue #95), regroupé pour n'ajouter qu'un seul
 * champ à {@link PanelConfig} — même patron que {@code AgentSettings}.
 *
 * <p><strong>Par cible, jamais en dur.</strong> Chaque cible RPGQuest déclare son propre point
 * d'accès RCON : le module reste utilisable avec plusieurs environnements, ce que le ticket exige
 * explicitement (« ne pas coder les actions uniquement pour DEV ou pour un unique serveur en
 * dur »). Une cible sans point d'accès n'est pas une erreur de configuration : la fonction
 * « redémarrer » s'affiche simplement <strong>indisponible</strong>, avec son motif.</p>
 *
 * @param rconEndpoints      points d'accès RCON déclarés, au plus un par cible
 * @param restartReturnTimeout délai maximal d'attente du retour en ligne après l'arrêt
 * @param restartPollInterval  intervalle entre deux sondes de vivacité
 * @param restartStopGrace     délai au-delà duquel « toujours joignable » devient suspect
 * @param rconTimeout          délai de connexion / lecture d'une session RCON
 * @param announceMinInterval  intervalle minimal entre deux annonces (anti-matraquage)
 * @param logTailLines         nombre de lignes demandées à l'agent par consultation de console
 */
public record OpsSettings(
        List<RestartService.RconEndpoint> rconEndpoints,
        Duration restartReturnTimeout,
        Duration restartPollInterval,
        Duration restartStopGrace,
        Duration rconTimeout,
        Duration announceMinInterval,
        int logTailLines) {

    public OpsSettings {
        rconEndpoints = rconEndpoints == null ? List.of() : List.copyOf(rconEndpoints);
        restartReturnTimeout = positive(restartReturnTimeout, Duration.ofSeconds(240));
        restartPollInterval = positive(restartPollInterval, Duration.ofSeconds(5));
        restartStopGrace = positive(restartStopGrace, Duration.ofSeconds(45));
        rconTimeout = positive(rconTimeout, Duration.ofSeconds(10));
        announceMinInterval = announceMinInterval == null || announceMinInterval.isNegative()
                ? Duration.ofSeconds(10) : announceMinInterval;
        logTailLines = Math.max(20, Math.min(500, logTailLines == 0 ? 200 : logTailLines));
    }

    public static OpsSettings defaults() {
        return new OpsSettings(List.of(), null, null, null, null, null, 200);
    }

    /** Point d'accès RCON de la cible, s'il est déclaré <strong>et</strong> complet. */
    public Optional<RestartService.RconEndpoint> rconFor(String targetId) {
        return rconEndpoints.stream()
                .filter(e -> e.targetId().equals(targetId))
                .filter(RestartService.RconEndpoint::usable)
                .findFirst();
    }

    private static Duration positive(Duration value, Duration fallback) {
        return value == null || value.isNegative() || value.isZero() ? fallback : value;
    }
}
