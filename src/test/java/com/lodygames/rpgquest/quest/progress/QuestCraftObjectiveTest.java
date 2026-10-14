package com.lodygames.rpgquest.quest.progress;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
import java.util.concurrent.TimeUnit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryType.SlotType;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapelessRecipe;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.inventory.WorkbenchInventoryMock;

/**
 * Issue #34 — {@code CRAFT_ITEM} compte le nombre réel d'objets fabriqués, plus une unité par
 * événement. Les tests diffusent de vrais {@code CraftItemEvent} au plugin : c'est l'écouteur
 * enregistré par le moteur qui réagit.
 *
 * <p>MockBukkit n'exécute pas le déplacement du résultat après l'événement ; pour les clics
 * mesurés (shift-clic…), le test fait donc ce que ferait le serveur — poser les objets dans
 * l'inventaire — avant le tick suivant.</p>
 */
class QuestCraftObjectiveTest {

    private static final long TIMEOUT_SECONDS = 5;
    private static final NamespacedKey CRAFT_QUEST = new NamespacedKey("rpgquest", "craft_quest");
    private static final int REQUIRED = 20;

    @TempDir
    Path tempDir;

    private ServerMock server;
    private RPGQuestPlugin plugin;
    private DatabaseManager database;
    private PlayerProfileRepository profileRepository;
    private QuestProgressEngine engine;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(RPGQuestPlugin.class);

        database = new DatabaseManager(tempDir.resolve("test.db"));
        database.initialize().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        Path questsDir = tempDir.resolve("quests");
        Files.createDirectories(questsDir);
        Files.writeString(questsDir.resolve("craft_quest.yml"), """
                id: rpgquest:craft_quest
                title: "Titre"
                description: "Description"
                category: test
                steps:
                  - id: craft_step
                    objectives:
                      - type: CRAFT_ITEM
                        material: OAK_PLANKS
                        amount: %d
                """.formatted(REQUIRED));

        YamlQuestEngine questEngine = new YamlQuestEngine(questsDir, plugin.getSLF4JLogger());
        questEngine.reload();
        profileRepository = new PlayerProfileRepository(database);
        QuestMessagesService messagesService = new QuestMessagesService(plugin);
        messagesService.start();
        engine = new QuestProgressEngine(plugin, questEngine, new QuestProgressRepository(database),
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
        engine.accept(player, CRAFT_QUEST).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        return player;
    }

    /** Diffuse un craft de 4 planches avec {@code logs} bûches dans la grille. */
    private void craft(PlayerMock player, ClickType click, int logs) {
        craft(player, click, logs, null);
    }

    /** {@code cursor} est posé après l'ouverture de l'établi, que MockBukkit réinitialise. */
    private void craft(PlayerMock player, ClickType click, int logs, ItemStack cursor) {
        ItemStack planks = new ItemStack(Material.OAK_PLANKS, 4);
        CraftingInventory workbench = new WorkbenchInventoryMock(player);
        ItemStack[] matrix = new ItemStack[9];
        matrix[0] = new ItemStack(Material.OAK_LOG, logs);
        workbench.setMatrix(matrix);
        workbench.setResult(planks.clone());
        InventoryView view = player.openInventory(workbench);
        if (cursor != null) {
            player.setItemOnCursor(cursor);
        }
        ShapelessRecipe recipe = new ShapelessRecipe(new NamespacedKey("rpgquest", "test_planks"), planks);
        recipe.addIngredient(Material.OAK_LOG);
        server.getPluginManager().callEvent(new CraftItemEvent(recipe, view, SlotType.RESULT, 0, click,
                click.isShiftClick() ? InventoryAction.MOVE_TO_OTHER_INVENTORY : InventoryAction.PICKUP_ALL));
    }

    private int liveCounter(PlayerMock player) {
        return engine.activeStepView(player.getUniqueId(), CRAFT_QUEST)
                .map(view -> view.objectives().get(0).current())
                .orElse(-1);
    }

