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
import java.util.HashSet;
import java.util.Set;
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
 * Issue #178 — objectif {@code DISCOVER_CITY} : « X villes distinctes » et « une ville précise ».
 *
 * <p>Comme pour les waypoints, le moteur n'est appelé que sur une <strong>première découverte
 * réellement insérée</strong> (abonnement du bootstrap à {@code CityDiscoveryService}). Une rentrée
 * dans une ville connue ne l'appelle donc jamais : c'est la situation reproduite ici en ne l'appelant
 * pas.</p>
 */
class QuestDiscoverCityObjectiveTest {

    private static final long TIMEOUT_SECONDS = 5;
    private static final NamespacedKey THREE_CITIES = new NamespacedKey("rpgquest", "city_three");
    private static final NamespacedKey PORT_LILY = new NamespacedKey("rpgquest", "city_port_lily");
    private static final NamespacedKey PORT_LILY_KNOWN = new NamespacedKey("rpgquest", "city_port_lily_known");
    private static final NamespacedKey TWO_KNOWN = new NamespacedKey("rpgquest", "city_two_known");

    @TempDir
    Path tempDir;

    private ServerMock server;
    private RPGQuestPlugin plugin;
    private DatabaseManager database;
    private QuestProgressRepository progressRepository;
    private PlayerProfileRepository profileRepository;
    private QuestProgressEngine engine;
    private YamlQuestEngine questEngine;
    private Path questsDirectory;
    /** Villes déjà découvertes, tenues comme le ferait CityDiscoveryService. */
    private final Map<UUID, Set<String>> known = new ConcurrentHashMap<>();

    private static String quest(String id, String objective) {
        return "id: rpgquest:" + id + "\ntitle: \"Quête " + id + "\"\ndescription: \"d\"\ncategory: test\n"
                + "steps:\n  - id: etape\n    objectives:\n      - type: DISCOVER_CITY\n" + objective;
    }

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(RPGQuestPlugin.class);
        database = new DatabaseManager(tempDir.resolve("test.db"));
        database.initialize().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        questsDirectory = tempDir.resolve("quests");
        Files.createDirectories(questsDirectory);
        Files.writeString(questsDirectory.resolve("city_three.yml"), quest("city_three", "        amount: 3\n"));
        Files.writeString(questsDirectory.resolve("city_port_lily.yml"),
                quest("city_port_lily", "        city: ville_port_lily\n"));
        Files.writeString(questsDirectory.resolve("city_port_lily_known.yml"),
                quest("city_port_lily_known", "        city: ville_port_lily\n        count-mode: INCLUDE_EXISTING\n"));
        Files.writeString(questsDirectory.resolve("city_two_known.yml"),
                quest("city_two_known", "        amount: 2\n        count-mode: INCLUDE_EXISTING\n"));

