package com.lodygames.rpgquest.feedback;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** Issue #817 — deux quêtes terminées ensemble : la seconde attend, elle n'efface plus la première. */
class QuestTitleQueueTest {

    private static final Title.Times TIMES =
            Title.Times.times(Duration.ofMillis(250), Duration.ofMillis(2500), Duration.ofMillis(500));
    private static final long DISPLAY = 2750L;

    private final AtomicLong now = new AtomicLong(5_000_000L);
    private final List<Runnable> tasks = new ArrayList<>();
    private final List<Long> delays = new ArrayList<>();
    private final QuestTitleQueue queue = new QuestTitleQueue(now::get, (task, delay) -> {
        tasks.add(task);
        delays.add(delay);
    });

    private ServerMock server;
    private RecordingPlayer player;

    /** PlayerMock ne mémorise pas les Title Adventure : on les enregistre ici. */
    private static final class RecordingPlayer extends PlayerMock {
        private final List<String> shown = new ArrayList<>();

        RecordingPlayer(ServerMock server) {
            super(server, "Lody", UUID.randomUUID());
        }

        @Override
        public void showTitle(@NotNull Title title) {
            shown.add(PlainTextComponentSerializer.plainText().serialize(title.subtitle()));
        }
    }

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        player = new RecordingPlayer(server);
        server.addPlayer(player);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private static Title title(String quest) {
        return Title.title(Component.text("Quête terminée"), Component.text(quest), TIMES);
    }

    private void runNextTask(long elapsed) {
        now.addAndGet(elapsed);
        tasks.remove(0).run();
    }

    @Test
    void twoQuestsCompletedTogetherAreShownOneAfterTheOther() {
        assertEquals(QuestTitleQueue.Decision.SHOWN, queue.completed(player, title("Bois du jour")));
        assertEquals(QuestTitleQueue.Decision.QUEUED, queue.completed(player, title("Le stock de Tania")));
        assertEquals(List.of("Bois du jour"), player.shown, "la seconde n'efface plus la première");
        assertEquals(List.of(DISPLAY), delays);

        runNextTask(DISPLAY);

        assertEquals(List.of("Bois du jour", "Le stock de Tania"), player.shown);
        assertEquals(0, tasks.size());
    }

    @Test
    void theWaitIsBoundedAndExtraTitlesAreDroppedNotQueuedForever() {
        queue.completed(player, title("A"));
        for (int i = 0; i < QuestTitleQueue.MAX_QUEUED; i++) {
            assertEquals(QuestTitleQueue.Decision.QUEUED, queue.completed(player, title("Q" + i)));
        }
        assertEquals(QuestTitleQueue.Decision.DROPPED, queue.completed(player, title("trop")));

        for (int i = 0; i < QuestTitleQueue.MAX_QUEUED; i++) {
            runNextTask(DISPLAY);
        }

        assertEquals(List.of("A", "Q0", "Q1", "Q2"), player.shown);
        assertEquals(0, tasks.size(), "aucune boucle : la file est vide");
    }

    @Test
    void aStartedTitleNeverHidesACompletion() {
        queue.completed(player, title("Bois du jour"));
        now.addAndGet(100);

        assertEquals(QuestTitleQueue.Decision.DROPPED, queue.started(player, title("Nouvelle quête")));
        assertEquals(List.of("Bois du jour"), player.shown);
    }

    @Test
    void aCompletionReplacesAStartedTitleAtOnce() {
        assertEquals(QuestTitleQueue.Decision.SHOWN, queue.started(player, title("Nouvelle quête")));

        assertEquals(QuestTitleQueue.Decision.SHOWN, queue.completed(player, title("Bois du jour")));
        assertEquals(List.of("Nouvelle quête", "Bois du jour"), player.shown);
    }

    @Test
    void onceTheScreenIsFreeTitlesAreShownImmediately() {
        queue.completed(player, title("A"));
        now.addAndGet(DISPLAY);

        assertEquals(QuestTitleQueue.Decision.SHOWN, queue.completed(player, title("B")));
        assertEquals(List.of("A", "B"), player.shown);
        assertEquals(0, tasks.size());
    }

    @Test
    void aQueuedTitleIsNotShownToAPlayerWhoLeft() {
        queue.completed(player, title("A"));
        queue.completed(player, title("B"));
        player.disconnect();

        runNextTask(DISPLAY);

        assertEquals(List.of("A"), player.shown);
    }
}
