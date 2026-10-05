package com.lodygames.rpgquest.panel.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Issue #95 — l'exigence centrale du ticket est négative : <strong>un {@code stop} n'est pas un
 * redémarrage</strong>. Ces tests vérifient donc surtout ce que le service <em>refuse</em> de
 * conclure : pas de serveur joignable avant l'arrêt → aucun arrêt demandé ; serveur jamais vu hors
 * ligne et uptime inchangé → échec, pas « redémarré ».
 */
class RestartServiceTest {

    private static final Duration FAST_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration FAST_POLL = Duration.ofMillis(10);
    private static final Duration FAST_GRACE = Duration.ofMillis(20);

    private ScheduledExecutorService scheduler;
    private RestartService service;

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.shutdown();
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    /** Passerelle RCON scriptable : on décide quelles sondes répondent, et dans quel ordre. */
    private static final class FakeGateway implements RestartService.RconGateway {
        final List<RconCommand> calls = new CopyOnWriteArrayList<>();
        volatile boolean available = true;
        volatile String unavailableReason = "RCON non configuré.";
        /** Nombre d'appels {@code LIST} qui répondent avant de simuler l'arrêt. */
        volatile int listOkBeforeDown = 1;
        /** Nombre d'appels {@code LIST} en échec (serveur arrêté) avant le retour en ligne. */
        volatile int listDownCount = 2;
        volatile boolean neverComesBack = false;
        volatile boolean stayAlwaysUp = false;
        volatile boolean saveAllFails = false;
        private final AtomicLong listCalls = new AtomicLong();

        @Override
        public RestartService.Availability availability() {
            return available ? RestartService.Availability.yes()
                    : RestartService.Availability.no(unavailableReason);
        }

        @Override
        public String run(RconCommand command) throws RconClient.RconException {
            calls.add(command);
            if (command == RconCommand.SAVE_ALL && saveAllFails) {
                throw new RconClient.RconException("save-all refusé", null);
            }
            if (command != RconCommand.LIST) {
                return "ok";
            }
            long n = listCalls.incrementAndGet();
            if (stayAlwaysUp) {
                return "There are 0 of a max of 20 players online: ";
            }
            if (n <= listOkBeforeDown) {
                return "There are 1 of a max of 20 players online: LoDyMcFly";
            }
            if (neverComesBack || n <= listOkBeforeDown + listDownCount) {
                throw new RconClient.RconException("serveur injoignable", null);
            }
            return "There are 0 of a max of 20 players online: ";
        }
    }

    private RestartService build(FakeGateway gateway, List<String> announces,
                                 RestartService.RestartWitness witness) {
        return build(gateway, announces, witness, RestartService.DEFAULT_ANNOUNCE_OFFSETS);
    }

    /**
     * Variante à décalages d'annonce réduits : tester le compte à rebours réel exigerait d'attendre
     * dix secondes de temps mural. Les décalages sont injectables pour cette raison, et parce qu'un
     * exploitant peut légitimement vouloir les régler.
     */
    private RestartService build(FakeGateway gateway, List<String> announces,
                                 RestartService.RestartWitness witness, List<Duration> offsets) {
        scheduler = Executors.newScheduledThreadPool(2);
        service = new RestartService(gateway, announces::add, witness, scheduler,
                FAST_TIMEOUT, FAST_POLL, FAST_GRACE, offsets);
        return service;
    }

    private void await(BooleanSupplier condition, String what) throws Exception {
        for (int i = 0; i < 600 && !condition.getAsBoolean(); i++) {
            Thread.sleep(10);
        }
        assertTrue(condition.getAsBoolean(), () -> "condition jamais atteinte : " + what);
    }

    private void awaitPhase(RestartService.Phase phase) throws Exception {
        await(() -> service.current().phase() == phase, "phase " + phase
                + " (vue : " + service.current().phase() + " / " + service.current().detail() + ")");
    }

    // ---- Disponibilité -------------------------------------------------------------------

