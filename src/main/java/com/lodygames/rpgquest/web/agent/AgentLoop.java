package com.lodygames.rpgquest.web.agent;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;
import org.slf4j.Logger;

/**
 * Cœur logique de l'agent PlugAdmin (issue #51), <strong>sans dépendance Bukkit</strong> : les deux
 * boucles {@link #heartbeatTick()} et {@link #pollTick()} sont appelées par {@link PlugAdminAgent}
 * depuis des threads asynchrones Bukkit, et testables directement.
 *
 * <p>Garanties :</p>
 * <ul>
 *   <li>aucune exception ne remonte des ticks — une panne réseau se traduit par un backoff, jamais
 *       par une perturbation du serveur ;</li>
 *   <li>backoff exponentiel plafonné, logs d'avertissement limités en fréquence ;</li>
 *   <li>idempotence : une action déjà traitée (même {@code action_id}) n'est jamais ré-exécutée,
 *       seul son résultat mémorisé est renvoyé.</li>
 * </ul>
 */
public final class AgentLoop {

    private static final Duration LOG_THROTTLE = Duration.ofSeconds(60);

    private final Logger logger;
    private final AgentConfig config;
    private final HeartbeatPayload heartbeatPayload;
    private final AgentActionExecutor executor;
    private final ProcessedActionCache processed;
    private final PlugAdminTransport client;
    private final Supplier<Instant> clock;

    private boolean firstHeartbeatLogged;
    private boolean firstHeartbeatConfirmed;
    private Instant heartbeatNextAllowed = Instant.MIN;
    private Instant pollNextAllowed = Instant.MIN;
    private int heartbeatFailures;
    private int pollFailures;
    private Instant lastHeartbeatWarn = Instant.EPOCH;
    private Instant lastPollWarn = Instant.EPOCH;

    public AgentLoop(Logger logger, AgentConfig config, HeartbeatPayload heartbeatPayload,
                     AgentActionExecutor executor, ProcessedActionCache processed, PlugAdminTransport client) {
        this(logger, config, heartbeatPayload, executor, processed, client, Instant::now);
    }

    /** Constructeur injectable (tests) : horloge explicite. */
    public AgentLoop(Logger logger, AgentConfig config, HeartbeatPayload heartbeatPayload,
                     AgentActionExecutor executor, ProcessedActionCache processed, PlugAdminTransport client,
                     Supplier<Instant> clock) {
        this.logger = logger;
        this.config = config;
        this.heartbeatPayload = heartbeatPayload;
        this.executor = executor;
        this.processed = processed;
        this.client = client;
        this.clock = clock;
    }

    // ---- Heartbeat ------------------------------------------------------------------

    public void heartbeatTick() {
        if (clock.get().isBefore(heartbeatNextAllowed)) {
            return;
        }
        try {
            int status = client.sendHeartbeat(heartbeatPayload.build(config));
            if (status == 200 || status == 202 || status == 204) {
                heartbeatFailures = 0;
                heartbeatNextAllowed = Instant.MIN;
                logFirstHeartbeat(true, "HTTP " + status);
            } else if (status == 401 || status == 403) {
                backoffHeartbeat();
                warnThrottled(true, "Heartbeat refusé (HTTP " + status + ") — vérifier le jeton de l'agent.");
            } else {
                backoffHeartbeat();
                warnThrottled(true, "Heartbeat : PlugAdmin a répondu HTTP " + status + ".");
            }
        } catch (PlugAdminUnavailableException e) {
            backoffHeartbeat();
            warnThrottled(true, e.getMessage());
            logFirstHeartbeat(false, e.getMessage());
        } catch (RuntimeException e) {
            backoffHeartbeat();
            logger.warn("Agent PlugAdmin : erreur inattendue pendant le heartbeat ({}).", e.getClass().getSimpleName());
        }
    }

    // ---- File d'actions -----------------------------------------------------------

