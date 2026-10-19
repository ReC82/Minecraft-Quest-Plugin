package com.lodygames.rpgquest.quest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.quest.model.DeliverItemToNpcObjective;
import com.lodygames.rpgquest.quest.model.KillEntityObjective;
import com.lodygames.rpgquest.quest.model.HeartReward;
import com.lodygames.rpgquest.quest.model.MoneyReward;
import com.lodygames.rpgquest.quest.model.QuestDefinition;
import com.lodygames.rpgquest.quest.model.DiscoverWaypointObjective;
import com.lodygames.rpgquest.quest.model.QuestObjective;
import com.lodygames.rpgquest.quest.model.SmeltItemObjective;
import com.lodygames.rpgquest.quest.model.RewardType;
import java.io.StringReader;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;

class QuestDefinitionParserTest {

    private final QuestDefinitionParser parser = new QuestDefinitionParser();

    @Test
    void validFileParsesSuccessfully() {
        QuestDefinitionParser.ParseResult result = parser.parse("valid.yml", load("""
                id: rpgquest:first_steps
                title: "<gold>Premiers pas</gold>"
                description: "<gray>Élimine des araignées.</gray>"
                category: tutorial
                repeatable: false

                steps:
                  - id: kill_spiders
                    objectives:
                      - type: KILL_ENTITY
                        entity: SPIDER
                        amount: 10

                rewards:
                  - type: EXPERIENCE
                    amount: 50

                variables:
                  started: "true"
                """));

        assertTrue(result.isSuccess(), () -> "issues: " + result.issues());
        QuestDefinition quest = result.quest();
        assertEquals("rpgquest:first_steps", quest.id().toString());
        assertEquals("<gold>Premiers pas</gold>", quest.title().base());
        assertEquals("tutorial", quest.category());
        assertEquals(Material.BOOK, quest.icon(), "icône par défaut quand « icon » est absent");
        assertFalse(quest.repeatable());
        assertEquals(1, quest.steps().size());
        assertEquals("kill_spiders", quest.steps().get(0).id());
        assertEquals(new KillEntityObjective(EntityType.SPIDER, 10), quest.steps().get(0).objectives().get(0));
        assertEquals("true", quest.variables().get("started"));
    }

    // ---- Issue #15 : quêtes quotidiennes / hebdomadaires « period: » --------------------------

    private QuestDefinitionParser.ParseResult parsePeriodic(String extra) {
        return parser.parse("periodic.yml", load("""
                id: rpgquest:daily_x
                title: "Quête"
                description: "Desc"
                category: daily
                %s
                steps:
                  - id: s
                    objectives:
                      - type: KILL_ENTITY
                        entity: SPIDER
                        amount: 1
                """.formatted(extra)));
    }

    @Test
    void periodMakesTheQuestPeriodicAndRepeatable() {
        QuestDefinitionParser.ParseResult daily = parsePeriodic("period: daily");
        assertTrue(daily.isSuccess(), () -> "issues: " + daily.issues());
        assertEquals(com.lodygames.rpgquest.quest.model.QuestPeriod.DAILY, daily.quest().period());
        assertTrue(daily.quest().repeatable(), "une quête périodique se renouvelle");
        assertTrue(daily.quest().isPeriodic());
        assertEquals(com.lodygames.rpgquest.quest.model.QuestPeriod.WEEKLY,
                parsePeriodic("period: WEEKLY").quest().period());
        assertFalse(parsePeriodic("repeatable: true").quest().isPeriodic(), "sans period : quête ordinaire");
    }

    @Test
    void periodRejectsContradictoryOrUnsupportedFields() {
        assertFalse(parsePeriodic("period: monthly").isSuccess());
        assertFalse(parsePeriodic("period: daily\nrepeatable: false").isSuccess());
        assertFalse(parsePeriodic("period: daily\ncooldown: 20h").isSuccess());
        assertFalse(parsePeriodic("period: weekly\nprerequisites:\n  - rpgquest:first_steps").isSuccess());
    }

