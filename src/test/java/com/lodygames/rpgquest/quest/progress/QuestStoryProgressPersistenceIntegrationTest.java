package com.lodygames.rpgquest.quest.progress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.quest.model.QuestState;
import com.lodygames.rpgquest.story.StoryService;
import com.lodygames.rpgquest.story.model.StoryState;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.EntityType;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerQuitEvent.QuitReason;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * Reproduit le bug confirmé sur VeryGames : « la progression Story/quête fonctionne normalement
 * pendant la session, mais elle se réinitialise lorsqu'un joueur meurt/respawn ou se
 * déconnecte/reconnecte ». Contrairement à {@link QuestProgressEngineTest} et {@code
 * StoryServiceTest} (qui construisent chacun leur propre {@link QuestProgressEngine}/{@code
 * StoryService} à la main, sans jamais enregistrer {@link QuestProgressConnectionListener} ni
 * {@code StoryConnectionListener} auprès d'un vrai serveur), ce test utilise volontairement le
 * plugin <strong>réellement</strong> démarré via {@code MockBukkit.load(RPGQuestPlugin.class)}
 * (même patron que {@link CrystalHuntIntegrationTest}) et déclenche de <strong>vrais</strong>
 * événements Bukkit ({@link PlayerQuitEvent}/{@link PlayerJoinEvent}, mort/réapparition, changement
 * de monde) : c'est le seul moyen d'exercer exactement le câblage de production (bootstrap complet,
 * les deux connection listeners réellement enregistrés) plutôt qu'une reconstruction simplifiée qui
 * pourrait masquer un bug d'intégration entre {@link QuestProgressEngine} et {@link StoryService}.
 */
class QuestStoryProgressPersistenceIntegrationTest {

    private static final long TIMEOUT_SECONDS = 5;
    private static final NamespacedKey BREAK_QUEST = new NamespacedKey("rpgquest", "break_quest");
    private static final NamespacedKey PREMIERS_PAS = new NamespacedKey("rpgquest", "premiers_pas");
    private static final NamespacedKey FIRST_STEPS = new NamespacedKey("rpgquest", "first_steps");
    private static final String MAIN_STORY = "main_story";

    private ServerMock server;
    private RPGQuestPlugin plugin;
    private QuestProgressEngine engine;
    private StoryService storyService;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(RPGQuestPlugin.class);
        engine = plugin.bootstrap().questProgressEngine();
        storyService = plugin.bootstrap().storyService();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    // ---- 1. quête BREAK_BLOCK à 2/3 -> quit -> join -> reste 2/3 ------------------------------

    @Test
    void breakBlockProgressAt2Of3SurvivesDisconnectAndReconnect() throws Exception {
        writeBreakBlockQuest(Material.DIRT, 3);
        engine.reloadQuestDefinitions();

        PlayerMock player = join();
        engine.accept(player, BREAK_QUEST).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        World wild = server.addSimpleWorld("wild");

        breakDirtIn(player, wild);
        breakDirtIn(player, wild);
        assertCounter(player, BREAK_QUEST, 2, "avant déconnexion : 2/3 attendu");

        reconnect(player);

        assertEquals(QuestState.ACTIVE, engine.stateOf(player.getUniqueId(), BREAK_QUEST).get(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "la quête doit rester ACTIVE après reconnexion, pas repartir de zéro");
        assertCounter(player, BREAK_QUEST, 2,
                "le compteur en mémoire doit être rechargé à 2/3 après reconnexion, jamais remis à 0/3");

        breakDirtIn(player, wild); // 3e bloc : ne doit compter qu'une fois de plus (2->3), pas repartir de 0
        assertEquals(QuestState.COMPLETED, engine.stateOf(player.getUniqueId(), BREAK_QUEST).get(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "un seul bloc de plus après reconnexion doit suffire : la progression (2/3) doit avoir survécu");
    }

    // ---- 2. Story à la quête 2 -> quit -> join -> reste quête 2 -------------------------------

    @Test
    void storyOnItsSecondQuestSurvivesDisconnectAndReconnect() throws Exception {
        PlayerMock player = join();
        storyService.start(player.getUniqueId(), player.getName(), MAIN_STORY).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        awaitUntil(() -> stateOf(player, PREMIERS_PAS) == QuestState.ACTIVE);

        // Complète premiers_pas (TALK_TO_NPC libraire) -> la story doit avancer toute seule vers first_steps.
        engine.handleTalkToNpc(player, "libraire");
        awaitUntil(() -> stateOf(player, FIRST_STEPS) == QuestState.ACTIVE);
        awaitUntil(() -> currentIndexOf(player) == 1);
        assertEquals(1, currentIndexOf(player), "la story doit être sur sa 2e quête (first_steps) avant déconnexion");

        // 4 araignées sur les 10 requises par first_steps : progression partielle avant reconnexion.
        for (int i = 0; i < 4; i++) {
            engine.handleKillEntity(player, EntityType.SPIDER);
        }
        assertCounter(player, FIRST_STEPS, 4, "avant déconnexion : 4/10 araignées attendu");

        reconnect(player);

        awaitUntil(() -> currentIndexOf(player) == 1);
        assertEquals(1, currentIndexOf(player), "l'index de la story (2e quête) doit survivre à la reconnexion");
        assertEquals(StoryState.ACTIVE, stateOfStory(player), "la story doit rester ACTIVE après reconnexion");
        assertEquals(QuestState.ACTIVE, stateOf(player, FIRST_STEPS), "first_steps doit rester la quête courante après reconnexion");
        assertCounter(player, FIRST_STEPS, 4,
                "le compteur d'araignées (4/10) doit avoir survécu à la reconnexion, jamais remis à 0/10");

        // Les 6 araignées restantes doivent suffire à terminer first_steps et faire avancer la story.
        for (int i = 0; i < 6; i++) {
            engine.handleKillEntity(player, EntityType.SPIDER);
        }
        assertEquals(QuestState.COMPLETED, stateOf(player, FIRST_STEPS));
        awaitUntil(() -> currentIndexOf(player) == 2);
        assertEquals(2, currentIndexOf(player), "la story doit avoir avancé vers sa 3e quête (crystal_hunt)");
    }

    // ---- 3. progression -> death/respawn -> reste identique -----------------------------------

    @Test
    void progressSurvivesDeathAndRespawn() throws Exception {
        writeBreakBlockQuest(Material.DIRT, 3);
        engine.reloadQuestDefinitions();

        PlayerMock player = join();
        engine.accept(player, BREAK_QUEST).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        World wild = server.addSimpleWorld("wild");
        breakDirtIn(player, wild);
        breakDirtIn(player, wild);
        assertCounter(player, BREAK_QUEST, 2, "avant la mort : 2/3 attendu");

        player.setHealth(0.0); // déclenche PlayerDeathEvent
        player.respawn(); // déclenche PlayerRespawnEvent

        assertEquals(QuestState.ACTIVE, engine.stateOf(player.getUniqueId(), BREAK_QUEST).get(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "la mort/réapparition ne doit jamais réinitialiser une quête active");
        assertCounter(player, BREAK_QUEST, 2, "le compteur (2/3) doit être identique juste après la réapparition");

        breakDirtIn(player, wild);
        assertEquals(QuestState.COMPLETED, engine.stateOf(player.getUniqueId(), BREAK_QUEST).get(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "un seul bloc après réapparition doit suffire : la progression a survécu à la mort");
    }

    // ---- 4. progression -> changement de monde -> reste identique -----------------------------

    @Test
    void progressSurvivesWorldChange() throws Exception {
        writeBreakBlockQuest(Material.DIRT, 3);
        engine.reloadQuestDefinitions();

        PlayerMock player = join();
        engine.accept(player, BREAK_QUEST).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        World defaultWorld = player.getWorld();
        World wild = server.addSimpleWorld("wild");
        breakDirtIn(player, wild);
        breakDirtIn(player, wild);
        assertCounter(player, BREAK_QUEST, 2, "avant changement de monde : 2/3 attendu");

        server.getPluginManager().callEvent(new PlayerChangedWorldEvent(player, defaultWorld));

        assertEquals(QuestState.ACTIVE, engine.stateOf(player.getUniqueId(), BREAK_QUEST).get(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "un changement de monde ne doit jamais réinitialiser une quête active");
        assertCounter(player, BREAK_QUEST, 2, "le compteur (2/3) doit être identique juste après le changement de monde");

        breakDirtIn(player, wild);
        assertEquals(QuestState.COMPLETED, engine.stateOf(player.getUniqueId(), BREAK_QUEST).get(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "un seul bloc après le changement de monde doit suffire : la progression a survécu");
    }

    // ---- 5. progression persistée -> simulation restart/reload -> reste identique -------------

    /**
     * Simule un redémarrage serveur : contrairement à un simple quit/join (qui passe par les
     * connection listeners), on vide directement les deux caches mémoire ({@code
     * QuestProgressEngine}/{@code StoryService}) sans passer par un événement de déconnexion — comme
     * le ferait un arrêt JVM, qui détruit toute la mémoire sans notifier personne — puis on recharge
     * comme le ferait une reconnexion après redémarrage. Seule la base SQLite peut avoir survécu :
     * si l'état rechargé diffère, la persistance elle-même (pas seulement le cycle
     * quit/join) est en cause.
     */
    @Test
    void progressPersistedInDatabaseSurvivesAFullMemoryWipeSimulatingAServerRestart() throws Exception {
        writeBreakBlockQuest(Material.DIRT, 3);
        engine.reloadQuestDefinitions();

        PlayerMock player = join();
        engine.accept(player, BREAK_QUEST).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        storyService.start(player.getUniqueId(), player.getName(), MAIN_STORY).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        awaitUntil(() -> stateOf(player, PREMIERS_PAS) == QuestState.ACTIVE);

        World wild = server.addSimpleWorld("wild");
        breakDirtIn(player, wild);
        breakDirtIn(player, wild);
        assertCounter(player, BREAK_QUEST, 2, "avant redémarrage simulé : 2/3 attendu");

        // « Redémarrage » : vide la mémoire directement, sans déconnexion explicite.
        UUID playerId = player.getUniqueId();
        engine.unloadForPlayer(playerId);
        storyService.unloadForPlayer(playerId);

        // « Reconnexion » après redémarrage : ne peut s'appuyer que sur la base.
        engine.loadForPlayer(playerId).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        storyService.loadForPlayer(player);
        awaitUntil(() -> currentIndexOf(player) == 0);

        assertEquals(QuestState.ACTIVE, engine.stateOf(playerId, BREAK_QUEST).get(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "l'état persisté de la quête BREAK_BLOCK doit survivre à un redémarrage simulé");
        assertCounter(player, BREAK_QUEST, 2,
                "le compteur (2/3) doit être rechargé depuis la base après un redémarrage simulé, jamais remis à 0/3");
        assertEquals(StoryState.ACTIVE, stateOfStory(player), "la story doit rester ACTIVE après un redémarrage simulé");
        assertEquals(0, currentIndexOf(player), "l'index de la story (premiers_pas) doit survivre à un redémarrage simulé");
    }

    // ---- Aides ----------------------------------------------------------------------------------

    private PlayerMock join() throws Exception {
        PlayerMock player = server.addPlayer();
        // Attend explicitement la fin du chargement asynchrone déclenché par PlayerJoinEvent, comme
        // CrystalHuntIntegrationTest : en jeu, la latence réseau garantit naturellement cet ordre.
        engine.loadForPlayer(player.getUniqueId()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        server.getScheduler().performTicks(3);
        return player;
    }

    private void reconnect(PlayerMock player) throws Exception {
        server.getPluginManager().callEvent(new PlayerQuitEvent(
                player, (net.kyori.adventure.text.Component) null, QuitReason.DISCONNECTED));
        server.getPluginManager().callEvent(new PlayerJoinEvent(player, (net.kyori.adventure.text.Component) null));
        // QuestProgressEngine#loadForPlayer se termine indépendamment du scheduler (voir sa Javadoc) :
        // l'attendre suffit pour son propre cache. StoryService#loadForPlayer, lui, republie via une
        // tâche planifiée (runTask) après sa propre lecture asynchrone — performTicks lui laisse
        // l'occasion de s'exécuter ; chaque test Story affine ensuite avec son propre awaitUntil.
        engine.loadForPlayer(player.getUniqueId()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        server.getScheduler().performTicks(5);
    }

    private void breakDirtIn(PlayerMock player, World world) {
        Block block = world.getBlockAt(0, 64, 0);
        block.setType(Material.DIRT);
        server.getPluginManager().callEvent(new BlockBreakEvent(block, player));
    }

    private void assertCounter(PlayerMock player, NamespacedKey questId, int expected, String message) {
        var view = engine.activeStepView(player.getUniqueId(), questId);
        assertTrue(view.isPresent(), message + " (aucune progression en mémoire trouvée)");
        assertEquals(expected, view.get().objectives().get(0).current(), message);
    }

    private QuestState stateOf(PlayerMock player, NamespacedKey questId) {
        try {
            return engine.stateOf(player.getUniqueId(), questId).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private StoryState stateOfStory(PlayerMock player) throws Exception {
        List<StoryService.StoryInfo> infos = storyService.info(player.getUniqueId()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        return infos.stream().filter(i -> i.story().id().equals(MAIN_STORY)).findFirst().orElseThrow().state();
    }

    private int currentIndexOf(PlayerMock player) {
        try {
            List<StoryService.StoryInfo> infos = storyService.info(player.getUniqueId()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return infos.stream().filter(i -> i.story().id().equals(MAIN_STORY)).findFirst().orElseThrow().currentIndex();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void awaitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT_SECONDS * 1000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            server.getScheduler().performTicks(1);
            Thread.sleep(10);
        }
    }

    private void writeBreakBlockQuest(Material material, int amount) throws Exception {
        Path questsDir = plugin.getDataFolder().toPath().resolve("quests");
        Files.createDirectories(questsDir);
        String yaml = """
                id: %s
                title: "Titre"
                description: "Description"
                category: test
                repeatable: true

                steps:
                  - id: break_step
                    objectives:
                      - type: BREAK_BLOCK
                        material: %s
                        amount: %d

                rewards:
                  - type: EXPERIENCE
                    amount: 5
                """.formatted(BREAK_QUEST, material, amount);
        Files.writeString(questsDir.resolve("break_quest.yml"), yaml);
    }
}
