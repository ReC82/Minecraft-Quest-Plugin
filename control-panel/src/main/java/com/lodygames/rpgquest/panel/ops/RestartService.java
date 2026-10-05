package com.lodygames.rpgquest.panel.ops;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Redémarrage du serveur piloté depuis PlugAdmin (issue #95, lot 1) : immédiat ou différé, avec
 * annonces, annulation tant que rien n'est exécuté, et <strong>vérification du retour effectif</strong>.
 *
 * <p><strong>Un {@code stop} n'est pas un redémarrage.</strong> C'est l'exigence centrale du ticket,
 * et elle dicte toute la machine à états : on vérifie d'abord que le serveur <em>répond</em> (sinon
 * un {@code stop} ne relancerait rien et on refuse l'opération), puis on arrête, puis on
 * <em>attend la reprise</em> en interrogeant le serveur jusqu'à ce qu'il traite de nouveau une
 * commande. Sans ce retour vérifié, l'opération se termine en {@link Phase#FAILED} avec un message
 * explicite — jamais en « redémarré » optimiste.</p>
 *
 * <p><strong>Single-flight.</strong> Une seule opération vit à la fois ; une seconde demande est
 * refusée avec l'identifiant de celle en cours. C'est aussi la protection contre le double-clic et
 * contre un rejeu de formulaire : le second POST ne déclenche pas un second arrêt.</p>
 *
 * <p><strong>Limite assumée de ce lot</strong> : l'opération vit en mémoire. Si PlugAdmin lui-même
 * redémarre, un redémarrage <em>différé</em> est perdu (il ne s'exécutera pas) — il faut le
 * reprogrammer. Rien n'est jamais exécuté « en retard » au redémarrage du panel, ce qui est le
 * comportement sûr.</p>
 */
public final class RestartService {

    /** Phase de l'opération. {@code SCHEDULED} est la seule phase annulable. */
    public enum Phase {
        /** Aucune opération. */
        IDLE,
        /** Programmée : annonces en cours, rien d'irréversible n'a été fait. */
        SCHEDULED,
        /** Arrêt demandé au serveur. */
        STOPPING,
        /** Arrêt effectif : on attend le retour en ligne. */
        WAITING_BACK,
        /** Serveur revenu et vérifié. */
        DONE,
        /** Échec : arrêt impossible, ou serveur non revenu avant le délai. */
        FAILED,
        /** Annulée avant exécution. */
        CANCELLED;

        public boolean active() {
            return this == SCHEDULED || this == STOPPING || this == WAITING_BACK;
        }

        public boolean terminal() {
            return this == DONE || this == FAILED || this == CANCELLED;
        }
    }

    /**
     * État observable d'une opération de redémarrage.
     *
     * @param id          identifiant d'opération (corrélation audit / UI)
     * @param phase       phase courante
     * @param requestedBy utilisateur PlugAdmin à l'origine
     * @param requestedAt instant de la demande
     * @param executeAt   instant d'exécution prévu ({@code == requestedAt} pour un immédiat)
     * @param finishedAt  instant de fin ({@code null} tant que non terminée)
     * @param detail      dernière information lisible (motif d'échec, étape en cours)
     * @param steps       journal court et ordonné des étapes réellement franchies
     */
    public record Operation(String id, Phase phase, String requestedBy, Instant requestedAt,
                            Instant executeAt, Instant finishedAt, String detail, List<String> steps) {
        public Operation {
            steps = steps == null ? List.of() : List.copyOf(steps);
        }
    }

    /** Résultat d'une demande / annulation. */
    public record Outcome(boolean ok, String message, String operationId) {
        static Outcome refused(String message) {
            return new Outcome(false, message, null);
        }
    }

    /** Ce que le service peut faire ici et maintenant, et pourquoi si la réponse est « rien ». */
    public record Availability(boolean available, String reason) {
        public static Availability yes() {
            return new Availability(true, null);
        }

        public static Availability no(String reason) {
            return new Availability(false, reason);
        }
    }

    /** Diffuseur d'annonce — implémenté côté panel en enfilant l'action agent {@code server.announce}. */
    public interface Announcer {
        void announce(String message);
    }

    /**
     * Témoin indépendant du redémarrage : l'uptime du plugin tel que le heartbeat de l'agent le
     * rapporte. Permet de constater une relance même si aucune sonde RCON n'est tombée pendant la
     * fenêtre d'arrêt. {@link Optional#empty()} = information indisponible (aucun heartbeat) — le
     * service se rabat alors sur la seule sonde RCON.
     */
    public interface RestartWitness {
        Optional<Long> pluginUptimeSeconds();

        /** Témoin inerte : utilisé quand aucun agent n'est configuré. */
        static RestartWitness none() {
            return Optional::empty;
        }
    }

    /**
     * Délais d'annonce avant l'arrêt, du plus lointain au plus proche. Une annonce dont l'instant
     * serait déjà passé n'est simplement pas programmée : prévenir « dans 5 minutes » quand il reste
     * 30 secondes serait faux.
     */
    public static final List<Duration> DEFAULT_ANNOUNCE_OFFSETS =
            List.of(Duration.ofMinutes(5), Duration.ofMinutes(1), Duration.ofSeconds(10));
    /** Délai maximal d'un redémarrage différé : au-delà, ce n'est plus une opération, c'est un oubli. */
    public static final Duration MAX_DELAY = Duration.ofMinutes(30);

    private final RconGateway rcon;
    private final Announcer announcer;
    private final RestartWitness witness;
    private final ScheduledExecutorService scheduler;
    private final Duration returnTimeout;
    private final Duration pollInterval;
    private final Duration stopGrace;
    private final List<Duration> announceOffsets;

    private final Object lock = new Object();
    private Operation operation = new Operation("-", Phase.IDLE, null, null, null, null, null, List.of());
    private final List<ScheduledFuture<?>> scheduled = new ArrayList<>();
    private final List<String> steps = Collections.synchronizedList(new ArrayList<>());

    public RestartService(RconGateway rcon, Announcer announcer, RestartWitness witness,
                          Duration returnTimeout, Duration pollInterval, Duration stopGrace) {
        this(rcon, announcer, witness, Executors.newScheduledThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "plugadmin-restart");
            thread.setDaemon(true);
            return thread;
        }), returnTimeout, pollInterval, stopGrace, DEFAULT_ANNOUNCE_OFFSETS);
    }

    /** Variante d'injection (tests) : ordonnanceur et délais d'annonce fournis. */
    public RestartService(RconGateway rcon, Announcer announcer, RestartWitness witness,
                          ScheduledExecutorService scheduler, Duration returnTimeout,
                          Duration pollInterval, Duration stopGrace, List<Duration> announceOffsets) {
        this.rcon = rcon;
        this.announcer = announcer;
        this.witness = witness == null ? RestartWitness.none() : witness;
        this.scheduler = scheduler;
        this.returnTimeout = returnTimeout;
        this.pollInterval = pollInterval;
        this.stopGrace = stopGrace;
        this.announceOffsets = announceOffsets == null || announceOffsets.isEmpty()
                ? DEFAULT_ANNOUNCE_OFFSETS : List.copyOf(announceOffsets);
    }

    public Availability availability() {
        return rcon.availability();
    }

    /** Opération courante (jamais {@code null} : {@link Phase#IDLE} quand il n'y a rien). */
    public Operation current() {
        synchronized (lock) {
            return operation;
        }
    }

    /**
     * Demande un redémarrage.
     *
     * @param username utilisateur PlugAdmin (audit)
     * @param delay    {@link Duration#ZERO} = immédiat ; sinon le délai avant l'arrêt
     */
    public Outcome request(String username, Duration delay) {
        Availability availability = availability();
        if (!availability.available()) {
            return Outcome.refused(availability.reason());
        }
        Duration wait = delay == null || delay.isNegative() ? Duration.ZERO : delay;
        if (wait.compareTo(MAX_DELAY) > 0) {
            return Outcome.refused("Délai trop long (maximum " + MAX_DELAY.toMinutes() + " minutes).");
        }
        synchronized (lock) {
            if (operation.phase().active()) {
                return Outcome.refused("Une opération de redémarrage est déjà en cours (" + operation.id()
                        + ", phase " + operation.phase() + ").");
            }
            String id = "rst-" + UUID.randomUUID().toString().substring(0, 8);
            Instant now = Instant.now();
            steps.clear();
            scheduled.clear();
            addStep(wait.isZero()
                    ? "Demande de redémarrage immédiat par " + safeUser(username)
                    : "Redémarrage programmé dans " + humanDelay(wait) + " par " + safeUser(username));
            operation = new Operation(id, Phase.SCHEDULED, safeUser(username), now, now.plus(wait),
                    null, wait.isZero() ? "Exécution immédiate." : "Annonces en cours, annulation possible.",
                    snapshotSteps());

            for (Duration offset : announceOffsets) {
                Duration at = wait.minus(offset);
                if (!at.isNegative() && !wait.isZero()) {
                    scheduled.add(scheduler.schedule(() -> announceCountdown(id, offset),
                            at.toMillis(), TimeUnit.MILLISECONDS));
                }
            }
            scheduled.add(scheduler.schedule(() -> execute(id), wait.toMillis(), TimeUnit.MILLISECONDS));
            return new Outcome(true, wait.isZero()
                    ? "Redémarrage immédiat lancé (" + id + ")."
                    : "Redémarrage programmé dans " + humanDelay(wait) + " (" + id + ") — annulable jusqu'à l'arrêt.", id);
        }
    }

    /** Annule l'opération — possible <strong>uniquement</strong> tant qu'elle est {@link Phase#SCHEDULED}. */
    public Outcome cancel(String username, String operationId) {
        synchronized (lock) {
            if (!operation.phase().active()) {
                return Outcome.refused("Aucune opération de redémarrage en cours.");
            }
            if (operationId != null && !operationId.isBlank() && !operationId.equals(operation.id())) {
                return Outcome.refused("Cette opération n'est plus celle en cours — recharger la page.");
            }
            if (operation.phase() != Phase.SCHEDULED) {
                return Outcome.refused("Trop tard : l'arrêt est déjà en cours (phase " + operation.phase()
                        + "). Le serveur reviendra de lui-même.");
            }
            for (ScheduledFuture<?> future : scheduled) {
                future.cancel(false);
            }
            scheduled.clear();
            addStep("Annulé par " + safeUser(username) + " avant tout arrêt");
            operation = new Operation(operation.id(), Phase.CANCELLED, operation.requestedBy(),
                    operation.requestedAt(), operation.executeAt(), Instant.now(),
                    "Annulé avant exécution : le serveur n'a pas été touché.", snapshotSteps());
            announcer.announce("Redémarrage annulé.");
            return new Outcome(true, "Redémarrage annulé — le serveur n'a pas été touché.", operation.id());
        }
    }

    // ---- Exécution ------------------------------------------------------------------------

    private void announceCountdown(String operationId, Duration remaining) {
        synchronized (lock) {
            if (!operation.id().equals(operationId) || operation.phase() != Phase.SCHEDULED) {
                return; // annulée entre-temps : surtout ne rien annoncer
            }
            addStep("Annonce T-" + humanDelay(remaining));
            operation = withSteps(operation);
        }
        announcer.announce("Redémarrage du serveur dans " + humanDelay(remaining) + ".");
    }

    private void execute(String operationId) {
        synchronized (lock) {
            if (!operation.id().equals(operationId) || operation.phase() != Phase.SCHEDULED) {
                return; // annulée
            }
            operation = phase(Phase.STOPPING, "Vérification du serveur avant arrêt.");
        }
        // 1. Le serveur répond-il ? Arrêter un serveur déjà injoignable ne le relancerait pas :
        //    ce serait un « stop » présenté comme un redémarrage, exactement ce qu'il faut éviter.
        try {
            rcon.run(RconCommand.LIST);
            addStep("Serveur joignable avant l'arrêt");
        } catch (RconClient.RconException e) {
            fail("Serveur déjà injoignable avant l'arrêt : aucun arrêt demandé (" + e.getMessage()
                    + "). Un « stop » n'aurait rien relancé.");
            return;
        }
        // 2. Sauvegarde best-effort. Un échec ici n'empêche pas l'arrêt, mais il est tracé.
        try {
            rcon.run(RconCommand.SAVE_ALL);
            addStep("save-all demandé (sauvegarde best-effort, pas un point de restauration)");
        } catch (RconClient.RconException e) {
            addStep("save-all en échec (" + e.getMessage() + ") — arrêt poursuivi");
        }
        // 3. Arrêt.
        try {
            rcon.run(RconCommand.STOP);
            addStep("stop demandé");
        } catch (RconClient.RconException e) {
            // Un « stop » coupe souvent la connexion avant la réponse : ce n'est pas un échec en soi.
            addStep("stop émis, réponse non lue (" + e.getMessage() + ")");
        }
        synchronized (lock) {
            operation = phase(Phase.WAITING_BACK, "Arrêt demandé — attente du retour en ligne.");
        }
        waitForReturn();
    }

    /**
     * Attend que le serveur <strong>traite de nouveau une commande</strong>. Ni l'absence de
     * réponse, ni le simple fait d'avoir envoyé {@code stop} ne prouvent un redémarrage.
     *
     * <p>Deux preuves sont acceptées, et il en faut au moins une :</p>
     * <ul>
     *   <li>le serveur a été <strong>vu hors ligne</strong> puis répond de nouveau ;</li>
     *   <li>l'<strong>uptime du plugin a diminué</strong> (relevé par le heartbeat de l'agent) : le
     *       processus a donc bien redémarré, même si aucune sonde n'est tombée pendant la fenêtre
     *       d'arrêt — une relance en moins de temps que l'intervalle de sonde est possible.</li>
     * </ul>
     *
     * <p>Sans preuve, l'opération échoue en disant exactement cela, plutôt que d'annoncer un
     * redémarrage non constaté.</p>
     */
    private void waitForReturn() {
        Instant start = Instant.now();
        Instant deadline = start.plus(returnTimeout);
        Optional<Long> uptimeBefore = witness.pluginUptimeSeconds();
        boolean sawOffline = false;
        boolean graceWarned = false;
        while (Instant.now().isBefore(deadline)) {
            sleep(pollInterval);
            try {
                String response = rcon.run(RconCommand.LIST);
                if (sawOffline) {
                    addStep("Serveur de nouveau en ligne et répondant");
                    succeed(response);
                    return;
                }
                if (uptimeDropped(uptimeBefore)) {
                    addStep("Relance constatée : l'uptime du plugin a diminué");
                    succeed(response);
                    return;
                }
                if (!graceWarned && Instant.now().isAfter(start.plus(stopGrace))) {
                    graceWarned = true;
                    addStep("Toujours joignable après " + stopGrace.toSeconds()
                            + " s — l'arrêt ne s'est peut-être pas produit");
                }
            } catch (RconClient.RconException e) {
                if (!sawOffline) {
                    sawOffline = true;
                    addStep("Serveur hors ligne (arrêt effectif constaté)");
                    synchronized (lock) {
                        operation = phase(Phase.WAITING_BACK, "Arrêt constaté — attente de la relance.");
                    }
                }
            }
        }
        if (sawOffline) {
            fail("Le serveur s'est bien arrêté mais n'est pas revenu en ligne dans le délai imparti ("
                    + returnTimeout.toSeconds() + " s). Vérifier le panel de l'hébergeur.");
        } else {
            fail("Aucun redémarrage constaté : le serveur n'a jamais été vu hors ligne et l'uptime du "
                    + "plugin n'a pas diminué. Un « stop » a pu échouer — vérifier l'état réel du serveur.");
        }
    }

    /** {@code true} si l'uptime relevé est maintenant <strong>inférieur</strong> à celui d'avant l'arrêt. */
    private boolean uptimeDropped(Optional<Long> before) {
        if (before.isEmpty()) {
            return false;
        }
        return witness.pluginUptimeSeconds().filter(now -> now < before.get()).isPresent();
    }

    private void succeed(String listResponse) {
        synchronized (lock) {
            operation = new Operation(operation.id(), Phase.DONE, operation.requestedBy(), operation.requestedAt(),
                    operation.executeAt(), Instant.now(),
                    "Serveur revenu en ligne et vérifié. " + summarize(listResponse), snapshotSteps());
        }
    }

    private void fail(String reason) {
        addStep("Échec : " + reason);
        synchronized (lock) {
            operation = new Operation(operation.id(), Phase.FAILED, operation.requestedBy(), operation.requestedAt(),
                    operation.executeAt(), Instant.now(), reason, snapshotSteps());
        }
    }

    private Operation phase(Phase next, String detail) {
        return new Operation(operation.id(), next, operation.requestedBy(), operation.requestedAt(),
                operation.executeAt(), null, detail, snapshotSteps());
    }

    private Operation withSteps(Operation base) {
        return new Operation(base.id(), base.phase(), base.requestedBy(), base.requestedAt(),
                base.executeAt(), base.finishedAt(), base.detail(), snapshotSteps());
    }

    private void addStep(String step) {
        if (steps.size() < 40) {
            steps.add(Instant.now().toString() + " — " + step);
        }
    }

    private List<String> snapshotSteps() {
        synchronized (steps) {
            return List.copyOf(steps);
        }
    }

    private void sleep(Duration duration) {
        try {
            Thread.sleep(Math.max(1, duration.toMillis()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Arrête l'ordonnanceur (appelé à l'arrêt du panel). N'annule pas un serveur déjà en train de revenir. */
    public void shutdown() {
        scheduler.shutdownNow();
    }

    private static String summarize(String listResponse) {
        if (listResponse == null || listResponse.isBlank()) {
            return "";
        }
        String oneLine = listResponse.replace('\n', ' ').trim();
        return oneLine.length() > 160 ? oneLine.substring(0, 160) + "…" : oneLine;
    }

    private static String safeUser(String username) {
        if (username == null || username.isBlank()) {
            return "?";
        }
        String t = username.trim();
        return t.length() > 40 ? t.substring(0, 40) : t;
    }

    /** « 5 minutes », « 1 minute », « 10 secondes » — jamais une durée ISO dans une annonce joueur. */
    public static String humanDelay(Duration duration) {
        long seconds = Math.max(0, duration.toSeconds());
        if (seconds % 60 == 0 && seconds >= 60) {
            long minutes = seconds / 60;
            return minutes + (minutes > 1 ? " minutes" : " minute");
        }
        return seconds + (seconds > 1 ? " secondes" : " seconde");
    }

    /** Accès RCON abstrait : implémentation réelle ou double de test. */
    public interface RconGateway {
        Availability availability();

        String run(RconCommand command) throws RconClient.RconException;
    }

    /** Implémentation réelle : une session courte par commande (pas de connexion maintenue). */
    public static final class ConfiguredRconGateway implements RconGateway {

        private final Optional<RconEndpoint> endpoint;
        private final Duration timeout;
        private final String unavailableReason;

        public ConfiguredRconGateway(Optional<RconEndpoint> endpoint, Duration timeout, String unavailableReason) {
            this.endpoint = endpoint;
            this.timeout = timeout;
            this.unavailableReason = unavailableReason;
        }

        @Override
        public Availability availability() {
            return endpoint.filter(RconEndpoint::usable).isPresent()
                    ? Availability.yes() : Availability.no(unavailableReason);
        }

        @Override
        public String run(RconCommand command) throws RconClient.RconException {
            RconEndpoint target = endpoint.filter(RconEndpoint::usable)
                    .orElseThrow(() -> new RconClient.RconException(unavailableReason, null));
            try (RconClient client = RconClient.connect(target.host(), target.port(), target.password(), timeout)) {
                return client.execute(command);
            }
        }
    }

    /**
     * Point d'accès RCON d'une cible. Le mot de passe vient de l'environnement du service, jamais du
     * dépôt ni d'une page.
     */
    public record RconEndpoint(String targetId, String host, int port, String password) {
        public boolean usable() {
            return host != null && !host.isBlank() && port > 0 && port < 65_536
                    && password != null && !password.isBlank();
        }

        @Override
        public String toString() {
            // Jamais le mot de passe, même par accident dans un log de debug.
            return "RconEndpoint[" + targetId + " " + host + ":" + port + "]";
        }
    }

    /** Normalise un identifiant de cible pour dériver un nom de variable d'environnement. */
    public static String envSuffix(String targetId) {
        return targetId == null ? "" : targetId.toUpperCase(Locale.ROOT).replace('-', '_');
    }
}