    // ---- Issue #13 : délai de ré-acceptation « cooldown: » ------------------------------------

    private QuestDefinitionParser.ParseResult parseCooldown(boolean repeatable, String cooldown) {
        return parser.parse("cooldown.yml", load("""
                id: rpgquest:side
                title: "Quête"
                description: "Desc"
                category: encounter
                repeatable: %s
                cooldown: %s
                steps:
                  - id: s
                    objectives:
                      - type: KILL_ENTITY
                        entity: SPIDER
                        amount: 1
                """.formatted(repeatable, cooldown)));
    }

    @Test
    void cooldownAcceptsSecondsAndUnits() {
        assertEquals(java.time.Duration.ofHours(20), parseCooldown(true, "20h").quest().cooldown());
        assertEquals(java.time.Duration.ofMinutes(30), parseCooldown(true, "30m").quest().cooldown());
        assertEquals(java.time.Duration.ofDays(1), parseCooldown(true, "\"1d\"").quest().cooldown());
        assertEquals(java.time.Duration.ofSeconds(90), parseCooldown(true, "90").quest().cooldown());
    }

    @Test
    void cooldownIsZeroWhenAbsent() {
        QuestDefinitionParser.ParseResult result = parser.parse("no-cooldown.yml", load(minimalQuestWithSteps("""
                steps:
                  - id: step_one
                    objectives:
                      - type: KILL_ENTITY
                        entity: SPIDER
                        amount: 1
                """)));
        assertTrue(result.isSuccess(), () -> "issues: " + result.issues());
        assertEquals(java.time.Duration.ZERO, result.quest().cooldown());
    }

    @Test
    void invalidCooldownsAreRejectedWithAClearMessage() {
        for (String bad : List.of("abc", "-5", "20x", "h", "31d")) {
            QuestDefinitionParser.ParseResult result = parseCooldown(true, bad);
            assertFalse(result.isSuccess(), bad);
            assertTrue(result.issues().stream().anyMatch(i -> i.message().contains("cooldown")), bad);
        }
    }

    @Test
    void cooldownOnANonRepeatableQuestIsAnError() {
        QuestDefinitionParser.ParseResult result = parseCooldown(false, "20h");
        assertFalse(result.isSuccess());
        assertTrue(result.issues().stream().anyMatch(i -> i.message().contains("repeatable: true")));
    }

    @Test
    void missingRequiredFieldsAreAllReportedTogether() {
        QuestDefinitionParser.ParseResult result = parser.parse("incomplete.yml", load("""
                steps:
                  - id: only_step
                    objectives:
                      - type: BREAK_BLOCK
                        material: STONE
                        amount: 1
                """));

        assertFalse(result.isSuccess());
        String combined = String.join(" | ", result.issues().stream().map(QuestLoadIssue::message).toList());
        assertTrue(combined.contains("id"), combined);
        assertTrue(combined.contains("title"), combined);
        assertTrue(combined.contains("description"), combined);
        assertTrue(combined.contains("category"), combined);
    }

    @Test
    void unknownObjectiveTypeIsRejected() {
        QuestDefinitionParser.ParseResult result = parser.parse("bad-objective.yml", load(minimalQuestWithSteps("""
                steps:
                  - id: step_one
                    objectives:
                      - type: FLY_TO_THE_MOON
                """)));

        assertFalse(result.isSuccess());
        assertTrue(result.issues().stream().anyMatch(i -> i.message().contains("type d'objectif inconnu")));
    }

    @Test
    void stepWithoutObjectiveIsRejected() {
        QuestDefinitionParser.ParseResult result = parser.parse("empty-step.yml", load(minimalQuestWithSteps("""
                steps:
                  - id: step_one
                """)));

        assertFalse(result.isSuccess());
        assertTrue(result.issues().stream().anyMatch(i -> i.message().contains("objectives")));
    }

    @Test
    void negativeAmountRewardIsRejected() {
        QuestDefinitionParser.ParseResult result = parser.parse("bad-reward.yml", load(minimalQuestWithStepsAnd("""
                rewards:
                  - type: EXPERIENCE
                    amount: -5
                """)));

        assertFalse(result.isSuccess());
        assertTrue(result.issues().stream().anyMatch(i -> i.message().contains("amount")));
    }

