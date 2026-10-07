package com.lodygames.rpgquest.quest.progress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.database.DatabaseManager;
import com.lodygames.rpgquest.database.NpcBindingRepository;
import com.lodygames.rpgquest.database.NpcIdRepository;
import com.lodygames.rpgquest.database.PlayerProfileRepository;
import com.lodygames.rpgquest.database.PlayerVariableRepository;
import com.lodygames.rpgquest.database.QuestProgressRepository;
import com.lodygames.rpgquest.economy.QuestRewardPayer;
import com.lodygames.rpgquest.npc.NpcIdentityService;
import com.lodygames.rpgquest.quest.QuestMessagesService;
import com.lodygames.rpgquest.quest.YamlQuestEngine;
import com.lodygames.rpgquest.quest.model.QuestState;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.inventory.FurnaceExtractEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * Issue #141 — objectif {@code SMELT_ITEM}. Le cas d'usage initial est celui de la story « Les
 * souvenirs de Lily » : cuire un cactus pour obtenir de la teinture verte.
 *
 * <p>Les tests passent par de vrais {@code FurnaceExtractEvent} diffusés au plugin, donc par le
 * vrai écouteur enregistré par le moteur — pas par un appel direct à la méthode interne.</p>
 */
class QuestSmeltObjectiveTest {

    private static final long TIMEOUT_SECONDS = 5;
    private static final NamespacedKey SMELT_QUEST = new NamespacedKey("rpgquest", "smelt_quest");
    private static final String STEP = "smelt_step";

    @TempDir
    Path tempDir;

    private ServerMock server;
    private RPGQuestPlugin plugin;
    private DatabaseManager database;
    private QuestProgressRepository progressRepository;
    private PlayerProfileRepository profileRepository;
    private QuestProgressEngine engine;
    private World world;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(RPGQuestPlugin.class);
        world = server.addSimpleWorld("world");

        database = new DatabaseManager(tempDir.resolve("test.db"));
        database.initialize().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        Path questsDir = tempDir.resolve("quests");
        Files.createDirectories(questsDir);
        Files.writeString(questsDir.resolve("smelt_quest.yml"), """
                id: rpgquest:smelt_quest
                title: "Titre"
                description: "Description"
                category: test
                steps:
                  - id: smelt_step
                    objectives:
                      - type: SMELT_ITEM
                        material: GREEN_DYE
                        amount: 3
                """);

