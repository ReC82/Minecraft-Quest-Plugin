package com.lodygames.rpgquest.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.dialogue.DialogueTextPlaceholders;
import com.lodygames.rpgquest.quest.model.KillEntityObjective;
import com.lodygames.rpgquest.quest.model.LocalizedText;
import com.lodygames.rpgquest.quest.model.QuestDefinition;
import com.lodygames.rpgquest.quest.model.QuestStep;
import com.lodygames.rpgquest.quest.model.TalkToNpcObjective;
import com.lodygames.rpgquest.quest.progress.ObjectiveProgressView;
import com.lodygames.rpgquest.quest.progress.QuestStepProgressView;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

/**
 * Issue #841 : {@code %quest_progress:<quête>%} — un PNJ dit au joueur où il en est, avec les
 * compteurs du journal. MockBukkit est nécessaire : la clé de traduction d'une entité passe par
 * l'implémentation du serveur.
 */
class QuestProgressTextTest {

    private static final Function<String, Optional<String>> NPC_NAMES =
            id -> "guard".equals(id) ? Optional.of("Gauthier") : Optional.empty();

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private static QuestDefinition tier1() {
        return new QuestDefinition(new NamespacedKey("rpgquest", "guard_tier1"),
                LocalizedText.of("<gold>Gauthier — Palier 1</gold>"), LocalizedText.of("Prouve ta valeur."),
                "combat", Material.WOODEN_SWORD, false, false, List.of(),
                List.of(new QuestStep("prove_worth", List.of(
                        new KillEntityObjective(EntityType.SPIDER, 5),
                        new KillEntityObjective(EntityType.ZOMBIE, 5),
                        new KillEntityObjective(EntityType.SKELETON, 5),
                        new KillEntityObjective(EntityType.CREEPER, 5)))),
                List.of(), Map.of(), "guard");
    }

    private static String plain(String miniMessage) {
        return PlainTextComponentSerializer.plainText().serialize(MiniMessage.miniMessage().deserialize(miniMessage));
    }

    @Test
    void anActiveQuestShowsItsTitleAndEveryObjectiveCounterLikeTheJournal() {
        QuestStepProgressView view = new QuestStepProgressView("prove_worth", List.of(
                new ObjectiveProgressView("Tuer SPIDER", 3, 5),
                new ObjectiveProgressView("Tuer ZOMBIE", 5, 5),
                new ObjectiveProgressView("Tuer SKELETON", 0, 5),
                new ObjectiveProgressView("Tuer CREEPER", 1, 5)));

        String rendered = QuestProgressText.render(tier1(), Optional.of(view), NPC_NAMES);

        assertTrue(rendered.startsWith("<gold>Gauthier — Palier 1</gold>"), rendered);
        assertEquals(4, rendered.split("<newline>").length - 1, () -> "une ligne par objectif : " + rendered);
        for (String counter : List.of(" 3/5", " 5/5", " 0/5", " 1/5")) {
            assertTrue(rendered.contains(counter), () -> "compteur " + counter + " absent : " + rendered);
        }
        assertTrue(rendered.contains("entity.minecraft.spider"), () -> "nom traduit côté client : " + rendered);
        assertTrue(!rendered.contains("SPIDER"), () -> "jamais l'identifiant technique : " + rendered);
        // La ligne du dialogue est exactement celle du journal.
        String journalLine = MiniMessage.miniMessage().serialize(ObjectiveLabels.counterLine(
                view.objectives().get(0), tier1().steps().get(0).objectives().get(0), NPC_NAMES));
        assertTrue(rendered.contains(journalLine), rendered);
    }

    @Test
    void aQuestThatIsNotInProgressSaysSoInsteadOfInventingCounters() {
        assertEquals(QuestProgressText.NOT_IN_PROGRESS, QuestProgressText.render(tier1(), Optional.empty(), NPC_NAMES));
    }

    @Test
    void anUnknownQuestLeavesTheTokenVisible() {
        assertNull(QuestProgressText.render(null, Optional.empty(), NPC_NAMES));
        DialogueTextPlaceholders placeholders = new DialogueTextPlaceholders(Map.of(), Map.of(
                "quest_progress", (context, raw) -> QuestProgressText.render(null, Optional.empty(), NPC_NAMES)));
        assertEquals("%quest_progress:inconnue%", placeholders.apply(null, "%quest_progress:inconnue%"));
    }

    @Test
    void aMultiStepQuestNamesTheCurrentStepAndNamesNpcsByTheirDisplayName() {
        QuestDefinition quest = new QuestDefinition(new NamespacedKey("rpgquest", "escort"),
                LocalizedText.of("<gold>Escorte</gold>"), LocalizedText.of("…"), "story", Material.MAP, false, false,
                List.of(), List.of(
                        new QuestStep("hunt", List.of(new KillEntityObjective(EntityType.ZOMBIE, 2))),
                        new QuestStep("report", List.of(new TalkToNpcObjective("guard")))),
                List.of(), Map.of(), "guard");
        QuestStepProgressView view = new QuestStepProgressView("report",
                List.of(new ObjectiveProgressView("Parler à Gauthier", 0, 1)));

        String text = plain(QuestProgressText.render(quest, Optional.of(view), NPC_NAMES));

        assertTrue(text.contains("étape 2/2"), text);
        assertTrue(text.contains("Parler à Gauthier"), text);
        assertTrue(!text.contains("0/1"), () -> "un objectif binaire n'a pas de compteur : " + text);
    }

    @Test
    void theQuestIdAcceptsTheBareFormAndTheNamespacedFormOfQuestStateConditions() {
        NamespacedKey expected = new NamespacedKey("rpgquest", "guard_tier1");
        assertEquals(expected, QuestProgressText.questKey("guard_tier1"));
        assertEquals(expected, QuestProgressText.questKey("rpgquest:guard_tier1"));
        assertNull(QuestProgressText.questKey(""));
        assertNull(QuestProgressText.questKey("a:b:c"));
    }

    @Test
    void theNamespacedArgumentReachesTheTokenValue() {
        DialogueTextPlaceholders placeholders = new DialogueTextPlaceholders(Map.of(), Map.of(
                "quest_progress", (context, raw) -> "[" + raw + "]"));
        assertEquals("Où : [rpgquest:guard_tier1]", placeholders.apply(null, "Où : %quest_progress:rpgquest:guard_tier1%"));
    }
}
