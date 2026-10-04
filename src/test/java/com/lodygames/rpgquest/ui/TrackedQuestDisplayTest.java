package com.lodygames.rpgquest.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.quest.model.KillEntityObjective;
import com.lodygames.rpgquest.quest.model.LocalizedText;
import com.lodygames.rpgquest.quest.model.QuestDefinition;
import com.lodygames.rpgquest.quest.model.QuestStep;
import com.lodygames.rpgquest.quest.progress.ObjectiveProgressView;
import com.lodygames.rpgquest.quest.progress.QuestStepProgressView;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * Issue #157 : la bossbar de suivi affichait une balise {@code </gray>} littérale (gabarit mal
 * formé -- une fermeture en trop, sans ouverture correspondante, que MiniMessage rend telle quelle
 * plutôt que de la refuser) et l'id technique brut de l'étape (ex. {@code kill_spiders}) au lieu
 * d'un libellé humain. Vérifie le résultat final réellement envoyé à la bossbar (le
 * {@link BossBar} affiché au joueur via MockBukkit), pas une fonction utilitaire isolée.
 */
class TrackedQuestDisplayTest {

    private ServerMock server;
    private PlayerMock player;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        player = server.addPlayer();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private QuestDefinition quest(String title) {
        return new QuestDefinition(
                new NamespacedKey("rpgquest", "premiers_pas"),
                LocalizedText.of(title),
                LocalizedText.of("Description"),
                "tutorial",
                Material.BOOK,
                false,
                false,
                List.of(),
                List.of(new QuestStep("kill_spiders", List.of(new KillEntityObjective(EntityType.SPIDER, 5)))),
                List.of(),
                Map.of(),
                null);
    }

    @Test
    void bossBarNeverShowsALiteralClosingTagAndShowsTheHumanObjectiveLabel() {
        TrackedQuestDisplay display = new TrackedQuestDisplay(true);
        QuestStepProgressView step = new QuestStepProgressView("kill_spiders",
                List.of(new ObjectiveProgressView("Tuer SPIDER", 2, 5)));

        display.update(player, quest("Premiers pas"), step);

        BossBar bar = player.getBossBars().iterator().next();
        String plain = PlainTextComponentSerializer.plainText().serialize(bar.name());
        assertFalse(plain.contains("</gray>"), "aucune balise littérale résiduelle : " + plain);
        assertFalse(plain.contains("<"), "aucune balise MiniMessage non interprétée : " + plain);
        assertTrue(plain.contains("Tuer SPIDER"), "le libellé humain de l'objectif doit apparaître : " + plain);
        assertFalse(plain.contains("kill_spiders"), "jamais l'id technique brut affiché : " + plain);
    }

    @Test
    void progressUpdatesTheSameBarWithoutDuplicatingIt() {
        TrackedQuestDisplay display = new TrackedQuestDisplay(true);
        QuestDefinition quest = quest("Premiers pas");
        display.update(player, quest, new QuestStepProgressView("kill_spiders",
                List.of(new ObjectiveProgressView("Tuer SPIDER", 1, 5))));
        display.update(player, quest, new QuestStepProgressView("kill_spiders",
                List.of(new ObjectiveProgressView("Tuer SPIDER", 3, 5))));

        assertEquals(1, player.getBossBars().size(), "une seule bossbar par joueur, jamais dupliquée");
        BossBar bar = player.getBossBars().iterator().next();
        assertEquals(0.6f, bar.progress(), 0.001f);
    }

    @Test
    void clearRemovesTheBossBar() {
        TrackedQuestDisplay display = new TrackedQuestDisplay(true);
        display.update(player, quest("Premiers pas"), new QuestStepProgressView("kill_spiders",
                List.of(new ObjectiveProgressView("Tuer SPIDER", 1, 5))));
        display.clear(player);
        assertTrue(player.getBossBars().isEmpty());
    }

    @Test
    void fallsBackToTheStepIdOnlyWhenNoObjectiveDescriptionIsAvailable() {
        TrackedQuestDisplay display = new TrackedQuestDisplay(true);
        display.update(player, quest("Premiers pas"), new QuestStepProgressView("kill_spiders", List.of()));

        BossBar bar = player.getBossBars().iterator().next();
        String plain = PlainTextComponentSerializer.plainText().serialize(bar.name());
        assertTrue(plain.contains("kill_spiders"), "repli sur l'id technique seulement si aucune description n'existe");
    }

    @Test
    void disabledDisplayNeverShowsAnyBossBar() {
        TrackedQuestDisplay display = new TrackedQuestDisplay(false);
        display.update(player, quest("Premiers pas"), new QuestStepProgressView("kill_spiders",
                List.of(new ObjectiveProgressView("Tuer SPIDER", 1, 5))));
        assertTrue(player.getBossBars().isEmpty(), "journal.tracker-enabled=false doit rester purement cosmétique");
    }
}
