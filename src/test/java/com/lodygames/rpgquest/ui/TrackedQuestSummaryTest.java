package com.lodygames.rpgquest.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.quest.model.CollectItemObjective;
import com.lodygames.rpgquest.quest.model.DeliverItemToNpcObjective;
import com.lodygames.rpgquest.quest.model.KillEntityObjective;
import com.lodygames.rpgquest.quest.model.LocalizedText;
import com.lodygames.rpgquest.quest.model.QuestDefinition;
import com.lodygames.rpgquest.quest.model.QuestObjective;
import com.lodygames.rpgquest.quest.model.QuestStep;
import com.lodygames.rpgquest.quest.model.TalkToNpcObjective;
import com.lodygames.rpgquest.quest.progress.ObjectiveProgressView;
import com.lodygames.rpgquest.quest.progress.QuestStepProgressView;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Issue #71 : composition de la bossbar de suivi — bornée quel que soit le nombre d'objectifs,
 * sans identifiant technique, compteur jamais tronqué. Fixtures isolées : aucune quête réelle.
 */
class TrackedQuestSummaryTest {

    @BeforeEach
    void setUp() {
        // Component.translatable(EntityType) lit la clé de traduction via le serveur.
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private static final Function<String, Optional<String>> NO_NAMES = id -> Optional.empty();

    private static final List<EntityType> MOBS = List.of(EntityType.SPIDER, EntityType.ZOMBIE,
            EntityType.SKELETON, EntityType.CREEPER, EntityType.WITCH, EntityType.SLIME, EntityType.ENDERMAN,
            EntityType.BLAZE, EntityType.HUSK, EntityType.STRAY, EntityType.DROWNED, EntityType.PHANTOM,
            EntityType.PILLAGER, EntityType.VINDICATOR, EntityType.EVOKER, EntityType.RAVAGER,
            EntityType.GHAST, EntityType.MAGMA_CUBE, EntityType.SILVERFISH, EntityType.CAVE_SPIDER);

    private static QuestDefinition quest(String title, List<QuestStep> steps) {
        return new QuestDefinition(new NamespacedKey("rpgquest", "fixture"), LocalizedText.of(title),
                LocalizedText.of("Description"), "tutorial", Material.BOOK, false, false, List.of(), steps,
                List.of(), Map.of(), null);
    }

    private static List<QuestObjective> kills(int count) {
        List<QuestObjective> objectives = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            objectives.add(new KillEntityObjective(MOBS.get(i), 5));
        }
        return objectives;
    }

    /** Progression : les {@code done} premiers objectifs terminés, les autres à 2/5. */
    private static QuestStepProgressView view(String stepId, int count, int done) {
        List<ObjectiveProgressView> progress = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            progress.add(new ObjectiveProgressView("Tuer " + MOBS.get(i).name(), i < done ? 5 : 2, 5));
        }
        return new QuestStepProgressView(stepId, progress);
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    private static void assertNoTechnicalId(String plain) {
        assertFalse(plain.matches(".*\\b[A-Z]{2,}(_[A-Z]+)*\\b.*"), "aucun identifiant en majuscules : " + plain);
        assertFalse(plain.contains("_"), "aucun identifiant technique (underscore) : " + plain);
        assertFalse(plain.contains("<"), "aucune balise MiniMessage résiduelle : " + plain);
    }

    @ParameterizedTest(name = "{0} objectif(s)")
    @ValueSource(ints = {1, 4, 10, 20})
    void summaryStaysWithinTheSmallestScreenWidth(int count) {
        String longTitle = "La très longue chasse aux cristaux oubliés des profondeurs du désert";
        QuestDefinition quest = quest(longTitle, List.of(
                new QuestStep("hunt_monsters", kills(count)), new QuestStep("return_to_guard", kills(1))));

        TrackedQuestSummary.Summary summary = TrackedQuestSummary.compose(quest,
                view("hunt_monsters", count, count / 2), kills(count), NO_NAMES);

        int estimated = TrackedQuestSummary.estimatedWidthOf(summary.name());
        assertTrue(estimated <= TrackedQuestSummary.MAX_WIDTH_PX, () -> "trop large (" + estimated + " px)");
        String plain = plain(summary.name());
        assertNoTechnicalId(plain);
        assertFalse(plain.contains("hunt_monsters"));
        assertTrue(plain.contains("Étape 1/2"), plain);
        assertTrue(plain.contains(TrackedQuestSummary.ELLIPSIS), "titre long raccourci proprement : " + plain);
        if (count == 1) {
            assertTrue(plain.contains("Spider"), "nom traduit (clé vanilla), jamais SPIDER : " + plain);
            assertTrue(plain.endsWith(" 2/5"), "compteur de l'objectif unique, jamais tronqué : " + plain);
        } else {
            assertTrue(plain.endsWith((count / 2) + "/" + count + " objectifs"),
                    "décompte d'objectifs, jamais la liste concaténée : " + plain);
            assertFalse(plain.contains("Spider") || plain.contains("Zombie"), "aucun nom d'objectif listé : " + plain);
        }
    }

