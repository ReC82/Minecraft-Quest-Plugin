package com.lodygames.rpgquest.quest.progress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.database.DatabaseManager;
import com.lodygames.rpgquest.database.NpcBindingRepository;
import com.lodygames.rpgquest.database.NpcIdRepository;
import com.lodygames.rpgquest.database.PlayerProfileRepository;
import com.lodygames.rpgquest.database.PlayerVariableRepository;
import com.lodygames.rpgquest.database.QuestProgressRepository;
import com.lodygames.rpgquest.economy.QuestRewardPayer;
import com.lodygames.rpgquest.feedback.ActionBarFeedback;
import com.lodygames.rpgquest.feedback.QuestTitleQueue;
import com.lodygames.rpgquest.npc.NpcIdentityService;
import com.lodygames.rpgquest.quest.QuestMessagesService;
import com.lodygames.rpgquest.quest.YamlQuestEngine;
import com.lodygames.rpgquest.quest.model.QuestState;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.block.BlockBreakEvent;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * Issue #817 — scénario du propriétaire : une bûche de chêne fait progresser trois quêtes, la
 * quotidienne se termine pendant que les deux autres continuent. Chemin réel : vrai {@link
 * BlockBreakEvent}, vrai moteur, vrai canal d'action bar et vraie file de titres.
 */
class QuestMultiQuestNotificationTest {

    private static final long TIMEOUT_SECONDS = 5;
    private static final NamespacedKey BOB = new NamespacedKey("rpgquest", "t817_bob");
    private static final NamespacedKey TANIA = new NamespacedKey("rpgquest", "t817_tania");
    private static final NamespacedKey DAILY = new NamespacedKey("rpgquest", "t817_daily");
    private static final NamespacedKey OTHER_DAILY = new NamespacedKey("rpgquest", "t817_other_daily");

    @TempDir
    Path tempDir;

    private ServerMock server;
    private DatabaseManager database;
    private QuestProgressRepository progressRepository;
    private QuestProgressEngine engine;
    private Path questsDir;
    private final AtomicLong now = new AtomicLong(10_000_000L);
    private final List<Task> scheduled = new ArrayList<>();

    private record Task(long at, Runnable task) {
    }
    private World world;

    /** PlayerMock ne mémorise pas les Title Adventure : on les enregistre ici. */
    private static final class RecordingPlayer extends PlayerMock {
        private final List<String> titles = new ArrayList<>();

        RecordingPlayer(ServerMock server) {
            super(server, "Lody", UUID.randomUUID());
        }

        @Override
        public void showTitle(@NotNull Title title) {
            titles.add(PlainTextComponentSerializer.plainText().serialize(title.title()) + " — "
                    + PlainTextComponentSerializer.plainText().serialize(title.subtitle()));
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        RPGQuestPlugin plugin = MockBukkit.load(RPGQuestPlugin.class);
        database = new DatabaseManager(tempDir.resolve("test.db"));
        database.initialize().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        questsDir = tempDir.resolve("quests");
        Files.createDirectories(questsDir);
        // Titres colorés comme sur DEV : la capture du 29/10 montrait « <gold>Bois... » en clair.
        writeLogQuest(BOB, "<gold>La requête du bûcheron</gold>", 20);
        writeLogQuest(TANIA, "<yellow>Le stock de Tania", 20);
        writeLogQuest(DAILY, "<gold>Bois du jour", 2);
        writeLogQuest(OTHER_DAILY, "<green><bold>Bois du soir", 2);
        YamlQuestEngine questEngine = new YamlQuestEngine(questsDir, plugin.getSLF4JLogger());
        questEngine.reload();

        progressRepository = new QuestProgressRepository(database);
        QuestMessagesService messagesService = new QuestMessagesService(plugin);
        messagesService.start();
        engine = new QuestProgressEngine(plugin, questEngine, progressRepository,
                new PlayerVariableRepository(database), messagesService,
                new NpcIdentityService(plugin, new NpcIdRepository(database), new NpcBindingRepository(database)),
                QuestRewardPayer.unavailable());
        engine.setActionBar(new ActionBarFeedback(now::get, (task, delay) -> scheduled.add(new Task(now.get() + delay, task))));
        engine.setTitleQueue(new QuestTitleQueue(now::get, (task, delay) -> scheduled.add(new Task(now.get() + delay, task))));
        engine.start();
        world = server.addSimpleWorld("wild");
    }

    @AfterEach
    void tearDown() {
        engine.stop();
        database.shutdown();
        MockBukkit.unmock();
    }

