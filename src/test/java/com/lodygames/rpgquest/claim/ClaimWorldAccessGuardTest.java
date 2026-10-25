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
import com.lodygames.rpgquest.permission.AdminBypassMode;
import com.lodygames.rpgquest.permission.RpgPermissions;
import com.lodygames.rpgquest.settings.GameSettings;
import com.lodygames.rpgquest.progression.ProgressionService;
import com.lodygames.rpgquest.travel.model.WorldPortalDefinition;
import com.lodygames.rpgquest.zone.ZoneRegistry;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
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
 * Issues #21/#22/#23 : {@link ClaimWorldAccessGuard} n'ouvre le monde des claims qu'aux joueurs
 * qui ont réellement débloqué leur premier terrain (variable {@code CLAIM_TIER_1} ou claim
 * existant), refuse les autres sans les téléporter, et se re-verrouille après un reset.
 */
class ClaimWorldAccessGuardTest {

    private static final long TIMEOUT_SECONDS = 5;
    private static final ClaimConfig CLAIMS_CONFIG = new ClaimConfig(64, 384, 3, 16, "claims", true);
    private static final WorldPortalDefinition TO_CLAIMS = new WorldPortalDefinition(
            "hub_to_claims", "world_hub", 0, 0, 0, 4, 4, 4, "claims", true);
    private static final WorldPortalDefinition TO_WILD = new WorldPortalDefinition(
            "hub_to_wild", "world_hub", 0, 0, 0, 4, 4, 4, "wild", true);

    @TempDir
    Path tempDir;

