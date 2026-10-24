package com.lodygames.rpgquest.feedback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import com.lodygames.rpgquest.ui.TrackedQuestSummary;
import org.junit.jupiter.api.Test;

/**
 * Issues #69 / #209 — la progression d'un objectif n'est plus effacée par l'XP du même kill.
 * Issue #817 — plusieurs quêtes qui progressent ou se terminent ensemble restent lisibles.
 */
class ActionBarFeedbackTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000069");
    private static final Component LOG = Component.text("Bûche de chêne");

    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final List<Long> scheduled = new ArrayList<>();
    private final ActionBarFeedback feedback = new ActionBarFeedback(now::get,
            (task, delay) -> scheduled.add(delay));

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    private static ActionBarFeedback.Progress progress(String quest, String title, Component objective,
                                                       int current, int total) {
        String count = " " + current + "/" + total;
        return new ActionBarFeedback.Progress(quest, quest + "/s/0", title, objective,
                objective.append(Component.text(count)), shortTitle -> Component.text(shortTitle + count));
    }

    private String objective(String quest, String title, Component objective, int current, int total) {
        Component line = feedback.composeObjective(PLAYER, progress(quest, title, objective, current, total));
        assertTrue(TrackedQuestSummary.estimatedWidthOf(line) <= ActionBarFeedback.MAX_WIDTH_PX,
                () -> "ligne plus large que l'écran minimal : " + plain(line));
        return plain(line);
    }

    private Optional<String> info(String message) {
        return feedback.composeInfo(PLAYER, null, Component.text(message)).map(ActionBarFeedbackTest::plain);
    }

    @Test
    void aSingleQuestKeepsTheShortObjectiveLine() {
        assertEquals("Araignée 3/10", objective("q", "Chasse", Component.text("Araignée"), 3, 10));
    }

    @Test
    void quickKillsUpdateTheSameObjectiveInsteadOfStackingIt() {
        objective("q", "Chasse", Component.text("Araignée"), 3, 10);
        now.addAndGet(300);

        assertEquals("Araignée 4/10", objective("q", "Chasse", Component.text("Araignée"), 4, 10));
    }

    @Test
    void twoQuestsOnTheSameLogShareOneLabelAndKeepTheirName() {
        objective("rpgquest:daily", "Bois du jour", LOG, 5, 20);

        assertEquals("Bûche de chêne : Bois du jour 5/20, Le stock 7/20",
                objective("rpgquest:tania", "Le stock", LOG, 7, 20));
    }

    @Test
    void oneLogCountingForThreeQuestsStaysOnScreenAndAttributesEveryCounter() {
        objective("rpgquest:bob", "La requête du bûcheron", LOG, 5, 20);
        objective("rpgquest:tania", "Le stock de Tania", LOG, 7, 20);

        // Avant #817 : « Quête · Bûche de chêne x/20 » trois fois, environ 700 px pour 320 visibles.
        // Le libellé commun cède la place en premier : le joueur vient de casser la bûche.
        assertEquals("La requête… 5/20, Le stock de… 7/20, Bois du jour 19/20",
                objective("rpgquest:daily", "Bois du jour", LOG, 19, 20));
    }

    @Test
    void whenNothingElseFitsTheOldestCountersBecomeAPlusN() {
        Component longA = Component.text("Minerai de cuivre profond");
        Component longB = Component.text("Bûche de bois de cerisier");
        Component longC = Component.text("Fleur de pissenlit rare");
        objective("a", "Les mines de cuivre", longA, 1, 64);
        objective("b", "Le bois des cerisiers", longB, 2, 64);
        String line = objective("c", "Le bouquet de printemps", longC, 3, 64);

        assertTrue(line.endsWith(" +1") || line.endsWith(" +2"), line);
        assertTrue(line.contains("3/64"), () -> "le compteur le plus récent reste toujours visible : " + line);
    }

    @Test
    void differentObjectivesOfSeveralQuestsAreGroupedPerObjective() {
        objective("a", "Bois du jour", LOG, 5, 20);
        String line = objective("b", "Cueillette", Component.text("Pomme"), 1, 3);

        assertEquals("Bûche de chêne : Bois du j… 5/20  ·  Pomme : Cueillette 1/3", line);
    }

    @Test
    void theMostRecentThreeObjectivesAreKept() {
        objective("a", "A", LOG, 1, 5);
        objective("b", "B", LOG, 1, 5);
        objective("c", "C", LOG, 1, 5);
        String line = objective("d", "D", LOG, 1, 5);

        assertEquals("Bûche de chêne : B 1/5, C 1/5, D 1/5", line, "l'objectif le plus ancien sort en premier");
    }

    @Test
    void aClosedStepNeverComesBackOnTheNextLine() {
        objective("rpgquest:daily", "Bois du jour", LOG, 20, 20);
        feedback.forgetQuest(PLAYER, "rpgquest:daily");

        String line = objective("rpgquest:bob", "La requête du bûcheron", LOG, 6, 20);

        assertEquals("Bûche de chêne 6/20", line, "plus de « 20/20 » périmé d'une quête terminée");
    }

    @Test
    void forgettingAQuestKeepsTheOtherQuestsCounters() {
        objective("a", "Bois du jour", LOG, 20, 20);
        objective("b", "Le stock", LOG, 7, 20);
        feedback.forgetQuest(PLAYER, "a");

        assertEquals("Bûche de chêne 8/20", objective("b", "Le stock", LOG, 8, 20));
    }

    @Test
    void anInfoDuringTheHoldIsQueuedInsteadOfBeingGluedToAnotherQuestsCounter() {
        objective("rpgquest:bob", "La requête du bûcheron", LOG, 5, 20);
        now.addAndGet(50);

        assertEquals(Optional.empty(), info("+20 XP RPG (Quêtes)"),
                "la récompense d'une autre quête ne doit pas sembler appartenir à Bob");
        assertEquals(List.of(ActionBarFeedback.HOLD_MILLIS - 50), scheduled, "une seule vérification planifiée");
    }

    @Test
    void queuedInfosAreShownAloneOnceTheHoldIsOverWithoutDuplicates() {
        objective("q", "Chasse", Component.text("Araignée"), 3, 10);
        info("+20 XP RPG (Quêtes)");
        info("+5 LodyCoins");
        info("+5 LodyCoins");
        assertEquals(1, scheduled.size());

        now.addAndGet(ActionBarFeedback.HOLD_MILLIS);

        assertEquals(Optional.of("+20 XP RPG (Quêtes)  ·  +5 LodyCoins"),
                feedback.flushDue(PLAYER).map(ActionBarFeedbackTest::plain));
        assertEquals(Optional.empty(), feedback.flushDue(PLAYER), "affichées une seule fois");
    }

    @Test
    void continuousProgressDelaysAnInfoByAtMostTheMaximalDelay() {
        objective("q", "Bois", LOG, 1, 20);
        info("+1 XP RPG (Bûcheronnage)");
        for (int i = 2; i <= 6; i++) {
            now.addAndGet(500);
            objective("q", "Bois", LOG, i, 20);
            if (now.get() < 1_000_000L + ActionBarFeedback.INFO_MAX_DELAY_MILLIS) {
                assertEquals(Optional.empty(), feedback.flushDue(PLAYER), "le compteur garde la priorité");
            }
        }

        assertEquals(Optional.of("+1 XP RPG (Bûcheronnage)"),
                feedback.flushDue(PLAYER).map(ActionBarFeedbackTest::plain),
                "délai borné : l'information finit par passer malgré une récolte continue");
    }

    @Test
    void onlyTheMostRecentInfosAreKept() {
        objective("q", "Chasse", Component.text("Araignée"), 3, 10);
        info("A");
        info("B");
        info("C");
        now.addAndGet(ActionBarFeedback.HOLD_MILLIS);

        assertEquals(Optional.of("B  ·  C"), feedback.flushDue(PLAYER).map(ActionBarFeedbackTest::plain));
    }

    @Test
    void afterTheHoldTheInfoIsShownAloneImmediately() {
        objective("q", "Chasse", Component.text("Araignée"), 3, 10);
        now.addAndGet(ActionBarFeedback.HOLD_MILLIS);

        assertEquals(Optional.of("+5 XP (Combat)"), info("+5 XP (Combat)"));
        assertTrue(scheduled.isEmpty());
    }

    @Test
    void playersDoNotShareTheirLine() {
        objective("q", "Chasse", Component.text("Araignée"), 3, 10);

        assertEquals(Optional.of("+5 XP (Combat)"), feedback.composeInfo(UUID.randomUUID(), null,
                Component.text("+5 XP (Combat)")).map(ActionBarFeedbackTest::plain));
    }

    @Test
    void shortTitlesAreCutOnCodePointsWithAnEllipsis() {
        int max = ActionBarFeedback.SHORT_TITLE;
        assertEquals("Bois du jour", ActionBarFeedback.shortTitle("Bois du jour", max));
        assertEquals("La requête…", ActionBarFeedback.shortTitle("La requête du bûcheron", max));
        String cut = ActionBarFeedback.shortTitle("Une quête au titre vraiment long", max);
        assertEquals(max, cut.codePointCount(0, cut.length()));
        assertFalse(ActionBarFeedback.shortTitle("Quête  à   très longue", max).contains(" …"));
    }
}