    @Test
    void aVariableRewardOnAKnownBooleanOnlyAcceptsTrueOrFalse() {
        QuestDefinitionParser.ParseResult bad = parser.parse("lasagne.yml", load(minimalQuestWithStepsAnd("""
                rewards:
                  - type: VARIABLE
                    key: CLAIM_TIER_1
                    value: lasagne
                """)));
        assertFalse(bad.isSuccess(), "issue #320");
        assertTrue(bad.issues().stream().anyMatch(i -> i.message().contains("true ou false")), () -> "issues: " + bad.issues());

        QuestDefinitionParser.ParseResult ok = parser.parse("tier.yml", load(minimalQuestWithStepsAnd("""
                rewards:
                  - type: VARIABLE
                    key: CLAIM_TIER_1
                    value: "true"
                """)));
        assertTrue(ok.isSuccess(), () -> "issues: " + ok.issues());

        QuestDefinitionParser.ParseResult free = parser.parse("free.yml", load(minimalQuestWithStepsAnd("""
                rewards:
                  - type: VARIABLE
                    key: woodcutter_reputation
                    value: lasagne
                """)));
        assertTrue(free.isSuccess(), () -> "une variable narrative libre reste libre : " + free.issues());
    }

    @Test
    void unknownRewardTypeIsRejected() {
        QuestDefinitionParser.ParseResult result = parser.parse("bad-reward-type.yml", load(minimalQuestWithStepsAnd("""
                rewards:
                  - type: FREE_HOUSE
                """)));

        assertFalse(result.isSuccess());
        assertTrue(result.issues().stream().anyMatch(i -> i.message().contains("type de récompense inconnu")));
    }

    @Test
    void selfReferencingPrerequisiteIsRejected() {
        QuestDefinitionParser.ParseResult result = parser.parse("self-ref.yml", load(minimalQuestWithStepsAnd("""
                prerequisites:
                  - rpgquest:loop
                """).replace("id: rpgquest:base", "id: rpgquest:loop")));

        assertFalse(result.isSuccess());
        assertTrue(result.issues().stream().anyMatch(i -> i.message().contains("elle-même")));
    }

    @Test
    void unknownMaterialIsRejected() {
        QuestDefinitionParser.ParseResult result = parser.parse("bad-material.yml", load(minimalQuestWithSteps("""
                steps:
                  - id: step_one
                    objectives:
                      - type: BREAK_BLOCK
                        material: NOT_A_REAL_BLOCK
                        amount: 1
                """)));

        assertFalse(result.isSuccess());
        assertTrue(result.issues().stream().anyMatch(i -> i.message().contains("matériau inconnu")));
    }

    @Test
    void explicitIconIsParsed() {
        QuestDefinitionParser.ParseResult result = parser.parse("with-icon.yml", load("""
                id: rpgquest:base
                title: "Titre"
                description: "Description"
                category: test
                icon: IRON_SWORD
                steps:
                  - id: step_one
                    objectives:
                      - type: BREAK_BLOCK
                        material: STONE
                        amount: 1
                """));

        assertTrue(result.isSuccess(), () -> "issues: " + result.issues());
        assertEquals(Material.IRON_SWORD, result.quest().icon());
    }

    @Test
    void unknownIconIsRejected() {
        QuestDefinitionParser.ParseResult result = parser.parse("bad-icon.yml", load(minimalQuestWithSteps("""
                icon: NOT_A_REAL_MATERIAL
                steps:
                  - id: step_one
                    objectives:
                      - type: BREAK_BLOCK
                        material: STONE
                        amount: 1
                """)));

        assertFalse(result.isSuccess());
        assertTrue(result.issues().stream().anyMatch(i -> i.message().contains("icon")));
    }

    private String minimalQuestWithSteps(String stepsYaml) {
        return """
                id: rpgquest:base
                title: "Titre"
                description: "Description"
                category: test
                """ + stepsYaml;
    }