    private ServerMock server;
    private RPGQuestPlugin plugin;
    private DatabaseManager database;
    private PlayerProfileRepository profileRepository;
    private PlayerVariableRepository variableRepository;
    private ClaimService claimService;
    private YamlCustomItemRegistry customItemRegistry;
    private ClaimWorldAccessGuard guard;
    private GameSettings settings = GameSettings.DEFAULTS;
    private final AdminBypassMode bypass = new AdminBypassMode(() -> settings);
    private final AtomicInteger teleportNowCalls = new AtomicInteger();
    /** Destination réelle de la Pierre de retour ; vidée par un test pour simuler un spawn non résolu. */
    private Optional<Location> hubReturnTarget = Optional.empty();

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(RPGQuestPlugin.class);
        server.addSimpleWorld("world_hub");
        server.addSimpleWorld("claims");

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
        claimService = new ClaimService(plugin, claimRepository, zoneRegistry, portalRegistry, configService,
                progressionService, variableRepository);
        claimService.start();
        // Réchauffe l'exécuteur SQLite (première requête notablement plus lente) pour que le
        // contrôle asynchrone du garde s'achève dans la fenêtre de pumpUntil.
        variableRepository.get(new java.util.UUID(0, 0), "warmup").get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        customItemRegistry = new YamlCustomItemRegistry(tempDir.resolve("items"), plugin.getSLF4JLogger());
        customItemRegistry.start();
        hubReturnTarget = Optional.of(new Location(server.getWorld("world_hub"), 0, 64, 0));
        ClaimReturnService returnService = new ClaimReturnService(plugin, customItemRegistry,
                () -> hubReturnTarget);
        guard = new ClaimWorldAccessGuard(plugin, claimService, returnService, () -> CLAIMS_CONFIG,
                (player, portal) -> teleportNowCalls.incrementAndGet(), bypass);
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
        player.teleport(new Location(server.getWorld("world_hub"), 2, 2, 2));
        player.nextMessage(); // vide le message de join éventuel.
        return player;
    }

    /** Session de bypass ouverte explicitement (issue #35), avec le droit de l'ouvrir. */
    private void armBypass(PlayerMock player) {
        player.addAttachment(plugin, AdminBypassMode.PERMISSION, true);
        assertEquals(AdminBypassMode.Outcome.ENABLED, bypass.enable(player));
    }

    private void grantTierOne(PlayerMock player) throws Exception {
        variableRepository.set(player.getUniqueId(), ClaimService.CLAIM_TIER_1_KEY, ClaimService.CLAIM_TIER_1_VALUE)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /** Pompe des ticks jusqu'à ce que la condition soit vraie, ou échoue après ~2 s. */
    private void pumpUntil(BooleanSupplier condition, String what) throws Exception {
        for (int i = 0; i < 400 && !condition.getAsBoolean(); i++) {
            server.getScheduler().performTicks(2);
            Thread.sleep(15);
        }
        assertTrue(condition.getAsBoolean(), () -> "condition jamais atteinte : " + what);
    }

    private List<String> drainMessages(PlayerMock player) {
        List<String> out = new ArrayList<>();
        String next;
        while ((next = player.nextMessage()) != null) {
            out.add(next);
        }
        return out;
    }

    /**
     * Pompe des ticks en accumulant les messages reçus (lecture destructive de {@code nextMessage},
     * donc jamais réévaluée dans une assertion) et échoue si le refus n'arrive pas.
     */
    private void awaitRefusal(PlayerMock player) throws Exception {
        awaitMessageContaining(player, "réservé aux joueurs");
    }

    /** Même lecture destructive, pour un message de refus dont le motif n'est pas l'éligibilité. */
    private void awaitMessageContaining(PlayerMock player, String needle) throws Exception {
        List<String> seen = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            server.getScheduler().performTicks(2);
            Thread.sleep(15);
            seen.addAll(drainMessages(player));
            if (seen.stream().anyMatch(m -> m.contains(needle))) {
                return;
            }
        }
        org.junit.jupiter.api.Assertions.fail("message « " + needle + " » jamais reçu ; messages : " + seen);
    }

    private boolean hasReturnStone(PlayerMock player) {
        return Arrays.stream(player.getInventory().getContents())
                .filter(Objects::nonNull)
                .anyMatch(stack -> customItemRegistry.identify(stack)
                        .map(RpgItemKeys.PIERRE_RETOUR::equals).orElse(false));
    }

    /** Remplit chaque emplacement de rangement jusqu'à ce qu'aucun objet ne puisse plus y entrer. */
    private void fillInventory(PlayerMock player) {
        for (int guardCount = 0; guardCount < 64 && player.getInventory().firstEmpty() >= 0; guardCount++) {
            player.getInventory().setItem(player.getInventory().firstEmpty(), new ItemStack(Material.COBBLESTONE, 64));
        }
        assertEquals(-1, player.getInventory().firstEmpty(), "précondition : inventaire réellement plein");
    }

    private long droppedItemsInHub() {
        return server.getWorld("world_hub").getEntities().stream()
                .filter(entity -> entity instanceof org.bukkit.entity.Item)
                .count();
    }

    @Test
    void aFreshPlayerWithoutUnlockIsRefusedAndNeverTeleported() throws Exception {
        PlayerMock player = addPlayer();

        assertFalse(guard.allowEntry(player, TO_CLAIMS), "l'entrée immédiate doit être bloquée");
        awaitRefusal(player);

        server.getScheduler().performTicks(5);
        assertEquals(0, teleportNowCalls.get(), "aucun joueur non éligible ne doit être téléporté dans le monde des claims");
    }

    @Test
    void aPlayerWithClaimTierOneIsLetThrough() throws Exception {
        PlayerMock player = addPlayer();
        grantTierOne(player);

        assertFalse(guard.allowEntry(player, TO_CLAIMS), "le passage réel se fait via teleportNow, après le contrôle async");
        pumpUntil(() -> teleportNowCalls.get() >= 1, "téléportation relancée pour un joueur éligible");
        assertTrue(drainMessages(player).stream().noneMatch(m -> m.contains("réservé aux joueurs")), "aucun refus attendu");
    }

    @Test
    void aPlayerWhoAlreadyOwnsAClaimIsLetThroughImmediately() throws Exception {
        PlayerMock player = addPlayer();
        grantTierOne(player);
        World claimWorld = server.addSimpleWorld("world_for_claim");
        var outcome = claimService.create(player, "main_" + player.getUniqueId(),
                        new Location(claimWorld, 0, 60, 0), new Location(claimWorld, 4, 63, 4))
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(ClaimService.CreateOutcome.CREATED, outcome);
        server.getScheduler().performTicks(2);

        assertTrue(guard.allowEntry(player, TO_CLAIMS), "un propriétaire de claim doit toujours pouvoir revenir");
        assertEquals(0, teleportNowCalls.get(), "autorisé de façon synchrone : pas de relance via teleportNow");
    }

    @Test
    void theExplicitBypassPermissionPasses() throws Exception {
        PlayerMock player = addPlayer();
        player.addAttachment(plugin, ClaimWorldAccessGuard.BYPASS_PERMISSION, true);
        armBypass(player);

        assertTrue(guard.allowEntry(player, TO_CLAIMS));
        assertTrue(drainMessages(player).stream().noneMatch(m -> m.contains("réservé aux joueurs")),
                "le bypass ne doit jamais être refusé");
        // Le bypass dispense du contrôle d'éligibilité, pas du moyen de repartir (issue #22).
        assertTrue(hasReturnStone(player), "un porteur du bypass reçoit tout de même sa Pierre de retour");
    }

    // ---- Issues #35/#321 : OP/DEOP × mode bypass ON/OFF × accès Claims ---------------------

    @Test
    void anOpWithoutTheBypassModeIsRefusedLikeAnyPlayer() throws Exception {
        PlayerMock op = addPlayer();
        op.setOp(true);

        assertFalse(guard.allowEntry(op, TO_CLAIMS), "être OP ne contourne plus le verrou Claims (#321)");
        awaitRefusal(op);
        server.getScheduler().performTicks(5);
        assertEquals(0, teleportNowCalls.get());
        assertTrue(op.hasPermission(RpgPermissions.LEGACY_ADMIN_WORLD), "les droits d'administration de l'OP sont intacts");
    }

    @Test
    void anOpWithTheBypassModeOnEntersThenIsRefusedAgainOnceItIsOff() throws Exception {
        PlayerMock op = addPlayer();
        op.setOp(true);
        assertEquals(AdminBypassMode.Outcome.ENABLED, bypass.enable(op), "un OP peut ouvrir la session");

        assertTrue(guard.allowEntry(op, TO_CLAIMS), "bypass ON : entrée sans CLAIM_TIER_1");

        bypass.disable(op.getUniqueId());
        assertFalse(guard.allowEntry(op, TO_CLAIMS), "bypass OFF : de nouveau les règles normales");
        awaitRefusal(op);
    }

    @Test
    void aDeoppedPlayerCannotOpenTheBypassAndIsRefused() throws Exception {
        PlayerMock player = addPlayer();
        player.setOp(false);

        assertEquals(AdminBypassMode.Outcome.NO_PERMISSION, bypass.enable(player));
        assertFalse(guard.allowEntry(player, TO_CLAIMS));
        awaitRefusal(player);
    }

    @Test
    void anOpWithAValidTierEntersWithoutTheBypass() throws Exception {
        PlayerMock op = addPlayer();
        op.setOp(true);
        grantTierOne(op);

        assertFalse(guard.allowEntry(op, TO_CLAIMS), "même parcours qu'un joueur normal : contrôle asynchrone");
        pumpUntil(() -> teleportNowCalls.get() >= 1, "téléportation relancée pour un OP éligible");
    }

    @Test
    void anInvalidOrFalseTierValueIsRefusedForAnOpToo() throws Exception {
        for (String value : List.of("false", "TRUE", "oui", "1")) {
            PlayerMock op = addPlayer();
            op.setOp(true);
            variableRepository.set(op.getUniqueId(), ClaimService.CLAIM_TIER_1_KEY, value)
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            assertFalse(guard.allowEntry(op, TO_CLAIMS), "CLAIM_TIER_1=" + value);
            awaitRefusal(op);
        }
        server.getScheduler().performTicks(5);
        assertEquals(0, teleportNowCalls.get(), "aucune valeur autre que « true » ne débloque l'accès");
    }

    @Test
    void theBypassNodeWithoutAnOpenSessionGrantsNothing() throws Exception {
        PlayerMock player = addPlayer();
        player.addAttachment(plugin, RpgPermissions.BYPASS_CLAIM_WORLD, true);

        assertFalse(guard.allowEntry(player, TO_CLAIMS), "le nœud seul ne suffit plus : session explicite requise");
        awaitRefusal(player);
    }

    @Test
    void anOpenSessionWithoutABypassNodeGrantsNothing() throws Exception {
        PlayerMock player = addPlayer();
        armBypass(player); // rpgquest.admin.bypass seul, sans rpgquest.bypass.claimworld ni ombrelle.

        assertFalse(guard.allowEntry(player, TO_CLAIMS), "le mode n'ajoute aucun droit, il arme ceux qu'on a");
        awaitRefusal(player);
    }

    @Test
    void whenTheModeIsWithdrawnInPlugAdminAnOpenSessionStopsCounting() throws Exception {
        PlayerMock op = addPlayer();
        op.setOp(true);
        assertEquals(AdminBypassMode.Outcome.ENABLED, bypass.enable(op));
        settings = GameSettings.DEFAULTS.with(java.util.Map.of("admin_bypass_available", "false")).settings();

        assertFalse(guard.allowEntry(op, TO_CLAIMS));
        awaitRefusal(op);
        assertEquals(AdminBypassMode.Outcome.UNAVAILABLE, bypass.enable(op), "ni rouvrable");
    }

    // ---- Issue #321 (24/10) : matrice complète OP × bypass × CLAIM_TIER_1 × claim possédé -----

    /** {@code null} = variable absente ; « TRUE » et « oui » = valeurs invalides (seul « true » exact débloque). */
    private static final List<String> TIER_VALUES = Arrays.asList(null, "false", "true", "TRUE", "oui");

    static Stream<Arguments> accessMatrix() {
        List<Arguments> cases = new ArrayList<>();
        for (boolean op : List.of(false, true)) {
            for (boolean bypassRequested : List.of(false, true)) {
                for (String tier : TIER_VALUES) {
                    for (boolean ownsClaim : List.of(false, true)) {
                        cases.add(Arguments.of(op, bypassRequested, tier, ownsClaim));
                    }
                }
            }
        }
        return cases.stream();
    }

    /**
     * Règle attendue, écrite une seule fois : le statut OP n'apparaît que dans la possibilité
     * d'ouvrir une session de bypass (nœud {@code rpgquest.admin.bypass}, {@code default: op}) ; il
     * n'intervient jamais dans la règle gameplay elle-même.
     * <ul>
     *   <li>session ouverte (donc OP) → entrée immédiate, contrôle CLAIM_TIER_1 non appliqué ;</li>
     *   <li>sinon claim déjà possédé → entrée immédiate, quelle que soit la variable ;</li>
     *   <li>sinon {@code CLAIM_TIER_1 == "true"} exact → entrée après lecture asynchrone ;</li>
     *   <li>sinon → refus, aucune téléportation.</li>
     * </ul>
     */
    @ParameterizedTest(name = "OP={0} bypass={1} CLAIM_TIER_1={2} claim={3}")
    @MethodSource("accessMatrix")
    void accessMatrixIsTheSameForOpAndNonOpWithoutAnExplicitBypass(
            boolean op, boolean bypassRequested, String tier, boolean ownsClaim) throws Exception {
        PlayerMock player = addPlayer();
        player.setOp(op);
        if (ownsClaim) {
            // Un claim ne se pose qu'avec le déblocage réel ; la variable est ensuite ramenée à la
            // valeur du cas, pour prouver que la propriété suffit seule (droit légitime conservé).
            grantTierOne(player);
            World claimWorld = server.addSimpleWorld("world_for_claim");
            assertEquals(ClaimService.CreateOutcome.CREATED, claimService.create(player, "main_" + player.getUniqueId(),
                            new Location(claimWorld, 0, 60, 0), new Location(claimWorld, 4, 63, 4))
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            server.getScheduler().performTicks(2);
            variableRepository.deleteAllForPlayer(player.getUniqueId()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
        if (tier != null) {
            variableRepository.set(player.getUniqueId(), ClaimService.CLAIM_TIER_1_KEY, tier)
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
        boolean bypassOpened = false;
        if (bypassRequested) {
            AdminBypassMode.Outcome outcome = bypass.enable(player);
            assertEquals(op ? AdminBypassMode.Outcome.ENABLED : AdminBypassMode.Outcome.NO_PERMISSION, outcome,
                    "seul un OP (rpgquest.admin.bypass) peut ouvrir la session");
            bypassOpened = outcome == AdminBypassMode.Outcome.ENABLED;
        }
        assertEquals(bypassOpened, bypass.isActive(player), "aucune session implicite");

        boolean immediate = guard.allowEntry(player, TO_CLAIMS);

        if (bypassOpened || ownsClaim) {
            assertTrue(immediate, "session explicite ou claim possédé : entrée immédiate");
            assertTrue(drainMessages(player).stream().noneMatch(m -> m.contains("réservé aux joueurs")));
            assertEquals(0, teleportNowCalls.get(), "autorisé de façon synchrone");
        } else if ("true".equals(tier)) {
            assertFalse(immediate, "contrôle asynchrone, comme pour tout joueur");
            pumpUntil(() -> teleportNowCalls.get() >= 1, "téléportation relancée pour CLAIM_TIER_1=true");
        } else {
            assertFalse(immediate, "aucun déblocage : refus, OP ou non");
            awaitRefusal(player);
            server.getScheduler().performTicks(5);
            assertEquals(0, teleportNowCalls.get(), "aucune téléportation sans déblocage");
        }
        if (op) {
            assertTrue(player.hasPermission(RpgPermissions.LEGACY_ADMIN_WORLD),
                    "les droits d'administration de l'OP restent intacts");
        }
    }

    // ---- Issue #22 : on n'entre pas sans le moyen de repartir ------------------------------

    @Test
    void anEligiblePlayerGetsTheReturnStoneBeforeTheTeleportation() throws Exception {
        PlayerMock player = addPlayer();
        grantTierOne(player);

        assertFalse(guard.allowEntry(player, TO_CLAIMS));
        pumpUntil(() -> teleportNowCalls.get() >= 1, "téléportation relancée");

        // L'objet doit être là AVANT le départ : le filet d'arrivée est un secours, pas le moyen
        // normal d'obtenir la Pierre — c'est précisément ce qui a piégé le joueur le 05/10.
        assertTrue(hasReturnStone(player), "Pierre de retour remise avant l'entrée");
    }

    @Test
    void anAlreadyHeldStoneIsNeverDuplicatedOnEntry() throws Exception {
        PlayerMock player = addPlayer();
        grantTierOne(player);
        player.getInventory().addItem(customItemRegistry.create(RpgItemKeys.PIERRE_RETOUR, 1).orElseThrow());

        assertFalse(guard.allowEntry(player, TO_CLAIMS));
        pumpUntil(() -> teleportNowCalls.get() >= 1, "téléportation relancée");

        long stones = Arrays.stream(player.getInventory().getContents())
                .filter(Objects::nonNull)
                .filter(stack -> customItemRegistry.identify(stack)
                        .map(RpgItemKeys.PIERRE_RETOUR::equals).orElse(false))
                .count();
        assertEquals(1, stones, "jamais de second exemplaire");
    }

    @Test
    void anEligiblePlayerWithAFullInventoryIsRefusedAndToldWhy() throws Exception {
        PlayerMock player = addPlayer();
        grantTierOne(player);
        fillInventory(player);

        assertFalse(guard.allowEntry(player, TO_CLAIMS));
        awaitMessageContaining(player, "inventaire est plein");

        server.getScheduler().performTicks(5);
        assertEquals(0, teleportNowCalls.get(),
                "entrer sans pouvoir recevoir la Pierre de retour serait un aller simple");
        assertFalse(hasReturnStone(player));
        // Déposer l'objet au point de DÉPART ne garantirait rien et joncherait le Hub.
        assertEquals(0, droppedItemsInHub(), "aucun objet laissé au sol au Hub par le contrôle préventif");
    }

    @Test
    void aClaimOwnerWithAFullInventoryIsRefusedToo() throws Exception {
        PlayerMock player = addPlayer();
        grantTierOne(player);
        World claimWorld = server.addSimpleWorld("world_for_claim");
        assertEquals(ClaimService.CreateOutcome.CREATED, claimService.create(player, "main_" + player.getUniqueId(),
                        new Location(claimWorld, 0, 60, 0), new Location(claimWorld, 4, 63, 4))
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        server.getScheduler().performTicks(2);
        fillInventory(player);

        // Branche synchrone (propriétaire d'un claim) : la règle de retour s'y applique aussi.
        assertFalse(guard.allowEntry(player, TO_CLAIMS), "même un propriétaire de claim ne part pas sans retour");
        assertTrue(drainMessages(player).stream().anyMatch(m -> m.contains("inventaire est plein")));
        assertEquals(0, teleportNowCalls.get());
    }

    @Test
    void entryIsRefusedWhenTheHubReturnDestinationCannotBeResolved() throws Exception {
        PlayerMock player = addPlayer();
        grantTierOne(player);
        hubReturnTarget = Optional.empty(); // spawn du village non résolu : la Pierre ne mènerait nulle part.

        assertFalse(guard.allowEntry(player, TO_CLAIMS));
        awaitMessageContaining(player, "retour depuis le monde des claims est indisponible");

        server.getScheduler().performTicks(5);
        assertEquals(0, teleportNowCalls.get(), "aucune entrée tant que le retour ne mène nulle part");
    }

    @Test
    void aBypassHolderIsNeverRefusedEvenWithoutAnyWayBack() throws Exception {
        PlayerMock player = addPlayer();
        player.addAttachment(plugin, ClaimWorldAccessGuard.BYPASS_PERMISSION, true);
        armBypass(player);
        fillInventory(player);
        hubReturnTarget = Optional.empty();

        // Le bypass reste un contournement assumé : un administrateur n'est jamais bloqué à l'entrée.
        assertTrue(guard.allowEntry(player, TO_CLAIMS));
        assertFalse(hasReturnStone(player), "inventaire plein : rien ne peut être remis, et rien n'est imposé");
    }

    @Test
    void resettingAPreviouslyEligiblePlayerRefusesAccessAgain() throws Exception {
        PlayerMock player = addPlayer();
        grantTierOne(player);
        assertFalse(guard.allowEntry(player, TO_CLAIMS));
        pumpUntil(() -> teleportNowCalls.get() >= 1, "1er passage éligible");
        drainMessages(player);
        teleportNowCalls.set(0);
        // Laisse expirer le laissez-passer court (CLEARED_TTL_TICKS) accordé par le passage réussi,
        // sinon le contrôle suivant serait court-circuité par cette fenêtre — sans rapport avec le reset.
        server.getScheduler().performTicks(220);

        // Simule /rpgadmin player resetnew : toutes les variables du joueur sont effacées.
        variableRepository.deleteAllForPlayer(player.getUniqueId()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertFalse(guard.allowEntry(player, TO_CLAIMS), "après reset, l'accès doit être de nouveau refusé");
        awaitRefusal(player);
        server.getScheduler().performTicks(5);
        assertEquals(0, teleportNowCalls.get(), "aucune téléportation après reset");
    }

    @Test
    void portalsThatDoNotLeadToTheClaimsWorldAreNeverAffected() throws Exception {
        PlayerMock player = addPlayer();

        assertTrue(guard.allowEntry(player, TO_WILD), "un portail vers le Wild n'est pas concerné par ce garde");
        assertTrue(drainMessages(player).isEmpty());
        assertEquals(0, teleportNowCalls.get());
    }

    // ---- Issue #829 : portails historiques (PortalService) --------------------------------------

    @Test
    void aHistoricPortalToTheClaimsWorldRefusesANonEligiblePlayerBeforeMovingThem() throws Exception {
        PlayerMock player = addPlayer();
        AtomicInteger allowed = new AtomicInteger();

        guard.checkEntry(player, "claims", "old_gate", allowed::incrementAndGet);

        awaitRefusal(player);
        server.getScheduler().performTicks(5);
        assertEquals(0, allowed.get(), "aucune téléportation ni débit pour un joueur non éligible");
    }

    @Test
    void aHistoricPortalLetsAnUnlockedPlayerThroughExactlyOnceWithAReturnStone() throws Exception {
        PlayerMock player = addPlayer();
        grantTierOne(player);
        AtomicInteger allowed = new AtomicInteger();

        guard.checkEntry(player, "claims", "old_gate", allowed::incrementAndGet);

        pumpUntil(() -> allowed.get() == 1, "passage autorisé après lecture de CLAIM_TIER_1");
        server.getScheduler().performTicks(5);
        assertEquals(1, allowed.get(), "jamais deux téléportations");
        assertTrue(hasReturnStone(player), "Pierre de retour garantie avant d'entrer (#22)");
        assertEquals(0, teleportNowCalls.get(), "le portail simple n'est pas relancé pour un portail historique");
    }

    @Test
    void aHistoricPortalLetsAClaimOwnerAndAnExplicitBypassThroughImmediately() throws Exception {
        PlayerMock owner = addPlayer();
        grantTierOne(owner);
        World other = server.addSimpleWorld("world_for_claim");
        assertEquals(ClaimService.CreateOutcome.CREATED, claimService.create(owner, "main_" + owner.getUniqueId(),
                        new Location(other, 0, 60, 0), new Location(other, 4, 63, 4))
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        server.getScheduler().performTicks(2);
        PlayerMock admin = addPlayer();
        admin.setOp(true);
        armBypass(admin);
        AtomicInteger allowed = new AtomicInteger();

        guard.checkEntry(owner, "claims", "old_gate", allowed::incrementAndGet);
        guard.checkEntry(admin, "claims", "old_gate", allowed::incrementAndGet);

        assertEquals(2, allowed.get(), "propriétaire et bypass explicite passent sans attendre la base");
    }

    @Test
    void anOpWithoutTheBypassModeIsRefusedByAHistoricPortalToo() throws Exception {
        PlayerMock op = addPlayer();
        op.setOp(true);
        AtomicInteger allowed = new AtomicInteger();

        guard.checkEntry(op, "claims", "old_gate", allowed::incrementAndGet);

        awaitRefusal(op);
        assertEquals(0, allowed.get(), "être OP ne suffit pas (#35/#321)");
    }

    @Test
    void historicPortalsToAnotherWorldAreNeverAffected() throws Exception {
        PlayerMock player = addPlayer();
        AtomicInteger allowed = new AtomicInteger();

        guard.checkEntry(player, "world_hub", "village_gate", allowed::incrementAndGet);

        assertEquals(1, allowed.get());
        assertFalse(hasReturnStone(player), "aucune Pierre de retour hors du monde des claims");
    }
}