        YamlQuestEngine questEngine = new YamlQuestEngine(questsDir, plugin.getSLF4JLogger());
        questEngine.reload();
        progressRepository = new QuestProgressRepository(database);
        profileRepository = new PlayerProfileRepository(database);
        QuestMessagesService messagesService = new QuestMessagesService(plugin);
        messagesService.start();
        engine = new QuestProgressEngine(plugin, questEngine, progressRepository,
                new PlayerVariableRepository(database), messagesService,
                new NpcIdentityService(plugin, new NpcIdRepository(database), new NpcBindingRepository(database)),
                QuestRewardPayer.unavailable());
        engine.start();
    }

    @AfterEach
    void tearDown() {
        engine.stop();
        database.shutdown();
        MockBukkit.unmock();
    }

    private PlayerMock player() throws Exception {
        PlayerMock player = server.addPlayer();
        profileRepository.findOrCreate(player.getUniqueId(), player.getName()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        engine.accept(player, SMELT_QUEST).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        return player;
    }

    /** Diffuse une extraction réelle : c'est l'écouteur enregistré par le moteur qui réagit. */
    private void extract(PlayerMock player, Material furnace, Material result, int amount) {
        extract(player, furnace, result, amount, Math.max(1, amount));
    }

    /**
     * {@code itemAmount} est la quantité portée par l'ÉVÉNEMENT, distincte de celle de la pile :
     * Bukkit refuse un {@code ItemStack} de 0, alors que l'événement peut annoncer 0.
     */
    private void extract(PlayerMock player, Material furnace, Material result, int itemAmount, int stackAmount) {
        Block block = world.getBlockAt(0, 64, 0);
        block.setType(furnace);
        server.getPluginManager().callEvent(
                new FurnaceExtractEvent(player, block, new ItemStack(result, stackAmount), itemAmount, 0));
    }

    private int counter(PlayerMock player) throws Exception {
        Map<Integer, Integer> counters = progressRepository
                .findObjectiveProgress(player.getUniqueId(), SMELT_QUEST, STEP)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        return counters.getOrDefault(0, 0);
    }

    private int liveCounter(PlayerMock player) {
        return engine.activeStepView(player.getUniqueId(), SMELT_QUEST)
                .map(view -> view.objectives().get(0).current())
                .orElse(-1);
    }

    @Test
    void extractingTheSmeltedResultProgressesTheObjective() throws Exception {
        PlayerMock player = player();

        extract(player, Material.FURNACE, Material.GREEN_DYE, 1);

        assertEquals(1, liveCounter(player));
    }

    /** Une extraction peut sortir plusieurs objets d'un coup : la progression suit la quantité. */
    @Test
    void aBulkExtractionProgressesByItsRealAmount() throws Exception {
        PlayerMock player = player();

        extract(player, Material.FURNACE, Material.GREEN_DYE, 2);

        assertEquals(2, liveCounter(player));
        Thread.sleep(80);
        assertEquals(2, counter(player), "la valeur persistée suit la quantité réelle");
    }

    @Test
    void anOversizedExtractionNeverExceedsTheRequiredAmount() throws Exception {
        PlayerMock player = player();

        extract(player, Material.FURNACE, Material.GREEN_DYE, 64);

        assertEquals(QuestState.COMPLETED, engine.stateOf(player.getUniqueId(), SMELT_QUEST)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS), "3 demandés, 64 sortis : la quête se termine");
    }

    @Test
    void theThreeVanillaFurnaceTypesAllCount() throws Exception {
        PlayerMock player = player();

        extract(player, Material.FURNACE, Material.GREEN_DYE, 1);
        extract(player, Material.BLAST_FURNACE, Material.GREEN_DYE, 1);
        extract(player, Material.SMOKER, Material.GREEN_DYE, 1);

        assertEquals(QuestState.COMPLETED, engine.stateOf(player.getUniqueId(), SMELT_QUEST)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    @Test
    void anotherBlockEmittingTheEventNeverCounts() throws Exception {
        PlayerMock player = player();

        // Bloc qui n'est pas un four : le type est vérifié explicitement, jamais supposé.
        extract(player, Material.CHEST, Material.GREEN_DYE, 5);

        assertEquals(0, liveCounter(player));
    }

    @Test
    void smeltingAnotherItemNeverCounts() throws Exception {
        PlayerMock player = player();

        extract(player, Material.FURNACE, Material.IRON_INGOT, 5);

        assertEquals(0, liveCounter(player));
    }

    /** Obtenir l'objet autrement (coffre, /give, craft, ramassage) n'émet aucun événement de four. */
    @Test
    void gettingTheItemWithoutSmeltingItNeverCounts() throws Exception {
        PlayerMock player = player();
        player.getInventory().addItem(new ItemStack(Material.GREEN_DYE, 64));

        engine.handleCollectItem(player, Material.GREEN_DYE);
        engine.handleCraftItem(player, Material.GREEN_DYE);

        assertEquals(0, liveCounter(player), "posséder, ramasser ou fabriquer ne cuit rien");
    }

    /** L'attribution vient du joueur de l'événement : un autre joueur ne progresse jamais. */
    @Test
    void progressGoesOnlyToThePlayerWhoExtracted() throws Exception {
        PlayerMock first = player();
        PlayerMock second = player();

        extract(first, Material.FURNACE, Material.GREEN_DYE, 2);

        assertEquals(2, liveCounter(first));
        assertEquals(0, liveCounter(second), "le four d'un joueur ne fait pas progresser l'autre");
    }

    @Test
    void progressSurvivesAReconnection() throws Exception {
        PlayerMock player = player();
        extract(player, Material.FURNACE, Material.GREEN_DYE, 2);
        Thread.sleep(80);

        engine.unloadForPlayer(player.getUniqueId());
        engine.loadForPlayer(player.getUniqueId()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(2, liveCounter(player));
        extract(player, Material.FURNACE, Material.GREEN_DYE, 1);
        assertEquals(QuestState.COMPLETED, engine.stateOf(player.getUniqueId(), SMELT_QUEST)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    @Test
    void anEventWithoutAnyItemIsIgnored() throws Exception {
        PlayerMock player = player();

        extract(player, Material.FURNACE, Material.GREEN_DYE, 0, 1);

        assertEquals(0, liveCounter(player));
    }

    @Test
    void noSmeltObjectiveMeansNoListenerCrash() throws Exception {
        PlayerMock player = player();
        // Un matériau sans aucun objectif : l'index renvoie une liste vide, rien ne se passe.
        assertTrue(engine.activeStepView(player.getUniqueId(), SMELT_QUEST).isPresent());
        extract(player, Material.FURNACE, Material.COOKED_BEEF, 3);
        assertEquals(0, liveCounter(player));
    }
}
