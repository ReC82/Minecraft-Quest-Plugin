package be.lloyd.rpgquest.backpack;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import be.lloyd.rpgquest.RPGQuestPlugin;
import be.lloyd.rpgquest.backpack.model.BackpackSize;
import be.lloyd.rpgquest.config.BackpackConfig;
import be.lloyd.rpgquest.database.BackpackRepository;
import be.lloyd.rpgquest.database.DatabaseManager;
import be.lloyd.rpgquest.database.EntitlementRepository;
import be.lloyd.rpgquest.database.PlayerProfileRepository;
import be.lloyd.rpgquest.entitlement.EntitlementService;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.bukkit.Material;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** Couvre les tests automatiques étape 20 « backpack imbriqué » et objets explicitement interdits. */
class BackpackListenerTest {

    private static final long TIMEOUT_SECONDS = 5;

    @TempDir
    Path tempDir;

    private ServerMock server;
    private RPGQuestPlugin plugin;
    private DatabaseManager database;
    private BackpackService service;
    private BackpackListener listener;
    private PlayerMock player;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(RPGQuestPlugin.class);

        database = new DatabaseManager(tempDir.resolve("test.db"));
        database.initialize().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        BackpackRepository repository = new BackpackRepository(database);
        EntitlementService entitlementService = new EntitlementRepository(database);
        PlayerProfileRepository profiles = new PlayerProfileRepository(database);

        BackpackConfig config = new BackpackConfig(
                1, 3, 6, Set.of(Material.BEDROCK), BackpackSize.SMALL, Material.BUNDLE);
        service = new BackpackService(plugin, repository, entitlementService, () -> config, plugin.getSLF4JLogger());
        service.start();
        listener = (BackpackListener) service.listener();

        player = server.addPlayer();
        profiles.findOrCreate(player.getUniqueId(), player.getName()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        player.addAttachment(plugin, BackpackService.FALLBACK_PERMISSION, true);
        await(service.open(player));
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
        MockBukkit.unmock();
    }

    private <T> T await(CompletableFuture<T> future) throws Exception {
        long deadline = System.currentTimeMillis() + TIMEOUT_SECONDS * 1000;
        while (!future.isDone() && System.currentTimeMillis() < deadline) {
            server.getScheduler().performTicks(1);
            Thread.sleep(10);
        }
        return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private InventoryView view() {
        return player.getOpenInventory();
    }

    @Test
    void placingTheOpenItemIntoTheBackpackIsCancelled() {
        ItemStack openItem = service.createOpenItem();
        player.setItemOnCursor(openItem);

        InventoryClickEvent event = new InventoryClickEvent(
                view(), InventoryType.SlotType.CONTAINER, 0, ClickType.LEFT, InventoryAction.PLACE_ALL);

        listener.onInventoryClick(event);

        assertTrue(event.isCancelled(), "un backpack ne doit jamais pouvoir être imbriqué dans un autre");
    }

    @Test
    void placingAnExplicitlyForbiddenMaterialIsCancelled() {
        player.setItemOnCursor(new ItemStack(Material.BEDROCK));

        InventoryClickEvent event = new InventoryClickEvent(
                view(), InventoryType.SlotType.CONTAINER, 0, ClickType.LEFT, InventoryAction.PLACE_ALL);

        listener.onInventoryClick(event);

        assertTrue(event.isCancelled());
    }

    @Test
    void placingAnOrdinaryAllowedItemIsNeverCancelled() {
        player.setItemOnCursor(new ItemStack(Material.DIAMOND));

        InventoryClickEvent event = new InventoryClickEvent(
                view(), InventoryType.SlotType.CONTAINER, 0, ClickType.LEFT, InventoryAction.PLACE_ALL);

        listener.onInventoryClick(event);

        assertFalse(event.isCancelled());
    }

    @Test
    void clicksOutsideTheBackpackViewAreIgnored() {
        // La vue du joueur n'est pas un backpack ici : jamais annulé, jamais consulté.
        InventoryView playerOwnView = server.addPlayer().getOpenInventory();
        InventoryClickEvent event = new InventoryClickEvent(
                playerOwnView, InventoryType.SlotType.CONTAINER, 0, ClickType.LEFT, InventoryAction.PLACE_ALL);

        listener.onInventoryClick(event);

        assertFalse(event.isCancelled());
    }

    @Test
    void draggingTheOpenItemAcrossTheBackpackIsCancelled() {
        ItemStack openItem = service.createOpenItem();
        InventoryDragEvent event = new InventoryDragEvent(
                view(), null, openItem, false, Map.of(0, openItem));

        listener.onInventoryDrag(event);

        assertTrue(event.isCancelled());
    }

    @Test
    void draggingAnAllowedItemAcrossTheBackpackIsNeverCancelled() {
        ItemStack diamond = new ItemStack(Material.DIAMOND);
        InventoryDragEvent event = new InventoryDragEvent(
                view(), null, diamond, false, Map.of(0, diamond));

        listener.onInventoryDrag(event);

        assertFalse(event.isCancelled());
    }
}