    private String minimalQuestWithStepsAnd(String extraYaml) {
        return minimalQuestWithSteps("""
                steps:
                  - id: step_one
                    objectives:
                      - type: BREAK_BLOCK
                        material: STONE
                        amount: 1
                """) + extraYaml;
    }

    // ---- Récompense monétaire (issue #16) -----------------------------------------------------

    @Test
    void aMoneyRewardIsParsedWithItsAmount() {
        QuestDefinitionParser.ParseResult result = parser.parse("money.yml", load(minimalQuestWithStepsAnd("""
                rewards:
                  - type: MONEY
                    amount: 250
                """)));

        assertTrue(result.isSuccess(), () -> "issues: " + result.issues());
        assertEquals(1, result.quest().rewards().size());
        MoneyReward reward = (MoneyReward) result.quest().rewards().get(0);
        assertEquals(250, reward.amount());
        assertEquals(RewardType.MONEY, reward.type());
    }

    @Test
    void aMoneyRewardRefusesANonPositiveOrMissingAmount() {
        for (String amount : new String[] {"0", "-50"}) {
            QuestDefinitionParser.ParseResult result = parser.parse("money.yml", load(minimalQuestWithStepsAnd("""
                    rewards:
                      - type: MONEY
                        amount: %s
                    """.formatted(amount))));
            assertFalse(result.isSuccess(), "montant « " + amount + " » doit être refusé");
            assertTrue(result.issues().stream().anyMatch(i -> i.message().contains("amount")));
        }

        QuestDefinitionParser.ParseResult missing = parser.parse("money.yml", load(minimalQuestWithStepsAnd("""
                rewards:
                  - type: MONEY
                """)));
        assertFalse(missing.isSuccess(), "sans montant, il n'y a rien à créditer");
    }

    @Test
    void aLargeMoneyRewardIsAcceptedBecauseBalancingIsNotATechnicalRule() {
        // Aucun plafond côté moteur : décider du gain maximum serait prendre une décision de
        // gameplay à la place de l'auteur. Le garde-fou contre la faute de frappe est un
        // AVERTISSEMENT côté panel, et il n'empêche rien.
        QuestDefinitionParser.ParseResult result = parser.parse("money.yml", load(minimalQuestWithStepsAnd("""
                rewards:
                  - type: MONEY
                    amount: 5000000
                """)));

        assertTrue(result.isSuccess(), () -> "issues: " + result.issues());
        assertEquals(5_000_000, ((MoneyReward) result.quest().rewards().get(0)).amount());
    }

    @Test
    void aMoneyRewardCoexistsWithTheOtherRewardTypes() {
        QuestDefinitionParser.ParseResult result = parser.parse("money.yml", load(minimalQuestWithStepsAnd("""
                rewards:
                  - type: EXPERIENCE
                    amount: 10
                  - type: MONEY
                    amount: 25
                  - type: ITEM
                    material: IRON_INGOT
                    amount: 2
                """)));

        assertTrue(result.isSuccess(), () -> "issues: " + result.issues());
        // L'ORDRE est préservé : il décide de l'ordre d'affichage du résumé de fin de quête.
        assertEquals(List.of(RewardType.EXPERIENCE, RewardType.MONEY, RewardType.ITEM),
                result.quest().rewards().stream().map(r -> r.type()).toList());
    }

    // ---- Tirage d'une table de récompenses (issue #177) ----------------------------------------

