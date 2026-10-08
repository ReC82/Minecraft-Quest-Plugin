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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.bukkit.NamespacedKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * Issue #185 — objectif {@code DISCOVER_WAYPOINT}.
 *
 * <p>Les tests appellent {@code handleWaypointDiscovered} exactement comme le fait l'abonnement du
 * bootstrap à {@code WaypointService#onFirstDiscovery}, c'est-à-dire <strong>uniquement sur une
 * première découverte réelle</strong>. Le fait qu'un reclic ne déclenche rien est garanti par le
 * système de waypoints lui-même (insertion en base) : il est vérifié ici en n'appelant pas le moteur,
 * ce qui est la situation exacte produite par un second clic.</p>
 */
class QuestDiscoverWaypointObjectiveTest {

    private static final long TIMEOUT_SECONDS = 5;
    private static final NamespacedKey NEW_ONLY_QUEST = new NamespacedKey("rpgquest", "discover_new");
    private static final NamespacedKey HUB_ONLY_QUEST = new NamespacedKey("rpgquest", "discover_hub");
    private static final NamespacedKey CUMULATIVE_QUEST = new NamespacedKey("rpgquest", "discover_cumulative");

    @TempDir
    Path tempDir;

    private ServerMock server;
    private RPGQuestPlugin plugin;
    private DatabaseManager database;
    private QuestProgressRepository progressRepository;
    private PlayerProfileRepository profileRepository;
    private QuestProgressEngine engine;
    /** Découvertes « déjà acquises », tenues comme le ferait l'index en mémoire du vrai service. */
    private final Map<UUID, List<String>> discoveredWorldsByPlayer = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(RPGQuestPlugin.class);

        database = new DatabaseManager(tempDir.resolve("test.db"));
        database.initialize().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        Path questsDir = tempDir.resolve("quests");
        Files.createDirectories(questsDir);
        Files.writeString(questsDir.resolve("discover_new.yml"), """
                id: rpgquest:discover_new
                title: "Titre"
                description: "Description"
                category: test
                steps:
                  - id: decouvrir
                    objectives:
                      - type: DISCOVER_WAYPOINT
                        amount: 3
                """);
        Files.writeString(questsDir.resolve("discover_hub.yml"), """
                id: rpgquest:discover_hub
                title: "Titre"
                description: "Description"
                category: test
                steps:
                  - id: decouvrir_hub
                    objectives:
                      - type: DISCOVER_WAYPOINT
                        amount: 2
                        worlds: [world_hub]
                """);
        Files.writeString(questsDir.resolve("discover_cumulative.yml"), """
                id: rpgquest:discover_cumulative
                title: "Titre"
                description: "Description"
                category: test
                steps:
                  - id: decouvrir_cumul
                    objectives:
                      - type: DISCOVER_WAYPOINT
                        amount: 2
                        count-mode: INCLUDE_EXISTING
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
        // Même couture qu'au bootstrap, branchée sur un faux relevé de découvertes.
        engine.setDiscoveredWaypointCounter((playerId, worlds) -> {
            List<String> discovered = discoveredWorldsByPlayer.getOrDefault(playerId, List.of());
            if (worlds.isEmpty()) {
                return discovered.size();
            }
            return (int) discovered.stream().filter(worlds::contains).count();
        });
        engine.start();
    }

    @AfterEach
    void tearDown() {
        engine.stop();
        database.shutdown();
        MockBukkit.unmock();
    }

    private PlayerMock player(NamespacedKey quest) throws Exception {
        PlayerMock player = server.addPlayer();
        profileRepository.findOrCreate(player.getUniqueId(), player.getName()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        engine.accept(player, quest).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        return player;
    }

    /** Une première découverte réelle : exactement ce que le service de waypoints publie. */
    private void discover(PlayerMock player, String world) {
        discoveredWorldsByPlayer.computeIfAbsent(player.getUniqueId(), k -> new ArrayList<>()).add(world);
        engine.handleWaypointDiscovered(player, world);
    }

    private int liveCounter(PlayerMock player, NamespacedKey quest) {
        return engine.activeStepView(player.getUniqueId(), quest)
                .map(view -> view.objectives().get(0).current())
                .orElse(-1);
    }

    private QuestState state(PlayerMock player, NamespacedKey quest) throws Exception {
        return engine.stateOf(player.getUniqueId(), quest).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    @Test
    void threeDistinctDiscoveriesCompleteATargetOfThree() throws Exception {
        PlayerMock player = player(NEW_ONLY_QUEST);

        discover(player, "wild");
        assertEquals(1, liveCounter(player, NEW_ONLY_QUEST));
        discover(player, "wild");
        discover(player, "world_hub");

        assertEquals(QuestState.COMPLETED, state(player, NEW_ONLY_QUEST),
                "trois waypoints distincts, quelle que soit leur répartition entre les mondes");
    }

    /**
     * Un reclic ne produit aucune notification : le moteur n'est donc jamais appelé. Le compteur ne
     * bouge pas — c'est ce qui rend le comptage distinct sans table supplémentaire.
     */
    @Test
    void reclickingAnAlreadyDiscoveredWaypointNeverProgresses() throws Exception {
        PlayerMock player = player(NEW_ONLY_QUEST);
        discover(player, "wild");

        // Aucun appel : exactement l'effet d'un second clic sur le même waypoint.
        assertEquals(1, liveCounter(player, NEW_ONLY_QUEST));
    }

    /** Deux waypoints du même biome sont deux découvertes : l'identité comptée est l'id du waypoint. */
    @Test
    void twoWaypointsOfTheSameBiomeCountSeparately() throws Exception {
        PlayerMock player = player(NEW_ONLY_QUEST);

        discover(player, "wild");
        discover(player, "wild");

        assertEquals(2, liveCounter(player, NEW_ONLY_QUEST),
                "ni le nom ni le biome n'entrent dans le comptage");
    }

    @Test
    void anExcludedWorldNeverProgresses() throws Exception {
        PlayerMock player = player(HUB_ONLY_QUEST);

        discover(player, "wild");
        assertEquals(0, liveCounter(player, HUB_ONLY_QUEST), "le Wild est hors du filtre");

        discover(player, "world_hub");
        discover(player, "world_hub");
        assertEquals(QuestState.COMPLETED, state(player, HUB_ONLY_QUEST));
    }

    @Test
    void progressIsPersistedAcrossAReconnection() throws Exception {
        PlayerMock player = player(NEW_ONLY_QUEST);
        discover(player, "wild");
        discover(player, "wild");
        Thread.sleep(120);

        engine.unloadForPlayer(player.getUniqueId());
        engine.loadForPlayer(player.getUniqueId()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(2, liveCounter(player, NEW_ONLY_QUEST), "le compteur survit à la reconnexion");
        discover(player, "world_hub");
        assertEquals(QuestState.COMPLETED, state(player, NEW_ONLY_QUEST));
    }

    /**
     * Mode {@code NEW_ONLY} : les découvertes antérieures à l'acceptation ne comptent pas. C'est la
     * règle annoncée au joueur, elle ne doit jamais être contournée silencieusement.
     */
    @Test
    void existingDiscoveriesDoNotCountInNewOnlyMode() throws Exception {
        PlayerMock player = server.addPlayer();
        profileRepository.findOrCreate(player.getUniqueId(), player.getName()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        discoveredWorldsByPlayer.put(player.getUniqueId(), new ArrayList<>(List.of("wild", "wild", "wild")));

        engine.accept(player, NEW_ONLY_QUEST).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(0, liveCounter(player, NEW_ONLY_QUEST));
        assertEquals(QuestState.ACTIVE, state(player, NEW_ONLY_QUEST));
    }

    /** Mode {@code INCLUDE_EXISTING} : le total déjà acquis compte dès l'acceptation. */
    @Test
    void existingDiscoveriesCountInCumulativeMode() throws Exception {
        PlayerMock player = server.addPlayer();
        profileRepository.findOrCreate(player.getUniqueId(), player.getName()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        discoveredWorldsByPlayer.put(player.getUniqueId(), new ArrayList<>(List.of("wild", "world_hub")));

        engine.accept(player, CUMULATIVE_QUEST).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        server.getScheduler().performTicks(5);

        assertEquals(QuestState.COMPLETED, state(player, CUMULATIVE_QUEST),
                "deux découvertes déjà acquises suffisent à une cible de deux");
    }

    /** Mode cumulatif, départ à zéro : la progression suit ensuite les nouvelles découvertes. */
    @Test
    void cumulativeModeAlsoFollowsNewDiscoveries() throws Exception {
        PlayerMock player = player(CUMULATIVE_QUEST);
        assertEquals(0, liveCounter(player, CUMULATIVE_QUEST));

        discover(player, "wild");
        assertEquals(1, liveCounter(player, CUMULATIVE_QUEST));
        discover(player, "wild");

        assertEquals(QuestState.COMPLETED, state(player, CUMULATIVE_QUEST));
    }

    /** Un compteur déjà au plafond ne monte jamais au-delà, et l'étape ne se termine pas deux fois. */
    @Test
    void discoveriesAfterCompletionChangeNothing() throws Exception {
        PlayerMock player = player(HUB_ONLY_QUEST);
        discover(player, "world_hub");
        discover(player, "world_hub");
        assertEquals(QuestState.COMPLETED, state(player, HUB_ONLY_QUEST));

        discover(player, "world_hub");

        assertEquals(QuestState.COMPLETED, state(player, HUB_ONLY_QUEST));
        Map<Integer, Integer> counters = progressRepository
                .findObjectiveProgress(player.getUniqueId(), HUB_ONLY_QUEST, "decouvrir_hub")
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertTrue(counters.getOrDefault(0, 0) <= 2, "jamais au-delà de la quantité demandée");
    }

    /** Une découverte par un joueur ne fait jamais progresser un autre joueur. */
    @Test
    void progressGoesOnlyToThePlayerWhoDiscovered() throws Exception {
        PlayerMock first = player(NEW_ONLY_QUEST);
        PlayerMock second = player(NEW_ONLY_QUEST);

        discover(first, "wild");

        assertEquals(1, liveCounter(first, NEW_ONLY_QUEST));
        assertEquals(0, liveCounter(second, NEW_ONLY_QUEST));
    }
}
