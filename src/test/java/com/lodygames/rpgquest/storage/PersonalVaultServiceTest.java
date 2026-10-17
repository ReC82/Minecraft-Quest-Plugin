package com.lodygames.rpgquest.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.backpack.ItemArraySerializer;
import com.lodygames.rpgquest.database.DatabaseManager;
import com.lodygames.rpgquest.database.PersonalVaultRepository;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** Issue #104 — socle des coffres personnels : isolation, persistance, sessions uniques. */
class PersonalVaultServiceTest {

    private static final long TIMEOUT_SECONDS = 5;
    private static final VaultId VAULT_02 = VaultId.of("vault_02");

    @TempDir
    Path tempDir;

    private ServerMock server;
    private RPGQuestPlugin plugin;
    private DatabaseManager database;
    private PersonalVaultRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(RPGQuestPlugin.class);
        database = new DatabaseManager(tempDir.resolve("test.db"));
        database.initialize().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        repository = new PersonalVaultRepository(database);
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
        MockBukkit.unmock();
    }

    private PersonalVaultService newService() {
        PersonalVaultService service = new PersonalVaultService(plugin, repository, plugin.getSLF4JLogger());
        service.start();
        return service;
    }

    /** Thread DB réel puis retour au thread principal : alterne attentes et ticks (cf. BackpackServiceTest). */
    private <T> T await(CompletableFuture<T> future) throws Exception {
        long deadline = System.currentTimeMillis() + TIMEOUT_SECONDS * 1000;
        while (!future.isDone() && System.currentTimeMillis() < deadline) {
            server.getScheduler().performTicks(1);
            Thread.sleep(10);
        }
        return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private void awaitDatabaseIdle() throws Exception {
        database.execute(connection -> null).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private Inventory top(PlayerMock player) {
        return player.getOpenInventory().getTopInventory();
    }

    private void closeAndFlush(PlayerMock player) throws Exception {
        player.closeInventory();
        awaitDatabaseIdle();
    }

    private ItemStack complexItem() {
        ItemStack sword = new ItemStack(Material.DIAMOND_SWORD);
        ItemMeta meta = sword.getItemMeta();
        meta.displayName(Component.text("Lame du Hub"));
        meta.lore(List.of(Component.text("Ligne 1"), Component.text("Ligne 2")));
        meta.addEnchant(Enchantment.SHARPNESS, 3, true);
        meta.getPersistentDataContainer().set(new NamespacedKey(plugin, "custom_id"), PersistentDataType.STRING, "lame_hub");
        sword.setItemMeta(meta);
        return sword;
    }

    @Test
    void firstOpeningShowsAnEmptyVaultOfDefaultCapacity() throws Exception {
        PersonalVaultService service = newService();
        PlayerMock player = server.addPlayer();

        assertEquals(PersonalVaultService.OpenOutcome.OPENED, await(service.open(player, VaultId.DEFAULT)));
        assertEquals(PersonalVaultService.DEFAULT_SLOTS, top(player).getSize());
        for (ItemStack item : top(player).getContents()) {
            assertNull(item);
        }
    }

    @Test
    void twoPlayersUsingVault01HaveIndependentContents() throws Exception {
        PersonalVaultService service = newService();
        PlayerMock alice = server.addPlayer();
        PlayerMock bob = server.addPlayer();

        await(service.open(alice, VaultId.DEFAULT));
        top(alice).setItem(0, new ItemStack(Material.DIAMOND, 5));
        closeAndFlush(alice);

        await(service.open(bob, VaultId.DEFAULT));
        assertNull(top(bob).getItem(0), "Bob ne voit jamais le contenu d'Alice");
        top(bob).setItem(0, new ItemStack(Material.COBBLESTONE, 64));
        closeAndFlush(bob);

        await(service.open(alice, VaultId.DEFAULT));
        assertEquals(new ItemStack(Material.DIAMOND, 5), top(alice).getItem(0));
    }

    @Test
    void reopeningRestoresComplexItemsSlotForSlot() throws Exception {
        PersonalVaultService service = newService();
        PlayerMock player = server.addPlayer();
        ItemStack sword = complexItem();

        await(service.open(player, VaultId.DEFAULT));
        top(player).setItem(4, sword);
        top(player).setItem(26, new ItemStack(Material.OAK_LOG, 32));
        closeAndFlush(player);

        assertEquals(PersonalVaultService.OpenOutcome.OPENED, await(service.open(player, VaultId.DEFAULT)));
        assertEquals(sword, top(player).getItem(4));
        assertEquals("lame_hub", top(player).getItem(4).getItemMeta().getPersistentDataContainer()
                .get(new NamespacedKey(plugin, "custom_id"), PersistentDataType.STRING));
        assertEquals(new ItemStack(Material.OAK_LOG, 32), top(player).getItem(26));
        assertNull(top(player).getItem(0));
    }

    @Test
    void severalVaultIdsPerPlayerAreIndependent() throws Exception {
        PersonalVaultService service = newService();
        PlayerMock player = server.addPlayer();

        await(service.open(player, VaultId.DEFAULT));
        top(player).setItem(0, new ItemStack(Material.EMERALD, 1));
        closeAndFlush(player);
        await(service.open(player, VAULT_02));
        assertNull(top(player).getItem(0));
        top(player).setItem(0, new ItemStack(Material.GOLD_INGOT, 2));
        closeAndFlush(player);

        assertEquals(List.of("vault_01", "vault_02"), await(service.vaultIds(player.getUniqueId())));
        await(service.open(player, VaultId.DEFAULT));
        assertEquals(new ItemStack(Material.EMERALD, 1), top(player).getItem(0));
    }

    @Test
    void switchingDirectlyFromOneVaultToAnotherSavesTheFirst() throws Exception {
        PersonalVaultService service = newService();
        PlayerMock player = server.addPlayer();

        await(service.open(player, VaultId.DEFAULT));
        top(player).setItem(0, new ItemStack(Material.EMERALD, 3));
        await(service.open(player, VAULT_02)); // ferme implicitement vault_01
        closeAndFlush(player);

        await(service.open(player, VaultId.DEFAULT));
        assertEquals(new ItemStack(Material.EMERALD, 3), top(player).getItem(0));
    }

    @Test
    void contentsSurviveARestartWithAFreshService() throws Exception {
        PersonalVaultService first = newService();
        PlayerMock player = server.addPlayer();
        await(first.open(player, VaultId.DEFAULT));
        top(player).setItem(0, new ItemStack(Material.IRON_INGOT, 9));
        closeAndFlush(player);
        first.stop();

        PersonalVaultService restarted = newService();
        await(restarted.open(player, VaultId.DEFAULT));
        assertEquals(new ItemStack(Material.IRON_INGOT, 9), top(player).getItem(0));
    }

    @Test
    void stopSavesAVaultStillOpen() throws Exception {
        PersonalVaultService service = newService();
        PlayerMock player = server.addPlayer();
        await(service.open(player, VaultId.DEFAULT));
        top(player).setItem(1, new ItemStack(Material.BREAD, 4));

        service.stop();
        awaitDatabaseIdle();

        byte[] stored = repository.find(player.getUniqueId(), "vault_01").get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .orElseThrow().contents();
        assertEquals(new ItemStack(Material.BREAD, 4), ItemArraySerializer.deserialize(stored)[1]);
    }

    @Test
    void disconnectingSavesAndTheContentIsBackAfterReconnection() throws Exception {
        PersonalVaultService service = newService();
        PlayerMock player = server.addPlayer();
        await(service.open(player, VaultId.DEFAULT));
        top(player).setItem(2, new ItemStack(Material.APPLE, 6));

        service.handleQuit(player.getUniqueId());
        player.closeInventory(); // l'événement de fermeture qui suit une déconnexion reste sans effet
        awaitDatabaseIdle();

        await(service.open(player, VaultId.DEFAULT));
        assertEquals(new ItemStack(Material.APPLE, 6), top(player).getItem(2));
    }

    @Test
    void openingAnAlreadyDisplayedVaultKeepsTheSameInventoryAndStillSaves() throws Exception {
        PersonalVaultService service = newService();
        PlayerMock player = server.addPlayer();
        await(service.open(player, VaultId.DEFAULT));
        Inventory first = top(player);

        assertEquals(PersonalVaultService.OpenOutcome.ALREADY_OPEN, await(service.open(player, VaultId.DEFAULT)));
        assertSame(first, top(player));
        first.setItem(0, new ItemStack(Material.COAL, 10));
        closeAndFlush(player);

        await(service.open(player, VaultId.DEFAULT));
        assertEquals(new ItemStack(Material.COAL, 10), top(player).getItem(0));
    }

    @Test
    void concurrentOpeningsNeverCreateTwoInventories() throws Exception {
        PersonalVaultService service = newService();
        PlayerMock player = server.addPlayer();

        CompletableFuture<PersonalVaultService.OpenOutcome> a = service.open(player, VaultId.DEFAULT);
        CompletableFuture<PersonalVaultService.OpenOutcome> b = service.open(player, VaultId.DEFAULT);
        List<PersonalVaultService.OpenOutcome> outcomes = List.of(await(a), await(b));

        assertTrue(outcomes.contains(PersonalVaultService.OpenOutcome.OPENED));
        assertTrue(outcomes.contains(PersonalVaultService.OpenOutcome.ALREADY_OPEN));
    }

    @Test
    void unreadableContentIsRefusedAndNeverOverwritten() throws Exception {
        PersonalVaultService service = newService();
        PlayerMock player = server.addPlayer();
        byte[] garbage = {1, 2, 3};
        repository.save(player.getUniqueId(), "vault_01", 1, garbage).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(PersonalVaultService.OpenOutcome.LOAD_FAILED, await(service.open(player, VaultId.DEFAULT)));
        awaitDatabaseIdle();
        assertArrayEquals(garbage, repository.find(player.getUniqueId(), "vault_01")
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS).orElseThrow().contents());
    }

    @Test
    void vaultIdsAreValidated() {
        assertEquals("vault_01", VaultId.DEFAULT.value());
        assertThrows(IllegalArgumentException.class, () -> VaultId.of("Vault 01"));
        assertThrows(IllegalArgumentException.class, () -> VaultId.of(""));
        assertThrows(IllegalArgumentException.class, () -> VaultId.of(null));
    }
}