    @Test
    void withoutRconTheServiceSaysSoAndRefusesEverything() {
        FakeGateway gateway = new FakeGateway();
        gateway.available = false;
        gateway.unavailableReason = "Aucun accès RCON configuré pour la cible « dev ».";
        build(gateway, new ArrayList<>(), RestartService.RestartWitness.none());

        assertFalse(service.availability().available());
        assertEquals(gateway.unavailableReason, service.availability().reason());

        RestartService.Outcome outcome = service.request("alice", Duration.ZERO);
        assertFalse(outcome.ok());
        assertEquals(gateway.unavailableReason, outcome.message());
        assertEquals(RestartService.Phase.IDLE, service.current().phase());
        assertTrue(gateway.calls.isEmpty(), "aucune commande ne doit partir");
    }

    @Test
    void anIdleServiceHasNoOperation() {
        build(new FakeGateway(), new ArrayList<>(), RestartService.RestartWitness.none());

        assertEquals(RestartService.Phase.IDLE, service.current().phase());
        assertFalse(service.current().phase().active());
    }

    // ---- Redémarrage immédiat, chemin vérifié --------------------------------------------

    @Test
    void anImmediateRestartStopsThenWaitsForAVerifiedReturn() throws Exception {
        FakeGateway gateway = new FakeGateway();
        build(gateway, new ArrayList<>(), RestartService.RestartWitness.none());

        assertTrue(service.request("alice", Duration.ZERO).ok());
        awaitPhase(RestartService.Phase.DONE);

        // L'ordre compte : vérifier, sauvegarder, arrêter, puis sonder jusqu'au retour.
        assertEquals(RconCommand.LIST, gateway.calls.get(0));
        assertEquals(RconCommand.SAVE_ALL, gateway.calls.get(1));
        assertEquals(RconCommand.STOP, gateway.calls.get(2));
        assertTrue(gateway.calls.size() > 3, "des sondes de retour doivent suivre l'arrêt");
        List<String> steps = service.current().steps();
        assertTrue(steps.stream().anyMatch(st -> st.contains("arrêt effectif constaté")), () -> steps.toString());
        assertTrue(steps.stream().anyMatch(st -> st.contains("de nouveau en ligne")), () -> steps.toString());
    }

    @Test
    void aFailingSaveAllDoesNotPreventTheRestartButIsRecorded() throws Exception {
        FakeGateway gateway = new FakeGateway();
        gateway.saveAllFails = true;
        build(gateway, new ArrayList<>(), RestartService.RestartWitness.none());

        assertTrue(service.request("alice", Duration.ZERO).ok());
        awaitPhase(RestartService.Phase.DONE);

        assertTrue(gateway.calls.contains(RconCommand.STOP), "l'arrêt doit quand même être demandé");
        assertTrue(service.current().steps().stream().anyMatch(st -> st.contains("save-all en échec")),
                () -> service.current().steps().toString());
    }

    // ---- Les refus qui comptent -----------------------------------------------------------

    @Test
    void anUnreachableServerIsNeverStoppedBecauseAStopWouldRelaunchNothing() throws Exception {
        FakeGateway gateway = new FakeGateway();
        gateway.listOkBeforeDown = 0; // la toute première sonde échoue déjà
        gateway.neverComesBack = true;
        build(gateway, new ArrayList<>(), RestartService.RestartWitness.none());

        assertTrue(service.request("alice", Duration.ZERO).ok(), "la demande est acceptée…");
        awaitPhase(RestartService.Phase.FAILED); // … mais l'exécution refuse d'agir

        assertFalse(gateway.calls.contains(RconCommand.STOP),
                "arrêter un serveur déjà injoignable serait un « stop » déguisé en redémarrage");
        assertTrue(service.current().detail().contains("déjà injoignable"), service.current().detail());
    }