    @Test
    void aTableRewardNamesItsTableAndCombinesWithGuaranteedRewards() {
        QuestDefinitionParser.ParseResult result = parser.parse("table.yml", load(minimalQuestWithStepsAnd("""
                rewards:
                  - type: EXPERIENCE
                    amount: 10
                  - type: TABLE
                    table: dragon_loot
                """)));
        assertTrue(result.isSuccess(), () -> "issues: " + result.issues());
        assertEquals(new com.lodygames.rpgquest.quest.model.TableReward("dragon_loot"), result.quest().rewards().get(1));

        QuestDefinitionParser.ParseResult missing = parser.parse("table.yml", load(minimalQuestWithStepsAnd("""
                rewards:
                  - type: TABLE
                """)));
        assertTrue(missing.issues().stream().anyMatch(i -> i.toString().contains("« table » obligatoire")),
                () -> "issues: " + missing.issues());
        QuestDefinitionParser.ParseResult bad = parser.parse("table.yml", load(minimalQuestWithStepsAnd("""
                rewards:
                  - type: TABLE
                    table: "Pas Valide"
                """)));
        assertTrue(!bad.isSuccess());
    }

    // ---- Cœur supplémentaire (issue #166) -------------------------------------------------------

    @Test
    void aHeartRewardDefaultsToOneHeartOwnedByTheQuest() {
        QuestDefinitionParser.ParseResult result = parser.parse("heart.yml", load(minimalQuestWithStepsAnd("""
                rewards:
                  - type: HEART
                """)));

        assertTrue(result.isSuccess(), () -> "issues: " + result.issues());
        HeartReward reward = (HeartReward) result.quest().rewards().get(0);
        assertEquals(1, reward.hearts());
        assertEquals(RewardType.HEART, reward.type());
        assertEquals("quest:" + result.quest().id(), reward.effectiveGrantKey(result.quest().id()));
    }

    @Test
    void aHeartRewardReadsItsAmountAndSharedGrantKey() {
        QuestDefinitionParser.ParseResult result = parser.parse("heart.yml", load(minimalQuestWithStepsAnd("""
                rewards:
                  - type: HEART
                    amount: 2
                    key: city:central_city
                """)));

        assertTrue(result.isSuccess(), () -> "issues: " + result.issues());
        HeartReward reward = (HeartReward) result.quest().rewards().get(0);
        assertEquals(2, reward.hearts());
        assertEquals("city:central_city", reward.effectiveGrantKey(result.quest().id()));
    }

    @Test
    void invalidHeartQuantitiesAndKeysAreRefused() {
        for (String option : new String[] {"amount: 0", "amount: -1", "amount: deux", "amount: 513",
                "key: \"Central City\"", "key: \"ville/1\""}) {
            QuestDefinitionParser.ParseResult result = parser.parse("heart.yml", load(minimalQuestWithStepsAnd("""
                    rewards:
                      - type: HEART
                        %s
                    """.formatted(option))));
            assertFalse(result.isSuccess(), "« " + option + " » doit être refusé");
        }
    }

    // ---- Remise d'objets à un PNJ (issue #123) -------------------------------------------------

    @Test
    void aDeliverObjectiveIsParsedWithItsNpcMaterialAndAmount() {
        QuestDefinitionParser.ParseResult result = parser.parse("deliver.yml", load("""
                id: rpgquest:deliver
                title: "Titre"
                description: "Description"
                category: test

                steps:
                  - id: deliver_step
                    objectives:
                      - type: DELIVER_ITEM_TO_NPC
                        npc: blacksmith
                        material: LEATHER
                        amount: 4
                """));

        assertTrue(result.isSuccess(), () -> "issues: " + result.issues());
        assertEquals(new DeliverItemToNpcObjective("blacksmith", Material.LEATHER, 4),
                result.quest().steps().get(0).objectives().get(0));
        assertEquals(4, QuestObjective.requiredAmount(result.quest().steps().get(0).objectives().get(0)));
    }

