package com.lodygames.rpgquest.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.config.StarterKitTier;
import com.lodygames.rpgquest.config.StarterToolKitConfig;
import com.lodygames.rpgquest.quest.model.DeliverItemToNpcObjective;
import com.lodygames.rpgquest.quest.model.LocalizedText;
import com.lodygames.rpgquest.quest.model.QuestDefinition;
import com.lodygames.rpgquest.quest.model.QuestStep;
import com.lodygames.rpgquest.quest.progress.DeliveryLine;
import com.lodygames.rpgquest.quest.progress.ObjectiveProgressView;
import com.lodygames.rpgquest.quest.progress.QuestStepProgressView;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

/**
 * Issue #235 — les exigences du palier suivant sont <strong>dérivées</strong> de la quête de
 * déblocage, jamais recopiées.
 *
 * <p>C'est la propriété qui compte dans six mois : si quelqu'un change les matériaux de
 * {@code kit_tier2}, le discours du Guide doit suivre sans qu'on touche au code ni au dialogue. Les
 * dépendances étant injectées en fonctions, tout est exécuté ici pour de vrai.</p>
 */
class KitProgressServiceTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-00000000f235");
    private static final NamespacedKey KIT_TIER2 = new NamespacedKey("rpgquest", "kit_tier2");

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private static StarterToolKitConfig config() {
        return new StarterToolKitConfig(true, List.of(
                new StarterKitTier(1, "Nouveau venu", List.of(Material.WOODEN_SWORD), null),
                new StarterKitTier(2, "Premiers pas dans le Wild",
                        List.of(Material.STONE_SWORD), "rpgquest:kit_tier2")));
    }

    /** La quête du palier 2, avec les matériaux réels du contenu livré. */
    private static QuestDefinition kitTier2() {
        return new QuestDefinition(
                KIT_TIER2,
                new LocalizedText(Map.of("default", "Premiers pas dans le Wild")),
                new LocalizedText(Map.of("default", "Rapporte de quoi améliorer ton équipement.")),
                "kit", Material.STONE_SWORD, false, false, List.of(),
                List.of(new QuestStep("rapporter_les_materiaux", List.of(
                        new DeliverItemToNpcObjective("guide", Material.STICK, 1),
                        new DeliverItemToNpcObjective("guide", Material.COBBLESTONE, 2),
                        new DeliverItemToNpcObjective("guide", Material.LEATHER, 4),
                        new DeliverItemToNpcObjective("guide", Material.WHEAT_SEEDS, 3)))),
                List.of(), Map.of(), "guide");
    }

    private KitProgressService service(int unlockedTier,
                                        Optional<QuestStepProgressView> activeStep) {
        return new KitProgressService(
                KitProgressServiceTest::config,
                player -> unlockedTier,
                key -> KIT_TIER2.equals(key) ? Optional.of(kitTier2()) : Optional.empty(),
                (player, quest) -> activeStep);
    }

    // ---- Exigences dérivées ---------------------------------------------------------------------

    /** Les quatre matériaux de {@code kit_tier2}, avec leurs quantités, sans rien de codé en dur. */
    @Test
    void theRequirementsComeFromTheUnlockQuestObjectives() {
        List<DeliveryLine> lines = service(1, Optional.empty()).requirements(PLAYER);

        assertEquals(4, lines.size());
        assertEquals(Material.STICK, lines.get(0).material());
        assertEquals(1, lines.get(0).required());
        assertEquals(Material.COBBLESTONE, lines.get(1).material());
        assertEquals(2, lines.get(1).required());
        assertEquals(Material.LEATHER, lines.get(2).material());
        assertEquals(4, lines.get(2).required());
        assertEquals(Material.WHEAT_SEEDS, lines.get(3).material());
        assertEquals(3, lines.get(3).required());
    }

    /** Quête non commencée : la demande complète, et zéro remis — ce qui est exactement vrai. */
    @Test
    void anUnstartedQuestShowsNothingDelivered() {
        List<DeliveryLine> lines = service(1, Optional.empty()).requirements(PLAYER);

        assertTrue(lines.stream().allMatch(l -> l.delivered() == 0));
        assertTrue(lines.stream().noneMatch(DeliveryLine::complete));
    }

    /**
     * Quête active : la progression réelle est superposée, pour que le joueur voie ce qu'il a déjà
     * donné — c'est la réassurance que #123 a rendue possible et que #235 doit rendre visible.
     */
    @Test
    void anActiveQuestOverlaysTheRealProgress() {
        QuestStepProgressView view = new QuestStepProgressView("rapporter_les_materiaux", List.of(
                new ObjectiveProgressView("Remettre STICK à guide", 1, 1),
                new ObjectiveProgressView("Remettre COBBLESTONE à guide", 1, 2),
                new ObjectiveProgressView("Remettre LEATHER à guide", 0, 4),
                new ObjectiveProgressView("Remettre WHEAT_SEEDS à guide", 3, 3)));

        List<DeliveryLine> lines = service(1, Optional.of(view)).requirements(PLAYER);

        assertTrue(lines.get(0).complete(), "1/1 bâton");
        assertEquals(1, lines.get(1).delivered());
        assertFalse(lines.get(1).complete(), "1/2 pierres");
        assertEquals(0, lines.get(2).delivered());
        assertTrue(lines.get(3).complete(), "3/3 graines");
    }

    /**
     * Une progression qui porte sur une <strong>autre étape</strong> n'est pas superposée.
     *
     * <p>Les compteurs sont indexés par position dans l'étape : mélanger deux étapes afficherait des
     * chiffres faux, ce qui est pire que d'afficher la demande complète.</p>
     */
    @Test
    void aProgressFromAnotherStepIsIgnoredRatherThanMisread() {
        QuestStepProgressView otherStep = new QuestStepProgressView("une_autre_etape", List.of(
                new ObjectiveProgressView("peu importe", 99, 99)));

        List<DeliveryLine> lines = service(1, Optional.of(otherStep)).requirements(PLAYER);

        assertTrue(lines.stream().allMatch(l -> l.delivered() == 0));
    }

    /** Un compteur aberrant ne produit jamais « 9/1 ». */
    @Test
    void aCounterAboveTheRequirementIsCapped() {
        QuestStepProgressView view = new QuestStepProgressView("rapporter_les_materiaux", List.of(
                new ObjectiveProgressView("Remettre STICK à guide", 9, 1),
                new ObjectiveProgressView("Remettre COBBLESTONE à guide", 0, 2),
                new ObjectiveProgressView("Remettre LEATHER à guide", 0, 4),
                new ObjectiveProgressView("Remettre WHEAT_SEEDS à guide", 0, 3)));

        assertEquals(1, service(1, Optional.of(view)).requirements(PLAYER).get(0).delivered());
    }

    // ---- Absence de palier suivant --------------------------------------------------------------

    @Test
    void atTheHighestTierThereIsNoRequirementAndNoQuest() {
        KitProgressService service = service(2, Optional.empty());

        assertTrue(service.requirements(PLAYER).isEmpty());
        assertTrue(service.nextUnlockQuest(PLAYER).isEmpty());
        assertTrue(service.nextTierText(PLAYER).contains("aucune"));
    }

    /**
     * Un {@code unlock-quest:} qui ne correspond à aucune quête chargée n'annonce aucune quête.
     *
     * <p>Mieux vaut ne rien promettre que renvoyer le joueur vers une quête fantôme qu'aucun PNJ ne
     * peut démarrer.</p>
     */
    @Test
    void anUnknownUnlockQuestAnnouncesNothing() {
        KitProgressService service = new KitProgressService(
                KitProgressServiceTest::config,
                player -> 1,
                key -> Optional.empty(),
                (player, quest) -> Optional.empty());

        assertTrue(service.nextUnlockQuest(PLAYER).isEmpty());
        assertTrue(service.requirements(PLAYER).isEmpty());
        // Le palier suivant EXISTE et reste annoncé : seule la quête manque.
        assertTrue(service.nextTierText(PLAYER).contains("Palier 2"));
    }

    /** Un palier sans {@code unlock-quest:} ne prétend pas en avoir une. */
    @Test
    void aTierWithoutUnlockQuestAnnouncesNoQuest() {
        StarterToolKitConfig noQuest = new StarterToolKitConfig(true, List.of(
                new StarterKitTier(1, "Nouveau venu", List.of(Material.WOODEN_SWORD), null),
                new StarterKitTier(2, "Mystère", List.of(Material.STONE_SWORD), null)));
        KitProgressService service = new KitProgressService(() -> noQuest, player -> 1,
                key -> Optional.of(kitTier2()), (player, quest) -> Optional.empty());

        assertTrue(service.nextUnlockQuest(PLAYER).isEmpty());
    }

    // ---- Les textes rendus ----------------------------------------------------------------------

    @Test
    void theRenderedTextsAreTheOnesTheGuideDisplays() {
        KitProgressService service = service(1, Optional.empty());

        assertEquals("<aqua>Palier 1 — Nouveau venu</aqua>", service.currentTierText(PLAYER));
        assertEquals("<gold>Palier 2 — Premiers pas dans le Wild</gold>", service.nextTierText(PLAYER));
        assertTrue(service.requirementsText(PLAYER).contains("0/1"), service.requirementsText(PLAYER));
    }

    /** Le palier affiché suit la source de vérité de #218, sans seconde logique. */
    @Test
    void theDisplayedTierIsTheOneTheKitServiceReports() {
        assertTrue(service(2, Optional.empty()).currentTierText(PLAYER).contains("Palier 2"));
        assertTrue(service(1, Optional.empty()).currentTierText(PLAYER).contains("Palier 1"));
    }
}