    public void pollTick() {
        if (clock.get().isBefore(pollNextAllowed)) {
            return;
        }
        List<AgentAction> actions;
        try {
            actions = client.fetchActions();
        } catch (PlugAdminUnavailableException e) {
            backoffPoll();
            warnThrottled(false, "Relevé d'actions : " + e.getMessage());
            return;
        } catch (RuntimeException e) {
            backoffPoll();
            logger.warn("Agent PlugAdmin : erreur inattendue pendant le relevé d'actions ({}).", e.getClass().getSimpleName());
            return;
        }
        pollFailures = 0;
        pollNextAllowed = Instant.MIN;
        for (AgentAction action : actions) {
            handleAction(action);
        }
    }

    private void handleAction(AgentAction action) {
        if (action.id() == null || action.id().isBlank()) {
            logger.warn("Agent PlugAdmin : action reçue sans identifiant, ignorée.");
            return;
        }
        Instant now = clock.get();
        AgentActionOutcome outcome = processed.lookup(action.id(), now).orElse(null);
        boolean replay = outcome != null;
        if (!replay) {
            try {
                outcome = executor.execute(action).join();
            } catch (RuntimeException e) {
                outcome = AgentActionOutcome.failed(action.id(), "Échec d'exécution : " + e.getClass().getSimpleName());
            }
            processed.remember(action.id(), outcome, now);
        }
        try {
            int status = client.sendResult(action.id(), outcome.toJson());
            if (status == 200 || status == 202 || status == 204) {
                logger.info("Agent PlugAdmin : action {} {} → {} envoyé.",
                        action.id(), replay ? "(rejeu, non ré-exécutée)" : "exécutée", outcome.status());
            } else {
                warnThrottled(false, "Résultat de l'action " + action.id() + " : HTTP " + status
                        + " (renvoi au prochain relevé).");
            }
        } catch (PlugAdminUnavailableException e) {
            warnThrottled(false, "Résultat de l'action " + action.id() + " : " + e.getMessage()
                    + " (renvoi au prochain relevé).");
        }
    }

    // ---- Backoff + logs ---------------------------------------------------------

    private void logFirstHeartbeat(boolean ok, String detail) {
        if (ok) {
            firstHeartbeatConfirmed = true;
            if (!firstHeartbeatLogged) {
                firstHeartbeatLogged = true;
                logger.info("event=plugadmin_probe status=ok detail=\"{}\" target={} — connectivité HTTPS "
                        + "sortante VeryGames → PlugAdmin CONFIRMÉE.", detail, config.baseUrl());
            }
            return;
        }
        if (!firstHeartbeatLogged) {
            logger.warn("event=plugadmin_probe status=failed detail=\"{}\" target={} — premier heartbeat non "
                    + "abouti (backoff, nouvelle tentative).", detail, config.baseUrl());
        }
    }

    private void backoffHeartbeat() {
        heartbeatFailures = Math.min(heartbeatFailures + 1, 20);
        heartbeatNextAllowed = clock.get().plusSeconds(backoffSeconds(heartbeatFailures));
    }

    private void backoffPoll() {
        pollFailures = Math.min(pollFailures + 1, 20);
        pollNextAllowed = clock.get().plusSeconds(backoffSeconds(pollFailures));
    }

    private long backoffSeconds(int failures) {
        long base = 5L << Math.min(failures - 1, 16); // 5, 10, 20, 40, ...
        return Math.min(config.maxBackoffSeconds(), Math.max(5, base));
    }

    private void warnThrottled(boolean heartbeat, String message) {
        Instant now = clock.get();
        Instant last = heartbeat ? lastHeartbeatWarn : lastPollWarn;
        if (now.isBefore(last.plus(LOG_THROTTLE))) {
            return;
        }
        if (heartbeat) {
            lastHeartbeatWarn = now;
        } else {
            lastPollWarn = now;
        }
        logger.warn("Agent PlugAdmin : {}", message);
    }

    // ---- Accès test --------------------------------------------------------------

    public boolean firstHeartbeatConfirmed() {
        return firstHeartbeatConfirmed;
    }

    public int heartbeatFailures() {
        return heartbeatFailures;
    }

    public int pollFailures() {
        return pollFailures;
    }

    public int processedCacheSize() {
        return processed.size();
    }
}