        questEngine = new YamlQuestEngine(questsDirectory, plugin.getSLF4JLogger());
        questEngine.reload();
        progressRepository = new QuestProgressRepository(database);
        profileRepository = new PlayerProfileRepository(database);
        QuestMessagesService messagesService = new QuestMessagesService(plugin);
        messagesService.start();
        engine = new QuestProgressEngine(plugin, questEngine, progressRepository,
                new PlayerVariableRepository(database), messagesService,
                new NpcIdentityService(plugin, new NpcIdRepository(database), new NpcBindingRepository(database)),
                QuestRewardPayer.unavailable());
        engine.setDiscoveredCityCounter((playerId, cityId) -> {
            Set<String> mine = known.getOrDefault(playerId, Set.of());
            return cityId == null ? mine.size() : (mine.contains(cityId) ? 1 : 0);
        });
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
        return player;
    }

    private void accept(PlayerMock player, NamespacedKey quest) throws Exception {
        engine.accept(player, quest).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        server.getScheduler().performTicks(5);
    }

    /** Une PREMIÈRE découverte : exactement ce que publie CityDiscoveryService. */
    private void discover(PlayerMock player, String cityId) {
        if (known.computeIfAbsent(player.getUniqueId(), k -> new HashSet<>()).add(cityId)) {
            engine.handleCityDiscovered(player, cityId);
        }
    }

    private int counter(PlayerMock player, NamespacedKey quest) {
        return engine.activeStepView(player.getUniqueId(), quest)
                .map(view -> view.objectives().get(0).current()).orElse(-1);
    }

    private QuestState state(PlayerMock player, NamespacedKey quest) throws Exception {
        return engine.stateOf(player.getUniqueId(), quest).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    @Test
    void threeDistinctCitiesCompleteAndARediscoveryNeverCounts() throws Exception {
        PlayerMock player = player();
        accept(player, THREE_CITIES);
        discover(player, "ville_a");
        discover(player, "ville_a"); // rentrée : CityDiscoveryService n'appelle pas le moteur
        assertEquals(1, counter(player, THREE_CITIES));
        discover(player, "ville_b");
        discover(player, "ville_c");
        assertEquals(QuestState.COMPLETED, state(player, THREE_CITIES));
    }

    @Test
    void aSpecificCityIgnoresOtherCities() throws Exception {
        PlayerMock player = player();
        accept(player, PORT_LILY);
        discover(player, "ville_bourg_neuf");
        assertEquals(0, counter(player, PORT_LILY), "une autre ville ne compte pas");
        discover(player, "ville_port_lily");
        assertEquals(QuestState.COMPLETED, state(player, PORT_LILY));
    }

    @Test
    void newOnlyIgnoresCitiesDiscoveredBeforeAcceptance() throws Exception {
        PlayerMock player = player();
        known.put(player.getUniqueId(), new HashSet<>(Set.of("ville_port_lily", "ville_a", "ville_b")));
        accept(player, PORT_LILY);
        accept(player, THREE_CITIES);
        assertEquals(0, counter(player, PORT_LILY));
        assertEquals(0, counter(player, THREE_CITIES));
        assertEquals(QuestState.ACTIVE, state(player, PORT_LILY),
                "règle annoncée : seules les nouvelles découvertes comptent");
    }

    @Test
    void includeExistingCountsCitiesAlreadyKnown() throws Exception {
        PlayerMock player = player();
        known.put(player.getUniqueId(), new HashSet<>(Set.of("ville_port_lily")));
        accept(player, PORT_LILY_KNOWN);
        assertEquals(QuestState.COMPLETED, state(player, PORT_LILY_KNOWN), "déjà découverte : acquise");

        accept(player, TWO_KNOWN);
        assertEquals(1, counter(player, TWO_KNOWN));
        discover(player, "ville_b");
        assertEquals(QuestState.COMPLETED, state(player, TWO_KNOWN));
    }

    @Test
    void oneDiscoveryAdvancesSeveralQuestsAtOnce() throws Exception {
        PlayerMock player = player();
        accept(player, THREE_CITIES);
        accept(player, PORT_LILY);
        discover(player, "ville_port_lily");
        assertEquals(1, counter(player, THREE_CITIES));
        assertEquals(QuestState.COMPLETED, state(player, PORT_LILY));
    }

    @Test
    void progressSurvivesAReconnection() throws Exception {
        PlayerMock player = player();
        accept(player, THREE_CITIES);
        discover(player, "ville_a");
        discover(player, "ville_b");
        Thread.sleep(120);
        engine.unloadForPlayer(player.getUniqueId());
        engine.loadForPlayer(player.getUniqueId()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(2, counter(player, THREE_CITIES));
        discover(player, "ville_c");
        assertEquals(QuestState.COMPLETED, state(player, THREE_CITIES));
    }

    @Test
    void aCityQuestPublishedWhileRunningProgressesAtOnce() throws Exception {
        Files.writeString(questsDirectory.resolve("city_hot.yml"),
                quest("city_hot", "        city: ville_hot\n        count-mode: NEW_ONLY\n"));
        questEngine.reload();
        engine.afterExternalReload();
        NamespacedKey hot = new NamespacedKey("rpgquest", "city_hot");
        PlayerMock player = player();
        accept(player, hot);
        discover(player, "ville_hot");
        assertEquals(QuestState.COMPLETED, state(player, hot), "publication à chaud, sans redémarrage");
    }

    @Test
    void theParserRejectsAmountWithACityAndAnUnknownMode() throws Exception {
        Files.writeString(questsDirectory.resolve("bad_amount.yml"),
                quest("bad_amount", "        city: ville_x\n        amount: 3\n"));
        Files.writeString(questsDirectory.resolve("bad_mode.yml"),
                quest("bad_mode", "        amount: 2\n        count-mode: SOMETIMES\n"));
        Files.writeString(questsDirectory.resolve("bad_empty.yml"), quest("bad_empty", "        count-mode: NEW_ONLY\n"));
        questEngine.reload();
        assertTrue(questEngine.find(new NamespacedKey("rpgquest", "bad_amount")).isEmpty());
        assertTrue(questEngine.find(new NamespacedKey("rpgquest", "bad_mode")).isEmpty());
        assertTrue(questEngine.find(new NamespacedKey("rpgquest", "bad_empty")).isEmpty(),
                "ni ville ni quantité : refusée, jamais une supposition");
        assertTrue(questEngine.find(THREE_CITIES).isPresent());
    }
}