    @Test
    void severalDeliverObjectivesCoexistInTheSameStep() {
        QuestDefinitionParser.ParseResult result = parser.parse("deliver_multi.yml", load("""
                id: rpgquest:deliver_multi
                title: "Titre"
                description: "Description"
                category: test

                steps:
                  - id: deliver_step
                    objectives:
                      - type: DELIVER_ITEM_TO_NPC
                        npc: guard
                        material: STICK
                        amount: 1
                      - type: DELIVER_ITEM_TO_NPC
                        npc: guard
                        material: COBBLESTONE
                        amount: 2
                      - type: DELIVER_ITEM_TO_NPC
                        npc: guard
                        material: LEATHER
                        amount: 4
                      - type: DELIVER_ITEM_TO_NPC
                        npc: guard
                        material: WHEAT_SEEDS
                        amount: 3
                """));

        assertTrue(result.isSuccess(), () -> "issues: " + result.issues());
        // L'ORDRE est préservé : il décide de l'ordre du récapitulatif lu par le joueur.
        assertEquals(List.of(Material.STICK, Material.COBBLESTONE, Material.LEATHER, Material.WHEAT_SEEDS),
                result.quest().steps().get(0).objectives().stream()
                        .map(o -> ((DeliverItemToNpcObjective) o).material())
                        .toList());
    }

    @Test
    void aDeliverObjectiveWithoutNpcMaterialOrAmountIsRejectedWithEveryReason() {
        QuestDefinitionParser.ParseResult result = parser.parse("deliver_broken.yml", load("""
                id: rpgquest:deliver_broken
                title: "Titre"
                description: "Description"
                category: test

                steps:
                  - id: deliver_step
                    objectives:
                      - type: DELIVER_ITEM_TO_NPC
                """));

        assertFalse(result.isSuccess());
        String combined = String.join(" | ", result.issues().stream().map(QuestLoadIssue::message).toList());
        assertTrue(combined.contains("npc"), combined);
        assertTrue(combined.contains("material"), combined);
        assertTrue(combined.contains("amount"), combined);
    }

    @Test
    void aDeliverObjectiveWithAnUnknownMaterialIsRejected() {
        QuestDefinitionParser.ParseResult result = parser.parse("deliver_bad_material.yml", load("""
                id: rpgquest:deliver_bad
                title: "Titre"
                description: "Description"
                category: test

                steps:
                  - id: deliver_step
                    objectives:
                      - type: DELIVER_ITEM_TO_NPC
                        npc: guard
                        material: PAS_UN_OBJET
                        amount: 1
                """));

        assertFalse(result.isSuccess());
        assertTrue(String.join(" ", result.issues().stream().map(QuestLoadIssue::message).toList())
                .contains("PAS_UN_OBJET"));
    }

    @Test
    void aDeliverObjectiveWithANonPositiveAmountIsRejected() {
        QuestDefinitionParser.ParseResult result = parser.parse("deliver_zero.yml", load("""
                id: rpgquest:deliver_zero
                title: "Titre"
                description: "Description"
                category: test

                steps:
                  - id: deliver_step
                    objectives:
                      - type: DELIVER_ITEM_TO_NPC
                        npc: guard
                        material: LEATHER
                        amount: 0
                """));

        assertFalse(result.isSuccess());
    }

    // ---- Cuisson (issue #141) ------------------------------------------------------------------

    @Test
    void aSmeltObjectiveIsParsedWithTheObtainedItem() {
        QuestDefinitionParser.ParseResult result = parser.parse("smelt.yml", load("""
                id: rpgquest:smelt
                title: "Titre"
                description: "Description"
                category: test

                steps:
                  - id: smelt_step
                    objectives:
                      - type: SMELT_ITEM
                        material: GREEN_DYE
                        amount: 2
                """));

        assertTrue(result.isSuccess(), () -> "issues: " + result.issues());
        assertEquals(new SmeltItemObjective(Material.GREEN_DYE, 2),
                result.quest().steps().get(0).objectives().get(0));
    }

    @Test
    void aSmeltObjectiveWithoutMaterialOrAmountIsRejected() {
        QuestDefinitionParser.ParseResult result = parser.parse("smelt_broken.yml", load("""
                id: rpgquest:smelt_broken
                title: "Titre"
                description: "Description"
                category: test

                steps:
                  - id: smelt_step
                    objectives:
                      - type: SMELT_ITEM
                """));

        assertFalse(result.isSuccess());
        String combined = String.join(" | ", result.issues().stream().map(QuestLoadIssue::message).toList());
        assertTrue(combined.contains("material"), combined);
        assertTrue(combined.contains("amount"), combined);
    }

