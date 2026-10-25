package com.lodygames.rpgquest.claim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.config.ClaimConfig;
import com.lodygames.rpgquest.config.ConfigService;
import com.lodygames.rpgquest.database.ClaimRepository;
import com.lodygames.rpgquest.database.DatabaseManager;
import com.lodygames.rpgquest.database.PlayerProfileRepository;
import com.lodygames.rpgquest.database.PlayerVariableRepository;
import com.lodygames.rpgquest.database.ProgressionRepository;
import com.lodygames.rpgquest.item.RpgItemKeys;
import com.lodygames.rpgquest.item.YamlCustomItemRegistry;
import com.lodygames.rpgquest.progression.ProgressionService;
import com.lodygames.rpgquest.zone.ZoneRegistry;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * Issues #21/#22/#23 : {@link ClaimWorldSafetyListener} garantit qu'un joueur légitimement présent
 * dans le monde des claims a toujours une Pierre de retour (retour Hub sans commande) et qu'un
 * joueur non éligible ne peut jamais y rester coincé (renvoyé au village).
 */
class ClaimWorldSafetyListenerTest {

    private static final long TIMEOUT_SECONDS = 5;
    private static final ClaimConfig CLAIMS_CONFIG = new ClaimConfig(64, 384, 3, 16, "claims", true);

    @TempDir
    Path tempDir;

    private ServerMock server;
    /** Issue #35 : mode bypass explicite, fermé par défaut. */
    private final com.lodygames.rpgquest.permission.AdminBypassMode bypass =
            new com.lodygames.rpgquest.permission.AdminBypassMode(() -> com.lodygames.rpgquest.settings.GameSettings.DEFAULTS);
    private RPGQuestPlugin plugin;
    private DatabaseManager database;
    private PlayerProfileRepository profileRepository;
    private PlayerVariableRepository variableRepository;
    private YamlCustomItemRegistry customItemRegistry;
    private ClaimService claimService;
    private ClaimWorldSafetyListener listener;
    private World hubWorld;
    private World claimsWorld;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(RPGQuestPlugin.class);
        hubWorld = server.addSimpleWorld("world_hub");
        claimsWorld = server.addSimpleWorld("claims");

