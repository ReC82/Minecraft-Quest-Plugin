package com.lodygames.rpgquest.travel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.claim.ClaimService;
import com.lodygames.rpgquest.config.ConfigService;
import com.lodygames.rpgquest.database.ClaimRepository;
import com.lodygames.rpgquest.database.DatabaseManager;
import com.lodygames.rpgquest.database.NpcBindingRepository;
import com.lodygames.rpgquest.database.NpcIdRepository;
import com.lodygames.rpgquest.database.PlayerVariableRepository;
import com.lodygames.rpgquest.database.PortalCooldownRepository;
import com.lodygames.rpgquest.database.ProgressionRepository;
import com.lodygames.rpgquest.database.QuestProgressRepository;
import com.lodygames.rpgquest.dialogue.PlayerConditionEvaluator;
import com.lodygames.rpgquest.dialogue.model.DialogueCondition;
import com.lodygames.rpgquest.dialogue.model.HasItemCondition;
import com.lodygames.rpgquest.dialogue.model.HasMainClaimCondition;
import com.lodygames.rpgquest.dialogue.model.HasPermissionCondition;
import com.lodygames.rpgquest.dialogue.model.NegatedCondition;
import com.lodygames.rpgquest.dialogue.model.QuestStateCondition;
import com.lodygames.rpgquest.dialogue.model.VariableEqualsCondition;
import com.lodygames.rpgquest.economy.QuestRewardPayer;
import com.lodygames.rpgquest.item.YamlCustomItemRegistry;
import com.lodygames.rpgquest.npc.NpcIdentityService;
import com.lodygames.rpgquest.progression.ProgressionService;
import com.lodygames.rpgquest.quest.QuestMessagesService;
import com.lodygames.rpgquest.quest.YamlQuestEngine;
import com.lodygames.rpgquest.quest.model.QuestState;
import com.lodygames.rpgquest.quest.progress.QuestProgressEngine;
import com.lodygames.rpgquest.travel.model.DestinationStrategy;
import com.lodygames.rpgquest.travel.model.PortalAccess;
import com.lodygames.rpgquest.travel.model.WorldPortalDefinition;
import com.lodygames.rpgquest.zone.ZoneRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * Issue #567 — une évaluation par type de condition proposé aux portails, par l'évaluateur partagé
 * avec les dialogues (base SQLite temporaire, joueurs fictifs). Aucune téléportation ici.
 */
class PortalAccessCheckerTest {

    private static final long TIMEOUT = 5;
    private static final NamespacedKey QUEST = new NamespacedKey("rpgquest", "first_steps");
    private static final Instant NOW = Instant.parse("2026-10-21T12:00:00Z");

    @TempDir
    Path tempDir;

