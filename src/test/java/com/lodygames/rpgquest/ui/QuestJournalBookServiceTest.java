package com.lodygames.rpgquest.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.database.DatabaseManager;
import com.lodygames.rpgquest.database.NpcBindingRepository;
import com.lodygames.rpgquest.database.NpcIdRepository;
import com.lodygames.rpgquest.database.PlayerProfileRepository;
import com.lodygames.rpgquest.database.PlayerVariableRepository;
import com.lodygames.rpgquest.database.QuestProgressRepository;
import com.lodygames.rpgquest.npc.NpcIdentityService;
import com.lodygames.rpgquest.quest.QuestMessagesService;
import com.lodygames.rpgquest.quest.YamlQuestEngine;
import com.lodygames.rpgquest.quest.model.LocalizedText;
import com.lodygames.rpgquest.quest.model.QuestState;
import com.lodygames.rpgquest.quest.progress.AcceptOutcome;
import com.lodygames.rpgquest.quest.progress.QuestProgressEngine;
import com.lodygames.rpgquest.story.model.StoryDefinition;
import com.lodygames.rpgquest.story.model.StoryState;
import com.lodygames.rpgquest.story.StoryService;
import com.lodygames.rpgquest.ui.QuestJournalBookService.DigestData;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.NamespacedKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * Mission « Journal des quêtes » : couvre le contenu compact du résumé ({@link
 * QuestJournalBookService#buildDigest}) — quête active + progression visibles, story secrète non
 * découverte jamais affichée.
 */
class QuestJournalBookServiceTest {

    private static final long TIMEOUT_SECONDS = 5;
    private static final NamespacedKey ACTIVE_QUEST = new NamespacedKey("rpgquest", "journal_active");
    private static final NamespacedKey SECRET_STORY_QUEST = new NamespacedKey("rpgquest", "journal_secret");

    @TempDir
    Path tempDir;

    private ServerMock server;
    private RPGQuestPlugin plugin;
    private DatabaseManager database;
    private YamlQuestEngine questEngine;
    private QuestProgressEngine progressEngine;
    private QuestJournalBookService service;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(RPGQuestPlugin.class);

        database = new DatabaseManager(tempDir.resolve("test.db"));
        database.initialize().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        PlayerProfileRepository profileRepository = new PlayerProfileRepository(database);
        PlayerVariableRepository variableRepository = new PlayerVariableRepository(database);

        Path questsDir = tempDir.resolve("quests");
        Files.createDirectories(questsDir);
        Files.writeString(questsDir.resolve("a.yml"), killQuest(ACTIVE_QUEST, "Chasser le gobelin", 3));
        Files.writeString(questsDir.resolve("b.yml"), killQuest(SECRET_STORY_QUEST, "Rituel interdit", 1));
        questEngine = new YamlQuestEngine(questsDir, plugin.getSLF4JLogger());
        questEngine.reload();

        QuestProgressRepository progressRepository = new QuestProgressRepository(database);
        QuestMessagesService messagesService = new QuestMessagesService(plugin);
        messagesService.start();
        NpcIdentityService npcIdentityService = new NpcIdentityService(
                plugin, new NpcIdRepository(database), new NpcBindingRepository(database));
        progressEngine = new QuestProgressEngine(
                plugin, questEngine, progressRepository, variableRepository, messagesService, npcIdentityService);
        progressEngine.start();

        StoryService storyService = null; // buildDigest ne touche jamais storyService (seul open() l'utilise).
        service = new QuestJournalBookService(plugin, null, progressEngine, questEngine, storyService);

        profileRepository.findOrCreate(java.util.UUID.randomUUID(), "seed").get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    @AfterEach
    void tearDown() {
        progressEngine.stop();
        database.shutdown();
        MockBukkit.unmock();
    }

    private String killQuest(NamespacedKey id, String title, int amount) {
        return """
                id: %s
                title: "%s"
                description: "d"
                category: test
                steps:
                  - id: s1
                    objectives:
                      - type: KILL_ENTITY
                        entity: ZOMBIE
                        amount: %d
                """.formatted(id, title, amount);
    }

    private List<String> plain(List<Component> lines) {
        return lines.stream().map(c -> PlainTextComponentSerializer.plainText().serialize(c)).toList();
    }

    @Test
    void anActiveQuestAndItsObjectiveProgressAreShown() throws Exception {
        PlayerMock player = server.addPlayer();
        new PlayerProfileRepository(database).findOrCreate(player.getUniqueId(), player.getName())
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        progressEngine.loadForPlayer(player.getUniqueId()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        AcceptOutcome outcome = progressEngine.accept(player, ACTIVE_QUEST).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertTrue(outcome.result() == AcceptOutcome.Result.ACCEPTED);

        Map<NamespacedKey, QuestState> states = new LinkedHashMap<>();
        states.put(ACTIVE_QUEST, QuestState.ACTIVE);
        List<String> lines = plain(service.buildDigest(player.getUniqueId(), new DigestData(List.of(), states)));

        assertTrue(lines.stream().anyMatch(l -> l.contains("Chasser le gobelin")), "la quête active doit apparaître");
        assertTrue(lines.stream().anyMatch(l -> l.contains("(0/3)")), "la progression de l'objectif doit apparaître");
    }

    @Test
    void anUndiscoveredSecretStoryIsNeverShown() {
        StoryDefinition secretStory = new StoryDefinition(
                "secret_ritual", LocalizedText.of("Le Rituel interdit"), List.of(SECRET_STORY_QUEST), true);
        StoryService.StoryInfo info = new StoryService.StoryInfo(secretStory, StoryState.NOT_STARTED, 0);

        List<String> lines = plain(service.buildDigest(
                java.util.UUID.randomUUID(), new DigestData(List.of(info), new LinkedHashMap<>())));

        assertFalse(lines.stream().anyMatch(l -> l.contains("Rituel interdit")),
                "une story secrète non découverte ne doit jamais apparaître dans le journal");
        assertTrue(lines.stream().anyMatch(l -> l.contains("Aucune aventure en cours")));
    }
}
