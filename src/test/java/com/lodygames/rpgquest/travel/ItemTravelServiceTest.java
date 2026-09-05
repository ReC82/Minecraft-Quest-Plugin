package com.lodygames.rpgquest.travel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.database.DatabaseManager;
import com.lodygames.rpgquest.database.ItemTravelCooldownRepository;
import com.lodygames.rpgquest.item.YamlCustomItemRegistry;
import com.lodygames.rpgquest.travel.model.ItemTravelDefinition;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * Mission « mécanique RPG générique de voyage par objet », premier objet : Pierre de retour. Couvre
 * le moteur générique ({@link ItemTravelService}) directement (comme {@code PortalServiceTest} pour
 * {@code PortalService}), pas la couche {@code PlayerInteractEvent} elle-même.
 */
class ItemTravelServiceTest {

    private static final long TIMEOUT_SECONDS = 5;
    private static final NamespacedKey PIERRE_RETOUR = new NamespacedKey("rpgquest", "pierre_retour");

    @TempDir
    Path tempDir;

    private ServerMock server;
    private RPGQuestPlugin plugin;
    private World world;
    private YamlCustomItemRegistry customItemRegistry;
    private DatabaseManager database;
    private ItemTravelService service;
    private Location destination;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(RPGQuestPlugin.class);
        world = server.addSimpleWorld("world");
        world.getBlockAt(50, 60, 50).setType(Material.STONE);
        destination = new Location(world, 50.5, 61, 50.5);

        customItemRegistry = new YamlCustomItemRegistry(tempDir.resolve("items"), plugin.getSLF4JLogger());
        customItemRegistry.start(); // génère notamment pierre_retour.yml.