    @Test
    void aSingleCraftOfFourPlanksCountsFour() throws Exception {
        PlayerMock player = player();

        craft(player, ClickType.LEFT, 1);

        assertEquals(4, liveCounter(player));
    }

    @Test
    void twoNormalCraftsAccumulate() throws Exception {
        PlayerMock player = player();

        craft(player, ClickType.LEFT, 2);
        craft(player, ClickType.LEFT, 1, new ItemStack(Material.OAK_PLANKS, 4));

        assertEquals(8, liveCounter(player));
    }

    @Test
    void aClickWhoseCursorCannotTakeTheResultCraftsNothing() throws Exception {
        PlayerMock player = player();

        craft(player, ClickType.LEFT, 1, new ItemStack(Material.STONE, 1));
        craft(player, ClickType.LEFT, 1, new ItemStack(Material.OAK_PLANKS, 62));

        assertEquals(0, liveCounter(player));
    }

    @Test
    void aShiftClickCountsTheTotalReallyMovedToTheInventory() throws Exception {
        PlayerMock player = player();

        craft(player, ClickType.SHIFT_LEFT, 3);
        assertEquals(0, liveCounter(player), "la quantité est mesurée au tick suivant, pas devinée");
        player.getInventory().addItem(new ItemStack(Material.OAK_PLANKS, 12));
        server.getScheduler().performOneTick();

        assertEquals(12, liveCounter(player));
    }

    @Test
    void aShiftClickWithAFullInventoryCountsOnlyWhatFitted() throws Exception {
        PlayerMock player = player();
        for (int slot = 0; slot < 36; slot++) {
            player.getInventory().setItem(slot, new ItemStack(Material.STONE, 64));
        }
        player.getInventory().setItem(0, new ItemStack(Material.OAK_PLANKS, 56));

        craft(player, ClickType.SHIFT_LEFT, 5);
        // Seules 8 planches (2 crafts) tiennent dans la pile partielle.
        player.getInventory().setItem(0, new ItemStack(Material.OAK_PLANKS, 64));
        server.getScheduler().performOneTick();

        assertEquals(8, liveCounter(player));
    }

    @Test
    void aShiftClickNeverCountsMoreThanTheIngredientsAllow() throws Exception {
        PlayerMock player = player();

        craft(player, ClickType.SHIFT_LEFT, 1);
        // Un ramassage de 30 planches dans le même tick ne doit pas être compté comme un craft.
        player.getInventory().addItem(new ItemStack(Material.OAK_PLANKS, 34));
        server.getScheduler().performOneTick();

        assertEquals(4, liveCounter(player));
    }

    @Test
    void aNearlyCompleteObjectiveIsCappedAtTheRequiredAmount() throws Exception {
        PlayerMock player = player();
        engine.handleCraftItem(player, Material.OAK_PLANKS, REQUIRED - 2);

        craft(player, ClickType.SHIFT_LEFT, 4);
        player.getInventory().addItem(new ItemStack(Material.OAK_PLANKS, 16));
        server.getScheduler().performOneTick();

        assertEquals(-1, liveCounter(player), "l'étape unique est terminée, plus d'étape active");
        assertEquals(QuestState.COMPLETED, engine.stateOf(player.getUniqueId(), CRAFT_QUEST)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    @Test
    void aCreativeCloneIsNotACraft() throws Exception {
        PlayerMock player = player();

        craft(player, ClickType.MIDDLE, 1);
        server.getScheduler().performOneTick();

        assertEquals(0, liveCounter(player));
    }

    @Test
    void helpersComputeTheTheoreticalBounds() {
        assertEquals(3, QuestCraftItemListener.maxCrafts(new ItemStack[] {
                new ItemStack(Material.OAK_LOG, 5), null, new ItemStack(Material.STICK, 3)}));
        assertEquals(0, QuestCraftItemListener.measuredAmount(10, 6, 8));
        assertEquals(8, QuestCraftItemListener.measuredAmount(0, 40, 8));
    }
}