    @Test
    void aServerThatNeverGoesOfflineIsReportedAsNotRestarted() throws Exception {
        FakeGateway gateway = new FakeGateway();
        gateway.stayAlwaysUp = true; // le stop n'a aucun effet observable
        build(gateway, new ArrayList<>(), RestartService.RestartWitness.none());

        service.request("alice", Duration.ZERO);
        awaitPhase(RestartService.Phase.FAILED);

        // Le cœur du ticket : sans preuve, on ne conclut pas au redémarrage.
        assertTrue(service.current().detail().contains("Aucun redémarrage constaté"),
                service.current().detail());
        assertTrue(service.current().steps().stream().anyMatch(st -> st.contains("Toujours joignable")),
                () -> service.current().steps().toString());
    }

    @Test
    void aServerThatStopsButNeverReturnsIsAFailureWithTheRightReason() throws Exception {
        FakeGateway gateway = new FakeGateway();
        gateway.neverComesBack = true;
        build(gateway, new ArrayList<>(), RestartService.RestartWitness.none());

        service.request("alice", Duration.ZERO);
        awaitPhase(RestartService.Phase.FAILED);

        assertTrue(gateway.calls.contains(RconCommand.STOP));
        assertTrue(service.current().detail().contains("n'est pas revenu en ligne"),
                service.current().detail());
    }

    @Test
    void aDroppingUptimeProvesTheRestartEvenIfNoProbeEverFailed() throws Exception {
        FakeGateway gateway = new FakeGateway();
        gateway.stayAlwaysUp = true; // aucune sonde ne tombe : relance plus rapide que l'intervalle
        AtomicReference<Long> uptime = new AtomicReference<>(5_000L);
        build(gateway, new ArrayList<>(), () -> Optional.ofNullable(uptime.get()));

        service.request("alice", Duration.ZERO);
        await(() -> gateway.calls.contains(RconCommand.STOP), "arrêt demandé");
        uptime.set(3L); // le plugin vient de redémarrer

        awaitPhase(RestartService.Phase.DONE);
        assertTrue(service.current().steps().stream().anyMatch(st -> st.contains("uptime du plugin a diminué")),
                () -> service.current().steps().toString());
    }

    @Test
    void anUnknownUptimeNeverCountsAsProof() throws Exception {
        FakeGateway gateway = new FakeGateway();
        gateway.stayAlwaysUp = true;
        build(gateway, new ArrayList<>(), RestartService.RestartWitness.none());

        service.request("alice", Duration.ZERO);
        awaitPhase(RestartService.Phase.FAILED);

        assertTrue(service.current().detail().contains("Aucun redémarrage constaté"));
    }

    // ---- Single-flight et double-clic -----------------------------------------------------

    @Test
    void aSecondRequestIsRefusedWhileAnOperationIsActive() {
        FakeGateway gateway = new FakeGateway();
        build(gateway, new ArrayList<>(), RestartService.RestartWitness.none());

        RestartService.Outcome first = service.request("alice", Duration.ofMinutes(5));
        RestartService.Outcome second = service.request("alice", Duration.ofMinutes(5));

        assertTrue(first.ok());
        assertFalse(second.ok(), "un double-clic ne doit pas lancer deux arrêts");
        assertTrue(second.message().contains(first.operationId()), second.message());
    }

    @Test
    void aNewRequestIsAcceptedOnceTheOperationIsOver() throws Exception {
        FakeGateway gateway = new FakeGateway();
        build(gateway, new ArrayList<>(), RestartService.RestartWitness.none());

        service.request("alice", Duration.ZERO);
        awaitPhase(RestartService.Phase.DONE);

        assertTrue(service.request("alice", Duration.ZERO).ok());
    }

    @Test
    void anExcessiveDelayIsRefused() {
        build(new FakeGateway(), new ArrayList<>(), RestartService.RestartWitness.none());

        RestartService.Outcome outcome = service.request("alice", RestartService.MAX_DELAY.plusMinutes(1));

        assertFalse(outcome.ok());
        assertTrue(outcome.message().contains("Délai trop long"), outcome.message());
    }

    // ---- Annulation -----------------------------------------------------------------------