        database = new DatabaseManager(tempDir.resolve("test.db"));
        database.initialize().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        service = new ItemTravelService(plugin, customItemRegistry,
                new ItemTravelCooldownRepository(database), plugin.getSLF4JLogger());
        service.start();
        service.register(new ItemTravelDefinition(
                PIERRE_RETOUR, 3, () -> Optional.of(destination), () -> Optional.of(world.getName())));
    }

    @AfterEach
    void tearDown() {
        service.stop();
        database.shutdown();
        MockBukkit.unmock();
    }

    private PlayerMock addPlayer() {
        PlayerMock player = server.addPlayer();
        player.teleport(new Location(world, 0.5, 61, 0.5));
        return player;
    }

    private ItemStack pierreDeRetour() {
        return customItemRegistry.create(PIERRE_RETOUR, 1).orElseThrow();
    }

    @Test
    void rightClickingTheRegisteredItemStartsChanneling() throws Exception {
        PlayerMock player = addPlayer();

        service.handleInteract(player, pierreDeRetour());
        awaitUntil(() -> service.isChanneling(player.getUniqueId()));

        assertTrue(service.isChanneling(player.getUniqueId()));
    }

    @Test
    void rightClickingAnUnrelatedItemNeverStartsChanneling() throws Exception {
        PlayerMock player = addPlayer();

        service.handleInteract(player, new ItemStack(Material.DIAMOND));
        awaitTicks(5);

        assertFalse(service.isChanneling(player.getUniqueId()));
    }

    @Test
    void movingDuringTheChannelCancelsItAndNeverTeleports() throws Exception {
        PlayerMock player = addPlayer();
        service.handleInteract(player, pierreDeRetour());
        awaitUntil(() -> service.isChanneling(player.getUniqueId()));

        player.teleport(new Location(world, 20.5, 61, 20.5));
        awaitUntil(() -> !service.isChanneling(player.getUniqueId()));

        awaitTicks(80); // laisse le temps aux 3s (60 ticks) de s'écouler si l'annulation avait échoué.
        assertFalse(service.isChanneling(player.getUniqueId()));
        assertEquals(new Location(world, 20.5, 61, 20.5), player.getLocation(), "aucun voyage ne doit avoir eu lieu");
    }

    @Test
    void damageDuringTheChannelCancelsItAndNeverTeleports() throws Exception {
        PlayerMock player = addPlayer();
        service.handleInteract(player, pierreDeRetour());
        awaitUntil(() -> service.isChanneling(player.getUniqueId()));

        service.handleDamage(player);

        assertFalse(service.isChanneling(player.getUniqueId()));
        awaitTicks(80);
        assertEquals(new Location(world, 0.5, 61, 0.5), player.getLocation(), "aucun voyage ne doit avoir eu lieu");
    }

    @Test
    void quittingDuringTheChannelClearsState() throws Exception {
        PlayerMock player = addPlayer();
        service.handleInteract(player, pierreDeRetour());
        awaitUntil(() -> service.isChanneling(player.getUniqueId()));

        service.handleQuit(player);

        assertFalse(service.isChanneling(player.getUniqueId()));
    }

    @Test
    void theItemIsNeverConsumedRegardlessOfTheOutcome() throws Exception {
        PlayerMock player = addPlayer();
        ItemStack item = pierreDeRetour();
        player.getInventory().addItem(item);
        int amountBefore = player.getInventory().all(item.getType()).values().stream().mapToInt(ItemStack::getAmount).sum();

        service.handleInteract(player, item);
        awaitTicks(80); // laisse le temps à la canalisation de se terminer (ou pas).

        int amountAfter = player.getInventory().all(item.getType()).values().stream().mapToInt(ItemStack::getAmount).sum();
        assertEquals(amountBefore, amountAfter, "l'objet de voyage ne doit jamais être consommé");
    }

    // ---- Restriction de monde (mission « Pierre de retour limitée à `claims` ») ----------------

    @Test
    void theItemDoesNothingOutsideItsRequiredWorld() throws Exception {
        World otherWorld = server.addSimpleWorld("wild");
        PlayerMock player = addPlayer();
        player.teleport(new Location(otherWorld, 0.5, 61, 0.5));

        service.handleInteract(player, pierreDeRetour());
        awaitTicks(5);

        assertFalse(service.isChanneling(player.getUniqueId()),
                "hors du monde requis par la définition, aucune canalisation ne doit démarrer");
    }

    @Test
    void anUnrestrictedDefinitionWorksInAnyWorld() throws Exception {
        World otherWorld = server.addSimpleWorld("wild");
        NamespacedKey unrestricted = new NamespacedKey("rpgquest", "acte_propriete");
        Location otherDestination = new Location(otherWorld, 5.5, 61, 5.5);
        service.register(new ItemTravelDefinition(unrestricted, 3, () -> Optional.of(otherDestination)));

        PlayerMock player = addPlayer();
        player.teleport(new Location(otherWorld, 0.5, 61, 0.5));
        ItemStack deed = customItemRegistry.create(unrestricted, 1).orElseThrow();

        service.handleInteract(player, deed);
        awaitUntil(() -> service.isChanneling(player.getUniqueId()));

        assertTrue(service.isChanneling(player.getUniqueId()),
                "sans restriction de monde (Optional::empty), l'objet doit fonctionner n'importe où");
    }

    // ---- Indicateur de canalisation (mission « indicateur de canalisation ») --------------------

    @Test
    void theProgressIndicatorReachesFullPercentThenIsClearedAfterASuccessfulTravel() throws Exception {
        PlayerMock player = addPlayer();
        service.handleInteract(player, pierreDeRetour());
        awaitUntil(() -> service.isChanneling(player.getUniqueId()));
        awaitUntil(() -> !service.isChanneling(player.getUniqueId()));

        List<String> renderedActionBars = drainActionBarsAsPlainText(player);

        assertTrue(renderedActionBars.contains("Voyage : 100%"),
                "la progression doit atteindre proprement 100% avant la complétion, jamais s'arrêter à un pourcentage tronqué");
        assertEquals("", renderedActionBars.get(renderedActionBars.size() - 1),
                "l'actionbar doit être retirée immédiatement après le succès, aucun résidu (ex. « 98% »)");
    }

    @Test
    void theProgressIndicatorIsClearedImmediatelyAfterACancelledTravel() throws Exception {
        PlayerMock player = addPlayer();
        service.handleInteract(player, pierreDeRetour());
        awaitUntil(() -> service.isChanneling(player.getUniqueId()));

        player.teleport(new Location(world, 20.5, 61, 20.5));
        awaitUntil(() -> !service.isChanneling(player.getUniqueId()));

        List<String> renderedActionBars = drainActionBarsAsPlainText(player);

        assertFalse(renderedActionBars.isEmpty());
        assertEquals("", renderedActionBars.get(renderedActionBars.size() - 1),
                "l'actionbar doit être retirée immédiatement après l'annulation, aucun résidu");
    }

    // ---- Cooldown (mission « Rune de rappel ») -------------------------------------------------

    private static final NamespacedKey RUNE = new NamespacedKey("rpgquest", "rune_rappel");

    private ItemStack rune() {
        return customItemRegistry.create(RUNE, 1).orElseThrow();
    }

    @Test
    void aSuccessfulTravelStartsACooldownThatBlocksTheNextUseAndIsPersisted() throws Exception {
        service.register(new ItemTravelDefinition(
                RUNE, 1, 3, () -> Optional.of(destination), () -> Optional.of(world.getName())));
        PlayerMock player = addPlayer();

        service.handleInteract(player, rune());
        awaitUntil(() -> service.isChanneling(player.getUniqueId()));
        awaitUntil(() -> !service.isChanneling(player.getUniqueId()));

        // Deuxième usage immédiat : refusé par le cooldown, aucune canalisation ne redémarre.
        player.teleport(new Location(world, 0.5, 61, 0.5));
        service.handleInteract(player, rune());
        awaitTicks(10);
        assertFalse(service.isChanneling(player.getUniqueId()),
                "un second usage pendant le cooldown ne doit jamais redémarrer de canalisation");

        var persisted = new ItemTravelCooldownRepository(database)
                .allForPlayer(player.getUniqueId()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertTrue(persisted.containsKey(RUNE.toString()), "le cooldown doit être persisté par joueur");
    }

    @Test
    void theRuneIsRefusedOutsideItsRequiredWorld() throws Exception {
        World other = server.addSimpleWorld("not_wild");
        service.register(new ItemTravelDefinition(
                RUNE, 1, 3, () -> Optional.of(destination), () -> Optional.of("wild")));
        PlayerMock player = addPlayer();
        player.teleport(new Location(other, 0.5, 61, 0.5));

        service.handleInteract(player, rune());
        awaitTicks(5);

        assertFalse(service.isChanneling(player.getUniqueId()),
                "hors du monde requis, la Rune ne doit jamais démarrer de canalisation");
    }

    private List<String> drainActionBarsAsPlainText(PlayerMock player) {
        List<String> rendered = new ArrayList<>();
        Component next;
        while ((next = player.nextActionBar()) != null) {
            rendered.add(PlainTextComponentSerializer.plainText().serialize(next));
        }
        return rendered;
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
