package com.lodygames.rpgquest.travel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.database.DatabaseManager;
import com.lodygames.rpgquest.database.NpcBindingRepository;
import com.lodygames.rpgquest.database.NpcIdRepository;
import com.lodygames.rpgquest.database.PlayerProfileRepository;
import com.lodygames.rpgquest.database.PlayerVariableRepository;
import com.lodygames.rpgquest.database.PortalCooldownRepository;
import com.lodygames.rpgquest.database.QuestProgressRepository;
import com.lodygames.rpgquest.database.WalletRepository;
import com.lodygames.rpgquest.economy.EconomyService;
import com.lodygames.rpgquest.npc.NpcIdentityService;
import com.lodygames.rpgquest.quest.QuestMessagesService;
import com.lodygames.rpgquest.quest.YamlQuestEngine;
import com.lodygames.rpgquest.quest.progress.QuestProgressEngine;
import com.lodygames.rpgquest.travel.model.Destination;
import com.lodygames.rpgquest.travel.model.PortalDefinition;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class PortalServiceTest {

    private static final long TIMEOUT_SECONDS = 5;
    private static final NamespacedKey QUEST_ID = new NamespacedKey("rpgquest", "first_steps");

    @TempDir
    Path tempDir;

    private ServerMock server;
    private RPGQuestPlugin plugin;
    private World world;
    private DatabaseManager database;
    private WalletRepository walletRepository;
    private EconomyService economyService;
    private PortalCooldownRepository cooldownRepository;
    private QuestProgressEngine questProgressEngine;
    private YamlPortalRegistry portalRegistry;
    private YamlDestinationRegistry destinationRegistry;
    private PortalService portalService;
    private PlayerProfileRepository profileRepository;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(RPGQuestPlugin.class);
        world = server.addSimpleWorld("world");

        database = new DatabaseManager(tempDir.resolve("test.db"));
        database.initialize().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        profileRepository = new PlayerProfileRepository(database);
        walletRepository = new WalletRepository(database);
        economyService = new EconomyService(walletRepository);
        cooldownRepository = new PortalCooldownRepository(database);

        Path questsDir = tempDir.resolve("quests");
        Files.createDirectories(questsDir);
        Files.writeString(questsDir.resolve("first_steps.yml"), """
                id: rpgquest:first_steps
                title: "Titre"
                description: "Description"
                category: test
                steps:
                  - id: step_one
                    objectives:
                      - type: KILL_ENTITY
                        entity: ZOMBIE
                        amount: 1
                """);
        YamlQuestEngine questEngine = new YamlQuestEngine(questsDir, plugin.getSLF4JLogger());
        questEngine.reload();
        QuestProgressRepository progressRepository = new QuestProgressRepository(database);
        PlayerVariableRepository variableRepository = new PlayerVariableRepository(database);
        QuestMessagesService messagesService = new QuestMessagesService(plugin);
        messagesService.start();
        NpcIdentityService npcIdentityService = new NpcIdentityService(
                plugin, new NpcIdRepository(database), new NpcBindingRepository(database));
        questProgressEngine = new QuestProgressEngine(
                plugin, questEngine, progressRepository, variableRepository, messagesService, npcIdentityService);
        questProgressEngine.start();

        portalRegistry = new YamlPortalRegistry(tempDir.resolve("portals"), plugin.getSLF4JLogger());
        portalRegistry.start();
        destinationRegistry = new YamlDestinationRegistry(tempDir.resolve("destinations"), plugin.getSLF4JLogger());
        destinationRegistry.start();

        // Sol solide sous la destination "village" ; pieds/tête restent AIR (défaut MockBukkit) : position sûre.
        world.getBlockAt(0, 64, 0).setType(Material.STONE);
        destinationRegistry.createOrUpdate(new Destination("village", "world", 0.5, 65.0, 0.5, 0f, 0f));

        portalService = new PortalService(plugin, portalRegistry, destinationRegistry, economyService, questProgressEngine, cooldownRepository);
        portalService.start();
    }

    @AfterEach
    void tearDown() {
        portalService.stop();
        questProgressEngine.stop();
        database.shutdown();
        MockBukkit.unmock();
    }

    private PortalDefinition basicPortal(String id, String destinationId, int channelSeconds, int cooldownSeconds,
                                          String permission, NamespacedKey questId, Integer level, Long cost) {
        return new PortalDefinition(id, "world", 10, 60, 10, 12, 63, 12,
                destinationId, channelSeconds, cooldownSeconds, permission, questId,
                questId != null ? com.lodygames.rpgquest.quest.model.QuestState.COMPLETED : null, level, cost);
    }

    private PlayerMock addPlayer() throws Exception {
        PlayerMock player = server.addPlayer();
        profileRepository.findOrCreate(player.getUniqueId(), player.getName()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        player.teleport(new Location(world, 0.5, 61, 0.5));
        return player;
    }

    private Location insideZone() {
        return new Location(world, 11.5, 61, 11.5);
    }

    @Test
    void enteringAPortalWithoutDestinationDoesNothing() throws Exception {
        portalRegistry.create(basicPortal("gate", null, 0, 0, null, null, null, null));
        PlayerMock player = addPlayer();

        portalService.handleMove(player, insideZone());
        awaitTicks(10);

        assertFalse(portalService.isChanneling(player.getUniqueId()));
    }

    @Test
    void missingPermissionPreventsActivation() throws Exception {
        portalRegistry.create(basicPortal("gate", "village", 0, 0, "rpgquest.portal.vip", null, null, null));
        PlayerMock player = addPlayer();

        portalService.handleMove(player, insideZone());
        awaitTicks(10);

        assertFalse(portalService.isChanneling(player.getUniqueId()));
    }

    @Test
    void unmetLevelRequirementPreventsActivation() throws Exception {
        portalRegistry.create(basicPortal("gate", "village", 0, 0, null, null, 5, null));
        PlayerMock player = addPlayer();
        player.setLevel(0);

        portalService.handleMove(player, insideZone());
        awaitTicks(10);

        assertFalse(portalService.isChanneling(player.getUniqueId()));
    }

    @Test
    void unmetQuestRequirementPreventsActivation() throws Exception {
        portalRegistry.create(basicPortal("gate", "village", 0, 0, null, QUEST_ID, null, null));
        PlayerMock player = addPlayer();
        // Quête jamais acceptée : état NOT_STARTED, différent de COMPLETED requis.

        portalService.handleMove(player, insideZone());
        awaitTicks(20);

        assertEquals(new Location(world, 0.5, 61, 0.5), player.getLocation());
    }

    @Test
    void metQuestRequirementAllowsSuccessfulTeleport() throws Exception {
        portalRegistry.create(basicPortal("gate", "village", 0, 0, null, QUEST_ID, null, null));
        PlayerMock player = addPlayer();
        questProgressEngine.accept(player, QUEST_ID).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        questProgressEngine.forceComplete(player, QUEST_ID).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        portalService.handleMove(player, insideZone());
        awaitUntil(() -> player.getWorld().equals(world) && Math.floor(player.getLocation().getX()) == 0.0);

        assertEquals(0, (int) Math.floor(player.getLocation().getX()));
        assertEquals(0, (int) Math.floor(player.getLocation().getZ()));
    }

    @Test
    void insufficientFundsPreventsActivationAndTakesNoMoney() throws Exception {
        portalRegistry.create(basicPortal("gate", "village", 0, 0, null, null, null, 50L));
        PlayerMock player = addPlayer();
        walletRepository.credit(player.getUniqueId(), 10, "TEST", null).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        portalService.handleMove(player, insideZone());
        awaitTicks(20);

        assertEquals(10L, walletRepository.balance(player.getUniqueId()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertEquals(new Location(world, 0.5, 61, 0.5), player.getLocation());
    }

    @Test
    void sufficientFundsAreDebitedOnlyOnSuccessfulTeleport() throws Exception {
        portalRegistry.create(basicPortal("gate", "village", 0, 0, null, null, null, 30L));
        PlayerMock player = addPlayer();
        walletRepository.credit(player.getUniqueId(), 100, "TEST", null).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        portalService.handleMove(player, insideZone());
        awaitUntil(() -> getBalanceNow(player) == 70L);

        assertEquals(70L, walletRepository.balance(player.getUniqueId()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    @Test
    void cooldownPreventsImmediateReuse() throws Exception {
        portalRegistry.create(basicPortal("gate", "village", 0, 30, null, null, null, null));
        PlayerMock player = addPlayer();

        portalService.handleMove(player, insideZone());
        awaitUntil(() -> Math.floor(player.getLocation().getX()) == 0.0);

        // Retour dans la zone d'activation, puis nouvelle tentative immédiate : doit être refusée (cooldown).
        player.teleport(new Location(world, 0.5, 61, 0.5));
        portalService.handleMove(player, new Location(world, 5, 61, 5)); // sort de la zone du portail
        portalService.handleMove(player, insideZone());
        awaitTicks(20);

        assertEquals(0, (int) Math.floor(player.getLocation().getX()), "toujours à la destination, aucune seconde téléportation");
    }

    @Test
    void teleportingToAMissingWorldFailsCleanly() throws Exception {
        destinationRegistry.createOrUpdate(new Destination("ghost_world", "does_not_exist", 0, 65, 0, 0, 0));
        portalRegistry.create(basicPortal("gate", "ghost_world", 0, 0, null, null, null, null));
        PlayerMock player = addPlayer();

        portalService.handleMove(player, insideZone());
        awaitTicks(20);

        assertEquals(new Location(world, 0.5, 61, 0.5), player.getLocation());
    }

    @Test
    void dangerousDestinationFailsCleanlyWithoutTeleporting() throws Exception {
        // Aucun sol solide autour de (50, 65, 50) : recherche de sécurité épuisée.
        destinationRegistry.createOrUpdate(new Destination("void_spot", "world", 50.5, 65.0, 50.5, 0f, 0f));
        portalRegistry.create(basicPortal("gate", "void_spot", 0, 0, null, null, null, null));
        PlayerMock player = addPlayer();

        portalService.handleMove(player, insideZone());
        awaitTicks(20);

        assertEquals(new Location(world, 0.5, 61, 0.5), player.getLocation());
    }

    @Test
    void movingBeyondToleranceDuringChannelingCancelsTheTeleport() throws Exception {
        portalRegistry.create(basicPortal("gate", "village", 3, 0, null, null, null, null));
        PlayerMock player = addPlayer();

        portalService.handleMove(player, insideZone());
        awaitUntil(() -> portalService.isChanneling(player.getUniqueId()));
        assertTrue(portalService.isChanneling(player.getUniqueId()));

        player.teleport(new Location(world, 20.5, 61, 20.5));
        awaitUntil(() -> !portalService.isChanneling(player.getUniqueId()));

        awaitTicks(80); // laisse le temps au canal (3s = 60 ticks) de se terminer s'il n'avait pas été annulé
        assertFalse(portalService.isChanneling(player.getUniqueId()));
        assertEquals(new Location(world, 20.5, 61, 20.5), player.getLocation(), "aucune téléportation ne doit avoir eu lieu");
    }

    @Test
    void damageDuringChannelingCancelsTheTeleport() throws Exception {
        portalRegistry.create(basicPortal("gate", "village", 3, 0, null, null, null, null));
        PlayerMock player = addPlayer();

        portalService.handleMove(player, insideZone());
        awaitUntil(() -> portalService.isChanneling(player.getUniqueId()));

        portalService.handleDamage(player);

        assertFalse(portalService.isChanneling(player.getUniqueId()));
        awaitTicks(80);
        assertEquals(new Location(world, 0.5, 61, 0.5), player.getLocation(), "aucune téléportation ne doit avoir eu lieu");
    }

    @Test
    void disconnectingDuringChannelingCancelsTheTeleportAndClearsState() throws Exception {
        portalRegistry.create(basicPortal("gate", "village", 3, 0, null, null, null, null));
        PlayerMock player = addPlayer();

        portalService.handleMove(player, insideZone());
        awaitUntil(() -> portalService.isChanneling(player.getUniqueId()));

        portalService.handleQuit(player);

        assertFalse(portalService.isChanneling(player.getUniqueId()));
    }

    @Test
    void reloadingThePortalRegistryPicksUpManuallyAddedFiles() {
        assertEquals(0, portalRegistry.portals().size());
        portalRegistry.create(basicPortal("gate", "village", 0, 0, null, null, null, null));
        assertEquals(1, portalRegistry.portals().size());

        portalRegistry.delete("gate");
        assertEquals(0, portalRegistry.portals().size());
    }

    private long getBalanceNow(PlayerMock player) {
        try {
            return walletRepository.balance(player.getUniqueId()).get(1, TimeUnit.SECONDS);
        } catch (Exception e) {
            return -1L;
        }
    }

    private void awaitTicks(int ticks) throws InterruptedException {
        for (int i = 0; i < ticks; i++) {
            server.getScheduler().performTicks(1);
            Thread.sleep(10);
        }
    }

    private void awaitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT_SECONDS * 1000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            server.getScheduler().performTicks(1);
            Thread.sleep(10);
        }
    }
}