    @Test
    void shortTitleIsNeverTruncated() {
        QuestDefinition quest = quest("Le Garde — Palier 2", List.of(new QuestStep("palier_2", kills(4))));
        String plain = plain(TrackedQuestSummary.compose(quest, view("palier_2", 4, 1), kills(4), NO_NAMES).name());
        assertEquals("Le Garde — Palier 2 — 1/4 objectifs", plain);
    }

    @Test
    void deliveryNamesTheNpcDisplayNameNeverItsId() {
        QuestObjective deliver = new DeliverItemToNpcObjective("encounter_merchant", Material.CACTUS, 12);
        QuestDefinition quest = quest("Marchandise du désert", List.of(new QuestStep("deliver", List.of(deliver))));
        QuestStepProgressView view = new QuestStepProgressView("deliver",
                List.of(new ObjectiveProgressView("Remettre CACTUS à encounter_merchant", 0, 12)));

        String named = plain(TrackedQuestSummary.compose(quest, view, List.of(deliver),
                id -> Optional.of("Marchand")).name());
        assertTrue(named.contains("à remettre à Marchand"), named);
        assertTrue(named.endsWith(" 0/12"), "la quantité reste visible : " + named);
        assertFalse(named.contains("encounter_merchant"), named);
        assertFalse(named.contains("CACTUS"), named);

        String anonymous = plain(TrackedQuestSummary.compose(quest, view, List.of(deliver), NO_NAMES).name());
        assertFalse(anonymous.contains("encounter_merchant"), "PNJ sans nom connu : jamais son id : " + anonymous);
    }

    @Test
    void npcNameThatDoesNotFitFallsBackToTheGenericDeliveryLabel() {
        QuestObjective deliver = new DeliverItemToNpcObjective("encounter_merchant", Material.CACTUS, 12);
        QuestDefinition quest = quest("Marchandise du désert", List.of(new QuestStep("deliver", List.of(deliver))));
        TrackedQuestSummary.Summary summary = TrackedQuestSummary.compose(quest, new QuestStepProgressView("deliver",
                List.of(new ObjectiveProgressView("x", 0, 12))), List.of(deliver), id -> Optional.of("Marchand égaré"));
        String plain = plain(summary.name());
        assertTrue(plain.contains("(à remettre) 0/12"), plain);
        assertTrue(TrackedQuestSummary.estimatedWidthOf(summary.name()) <= TrackedQuestSummary.MAX_WIDTH_PX, plain);
        assertFalse(plain.contains("encounter_merchant"), plain);
    }

    @Test
    void longNpcNameIsShortened() {
        QuestObjective talk = new TalkToNpcObjective("guard");
        QuestDefinition quest = quest("Rapport", List.of(new QuestStep("talk", List.of(talk))));
        QuestStepProgressView view = new QuestStepProgressView("talk",
                List.of(new ObjectiveProgressView("Parler à guard", 0, 1)));
        String plain = plain(TrackedQuestSummary.compose(quest, view, List.of(talk),
                id -> Optional.of("Capitaine de la garde royale de Nerana")).name());
        assertTrue(plain.contains(TrackedQuestSummary.ELLIPSIS), plain);
        assertFalse(plain.contains("royale de Nerana"), "nom de PNJ borné : " + plain);
        assertFalse(plain.contains("guard"), plain);
        assertFalse(plain.contains("0/1"), "objectif binaire : pas de compteur : " + plain);
    }