    private ServerMock server;
    private RPGQuestPlugin plugin;
    private DatabaseManager database;
    private QuestProgressEngine questProgressEngine;
    private ClaimService claimService;
    private PlayerVariableRepository variables;
    private PortalCooldownRepository cooldownRepository;
    private PortalAccessChecker checker;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(RPGQuestPlugin.class);
        database = new DatabaseManager(tempDir.resolve("test.db"));
        database.initialize().get(TIMEOUT, TimeUnit.SECONDS);
        Path quests = tempDir.resolve("quests");
        Files.createDirectories(quests);
        Files.writeString(quests.resolve("first_steps.yml"), """
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
        YamlQuestEngine questEngine = new YamlQuestEngine(quests, plugin.getSLF4JLogger());
        questEngine.reload();
        variables = new PlayerVariableRepository(database);
        QuestMessagesService messages = new QuestMessagesService(plugin);
        messages.start();
        questProgressEngine = new QuestProgressEngine(plugin, questEngine, new QuestProgressRepository(database),
                variables, messages, new NpcIdentityService(plugin, new NpcIdRepository(database),
                new NpcBindingRepository(database)), QuestRewardPayer.unavailable());
        questProgressEngine.start();
        ZoneRegistry zones = new ZoneRegistry(tempDir.resolve("zones"), plugin.getSLF4JLogger());
        zones.start();
        YamlPortalRegistry portals = new YamlPortalRegistry(tempDir.resolve("portals"), plugin.getSLF4JLogger());
        portals.start();
        ConfigService config = new ConfigService(plugin);
        config.start();
        ProgressionService progression = new ProgressionService(plugin, new ProgressionRepository(database),
                () -> config.current().progression(), plugin.getSLF4JLogger());
        progression.start();
        claimService = new ClaimService(plugin, new ClaimRepository(database), zones, portals, config, progression,
                variables);
        claimService.start();
        YamlCustomItemRegistry items = new YamlCustomItemRegistry(tempDir.resolve("items"), plugin.getSLF4JLogger());
        items.start();
        cooldownRepository = new PortalCooldownRepository(database);
        checker = new PortalAccessChecker(new PlayerConditionEvaluator(questProgressEngine, variables, claimService,
                items), PortalAccessChecker.persistent(cooldownRepository, plugin.getSLF4JLogger()),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void tearDown() {
        claimService.stop();
        questProgressEngine.stop();
        database.shutdown();
        MockBukkit.unmock();
    }

    private PlayerMock addPlayer() throws Exception {
        PlayerMock player = server.addPlayer();
        new com.lodygames.rpgquest.database.PlayerProfileRepository(database)
                .findOrCreate(player.getUniqueId(), player.getName()).get(TIMEOUT, TimeUnit.SECONDS);
        return player;
    }

    private static WorldPortalDefinition portal(PortalAccess access) {
        return new WorldPortalDefinition("arche", "world", 0, 60, 0, 2, 63, 0, "wild", true,
                DestinationStrategy.WORLD_SPAWN, WorldPortalDefinition.Details.NONE, null, "", access);
    }

    private static PortalAccess only(DialogueCondition condition) {
        return new PortalAccess(null, "", List.of(new PortalAccess.Rule(condition, "")), 0, "");
    }

    private PortalAccessChecker.Verdict evaluate(PlayerMock player, PortalAccess access) throws Exception {
        return checker.evaluate(player, portal(access), true).get(TIMEOUT, TimeUnit.SECONDS);
    }

    @Test
    void questState() throws Exception {
        PlayerMock player = addPlayer();
        PortalAccess notStarted = only(new QuestStateCondition(QUEST, QuestState.NOT_STARTED));
        PortalAccess active = only(new QuestStateCondition(QUEST, QuestState.ACTIVE));
        assertTrue(evaluate(player, notStarted).allowed());
        assertFalse(evaluate(player, active).allowed());

        questProgressEngine.accept(player, QUEST).get(TIMEOUT, TimeUnit.SECONDS);

        assertTrue(evaluate(player, active).allowed());
        assertFalse(evaluate(player, notStarted).allowed());
    }

    @Test
    void hasItem() throws Exception {
        PlayerMock player = addPlayer();
        PortalAccess twoDiamonds = only(new HasItemCondition(Material.DIAMOND, 2));
        player.getInventory().addItem(new ItemStack(Material.DIAMOND, 1));
        assertFalse(evaluate(player, twoDiamonds).allowed());

        player.getInventory().addItem(new ItemStack(Material.DIAMOND, 1));

        assertTrue(evaluate(player, twoDiamonds).allowed());
        assertEquals(2, player.getInventory().all(Material.DIAMOND).values().stream().mapToInt(ItemStack::getAmount)
                .sum(), "l'évaluation ne retire rien");
    }

    @Test
    void hasPermission() throws Exception {
        PlayerMock player = addPlayer();
        PortalAccess perm = only(new HasPermissionCondition("rpgquest.portal.arche"));
        assertFalse(evaluate(player, perm).allowed());

        player.addAttachment(plugin, "rpgquest.portal.arche", true);

        assertTrue(evaluate(player, perm).allowed());
    }

    @Test
    void variableEquals() throws Exception {
        PlayerMock player = addPlayer();
        PortalAccess tier = only(new VariableEqualsCondition("CLAIM_TIER_1", "true"));
        assertFalse(evaluate(player, tier).allowed(), "variable absente = refus");

        variables.set(player.getUniqueId(), "CLAIM_TIER_1", "true").get(TIMEOUT, TimeUnit.SECONDS);

        assertTrue(evaluate(player, tier).allowed());
    }

    @Test
    void hasMainClaimAndItsNegation() throws Exception {
        PlayerMock player = addPlayer();

        assertFalse(evaluate(player, only(new HasMainClaimCondition())).allowed(), "aucun claim");
        assertTrue(evaluate(player, only(new NegatedCondition(new HasMainClaimCondition()))).allowed());
    }

    @Test
    void requiredLevelUsesTheSharedRule() throws Exception {
        PlayerMock player = addPlayer();
        checker.setRequiredLevelRule(p -> 4);
        PortalAccess level5 = new PortalAccess(5, "<red>Niveau <level> requis.</red>", List.of(), 0, "");

        PortalAccessChecker.Verdict v = evaluate(player, level5);

        assertFalse(v.allowed());
        assertEquals("LEVEL", v.firstRefusal().orElseThrow().code());
        assertEquals("Niveau 5 requis.", v.firstRefusal().orElseThrow().refusalText());
        checker.setRequiredLevelRule(p -> 5);
        assertTrue(evaluate(player, level5).allowed());
    }

    @Test
    void everyCheckIsReportedInOrderWithItsOwnMessage() throws Exception {
        PlayerMock player = addPlayer();
        checker.setRequiredLevelRule(p -> 10);
        PortalAccess access = new PortalAccess(2, "", List.of(
                new PortalAccess.Rule(new HasItemCondition(Material.DIAMOND, 1), "<red>Apporte un diamant.</red>"),
                new PortalAccess.Rule(new HasPermissionCondition("rpgquest.portal.arche"), "")), 0, "");

        PortalAccessChecker.Verdict v = evaluate(player, access);

        assertEquals(List.of("LEVEL", "CONDITION", "CONDITION"), v.checks().stream()
                .map(PortalAccessChecker.Check::code).toList());
        assertEquals(List.of(true, false, false), v.checks().stream().map(PortalAccessChecker.Check::passed).toList());
        assertEquals("Apporte un diamant.", v.firstRefusal().orElseThrow().refusalText());
        assertEquals("Tu n'as pas l'autorisation d'emprunter ce portail.", v.checks().get(2).refusalText(),
                "message vide = message par défaut de la condition");
    }

    @Test
    void cooldownIsPersistedAndOnlyReadWhenAsked() throws Exception {
        PlayerMock player = addPlayer();
        PortalAccess access = new PortalAccess(null, "", List.of(), 30, "");
        WorldPortalDefinition p = portal(access);
        assertTrue(evaluate(player, access).allowed());

        checker.recordPassage(player, p);
        long deadline = System.currentTimeMillis() + TIMEOUT * 1000;
        while (cooldownRepository.allForPlayer(player.getUniqueId()).get(TIMEOUT, TimeUnit.SECONDS).isEmpty()
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(20); // écriture asynchrone réelle (thread de la base).
        }

        assertFalse(evaluate(player, access).allowed());
        assertTrue(checker.evaluate(player, p, false).get(TIMEOUT, TimeUnit.SECONDS).allowed(),
                "sans délai demandé (test de téléportation), le délai n'est pas lu");
        assertEquals(NOW.plusSeconds(30), cooldownRepository.allForPlayer(player.getUniqueId())
                .get(TIMEOUT, TimeUnit.SECONDS).get("world:arche"), "clé distincte des portails de zone");
    }
}
