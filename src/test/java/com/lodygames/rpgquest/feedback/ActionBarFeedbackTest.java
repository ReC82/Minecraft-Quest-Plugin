package com.lodygames.rpgquest.feedback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.ui.TrackedQuestSummary;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

/**
 * Issues #69 / #209 — la progression d'un objectif n'est plus effacée par l'XP du même kill.
 * Issue #817 — plusieurs quêtes qui progressent ensemble : une progression par ligne, chacune à
 * son tour, titre en texte brut ; plus jamais « {@code <gold>Bois... 2/16, <gold>La re... 2/20} ».
 */
class ActionBarFeedbackTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000069");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000070");
    private static final MiniMessage MM = MiniMessage.miniMessage();
    /** Les formes réelles de {@code messages.yml}. */
    private static final String ALONE = "<yellow><objective></yellow> <gray><current>/<total></gray>";
    private static final String NAMED =
            "<white><quest></white> <dark_gray>·</dark_gray> <yellow><objective></yellow> <gray><current>/<total></gray>";
    /** Libellé traduit côté client, comme en jeu (« Oak Log » / « Bûche de chêne »). */
    private static final Component LOG = Component.translatable("block.minecraft.oak_log");

    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final List<Task> scheduled = new ArrayList<>();
    private final ActionBarFeedback feedback = new ActionBarFeedback(now::get,
            (task, delay) -> scheduled.add(new Task(now.get() + delay, task)));
    private final List<Component> sent = new ArrayList<>();
    private final List<Component> sentToOther = new ArrayList<>();

    private record Task(long at, Runnable task) {
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    /** Progression telle que le moteur la construit, à partir d'un titre MiniMessage brut. */
    private static ActionBarFeedback.Progress progress(String quest, String rawTitle, Component objective,
                                                       int current, int total) {
        var counts = net.kyori.adventure.text.minimessage.tag.resolver.TagResolver.resolver(
                Placeholder.unparsed("current", String.valueOf(current)),
                Placeholder.unparsed("total", String.valueOf(total)));
        return new ActionBarFeedback.Progress("rpgquest:" + quest, "rpgquest:" + quest + "/logs/0",
                TrackedQuestSummary.plainTitle(rawTitle, id -> Optional.empty()),
                MM.deserialize(ALONE, Placeholder.component("objective", objective), counts),
                shortTitle -> MM.deserialize(NAMED, Placeholder.unparsed("quest", shortTitle),
                        Placeholder.component("objective", objective), counts));
    }

    private void objective(String quest, String rawTitle, int current, int total) {
        feedback.objective(PLAYER, sent::add, () -> true, progress(quest, rawTitle, LOG, current, total));
    }

    private void info(String message) {
        feedback.info(PLAYER, sent::add, () -> true, Component.text(message));
    }

    /** Avance l'horloge et exécute les tâches échues, comme le planificateur Bukkit. */
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

    private String last() {
        return plain(sent.get(sent.size() - 1));
    }

    private List<String> sentLines() {
        return sent.stream().map(ActionBarFeedbackTest::plain).toList();
    }

    private void assertReadable(Component line) {
        String text = plain(line);
        assertFalse(text.contains("<") || text.contains(">"), () -> "balise en clair : " + text);
        assertFalse(text.contains(","), () -> "deux progressions concaténées : " + text);
        assertTrue(TrackedQuestSummary.estimatedWidthOf(line) <= ActionBarFeedback.MAX_WIDTH_PX,
                () -> "ligne plus large que l'écran minimal : " + text);
        assertTrue(text.split("\\d+/\\d+", -1).length <= 2, () -> "plus d'une progression : " + text);
    }

    @Test
    void aSingleQuestKeepsTheShortObjectiveLine() {
        objective("hunt", "<gold>Chasse", 3, 10);

        assertEquals("Oak Log 3/10", last());
    }

    @Test
    void quickProgressOfTheShownObjectiveIsUpdatedInPlace() {
        objective("hunt", "Chasse", 3, 10);
        advance(300);
        objective("hunt", "Chasse", 4, 10);

        assertEquals("Oak Log 4/10", last());
        assertEquals(0, feedback.waiting(PLAYER));
    }

    /** La capture du 29/10 : deux quêtes de bûches dont le titre est coloré en MiniMessage. */
    @Test
    void ownerScreenshotTwoColouredTitlesNeverLeakTagsAndAreShownOneAfterTheOther() {
        objective("daily", "<gold>Bois du jour</gold>", 2, 16);
        objective("request", "<gold>La requête du bûcheron", 2, 20);

        assertEquals("Bois du jour · Oak Log 2/16", last(),
                "la première ligne est renommée dès que la seconde quête arrive");
        advance(ActionBarFeedback.SLOT_MILLIS);
        assertEquals("La requête du bûcheron · Oak Log 2/20", last());
        sent.forEach(this::assertReadable);
    }

    @Test
    void threeQuestsOnTheSameLogAreEachShownOnceWithTheirLatestCounter() {
        objective("bob", "<gold>La requête du bûcheron", 5, 20);
        objective("tania", "<yellow>Le stock de Tania", 7, 20);
        objective("daily", "<green><bold>Bois du jour", 1, 2);
        advance(500);
        // Le joueur casse une deuxième bûche avant la fin du premier tour.
        objective("bob", "<gold>La requête du bûcheron", 6, 20);
        objective("tania", "<yellow>Le stock de Tania", 8, 20);
        objective("daily", "<green><bold>Bois du jour", 2, 2);
        advance(3 * ActionBarFeedback.SLOT_MILLIS);

        // La toute première ligne (seule) est renommée dans le même tick : le client ne voit que la seconde.
        assertEquals(List.of(
                "Oak Log 5/20",
                "La requête du bûcheron · Oak Log 5/20",
                "La requête du bûcheron · Oak Log 6/20",
                "Le stock de Tania · Oak Log 8/20",
                "Bois du jour · Oak Log 2/2"), sentLines());
        sent.forEach(this::assertReadable);
    }

    @Test
    void continuousHarvestingNeverStarvesTheOtherQuests() {
        for (int log = 1; log <= 12; log++) {
            objective("bob", "La requête du bûcheron", log, 20);
            objective("tania", "Le stock de Tania", log, 20);
            advance(400);
        }
        advance(2 * ActionBarFeedback.SLOT_MILLIS);

        List<String> lines = sentLines();
        assertTrue(lines.stream().filter(l -> l.startsWith("Le stock")).count() >= 3, lines::toString);
        assertTrue(lines.contains("Le stock de Tania · Oak Log 12/20"), lines::toString);
        assertTrue(lines.contains("La requête du bûcheron · Oak Log 12/20"), lines::toString);
    }

    @Test
    void aVeryLongTitleIsCutOnPlainTextToFitTheSmallestScreen() {
        objective("a", "<gradient:gold:red>La très longue quête du grand bûcheron des forêts du Nord</gradient>",
                3, 20);
        objective("b", "<gold>Autre", 1, 5);

        String line = last();
        assertTrue(line.startsWith("La très longue"), line);
        assertTrue(line.contains("… · Oak Log 3/20"), line);
        sent.forEach(this::assertReadable);
    }

    @Test
    void escapedAngleBracketsInATitleStayLiteralText() {
        objective("a", "Coffre \\<vide>", 1, 3);
        objective("b", "Autre", 1, 3);

        assertTrue(last().startsWith("Coffre <vide>"), last());
    }

    @Test
    void anXpArrivingRightBeforeTheKillProgressIsShownAfterIt() {
        info("+5 XP RPG");
        objective("hunt", "Chasse", 3, 10);

        assertEquals("Oak Log 3/10", last(), "#69 : la progression passe devant");
        advance(ActionBarFeedback.SLOT_MILLIS);
        assertEquals("+5 XP RPG", last(), "l'XP n'est pas perdue");
    }

    @Test
    void anInfoDuringAProgressWaitsItsTurnAndIsShownAlone() {
        objective("hunt", "Chasse", 3, 10);
        advance(50);
        info("+5 XP RPG");
        assertEquals("Oak Log 3/10", last());

        advance(ActionBarFeedback.SLOT_MILLIS);
        assertEquals("+5 XP RPG", last());
    }

    @Test
    void anOldInfoOnScreenIsNotPreemptedButFollowedByTheProgress() {
        info("+5 LodyCoins");
        advance(ActionBarFeedback.PREEMPT_MILLIS + 100);
        objective("hunt", "Chasse", 3, 10);
        assertEquals("+5 LodyCoins", last());

        advance(ActionBarFeedback.SLOT_MILLIS);
        assertEquals("Oak Log 3/10", last());
    }

    @Test
    void identicalInfosAreMergedAndOnlyTheMostRecentAreKept() {
        objective("hunt", "Chasse", 3, 10);
        info("+1 XP RPG");
        info("+1 XP RPG");
        info("Niveau supérieur !");
        info("+2 LodyCoins");
        info("+3 LodyCoins");

        assertEquals(ActionBarFeedback.MAX_INFOS, feedback.waiting(PLAYER));
        advance(10 * ActionBarFeedback.SLOT_MILLIS);
        assertEquals(List.of("Oak Log 3/10", "Niveau supérieur !", "+2 LodyCoins", "+3 LodyCoins"),
                sentLines());
    }

    @Test
    void aFinishedQuestLeavesTheQueueButNotTheOthers() {
        objective("daily", "Bois du jour", 2, 2);
        objective("bob", "La requête du bûcheron", 2, 20);
        objective("tania", "Le stock de Tania", 2, 20);
        feedback.forgetQuest(PLAYER, "rpgquest:bob");
        advance(3 * ActionBarFeedback.SLOT_MILLIS);

        List<String> lines = sentLines();
        assertFalse(lines.stream().anyMatch(l -> l.startsWith("La requête")), lines::toString);
        assertTrue(lines.contains("Le stock de Tania · Oak Log 2/20"), lines::toString);
    }

    @Test
    void theSeriesEndsWhenEverythingHasBeenShownAndNamingResets() {
        objective("a", "Bois du jour", 1, 2);
        objective("b", "Autre", 1, 2);
        advance(5 * ActionBarFeedback.SLOT_MILLIS);
        assertTrue(scheduled.isEmpty());

        objective("a", "Bois du jour", 2, 2);
        assertEquals("Oak Log 2/2", last(), "nouvelle série à une seule quête");
    }

    @Test
    void aProgressArrivingAfterTheTurnButBeforeTheTickKeepsTheQueueOrder() {
        objective("a", "A", 1, 5);
        objective("b", "B", 1, 5);
        now.addAndGet(ActionBarFeedback.SLOT_MILLIS + 10); // tour fini, tâche pas encore exécutée
        objective("c", "C", 1, 5);

        assertEquals("B · Oak Log 1/5", last());
        advance(ActionBarFeedback.SLOT_MILLIS);
        assertEquals("C · Oak Log 1/5", last());
    }

    @Test
    void anOfflinePlayerEndsTheSeries() {
        feedback.objective(PLAYER, sent::add, () -> false, progress("a", "A", LOG, 1, 5));
        feedback.objective(PLAYER, sent::add, () -> false, progress("b", "B", LOG, 1, 5));
        advance(ActionBarFeedback.SLOT_MILLIS);

        assertFalse(sentLines().stream().anyMatch(l -> l.startsWith("B ")), sentLines()::toString);
        assertEquals(0, feedback.waiting(PLAYER));
    }

    @Test
    void playersDoNotShareTheirLine() {
        objective("a", "A", 1, 5);
        feedback.objective(OTHER, sentToOther::add, () -> true, progress("b", "B", LOG, 4, 5));

        assertEquals("Oak Log 1/5", last());
        assertEquals("Oak Log 4/5", plain(sentToOther.get(0)));
    }
}
