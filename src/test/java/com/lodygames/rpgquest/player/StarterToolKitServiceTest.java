package com.lodygames.rpgquest.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.config.StarterToolKitConfig;
import com.lodygames.rpgquest.database.DatabaseManager;
import com.lodygames.rpgquest.database.PlayerProfileRepository;
import com.lodygames.rpgquest.database.PlayerVariableRepository;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * Kit d'outils en bois demandé au Guide (issue #26, partie A). Les quatre objets de
 * {@link StarterToolKitService} sont volontairement les quatre outils vanilla réels (jamais de
 * matériau factice) pour vérifier aussi le contenu exact livré.
 *
 * <p>{@code MockBukkit.load(RPGQuestPlugin.class)} démarre le <strong>vrai</strong> plugin
 * complet (sa propre base, ses propres écouteurs de connexion — ex. la Rune de rappel de
 * {@link StarterKitListener}) : des messages sans rapport peuvent donc apparaître dans la file du
 * joueur à tout moment. Les assertions ci-dessous n'utilisent jamais le premier message reçu ni un
 * compte strict de la file ; elles attendent un état déterministe (variable persistée,
 * inventaire) puis cherchent le message attendu <em>parmi</em> ceux reçus.</p>
 */
class StarterToolKitServiceTest {

    private static final long TIMEOUT_SECONDS = 5;
    private static final List<Material> KIT_ITEMS =
            List.of(Material.WOODEN_SWORD, Material.WOODEN_PICKAXE, Material.WOODEN_SHOVEL, Material.WOODEN_AXE);

    @TempDir
    Path tempDir;

    private ServerMock server;
    private RPGQuestPlugin plugin;
    private DatabaseManager database;
    private PlayerVariableRepository variableRepository;
    private PlayerProfileRepository profileRepository;
    private AtomicReference<StarterToolKitConfig> config;
    private StarterToolKitService service;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(RPGQuestPlugin.class);

        database = new DatabaseManager(tempDir.resolve("test.db"));
        database.initialize().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        variableRepository = new PlayerVariableRepository(database);
        profileRepository = new PlayerProfileRepository(database);

