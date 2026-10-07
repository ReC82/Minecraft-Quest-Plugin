package com.lodygames.rpgquest.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.config.StarterKitTier;
import com.lodygames.rpgquest.config.StarterToolKitConfig;
import com.lodygames.rpgquest.database.DatabaseManager;
import com.lodygames.rpgquest.database.PlayerProfileRepository;
import com.lodygames.rpgquest.database.PlayerVariableRepository;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
 * Issue #218 — progression du kit de départ par paliers. Couvre ce que le ticket exige : palier
 * persistant, impossible de sauter un palier, kit récupéré correspondant au meilleur palier
 * débloqué, une seule remise réussie par vie, emplacements requis calculés sur le contenu réel, et
 * survie à la reconnexion / au redémarrage.
 *
 * <p>Les règles héritées de #26 (aucune remise automatique, tout ou rien, anti double-clic) restent
 * couvertes par {@link StarterToolKitServiceTest} ; ce test-ci ne couvre que ce qu'ajoute #218.</p>
 */
class StarterKitTierProgressionTest {

    private static final long TIMEOUT_SECONDS = 5;

    private static final List<Material> TIER_1 = List.of(
            Material.WOODEN_SWORD, Material.WOODEN_PICKAXE, Material.WOODEN_SHOVEL, Material.WOODEN_AXE);
    /** Contenu décidé du palier 2 : six objets — donc six emplacements requis, pas quatre. */
    private static final List<Material> TIER_2 = List.of(
            Material.STONE_SWORD, Material.WOODEN_PICKAXE, Material.WOODEN_SHOVEL, Material.WOODEN_AXE,
            Material.LEATHER_BOOTS, Material.BREAD);

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