        database = new DatabaseManager(tempDir.resolve("test.db"));
        database.initialize().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        profileRepository = new PlayerProfileRepository(database);
        variableRepository = new PlayerVariableRepository(database);
        ClaimRepository claimRepository = new ClaimRepository(database);
        ZoneRegistry zoneRegistry = new ZoneRegistry(tempDir.resolve("zones"), plugin.getSLF4JLogger());
        zoneRegistry.start();
        com.lodygames.rpgquest.travel.YamlPortalRegistry portalRegistry =
                new com.lodygames.rpgquest.travel.YamlPortalRegistry(tempDir.resolve("portals"), plugin.getSLF4JLogger());
        portalRegistry.start();
        ConfigService configService = new ConfigService(plugin);
        configService.start();
        ProgressionService progressionService = new ProgressionService(
                plugin, new ProgressionRepository(database), () -> configService.current().progression(), plugin.getSLF4JLogger());
        progressionService.start();
        customItemRegistry = new YamlCustomItemRegistry(tempDir.resolve("items"), plugin.getSLF4JLogger());
        customItemRegistry.start();
        claimService = new ClaimService(plugin, claimRepository, zoneRegistry, portalRegistry, configService,
                progressionService, variableRepository);
        claimService.start();
        // Réchauffe l'exécuteur SQLite (première requête notablement plus lente) pour que les
        // contrôles asynchrones du listener s'achèvent dans la fenêtre de pumpUntil.
        variableRepository.get(new java.util.UUID(0, 0), "warmup").get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        ClaimReturnService returnService = new ClaimReturnService(plugin, customItemRegistry,
                () -> Optional.of(new Location(hubWorld, 0, 64, 0)));
        listener = new ClaimWorldSafetyListener(plugin, claimService, returnService, () -> CLAIMS_CONFIG,
                () -> Optional.of(new Location(hubWorld, 0, 64, 0)), bypass);
    }

    @AfterEach
    void tearDown() {
        claimService.stop();
        database.shutdown();
        MockBukkit.unmock();
    }

    private PlayerMock addPlayer() throws Exception {
        PlayerMock player = server.addPlayer();
        profileRepository.findOrCreate(player.getUniqueId(), player.getName()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        return player;
    }

    private void grantTierOne(PlayerMock player) throws Exception {
        variableRepository.set(player.getUniqueId(), ClaimService.CLAIM_TIER_1_KEY, ClaimService.CLAIM_TIER_1_VALUE)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Place le joueur dans le monde des claims via {@code setLocation} (aucun événement) puis
     * invoque directement le handler — {@code player.teleport(...)} déclencherait un vrai {@code
     * PlayerChangedWorldEvent} capté par l'instance de {@link ClaimWorldSafetyListener} enregistrée
     * par le vrai bootstrap du plugin (chargé par MockBukkit), qui agirait en parallèle avec sa
     * propre base vide. Même précaution que {@code ClaimsWorldRulesListenerTest} (events construits,
     * jamais {@code callEvent}).
     */
    private void arriveInClaims(PlayerMock player) {
        player.setLocation(new Location(claimsWorld, 0.5, 64, 0.5));
        listener.onWorldChange(new PlayerChangedWorldEvent(player, hubWorld));
    }

    private void pumpUntil(BooleanSupplier condition, String what) throws Exception {
        for (int i = 0; i < 400 && !condition.getAsBoolean(); i++) {
            server.getScheduler().performTicks(2);
            Thread.sleep(15);
        }
        assertTrue(condition.getAsBoolean(), () -> "condition jamais atteinte : " + what);
    }

    private boolean hasReturnStone(PlayerMock player) {
        return Arrays.stream(player.getInventory().getContents())
                .filter(Objects::nonNull)
                .anyMatch(s -> customItemRegistry.identify(s).map(RpgItemKeys.PIERRE_RETOUR::equals).orElse(false));
    }

    /**
     * Pompe des ticks jusqu'à recevoir un message contenant {@code needle}, en <strong>accumulant</strong>
     * les messages lus ({@code nextMessage()} est destructif, donc jamais relu dans une assertion).
     *
     * <p>Un {@code performTicks(10)} fixe ne suffit pas : l'éligibilité se lit en base de façon
     * asynchrone, et la fenêtre dépend de la charge de la machine. Ce test a réussi en suite
     * complète puis échoué pendant un déploiement — c'était la course, pas le code de production.</p>
     */
    private void awaitMessageContaining(PlayerMock player, String needle, String what) throws Exception {
        java.util.List<String> seen = new java.util.ArrayList<>();
        for (int i = 0; i < 400; i++) {
            server.getScheduler().performTicks(2);
            Thread.sleep(15);
            String msg;
            while ((msg = player.nextMessage()) != null) {
                seen.add(msg);
            }
            if (seen.stream().anyMatch(m -> m.contains(needle))) {
                return;
            }
        }
        org.junit.jupiter.api.Assertions.fail(what + " — message « " + needle + " » jamais reçu ; messages : " + seen);
    }

    private int returnStoneCount(PlayerMock player) {
        int n = 0;
        for (ItemStack s : player.getInventory().getContents()) {
            if (s != null && customItemRegistry.identify(s).map(RpgItemKeys.PIERRE_RETOUR::equals).orElse(false)) {
                n += s.getAmount();
            }
        }
        return n;
    }

    @Test
    void anEligiblePlayerEnteringTheClaimsWorldWithoutAReturnStoneReceivesOne() throws Exception {
        PlayerMock player = addPlayer();
        grantTierOne(player);

        arriveInClaims(player);

        pumpUntil(() -> hasReturnStone(player), "Pierre de retour donnée à l'arrivée");
        assertEquals(1, returnStoneCount(player));
    }

    @Test
    void aSecondArrivalNeverGrantsADuplicateReturnStone() throws Exception {
        PlayerMock player = addPlayer();
        grantTierOne(player);

        arriveInClaims(player);
        pumpUntil(() -> hasReturnStone(player), "1re Pierre de retour");
        arriveInClaims(player);
        server.getScheduler().performTicks(10);

        assertEquals(1, returnStoneCount(player), "jamais de second exemplaire");
    }

    @Test
    void aNonEligiblePlayerIsSentBackToTheHub() throws Exception {
        PlayerMock player = addPlayer();

        arriveInClaims(player);

        pumpUntil(() -> player.getWorld().getName().equals("world_hub"), "renvoi au Hub");
        assertFalse(hasReturnStone(player), "un joueur non éligible n'a pas besoin d'une Pierre de retour, il est renvoyé");
        boolean explained = false;
        String msg;
        while ((msg = player.nextMessage()) != null) {
            if (msg.contains("ramené au village") || msg.contains("réservé aux joueurs")) {
                explained = true;
            }
        }
        assertTrue(explained, "le renvoi doit être expliqué au joueur");
    }

    @Test
    void aPlayerWhoOwnsAClaimIsNeverBouncedAndGetsAStone() throws Exception {
        PlayerMock player = addPlayer();
        grantTierOne(player);
        World claimWorld = server.addSimpleWorld("world_for_claim");
        var outcome = claimService.create(player, "main_" + player.getUniqueId(),
                        new Location(claimWorld, 0, 60, 0), new Location(claimWorld, 4, 63, 4))
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(ClaimService.CreateOutcome.CREATED, outcome);
        server.getScheduler().performTicks(2);

        arriveInClaims(player);

        pumpUntil(() -> hasReturnStone(player), "Pierre de retour pour un propriétaire de claim");
        assertEquals("claims", player.getWorld().getName(), "un propriétaire n'est jamais renvoyé");
    }

    /**
     * Issue #321 (24/10) — filet d'arrivée, matrice OP × bypass × {@code CLAIM_TIER_1} : c'est lui
     * qui couvre tous les chemins qui ne passent pas par le portail (vanilla {@code /tp},
     * Multiverse, {@code /rpgadmin world tp}, {@code /claim admin tp}, portails historiques,
     * connexion dans le monde). Un OP sans session ouverte y est traité comme n'importe quel joueur.
     */
    static Stream<Arguments> arrivalMatrix() {
        java.util.List<Arguments> cases = new java.util.ArrayList<>();
        for (boolean op : java.util.List.of(false, true)) {
            for (boolean bypassRequested : java.util.List.of(false, true)) {
                for (String tier : Arrays.asList(null, "false", "true", "TRUE", "oui")) {
                    cases.add(Arguments.of(op, bypassRequested, tier));
                }
            }
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "OP={0} bypass={1} CLAIM_TIER_1={2}")
    @MethodSource("arrivalMatrix")
    void anArrivalByAnyRouteFollowsTheSameRuleForOpAndNonOp(boolean op, boolean bypassRequested, String tier)
            throws Exception {
        PlayerMock player = addPlayer();
        player.setOp(op);
        if (tier != null) {
            variableRepository.set(player.getUniqueId(), ClaimService.CLAIM_TIER_1_KEY, tier)
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
        boolean bypassOpened = bypassRequested
                && bypass.enable(player) == com.lodygames.rpgquest.permission.AdminBypassMode.Outcome.ENABLED;
        assertEquals(bypassRequested && op, bypassOpened, "seul un OP peut ouvrir la session, et jamais implicitement");

        arriveInClaims(player);

        if (bypassOpened || "true".equals(tier)) {
            pumpUntil(() -> hasReturnStone(player), "Pierre de retour pour une présence légitime");
            assertEquals("claims", player.getWorld().getName(), "présence légitime : jamais renvoyé");
        } else {
            pumpUntil(() -> player.getWorld().getName().equals("world_hub"), "renvoi au Hub sans déblocage");
        }
    }

    /**
     * Issue #22 — ce test affirmait l'inverse jusqu'au 05/10 (« aucun objet imposé à un joueur avec
     * le bypass »), et figeait donc le piège : un administrateur éligible arrivé dans le monde des
     * claims n'était ni renvoyé, ni doté d'une Pierre de retour, donc sans aucune sortie. C'est
     * exactement le blocage signalé. Le bypass garde son seul sens défendable — ne jamais
     * téléporter de force quelqu'un venu volontairement — mais il ne prive plus du moyen de
     * repartir.
     */
    @Test
    void theExplicitBypassPermissionIsNeverBouncedButStillGetsAStone() throws Exception {
        PlayerMock player = addPlayer();
        player.addAttachment(plugin, ClaimWorldAccessGuard.BYPASS_PERMISSION, true);
        player.addAttachment(plugin, com.lodygames.rpgquest.permission.AdminBypassMode.PERMISSION, true);
        bypass.enable(player);

        arriveInClaims(player);

        pumpUntil(() -> hasReturnStone(player), "Pierre de retour garantie malgré le bypass");
        assertEquals("claims", player.getWorld().getName(),
                "un joueur avec le bypass n'est JAMAIS téléporté de force");
        assertEquals(1, returnStoneCount(player), "jamais de second exemplaire");
    }

    @Test
    void aBypassHolderWhoAlreadyHasAStoneGetsNoDuplicate() throws Exception {
        PlayerMock player = addPlayer();
        player.addAttachment(plugin, ClaimWorldAccessGuard.BYPASS_PERMISSION, true);
        player.addAttachment(plugin, com.lodygames.rpgquest.permission.AdminBypassMode.PERMISSION, true);
        bypass.enable(player);

        arriveInClaims(player);
        pumpUntil(() -> hasReturnStone(player), "1re Pierre de retour");
        arriveInClaims(player);
        server.getScheduler().performTicks(10);

        assertEquals(1, returnStoneCount(player));
    }

    @Test
    void aBypassHolderWithAFullInventoryGetsTheStoneAtTheirFeetAndIsToldSo() throws Exception {
        PlayerMock player = addPlayer();
        player.addAttachment(plugin, ClaimWorldAccessGuard.BYPASS_PERMISSION, true);
        player.addAttachment(plugin, com.lodygames.rpgquest.permission.AdminBypassMode.PERMISSION, true);
        bypass.enable(player);
        fillInventory(player);
        assertEquals(-1, player.getInventory().firstEmpty(),
                "précondition : l'inventaire doit être réellement plein");

        arriveInClaims(player);

        // L'objet ne peut pas entrer dans l'inventaire : il tombe au sol. Le point corrigé est le
        // MESSAGE — avant, le joueur lisait « tu reçois une Pierre de retour » et la cherchait
        // dans des poches pleines.
        awaitMessageContaining(player, "à tes pieds", "porteur du bypass, inventaire plein");
        assertFalse(hasReturnStone(player), "inventaire plein : l'objet ne peut pas y entrer");
    }

    @Test
    void anEligiblePlayerWithAFullInventoryIsAlsoToldWhereTheStoneWent() throws Exception {
        PlayerMock player = addPlayer();
        grantTierOne(player);
        fillInventory(player);
        assertEquals(-1, player.getInventory().firstEmpty(),
                "précondition : l'inventaire doit être réellement plein");

        arriveInClaims(player);

        awaitMessageContaining(player, "à tes pieds", "joueur éligible non admin, inventaire plein");
        assertFalse(hasReturnStone(player), "inventaire plein : l'objet ne peut pas y entrer");
    }

    /**
     * Remplit l'inventaire jusqu'à saturation réelle.
     *
     * <p>On ne présume pas le nombre d'emplacements : {@code firstEmpty()} de MockBukkit renvoie
     * encore 36 après avoir rempli 0–35, alors que le rangement d'un joueur Bukkit s'arrête à 35.
     * Boucler sur {@code firstEmpty()} rend le test juste quelle que soit cette différence, au lieu
     * de tester un inventaire qu'on croit plein.</p>
     */
    private void fillInventory(PlayerMock player) {
        for (int guard = 0; guard < 64; guard++) {
            int slot = player.getInventory().firstEmpty();
            if (slot < 0) {
                return;
            }
            player.getInventory().setItem(slot,
                    new org.bukkit.inventory.ItemStack(org.bukkit.Material.COBBLESTONE, 64));
        }
    }

    @Test
    void joiningInsideTheClaimsWorldIsHandledLikeAnArrival() throws Exception {
        PlayerMock player = addPlayer();
        grantTierOne(player);
        player.setLocation(new Location(claimsWorld, 0.5, 64, 0.5));

        listener.onJoin(new PlayerJoinEvent(player, (net.kyori.adventure.text.Component) null));

        pumpUntil(() -> hasReturnStone(player), "Pierre de retour donnée à la connexion dans le monde des claims");
    }

    // ---- Issue #829 : défaillances bornées du filet d'arrivée ---------------------------------

    private static final long[] SHORT_RETRIES = {2L, 4L};
    private static final java.util.concurrent.CompletableFuture<Boolean> DB_DOWN =
            java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("base indisponible (test)"));

    private ClaimWorldSafetyListener listenerWith(
            java.util.function.Function<java.util.UUID, java.util.concurrent.CompletableFuture<Boolean>> lookup,
            java.util.function.Supplier<Optional<Location>> hub) {
        ClaimReturnService returnService = new ClaimReturnService(plugin, customItemRegistry,
                () -> Optional.of(new Location(hubWorld, 0, 64, 0)));
        return new ClaimWorldSafetyListener(plugin, claimService, returnService, () -> CLAIMS_CONFIG, hub, bypass,
                lookup, SHORT_RETRIES);
    }

    private void arriveInClaims(PlayerMock player, ClaimWorldSafetyListener target) {
        player.setLocation(new Location(claimsWorld, 0.5, 64, 0.5));
        target.onWorldChange(new PlayerChangedWorldEvent(player, hubWorld));
    }

    @Test
    void aTransientDatabaseFailureIsRetriedAndAnEligiblePlayerStays() throws Exception {
        PlayerMock player = addPlayer();
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        ClaimWorldSafetyListener flaky = listenerWith(id -> calls.incrementAndGet() == 1
                ? DB_DOWN : java.util.concurrent.CompletableFuture.completedFuture(true),
                () -> Optional.of(new Location(hubWorld, 0, 64, 0)));

        arriveInClaims(player, flaky);

        pumpUntil(() -> calls.get() == 2, "nouvel essai après l'échec de lecture");
        server.getScheduler().performTicks(20);
        assertEquals("claims", player.getWorld().getName(), "éligible après le nouvel essai : jamais renvoyé");
        assertTrue(hasReturnStone(player));
        assertEquals(2, calls.get());
    }

    @Test
    void aPersistentDatabaseFailureSendsThePlayerBackToTheHubAfterBoundedRetries() throws Exception {
        PlayerMock player = addPlayer();
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        ClaimWorldSafetyListener down = listenerWith(id -> {
            calls.incrementAndGet();
            return DB_DOWN;
        }, () -> Optional.of(new Location(hubWorld, 0, 64, 0)));

        arriveInClaims(player, down);

        awaitMessageContaining(player, "par précaution", "renvoi au Hub faute de vérification possible");
        assertEquals("world_hub", player.getWorld().getName(), "accès non vérifiable : jamais laissé dans Claims");
        server.getScheduler().performTicks(40);
        assertEquals(3, calls.get(), "1 lecture + 2 nouveaux essais, puis plus rien");
    }

    @Test
    void anUnresolvedHubTargetIsRetriedBoundedThenReportedWithoutLooping() throws Exception {
        PlayerMock player = addPlayer();
        java.util.concurrent.atomic.AtomicInteger resolutions = new java.util.concurrent.atomic.AtomicInteger();
        ClaimWorldSafetyListener noHub = listenerWith(id -> java.util.concurrent.CompletableFuture.completedFuture(false),
                () -> {
                    resolutions.incrementAndGet();
                    return Optional.empty();
                });

        arriveInClaims(player, noHub);

        awaitMessageContaining(player, "Un administrateur a été prévenu", "échec du renvoi signalé");
        server.getScheduler().performTicks(40);
        assertEquals(3, resolutions.get(), "renvoi retenté deux fois seulement : aucune boucle");
        assertEquals("claims", player.getWorld().getName());
        assertTrue(hasReturnStone(player), "le moyen de repartir reste garanti");
    }

    @Test
    void aHubTargetInsideTheClaimsWorldIsNeverUsedAndALateTargetIsUsedOnRetry() throws Exception {
        PlayerMock player = addPlayer();
        java.util.concurrent.atomic.AtomicInteger resolutions = new java.util.concurrent.atomic.AtomicInteger();
        ClaimWorldSafetyListener lateHub = listenerWith(id -> java.util.concurrent.CompletableFuture.completedFuture(false),
                () -> resolutions.incrementAndGet() == 1
                        ? Optional.of(new Location(claimsWorld, 0, 64, 0)) // mal configuré : ignoré
                        : Optional.of(new Location(hubWorld, 0, 64, 0)));

        arriveInClaims(player, lateHub);

        pumpUntil(() -> player.getWorld().getName().equals("world_hub"), "renvoi au Hub au nouvel essai");
        assertEquals(2, resolutions.get());
    }

    @Test
    void leavingTheClaimsWorldStopsPendingRetries() throws Exception {
        PlayerMock player = addPlayer();
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        ClaimWorldSafetyListener down = listenerWith(id -> {
            calls.incrementAndGet();
            return DB_DOWN;
        }, () -> Optional.of(new Location(hubWorld, 0, 64, 0)));

        arriveInClaims(player, down);
        pumpUntil(() -> calls.get() == 1, "première lecture");
        player.setLocation(new Location(hubWorld, 5, 64, 5)); // reparti par ses propres moyens

        server.getScheduler().performTicks(40);
        assertEquals(1, calls.get(), "aucun nouvel essai pour un joueur qui n'est plus dans Claims");
    }

    // ---- Issue #829 : téléportations internes au monde des claims ------------------------------

    private void teleportWithinClaims(PlayerMock player, ClaimWorldSafetyListener target, Location to,
                                      org.bukkit.event.player.PlayerTeleportEvent.TeleportCause cause) {
        Location from = player.getLocation();
        player.setLocation(to);
        target.onTeleportWithinClaimsWorld(new org.bukkit.event.player.PlayerTeleportEvent(player, from, to, cause));
    }

    @Test
    void anInternalCommandTeleportOfANonEligiblePlayerReplaysTheEntryCheck() throws Exception {
        PlayerMock player = addPlayer();
        player.setLocation(new Location(claimsWorld, 0.5, 64, 0.5));

        teleportWithinClaims(player, listener, new Location(claimsWorld, 200.5, 64, 200.5),
                org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.COMMAND);

        pumpUntil(() -> player.getWorld().getName().equals("world_hub"), "renvoi au Hub après /tp interne");
    }

    @Test
    void anEligiblePlayerTeleportedIntoSomeoneElsesClaimStaysButGainsNoRight() throws Exception {
        PlayerMock owner = addPlayer();
        grantTierOne(owner);
        assertEquals(ClaimService.CreateOutcome.CREATED, claimService.create(owner, "main_" + owner.getUniqueId(),
                        new Location(claimsWorld, 100, 60, 100), new Location(claimsWorld, 110, 70, 110))
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        server.getScheduler().performTicks(2);
        PlayerMock visitor = addPlayer();
        grantTierOne(visitor);
        visitor.setLocation(new Location(claimsWorld, 0.5, 64, 0.5));

        Location inside = new Location(claimsWorld, 105.5, 64, 105.5);
        teleportWithinClaims(visitor, listener, inside, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.COMMAND);

        pumpUntil(() -> hasReturnStone(visitor), "contrôle rejoué : éligible, Pierre garantie");
        server.getScheduler().performTicks(10);
        assertEquals(inside, visitor.getLocation(), "accès au monde légitime : jamais déplacé");
        var claim = claimService.claimAt("claims", 105, 64, 105).orElseThrow();
        assertFalse(claim.isTrusted(visitor.getUniqueId()), "la présence n'accorde aucun droit sur le claim d'autrui");
    }

    @Test
    void gameplayMovesInsideTheClaimsWorldAreNotChecked() throws Exception {
        PlayerMock player = addPlayer();
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        ClaimWorldSafetyListener counting = listenerWith(id -> {
            calls.incrementAndGet();
            return java.util.concurrent.CompletableFuture.completedFuture(false);
        }, () -> Optional.of(new Location(hubWorld, 0, 64, 0)));
        player.setLocation(new Location(claimsWorld, 0.5, 64, 0.5));

        teleportWithinClaims(player, counting, new Location(claimsWorld, 8.5, 64, 8.5),
                org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.ENDER_PEARL);

        server.getScheduler().performTicks(10);
        assertEquals(0, calls.get(), "perle de l'Ender : aucun contrôle d'entrée");
        assertEquals("claims", player.getWorld().getName());
    }
}
