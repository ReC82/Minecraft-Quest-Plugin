package com.lodygames.rpgquest.feedback;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

/** Issues #69 / #209 — la progression d'un objectif n'est plus effacée par l'XP du même kill. */
class ActionBarFeedbackTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000069");

    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final ActionBarFeedback feedback = new ActionBarFeedback(now::get);

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    @Test
    void xpEarnedRightAfterAKillIsAppendedInsteadOfReplacingTheProgress() {
        feedback.composeObjective(PLAYER, "q/s/0", Component.text("Araignées : 3/10"));
        now.addAndGet(50);

        String line = plain(feedback.composeInfo(PLAYER, Component.text("+5 XP (Combat)")));

        assertEquals("Araignées : 3/10  ·  +5 XP (Combat)", line);
    }

    @Test
    void afterTheHoldTheXpIsShownAlone() {
        feedback.composeObjective(PLAYER, "q/s/0", Component.text("Araignées : 3/10"));
        now.addAndGet(ActionBarFeedback.HOLD_MILLIS);

        assertEquals("+5 XP (Combat)", plain(feedback.composeInfo(PLAYER, Component.text("+5 XP (Combat)"))));
    }

    @Test
    void quickKillsUpdateTheSameObjectiveInsteadOfStackingIt() {
        feedback.composeObjective(PLAYER, "q/s/0", Component.text("Araignées : 3/10"));
        now.addAndGet(300);

        String line = plain(feedback.composeObjective(PLAYER, "q/s/0", Component.text("Araignées : 4/10")));

        assertEquals("Araignées : 4/10", line);
    }

    @Test
    void aKillCountingForTwoQuestsShowsBothAndKeepsTheMostRecentThree() {
        feedback.composeObjective(PLAYER, "a/s/0", Component.text("Zombies : 1/5"));
        assertEquals("Zombies : 1/5  ·  Zombies : 2/3",
                plain(feedback.composeObjective(PLAYER, "b/s/0", Component.text("Zombies : 2/3"))));

        feedback.composeObjective(PLAYER, "c/s/0", Component.text("C"));
        String line = plain(feedback.composeObjective(PLAYER, "d/s/0", Component.text("D")));

        assertEquals(ActionBarFeedback.MAX_PARTS, line.split("  ·  ").length);
        assertEquals("Zombies : 2/3  ·  C  ·  D", line, "l'objectif le plus ancien sort en premier");
    }

    @Test
    void playersDoNotShareTheirLine() {
        feedback.composeObjective(PLAYER, "q/s/0", Component.text("Araignées : 3/10"));

        assertEquals("+5 XP (Combat)", plain(feedback.composeInfo(UUID.randomUUID(), Component.text("+5 XP (Combat)"))));
    }
}