        config = new AtomicReference<>(new StarterToolKitConfig(true, KIT_ITEMS));
        service = new StarterToolKitService(plugin, variableRepository, config::get);
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
        MockBukkit.unmock();
    }

    /**
     * {@code MockBukkit.load(RPGQuestPlugin.class)} démarre le vrai plugin, dont les propres
     * écouteurs de connexion (ex. {@link StarterKitListener}, Rune de rappel) agissent de façon
     * asynchrone sur ce même joueur — on les laisse se terminer puis on repart d'un inventaire
     * connu pour que les tests d'espace libre restent déterministes.
     */
    private PlayerMock addPlayer() throws Exception {
        PlayerMock player = server.addPlayer();
        profileRepository.findOrCreate(player.getUniqueId(), player.getName()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        settle();
        player.getInventory().clear();
        return player;
    }

    private void tick() throws InterruptedException {
        server.getScheduler().performTicks(1);
        Thread.sleep(10);
    }

    private void settle() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 300;
        while (System.currentTimeMillis() < deadline) {
            tick();
        }
    }

    private String availableValue(UUID playerId) throws Exception {
        Optional<String> value = variableRepository.get(playerId, StarterToolKitService.AVAILABLE_KEY)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        return value.orElse(null);
    }

    /** Attend que la variable persistée atteigne {@code expected} (ou {@code null} pour « absente »). */
    private void awaitAvailableValue(UUID playerId, String expected) throws Exception {
        long deadline = System.currentTimeMillis() + TIMEOUT_SECONDS * 1000;
        String actual = availableValue(playerId);
        while (!Objects.equals(expected, actual) && System.currentTimeMillis() < deadline) {
            tick();
            actual = availableValue(playerId);
        }
        assertEquals(expected, actual, "droit au kit de départ jamais atteint l'état attendu avant le délai");
    }

    /**
     * Attend qu'un message contenant {@code expectedExcerpt} apparaisse, en ignorant tout message
     * sans rapport (ex. messages de connexion du vrai plugin démarré par {@code MockBukkit.load}) —
     * nécessaire pour synchroniser sur un refus, qui n'écrit jamais aucune variable.
     */
    private void awaitMessageContaining(PlayerMock player, String expectedExcerpt) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT_SECONDS * 1000;
        while (System.currentTimeMillis() < deadline) {
            String message = player.nextMessage();
            if (message != null && message.contains(expectedExcerpt)) {
                return;
            }
            tick();
        }
        fail("aucun message contenant \"" + expectedExcerpt + "\" reçu avant le délai");
    }

    private List<String> drainMessages(PlayerMock player) {
        List<String> messages = new ArrayList<>();
        String message;
        while ((message = player.nextMessage()) != null) {
            messages.add(message);
        }
        return messages;
    }

    private void assertAnyMessageContains(PlayerMock player, String expectedExcerpt) {
        List<String> messages = drainMessages(player);
        assertTrue(messages.stream().anyMatch(m -> m.contains(expectedExcerpt)),
                () -> "aucun message ne contient \"" + expectedExcerpt + "\" parmi : " + messages);
    }

    private void assertNoMessageContains(PlayerMock player, String excerpt) {
        List<String> messages = drainMessages(player);
        assertTrue(messages.stream().noneMatch(m -> m.contains(excerpt)),
                () -> "un message contient \"" + excerpt + "\" alors qu'aucun n'était attendu, obtenu : " + messages);
    }

    private int countOf(PlayerMock player, Material material) {
        return Arrays.stream(player.getInventory().getStorageContents())
                .filter(stack -> stack != null && stack.getType() == material)
                .mapToInt(ItemStack::getAmount)
                .sum();
    }

    private void assertHasExactlyOneKit(PlayerMock player) {
        for (Material material : KIT_ITEMS) {
            assertEquals(1, countOf(player, material), () -> "exactement un(e) " + material + " attendu(e)");
        }
    }

    private void assertHasNoKitItem(PlayerMock player) {
        for (Material material : KIT_ITEMS) {
            assertEquals(0, countOf(player, material), () -> "aucun " + material + " ne doit avoir été distribué");
        }
    }

    private void fillStorageLeavingFreeSlots(PlayerMock player, int freeSlots) {
        ItemStack[] storage = player.getInventory().getStorageContents();
        for (int i = 0; i < storage.length - freeSlots; i++) {
            player.getInventory().setItem(i, new ItemStack(Material.COBBLESTONE, 64));
        }
    }

    private void simulateDeath(PlayerMock player) {
        service.onDeath(new PlayerDeathEvent(player, DamageSource.builder(DamageType.GENERIC).build(),
                new ArrayList<>(), 0, Component.text("t"), false));
    }

    @Test
    void firstRequestGrantsExactlyTheFourWoodenToolsAndNothingElse() throws Exception {
        PlayerMock player = addPlayer();

        service.requestKit(player);
        awaitAvailableValue(player.getUniqueId(), "false");

        assertHasExactlyOneKit(player);
        assertAnyMessageContains(player, "kit de départ");
    }

    @Test
    void refusesWhenFewerThanFourFreeSlotsAndPreservesTheRight() throws Exception {
        PlayerMock player = addPlayer();
        fillStorageLeavingFreeSlots(player, 3);

        service.requestKit(player);
        awaitMessageContaining(player, "pas assez de place"); // aucune variable à attendre : un refus n'écrit jamais rien.

        assertHasNoKitItem(player);
        assertNull(availableValue(player.getUniqueId()), "le droit ne doit pas être consommé par un refus");
    }

    @Test
    void retryingAfterFreeingSpaceSucceeds() throws Exception {
        PlayerMock player = addPlayer();
        fillStorageLeavingFreeSlots(player, 3);

        service.requestKit(player);
        awaitMessageContaining(player, "pas assez de place");
        assertHasNoKitItem(player);

        // Libère un emplacement supplémentaire (4 au total) puis redemande.
        player.getInventory().setItem(0, null);
        service.requestKit(player);
        awaitAvailableValue(player.getUniqueId(), "false");

        assertHasExactlyOneKit(player);
        assertAnyMessageContains(player, "kit de départ");
    }

    @Test
    void secondRequestWithoutDeathIsRefusedAndExplained() throws Exception {
        PlayerMock player = addPlayer();
        service.requestKit(player);
        awaitAvailableValue(player.getUniqueId(), "false");

        service.requestKit(player);
        settle();

        assertAnyMessageContains(player, "déjà reçu");
        assertHasExactlyOneKit(player); // toujours un seul exemplaire, pas un second
    }

    @Test
    void aDeathAfterSuccessReopensTheRightWithoutAutomaticGrant() throws Exception {
        PlayerMock player = addPlayer();
        service.requestKit(player);
        awaitAvailableValue(player.getUniqueId(), "false");

        simulateDeath(player);
        awaitAvailableValue(player.getUniqueId(), "true");
        assertHasExactlyOneKit(player); // la mort seule ne redonne jamais rien automatiquement

        service.requestKit(player);
        awaitAvailableValue(player.getUniqueId(), "false");

        assertEquals(2, countOf(player, Material.WOODEN_SWORD),
                "deux cycles mort/remise = deux épées (une par cycle), jamais plus");
    }

    @Test
    void multipleDeathGrantCyclesEachAllowExactlyOneGrant() throws Exception {
        PlayerMock player = addPlayer();
        for (int cycle = 1; cycle <= 3; cycle++) {
            service.requestKit(player);
            awaitAvailableValue(player.getUniqueId(), "false");
            simulateDeath(player);
            awaitAvailableValue(player.getUniqueId(), "true");
        }

        assertEquals(3, countOf(player, Material.WOODEN_AXE), "trois morts/remises => trois haches, jamais plus");
    }

    @Test
    void aDeathBeforeTheFirstGrantDoesNotRemoveTheInitialRight() throws Exception {
        PlayerMock player = addPlayer();

        simulateDeath(player);
        awaitAvailableValue(player.getUniqueId(), "true");

        service.requestKit(player);
        awaitAvailableValue(player.getUniqueId(), "false");

        assertHasExactlyOneKit(player);
        assertAnyMessageContains(player, "kit de départ");
    }

    @Test
    void rapidDoubleClickNeverGrantsTwice() throws Exception {
        PlayerMock player = addPlayer();

        service.requestKit(player);
        service.requestKit(player); // clic rapide avant que le premier n'ait pu aboutir
        awaitAvailableValue(player.getUniqueId(), "false");
        settle(); // laisse le temps à un éventuel (faux) second octroi de se manifester

        assertHasExactlyOneKit(player);
        long successMessages = drainMessages(player).stream()
                .filter(m -> m.contains("reçois ton kit de départ")).count();
        assertEquals(1, successMessages, "un clic rapide ne doit jamais produire deux remises");
    }

    @Test
    void resettingTheVariableRestoresTheInitialRightLikeResetnewWould() throws Exception {
        PlayerMock player = addPlayer();
        service.requestKit(player);
        awaitAvailableValue(player.getUniqueId(), "false");

        // Même mécanisme que /rpgadmin player resetnew (PlayerResetService#resetToNewPlayer) :
        // suppression de toutes les variables du joueur, sans code dédié au kit.
        variableRepository.deleteAllForPlayer(player.getUniqueId()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertNull(availableValue(player.getUniqueId()));

        service.requestKit(player);
        awaitAvailableValue(player.getUniqueId(), "false");
        assertAnyMessageContains(player, "kit de départ");
    }

    @Test
    void disabledKitNeverGrantsAnything() throws Exception {
        config.set(new StarterToolKitConfig(false, KIT_ITEMS));
        PlayerMock player = addPlayer();

        service.requestKit(player);
        settle();

        assertHasNoKitItem(player);
        assertNull(availableValue(player.getUniqueId()));
        assertNoMessageContains(player, "kit de départ");
    }
}
