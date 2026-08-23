package com.lodygames.rpgquest.travel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.item.YamlCustomItemRegistry;
import com.lodygames.rpgquest.travel.model.ItemTravelDefinition;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.BooleanSupplier;
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
    private ItemTravelService service;
    private Location destination;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(RPGQuestPlugin.class);
        world = server.addSimpleWorld("world");
        world.getBlockAt(50, 60, 50).setType(Material.STONE);
        destination = new Location(world, 50.5, 61, 50.5);

        customItemRegistry = new YamlCustomItemRegistry(tempDir.resolve("items"), plugin.getSLF4JLogger());
        customItemRegistry.start(); // génère notamment pierre_retour.yml.

        service = new ItemTravelService(plugin, customItemRegistry, plugin.getSLF4JLogger());
        service.start();
        service.register(new ItemTravelDefinition(PIERRE_RETOUR, 3, () -> Optional.of(destination)));
    }

    @AfterEach
    void tearDown() {
        service.stop();
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