    @Test
    void oneLogProgressingThreeQuestsShowsEachQuestOnItsOwnReadableLine() throws Exception {
        RecordingPlayer player = playerWith(BOB, TANIA, DAILY);

        breakLog(player);
        advance(4 * ActionBarFeedback.SLOT_MILLIS);

        List<String> lines = counterLines(player);
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("La requête du bûcheron · ") && l.endsWith(" 1/20")),
                lines::toString);
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("Le stock de Tania · ") && l.endsWith(" 1/20")),
                lines::toString);
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("Bois du jour · ") && l.endsWith(" 1/2")),
                lines::toString);
        for (String line : lines) {
            assertFalse(line.contains("<") || line.contains(">"), () -> "balise en clair : " + line);
            assertEquals(1, line.split("\\d+/\\d+", -1).length - 1, () -> "une progression par ligne : " + line);
        }
    }

    @Test
    void aCompletedQuestIsAnnouncedWithItsRewardsAndItsCounterDoesNotComeBack() throws Exception {
        RecordingPlayer player = playerWith(BOB, TANIA, DAILY);
        breakLog(player);
        advance(4 * ActionBarFeedback.SLOT_MILLIS);
        breakLog(player); // Bois du jour 2/2 : terminée

        assertEquals(QuestState.COMPLETED, state(player, DAILY));
        assertEquals(List.of("Quête terminée — Bois du jour"), player.titles);
        List<String> chat = drainChat(player);
        assertTrue(chat.stream().anyMatch(m -> m.contains("Quête terminée") && m.contains("Bois du jour")),
                () -> "le bilan de la quête terminée part dans le chat, attribué : " + chat);
        advance(4 * ActionBarFeedback.SLOT_MILLIS);
        counterLines(player);

        breakLog(player);
        advance(4 * ActionBarFeedback.SLOT_MILLIS);
        List<String> lines = counterLines(player);
        assertFalse(lines.stream().anyMatch(l -> l.contains("Bois du jour")), () -> "quête terminée revenue : " + lines);
        assertTrue(lines.stream().anyMatch(l -> l.endsWith(" 3/20")), lines::toString);
    }

    @Test
    void twoQuestsCompletedByTheSameLogBothKeepTheirTitleAndChatSummary() throws Exception {
        RecordingPlayer player = playerWith(BOB, DAILY, OTHER_DAILY);
        breakLog(player);
        breakLog(player); // les deux quotidiennes se terminent sur le même événement

        assertEquals(1, player.titles.size(), "la seconde fin attend, elle n'efface pas la première");
        List<String> chat = drainChat(player);
        assertTrue(chat.stream().anyMatch(m -> m.contains("Bois du jour")), chat::toString);
        assertTrue(chat.stream().anyMatch(m -> m.contains("Bois du soir")), chat::toString);

        advance(3_000);

        assertEquals(2, player.titles.size(), () -> "les deux fins sont annoncées : " + player.titles);
    }

    @Test
    void manyLogsInARowDoNotSpamTheChat() throws Exception {
        RecordingPlayer player = playerWith(BOB, TANIA);
        for (int i = 0; i < 10; i++) {
            breakLog(player);
            advance(400);
        }
        advance(3 * ActionBarFeedback.SLOT_MILLIS);

        assertEquals(List.of(), drainChat(player), "un compteur ne va jamais dans le chat");
        List<String> lines = counterLines(player);
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("La requête du bûcheron") && l.endsWith(" 10/20")),
                lines::toString);
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("Le stock de Tania") && l.endsWith(" 10/20")),
                lines::toString);
    }

    // ---- Outils ---------------------------------------------------------------------------------

    private RecordingPlayer playerWith(NamespacedKey... quests) throws Exception {
        RecordingPlayer player = new RecordingPlayer(server);
        server.addPlayer(player);
        new PlayerProfileRepository(database).findOrCreate(player.getUniqueId(), player.getName())
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        for (NamespacedKey quest : quests) {
            engine.accept(player, quest, true).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
        server.getScheduler().performTicks(2);
        drainChat(player);
        player.titles.clear();
        while (player.nextActionBar() != null) {
            // annonces d'acceptation hors périmètre
        }
        return player;
    }

    private void breakLog(PlayerMock player) {
        Block block = world.getBlockAt(0, 64, 0);
        block.setType(Material.OAK_LOG);
        server.getPluginManager().callEvent(new BlockBreakEvent(block, player));
        server.getScheduler().performTicks(1);
    }

    /** Avance l'horloge et exécute les tâches échues des deux canaux, dans l'ordre. */
    private void advance(long millis) {
        long target = now.get() + millis;
        while (true) {
            Task next = scheduled.stream().filter(t -> t.at() <= target)
                    .min((a, b) -> Long.compare(a.at(), b.at())).orElse(null);
            if (next == null) {
                break;
            }
            scheduled.remove(next);
            now.set(Math.max(now.get(), next.at()));
            next.task().run();
        }
        now.set(target);
    }

    /** Toutes les lignes de compteurs envoyées depuis le dernier appel. */
    private static List<String> counterLines(PlayerMock player) {
        List<String> lines = new ArrayList<>();
        for (Component next = player.nextActionBar(); next != null; next = player.nextActionBar()) {
            String line = PlainTextComponentSerializer.plainText().serialize(next);
            if (line.matches(".*\\d+/\\d+.*")) {
                lines.add(line);
            }
        }
        return lines;
    }

    private static List<String> drainChat(PlayerMock player) {
        List<String> messages = new ArrayList<>();
        for (Component next = player.nextComponentMessage(); next != null; next = player.nextComponentMessage()) {
            messages.add(PlainTextComponentSerializer.plainText().serialize(next));
        }
        return messages;
    }

    private QuestState state(PlayerMock player, NamespacedKey quest) throws Exception {
        return progressRepository.find(player.getUniqueId(), quest).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .orElseThrow().state();
    }

    private void writeLogQuest(NamespacedKey id, String title, int amount) throws Exception {
        Files.writeString(questsDir.resolve(id.getKey() + ".yml"), """
                id: %s
                title: "%s"
                description: "Description"
                category: test
                repeatable: true

                steps:
                  - id: logs
                    objectives:
                      - type: BREAK_BLOCK
                        material: OAK_LOG
                        amount: %d

                rewards:
                  - type: EXPERIENCE
                    amount: 20
                """.formatted(id, title, amount));
    }
}