        config = new AtomicReference<>(twoTiers());
        service = new StarterToolKitService(plugin, variableRepository, config::get);
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
        MockBukkit.unmock();
    }

    private static StarterToolKitConfig twoTiers() {
        return new StarterToolKitConfig(true, List.of(
                new StarterKitTier(1, "Nouveau venu", TIER_1, null),
                new StarterKitTier(2, "Premiers pas dans le Wild", TIER_2, "rpgquest:kit_tier2")));
    }

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

    private List<Material> kitInInventory(PlayerMock player) {
        List<Material> out = new ArrayList<>();
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack != null && !stack.getType().isAir()) {
                out.add(stack.getType());
            }
        }
        return out;
    }

    private void simulateDeath(PlayerMock player) {
        service.onDeath(new PlayerDeathEvent(player, DamageSource.builder(DamageType.GENERIC).build(),
                new ArrayList<>(), 0, Component.text("t"), false));
    }

    private void leaveFreeSlots(PlayerMock player, int freeSlots) {
        int storage = player.getInventory().getStorageContents().length;
        for (int i = 0; i < storage - freeSlots; i++) {
            player.getInventory().setItem(i, new ItemStack(Material.COBBLESTONE, 64));
        }
    }

    private int tierOf(PlayerMock player) throws Exception {
        return service.unlockedTier(player.getUniqueId()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private StarterToolKitService.GrantResult grant(PlayerMock player, int level) throws Exception {
        return service.grantTier(player.getUniqueId(), level).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    // ---- Palier par défaut et progression -------------------------------

    @Test
    void aNewPlayerIsAtTierOneWithoutAnyUnlock() throws Exception {
        PlayerMock player = addPlayer();

        assertEquals(1, tierOf(player), "le palier 1 est acquis d'office, sans quête");
        service.requestKit(player);
        settle();
        assertEquals(TIER_1, kitInInventory(player));
    }

    @Test
    void unlockingTierTwoChangesTheKitActuallyHandedOut() throws Exception {
        PlayerMock player = addPlayer();

        assertEquals(StarterToolKitService.GrantOutcome.GRANTED, grant(player, 2).outcome());
        assertEquals(2, tierOf(player));

        service.requestKit(player);
        settle();
        assertEquals(TIER_2, kitInInventory(player), "le kit remis suit le meilleur palier débloqué");
    }

    @Test
    void grantingTheSameTierTwiceChangesNothing() throws Exception {
        PlayerMock player = addPlayer();
        grant(player, 2);

        StarterToolKitService.GrantResult again = grant(player, 2);

        assertEquals(StarterToolKitService.GrantOutcome.ALREADY_AT_LEAST, again.outcome(),
                "une quête rejouée ne doit jamais « redonner » un palier");
        assertEquals(2, again.tier());
    }

    // ---- Impossible de sauter un palier ---------------------------------

    @Test
    void skippingATierIsRefused() throws Exception {
        PlayerMock player = addPlayer();
        config.set(new StarterToolKitConfig(true, List.of(
                new StarterKitTier(1, "Nouveau venu", TIER_1, null),
                new StarterKitTier(2, "Premiers pas", TIER_2, "rpgquest:kit_tier2"),
                new StarterKitTier(3, "Palier 3", List.of(Material.STONE_AXE), "rpgquest:kit_tier3"))));

        StarterToolKitService.GrantResult jump = grant(player, 3);

        assertEquals(StarterToolKitService.GrantOutcome.SKIPPED, jump.outcome());
        assertEquals(1, tierOf(player), "aucune écriture : le joueur reste au palier 1");

        // Le chemin normal, lui, fonctionne palier par palier.
        assertEquals(StarterToolKitService.GrantOutcome.GRANTED, grant(player, 2).outcome());
        assertEquals(StarterToolKitService.GrantOutcome.GRANTED, grant(player, 3).outcome());
        assertEquals(3, tierOf(player));
    }

    @Test
    void anUndefinedTierIsRefusedWithoutTouchingTheProgress() throws Exception {
        PlayerMock player = addPlayer();

        StarterToolKitService.GrantResult result = grant(player, 9);

        assertEquals(StarterToolKitService.GrantOutcome.UNKNOWN_TIER, result.outcome());
        assertEquals(1, tierOf(player));
    }

    @Test
    void grantingIsRefusedWhenTheKitIsDisabled() throws Exception {
        PlayerMock player = addPlayer();
        config.set(new StarterToolKitConfig(false, List.of(new StarterKitTier(1, "N", TIER_1, null))));

        assertEquals(StarterToolKitService.GrantOutcome.DISABLED, grant(player, 2).outcome());
    }

    // ---- Emplacements requis calculés sur le contenu réel ---------------

    @Test
    void theRequiredSlotsFollowTheTierContent() throws Exception {
        PlayerMock player = addPlayer();
        grant(player, 2);
        // Cinq emplacements libres : suffisant pour le palier 1 (4 objets), PAS pour le palier 2 (6).
        leaveFreeSlots(player, 5);

        service.requestKit(player);
        settle();

        assertFalse(kitInInventory(player).contains(Material.STONE_SWORD),
                "aucun objet du palier 2 ne doit être remis avec seulement 5 emplacements libres");
        assertEquals(1, tierOf(player) - 1, "le palier reste débloqué : seule la remise a été refusée");

        // Avec six emplacements, la remise passe.
        player.getInventory().clear();
        leaveFreeSlots(player, 6);
        service.requestKit(player);
        settle();
        assertTrue(kitInInventory(player).containsAll(TIER_2), () -> "obtenu : " + kitInInventory(player));
    }

    // ---- Une seule remise par vie, indépendamment du palier -------------

    @Test
    void onlyOneSuccessfulHandoutPerLifeEvenAfterATierUpgrade() throws Exception {
        PlayerMock player = addPlayer();
        service.requestKit(player);
        settle();
        assertEquals(TIER_1, kitInInventory(player));

        // Montée de palier au milieu d'une vie : le droit n'est PAS réouvert pour autant.
        grant(player, 2);
        player.getInventory().clear();
        service.requestKit(player);
        settle();
        assertTrue(kitInInventory(player).isEmpty(), "pas de seconde remise dans la même vie");

        // Après une mort, le droit se réouvre — et c'est le palier 2 qui est servi.
        simulateDeath(player);
        settle();
        service.requestKit(player);
        settle();
        assertEquals(TIER_2, kitInInventory(player));
    }

    // ---- Persistance -----------------------------------------------------

    @Test
    void theUnlockedTierSurvivesDeathReconnectionAndRestart() throws Exception {
        PlayerMock player = addPlayer();
        grant(player, 2);

        simulateDeath(player);
        settle();
        assertEquals(2, tierOf(player), "une mort ne fait jamais perdre un palier");

        // Redémarrage : service entièrement neuf, même base.
        StarterToolKitService afterRestart =
                new StarterToolKitService(plugin, variableRepository, config::get);
        assertEquals(2, afterRestart.unlockedTier(player.getUniqueId()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));

        player.getInventory().clear();
        afterRestart.requestKit(player);
        settle();
        assertEquals(TIER_2, kitInInventory(player), "après redémarrage, le kit du palier 2 est toujours servi");
    }

    /** Une valeur de variable illisible ne doit jamais priver le joueur de kit. */
    @Test
    void anUnreadableStoredTierFallsBackToTierOne() throws Exception {
        PlayerMock player = addPlayer();
        variableRepository.set(player.getUniqueId(), StarterToolKitService.TIER_KEY, "n'importe quoi")
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(1, tierOf(player));
        service.requestKit(player);
        settle();
        assertEquals(TIER_1, kitInInventory(player));
    }

    /** Un palier retiré de la configuration ne doit pas laisser le joueur sans kit. */
    @Test
    void aStoredTierHigherThanTheConfigurationFallsBackToTheBestDefinedTier() throws Exception {
        PlayerMock player = addPlayer();
        variableRepository.set(player.getUniqueId(), StarterToolKitService.TIER_KEY, "7")
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        service.requestKit(player);
        settle();

        assertEquals(TIER_2, kitInInventory(player), "le meilleur palier défini est servi, jamais rien");
    }

    @Test
    void doubleClickNeverHandsOutTwoKits() throws Exception {
        PlayerMock player = addPlayer();
        grant(player, 2);

        service.requestKit(player);
        service.requestKit(player);
        settle();

        long swords = Arrays.stream(player.getInventory().getStorageContents())
                .filter(s -> s != null && s.getType() == Material.STONE_SWORD)
                .count();
        assertEquals(1, swords, "un seul exemplaire malgré le double clic");
    }
}