    // ---- Découverte de waypoints (issue #185) --------------------------------------------------

    @Test
    void aDiscoverWaypointObjectiveDefaultsToNewDiscoveriesAndAllWorlds() {
        QuestDefinitionParser.ParseResult result = parser.parse("discover.yml", load("""
                id: rpgquest:discover
                title: "Titre"
                description: "Description"
                category: test

                steps:
                  - id: decouvrir
                    objectives:
                      - type: DISCOVER_WAYPOINT
                        amount: 5
                """));

        assertTrue(result.isSuccess(), () -> "issues: " + result.issues());
        assertEquals(new DiscoverWaypointObjective(5, List.of(), DiscoverWaypointObjective.CountMode.NEW_ONLY),
                result.quest().steps().get(0).objectives().get(0));
    }

    @Test
    void aDiscoverWaypointObjectiveKeepsItsWorldFilterAndCountMode() {
        QuestDefinitionParser.ParseResult result = parser.parse("discover_filtered.yml", load("""
                id: rpgquest:discover_filtered
                title: "Titre"
                description: "Description"
                category: test

                steps:
                  - id: decouvrir
                    objectives:
                      - type: DISCOVER_WAYPOINT
                        amount: 2
                        worlds: [world_hub, wild]
                        count-mode: include_existing
                """));

        assertTrue(result.isSuccess(), () -> "issues: " + result.issues());
        assertEquals(new DiscoverWaypointObjective(2, List.of("world_hub", "wild"),
                        DiscoverWaypointObjective.CountMode.INCLUDE_EXISTING),
                result.quest().steps().get(0).objectives().get(0));
    }

    /**
     * Un mode inconnu est REFUSÉ, jamais rabattu sur le défaut : la règle de comptage change ce que
     * le joueur doit faire, une faute de frappe ne doit pas la retourner en silence.
     */
    @Test
    void anUnknownCountModeIsRejectedRatherThanDefaulted() {
        QuestDefinitionParser.ParseResult result = parser.parse("discover_broken.yml", load("""
                id: rpgquest:discover_broken
                title: "Titre"
                description: "Description"
                category: test

                steps:
                  - id: decouvrir
                    objectives:
                      - type: DISCOVER_WAYPOINT
                        amount: 2
                        count-mode: CUMULATIF
                """));

        assertFalse(result.isSuccess());
        String combined = String.join(" | ", result.issues().stream().map(QuestLoadIssue::message).toList());
        assertTrue(combined.contains("count-mode"), combined);
    }

    private ConfigurationSection load(String yaml) {
        return YamlConfiguration.loadConfiguration(new StringReader(yaml));
    }

    // ---- Issue #171 : boss de quête personnel ---------------------------------------------------

    private QuestDefinitionParser.ParseResult parseQuestBoss(String objective) {
        return parser.parse("boss.yml", load(minimalQuestWithSteps("""
                steps:
                  - id: chasse
                    objectives:
                      - type: KILL_QUEST_BOSS
                """ + objective)));
    }

    @Test
    void questBossAcceptsShortAndFullProfileIds() {
        QuestDefinitionParser.ParseResult shortForm = parseQuestBoss("        mob: Ogre\n");
        assertTrue(shortForm.isSuccess(), () -> "issues: " + shortForm.issues());
        assertEquals(new com.lodygames.rpgquest.quest.model.KillQuestBossObjective(
                new org.bukkit.NamespacedKey("rpgquest", "ogre")), shortForm.quest().steps().get(0).objectives().get(0));
        assertTrue(parseQuestBoss("        mob: rpgquest:ogre\n        amount: 1\n").isSuccess());
    }

    @Test
    void questBossWithoutProfileOrWithAmountAboveOneIsRejected() {
        assertFalse(parseQuestBoss("        amount: 1\n").isSuccess());
        QuestDefinitionParser.ParseResult many = parseQuestBoss("        mob: ogre\n        amount: 3\n");
        assertFalse(many.isSuccess());
        assertTrue(many.issues().toString().contains("amount"), () -> many.issues().toString());
    }
}
