package com.lodygames.rpgquest.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.database.DatabaseManager;
import com.lodygames.rpgquest.database.PlayerProfileRepository;
import com.lodygames.rpgquest.database.PlayerVariableRepository;
import com.lodygames.rpgquest.item.RpgItemKeys;
import com.lodygames.rpgquest.item.YamlCustomItemRegistry;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.bukkit.Material;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * {@link StarterKitListener} isolé de sa propre instance bootstrappée (même technique que
 * {@code StarterToolKitServiceTest} : {@code MockBukkit.load(RPGQuestPlugin.class)} fait tourner
 * en arrière-plan le <strong>vrai</strong> {@code StarterKitListener} du plugin réel sur la même
 * base {@code player_variables}/registre d'objets que celle du plugin -- sans effet sur les
 * assertions ci-dessous, qui portent sur une instance et une base entièrement séparées).
 *
 * <p>Retour joueur 2026-10-04 (suite issue #154) : couvre le garde-fou anti-perte -- un inventaire
 * plein à la première connexion ne doit plus jamais marquer la Rune comme distribuée pour
 * toujours.</p>
 */
class StarterKitListenerTest {

    private static final long TIMEOUT_SECONDS = 5;

    @TempDir
    Path tempDir;

    private ServerMock server;
    private RPGQuestPlugin plugin;
    private DatabaseManager database;
    private PlayerVariableRepository variableRepository;
    private PlayerProfileRepository profileRepository;
    private YamlCustomItemRegistry customItemRegistry;
    private StarterKitListener listener;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(RPGQuestPlugin.class);

        database = new DatabaseManager(tempDir.resolve("test.db"));
        database.initialize().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        variableRepository = new PlayerVariableRepository(database);
        profileRepository = new PlayerProfileRepository(database);
        customItemRegistry = new YamlCustomItemRegistry(tempDir.resolve("items"), plugin.getSLF4JLogger());
        customItemRegistry.start();

        listener = new StarterKitListener(plugin, variableRepository, customItemRegistry);
        server.getPluginManager().registerEvents(listener, plugin);
    }

    /** {@code player_variables.player_uuid} a une clé étrangère vers {@code player_profiles} : la
     * créer d'abord, comme le fait le vrai parcours de connexion, sinon l'écriture de
     * {@code GRANTED_VARIABLE} échoue silencieusement (capturée par {@code .exceptionally}). */
    private PlayerMock addPlayer() throws Exception {
        PlayerMock player = server.addPlayer();
        profileRepository.findOrCreate(player.getUniqueId(), player.getName()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        return player;
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
        MockBukkit.unmock();
    }

    @Test
    void grantsTheRuneOnFirstJoinWhenAbsent() throws Exception {
        PlayerMock player = addPlayer();
        settle();

        assertTrue(hasRune(player), "la Rune de rappel doit être distribuée à la première connexion");
        assertEquals("true", variableValue(player.getUniqueId()));
    }

    @Test
    void neverDuplicatesWhenTheRuneIsAlreadyPresent() throws Exception {
        PlayerMock player = addPlayer();
        settle();
        player.getInventory().clear();
        customItemRegistry.create(RpgItemKeys.RUNE_RAPPEL, 1).ifPresent(stack -> player.getInventory().addItem(stack));

        listener.onJoin(new PlayerJoinEvent(player, (net.kyori.adventure.text.Component) null));
        settle();

        assertEquals(1, countRunes(player), "aucun exemplaire supplémentaire si le joueur en a déjà une");
    }

    /**
     * Retour joueur 2026-10-04 : avant ce correctif, un inventaire plein à la première connexion
     * faisait perdre silencieusement la Rune pour toujours ({@code GRANTED_VARIABLE} étant marqué
     * même en cas d'échec de {@code addItem}). Elle doit maintenant rester accessible : rien n'est
     * marqué distribué tant qu'elle n'a pas réellement pu être ajoutée.
     */
    @Test
    void fullInventoryAtFirstJoinNeverMarksTheRuneAsGrantedAndRetriesLater() throws Exception {
        PlayerMock player = addPlayer();
        fillInventoryCompletely(player);
        settle();

        assertFalse(hasRune(player), "inventaire plein : rien n'a pu être ajouté à cette connexion");
        assertNull(variableValue(player.getUniqueId()),
                "jamais marqué distribué tant que la Rune n'a pas pu être réellement donnée");

        // Une place se libère, puis une nouvelle connexion est simulée (rejoue le même écouteur).
        player.getInventory().clear();
        listener.onJoin(new PlayerJoinEvent(player, (net.kyori.adventure.text.Component) null));
        settle();

        assertTrue(hasRune(player), "une fois de la place libérée, la Rune doit être distribuée à la connexion suivante");
        assertEquals("true", variableValue(player.getUniqueId()));
    }

    // ---- helpers ----------------------------------------------------------------------

    private void fillInventoryCompletely(PlayerMock player) {
        for (int i = 0; i < player.getInventory().getSize(); i++) {
            player.getInventory().setItem(i, new ItemStack(Material.STONE, 64));
        }
    }

    private boolean hasRune(PlayerMock player) {
        return countRunes(player) > 0;
    }

    private int countRunes(PlayerMock player) {
        return (int) Arrays.stream(player.getInventory().getContents())
                .filter(Objects::nonNull)
                .filter(stack -> customItemRegistry.identify(stack).map(RpgItemKeys.RUNE_RAPPEL::equals).orElse(false))
                .count();
    }

    private String variableValue(UUID playerId) throws Exception {
        Optional<String> value = variableRepository.get(playerId, StarterKitListener.GRANTED_VARIABLE)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        return value.orElse(null);
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
}