    @Test
    void aScheduledRestartCanBeCancelledAndNothingIsTouched() {
        FakeGateway gateway = new FakeGateway();
        List<String> announces = new CopyOnWriteArrayList<>();
        build(gateway, announces, RestartService.RestartWitness.none());
        String id = service.request("alice", Duration.ofMinutes(10)).operationId();

        RestartService.Outcome outcome = service.cancel("bob", id);

        assertTrue(outcome.ok());
        assertEquals(RestartService.Phase.CANCELLED, service.current().phase());
        assertTrue(gateway.calls.isEmpty(), "aucune commande ne doit avoir été émise");
        assertTrue(announces.stream().anyMatch(a -> a.contains("annulé")), announces.toString());
        assertTrue(service.current().steps().stream().anyMatch(st -> st.contains("Annulé par bob")),
                () -> service.current().steps().toString());
    }

    @Test
    void cancellingWithTheWrongOperationIdIsRefused() {
        build(new FakeGateway(), new ArrayList<>(), RestartService.RestartWitness.none());
        service.request("alice", Duration.ofMinutes(10));

        RestartService.Outcome outcome = service.cancel("bob", "rst-inexistant");

        assertFalse(outcome.ok());
        assertTrue(outcome.message().contains("plus celle en cours"), outcome.message());
        assertEquals(RestartService.Phase.SCHEDULED, service.current().phase());
    }

    @Test
    void cancellingWhenNothingIsRunningIsRefused() {
        build(new FakeGateway(), new ArrayList<>(), RestartService.RestartWitness.none());

        assertFalse(service.cancel("bob", "").ok());
    }

    @Test
    void cancellingAfterTheStopStartedIsRefusedWithAnHonestMessage() throws Exception {
        FakeGateway gateway = new FakeGateway();
        gateway.neverComesBack = true; // l'opération reste longtemps en attente de retour
        build(gateway, new ArrayList<>(), RestartService.RestartWitness.none());
        String id = service.request("alice", Duration.ZERO).operationId();
        await(() -> gateway.calls.contains(RconCommand.STOP), "arrêt lancé");

        RestartService.Outcome outcome = service.cancel("bob", id);

        // On ne peut pas « dé-arrêter » un serveur : le dire vaut mieux que faire semblant.
        assertFalse(outcome.ok());
        assertTrue(outcome.message().contains("Trop tard"), outcome.message());
    }

    // ---- Annonces du compte à rebours -----------------------------------------------------

    @Test
    void aDeferredRestartAnnouncesTheCountdownInOrderThenExecutes() throws Exception {
        FakeGateway gateway = new FakeGateway();
        List<String> announces = new CopyOnWriteArrayList<>();
        // Décalages réduits : T-400 ms puis T-200 ms avant un arrêt à T+600 ms.
        build(gateway, announces, RestartService.RestartWitness.none(),
                List.of(Duration.ofMillis(400), Duration.ofMillis(200)));

        assertTrue(service.request("alice", Duration.ofMillis(600)).ok());
        awaitPhase(RestartService.Phase.DONE);

        // Les deux annonces doivent être parties, dans l'ordre du compte à rebours.
        assertEquals(2, announces.size(), announces.toString());
        assertTrue(announces.get(0).contains("0 seconde") || announces.get(0).contains("seconde"),
                announces.toString());
        assertTrue(service.current().steps().stream().anyMatch(st -> st.startsWith("20")
                && st.contains("Annonce T-")), () -> service.current().steps().toString());
    }

    @Test
    void anAnnouncementWhoseMomentHasPassedIsNeverSent() throws Exception {
        FakeGateway gateway = new FakeGateway();
        List<String> announces = new CopyOnWriteArrayList<>();
        // Décalage de 5 s pour un redémarrage dans 200 ms : l'instant T-5 s est déjà passé.
        build(gateway, announces, RestartService.RestartWitness.none(),
                List.of(Duration.ofSeconds(5), Duration.ofMillis(100)));

        service.request("alice", Duration.ofMillis(200));
        awaitPhase(RestartService.Phase.DONE);

        // Annoncer « dans 5 secondes » alors qu'il reste 200 ms serait faux.
        assertTrue(announces.stream().noneMatch(a -> a.contains("5 secondes")), announces.toString());
        assertEquals(1, announces.size(), announces.toString());
    }