    @Test
    void stepWithoutObjectivesShowsOnlyTitleAndStepNeverTheStepId() {
        QuestDefinition quest = quest("Premiers pas", List.of(new QuestStep("kill_spiders", kills(1))));
        String plain = plain(TrackedQuestSummary.compose(quest, new QuestStepProgressView("kill_spiders", List.of()),
                List.of(), NO_NAMES).name());
        assertEquals("Premiers pas", plain);
    }

    @Test
    void desynchronisedDefinitionNeverShowsTheRawEngineDescription() {
        QuestDefinition quest = quest("Premiers pas", List.of(new QuestStep("kill_spiders", kills(1))));
        String plain = plain(TrackedQuestSummary.compose(quest,
                new QuestStepProgressView("kill_spiders", List.of(new ObjectiveProgressView("Tuer SPIDER", 1, 5))),
                List.of(), NO_NAMES).name());
        assertNoTechnicalId(plain);
        assertTrue(plain.endsWith("0/1 objectif"), plain);
    }

    @Test
    void titleMarkupIsStrippedBeforeTruncation() {
        QuestDefinition quest = quest("<red>La chasse aux cristaux</red>", List.of(new QuestStep("hunt", kills(1))));
        String plain = plain(TrackedQuestSummary.compose(quest, view("hunt", 1, 0), kills(1), NO_NAMES).name());
        assertTrue(plain.startsWith("La chasse aux cristaux — "), plain);
    }

    @Test
    void progressCountsObjectivesNotHeterogeneousUnits() {
        List<ObjectiveProgressView> mixed = List.of(new ObjectiveProgressView("a", 3, 3),
                new ObjectiveProgressView("b", 0, 100));
        assertEquals(0.5f, TrackedQuestSummary.progressOf(mixed), 0.001f,
                "1 objectif sur 2 terminé = moitié, pas 3/103");
        assertEquals(0.4f, TrackedQuestSummary.progressOf(List.of(new ObjectiveProgressView("a", 2, 5))), 0.001f);
    }

    @Test
    void truncateKeepsAMinimumAndAddsEllipsis() {
        String cut = TrackedQuestSummary.truncate("Une quête au titre vraiment interminable", 60);
        assertTrue(cut.endsWith(TrackedQuestSummary.ELLIPSIS), cut);
        assertTrue(cut.codePointCount(0, cut.length()) - 1 >= TrackedQuestSummary.MIN_TITLE_CHARS, cut);
        assertEquals("Court", TrackedQuestSummary.truncate("Court", 300));
    }

    @Test
    void translatedNameIsEstimatedFromItsKeyWithFrenchMargin() {
        // « spider » (6) → 9 caractères estimés de 6 px, soit au moins « Araignée ».
        assertEquals(9 * 6, TrackedQuestSummary.estimatedWidthOf(Component.translatable("entity.minecraft.spider")));
        assertEquals(8 * 6 + TrackedQuestSummary.widthOf(" 2/5"),
                TrackedQuestSummary.estimatedWidthOf(Component.translatable("entity.minecraft.pig")
                        .append(Component.text(" 2/5"))), "le texte ajouté au nom est mesuré aussi");
    }

    @Test
    void widthTableMatchesTheVanillaFontForCommonGlyphs() {
        assertEquals(6, TrackedQuestSummary.widthOf("a"));
        assertEquals(2, TrackedQuestSummary.widthOf("i"));
        assertEquals(4, TrackedQuestSummary.widthOf(" "));
        assertEquals(6, TrackedQuestSummary.widthOf("é"));
    }

    @Test
    void collectObjectiveUsesTranslatedItemName() {
        QuestObjective collect = new CollectItemObjective(Material.AMETHYST_SHARD, 20);
        QuestDefinition quest = quest("Cristaux", List.of(new QuestStep("gather_crystals", List.of(collect))));
        String plain = plain(TrackedQuestSummary.compose(quest, new QuestStepProgressView("gather_crystals",
                List.of(new ObjectiveProgressView("Collecter AMETHYST_SHARD", 7, 20))), List.of(collect), NO_NAMES).name());
        assertTrue(plain.contains("Amethyst Shard") && plain.endsWith(" 7/20"), plain);
    }
}