    @Test
    void anImmediateRestartAnnouncesNothingBecauseThereIsNoTimeToWarn() throws Exception {
        FakeGateway gateway = new FakeGateway();
        List<String> announces = new CopyOnWriteArrayList<>();
        build(gateway, announces, RestartService.RestartWitness.none());

        service.request("alice", Duration.ZERO);
        awaitPhase(RestartService.Phase.DONE);

        assertTrue(announces.isEmpty(),
                () -> "une annonce « dans 0 seconde » n'aide personne : " + announces);
    }

    @Test
    void aCancelledOperationNeverAnnouncesItsCountdownAfterwards() throws Exception {
        FakeGateway gateway = new FakeGateway();
        List<String> announces = new CopyOnWriteArrayList<>();
        build(gateway, announces, RestartService.RestartWitness.none(),
                List.of(Duration.ofMillis(200)));
        String id = service.request("alice", Duration.ofMillis(400)).operationId();

        service.cancel("bob", id);
        Thread.sleep(400);

        // Seule l'annonce d'annulation est acceptable ; annoncer un compte à rebours après coup
        // serait un mensonge envoyé à tous les joueurs.
        assertTrue(announces.stream().noneMatch(a -> a.contains("Redémarrage du serveur dans")),
                announces.toString());
        assertTrue(gateway.calls.isEmpty(), "et surtout : rien ne doit avoir été exécuté");
    }

    // ---- Formatage humain -----------------------------------------------------------------

    @Test
    void delaysAreWrittenForPlayersNotForMachines() {
        assertEquals("5 minutes", RestartService.humanDelay(Duration.ofMinutes(5)));
        assertEquals("1 minute", RestartService.humanDelay(Duration.ofMinutes(1)));
        assertEquals("10 secondes", RestartService.humanDelay(Duration.ofSeconds(10)));
        assertEquals("1 seconde", RestartService.humanDelay(Duration.ofSeconds(1)));
        assertEquals("0 seconde", RestartService.humanDelay(Duration.ZERO));
    }

    @Test
    void anEnvironmentSuffixIsDerivedFromTheTargetId() {
        assertEquals("DEV", RestartService.envSuffix("dev"));
        assertEquals("RPGQUEST_STAGING", RestartService.envSuffix("rpgquest-staging"));
        assertEquals("", RestartService.envSuffix(null));
    }

    @Test
    void anEndpointIsUsableOnlyWhenEveryPieceIsPresentAndNeverPrintsItsPassword() {
        RestartService.RconEndpoint complete =
                new RestartService.RconEndpoint("dev", "example.test", 25575, "s3cret");
        assertTrue(complete.usable());
        assertFalse(complete.toString().contains("s3cret"),
                "un mot de passe ne doit jamais fuir, même dans un log de debug");

        assertFalse(new RestartService.RconEndpoint("dev", "", 25575, "s3cret").usable());
        assertFalse(new RestartService.RconEndpoint("dev", "h", 0, "s3cret").usable());
        assertFalse(new RestartService.RconEndpoint("dev", "h", 25575, "").usable());
        assertFalse(new RestartService.RconEndpoint("dev", "h", 25575, null).usable());
    }

    @Test
    void aGatewayWithoutEndpointFailsWithItsConfiguredReasonInsteadOfAnException() {
        RestartService.ConfiguredRconGateway gateway = new RestartService.ConfiguredRconGateway(
                Optional.empty(), Duration.ofSeconds(1), "Motif exact à afficher.");

        assertFalse(gateway.availability().available());
        assertEquals("Motif exact à afficher.", gateway.availability().reason());
        RconClient.RconException thrown = org.junit.jupiter.api.Assertions.assertThrows(
                RconClient.RconException.class, () -> gateway.run(RconCommand.LIST));
        assertEquals("Motif exact à afficher.", thrown.getMessage());
    }
}
