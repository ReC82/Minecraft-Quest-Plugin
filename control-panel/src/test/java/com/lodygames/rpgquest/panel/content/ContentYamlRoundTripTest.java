package com.lodygames.rpgquest.panel.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Garde-fou round-trip #46 (B12) : le YAML émis par {@link QuestYaml} / {@link StoryYaml} doit se
 * relire à l'identique avec le mini-lecteur, faute de quoi l'éditeur refuse d'écrire dans la source.
 */
class ContentYamlRoundTripTest {

    private static Map<String, String> obj(String kind, String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("kind", kind);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void minimalQuestRoundTrips() {
        QuestDraft q = QuestDraft.blank();
        q.id = "test_quest";
        q.title = "Une quête d'essai";
        q.description = "Description d'essai.";
        q.steps.get(0).objectives.get(0).put("entity", "SPIDER");
        q.steps.get(0).objectives.get(0).put("amount", "5");

        String yaml = QuestYaml.write(q);
        assertTrue(QuestYaml.roundTripProblems(yaml).isEmpty(), () -> "problèmes: " + QuestYaml.roundTripProblems(yaml)
                + "\n---\n" + yaml);

        QuestYaml.ReadResult r = QuestYaml.read(yaml);
        assertEquals("test_quest", r.draft().id);
        assertEquals("Une quête d'essai", r.draft().title);
        assertEquals("SPIDER", r.draft().steps.get(0).objectives.get(0).get("entity"));
        assertEquals("5", r.draft().steps.get(0).objectives.get(0).get("amount"));
    }

    @Test
    void richQuestRoundTrips() {
        QuestDraft q = new QuestDraft();
        q.id = "rpgquest:crystal_hunt_v2";
        q.title = "<gold>La chasse aux cristaux</gold>";
        q.description = "<gray>Ramener des crocs.</gray>";
        q.category = "crafting";
        q.giver = "guard";
        q.icon = "AMETHYST_SHARD";
        q.repeatable = false;
        q.prerequisites.add("first_steps");

        QuestDraft.Step s1 = new QuestDraft.Step("hunt_spiders");
        s1.objectives.add(obj("KILL_ENTITY", "entity", "SPIDER", "amount", "5"));
        QuestDraft.Step s2 = new QuestDraft.Step("gather");
        s2.objectives.add(obj("COLLECT_ITEM", "material", "AMETHYST_SHARD", "amount", "2"));
        s2.objectives.add(obj("TALK_TO_NPC", "npc", "guard"));
        q.steps.add(s1);
        q.steps.add(s2);

        q.rewards.add(obj("EXPERIENCE", "amount", "100"));
        q.rewards.add(obj("COMMAND", "command", "customitem give %player% rpgquest:miner_pickaxe 1"));
        q.rewards.add(obj("VARIABLE", "key", "CLAIM_TIER_1", "value", "true"));
        q.variables.put("crystal_hunt_started", "true");

        String yaml = QuestYaml.write(q);
        assertTrue(QuestYaml.roundTripProblems(yaml).isEmpty(),
                () -> "problèmes: " + QuestYaml.roundTripProblems(yaml) + "\n---\n" + yaml);

        QuestYaml.ReadResult r = QuestYaml.read(yaml);
        assertEquals(2, r.draft().steps.size());
        assertEquals(2, r.draft().steps.get(1).objectives.size());
        assertEquals(3, r.draft().rewards.size());
        assertEquals("true", r.draft().variables.get("crystal_hunt_started"));
        assertEquals("first_steps", r.draft().prerequisites.get(0));
    }

    @Test
    void storyRoundTrips() {
        StoryDraft s = new StoryDraft();
        s.id = "main_story_v2";
        s.name = "Histoire principale";
        s.questIds.add("premiers_pas");
        s.questIds.add("rpgquest:first_steps");
        s.questIds.add("crystal_hunt");

        String yaml = StoryYaml.write(s);
        assertTrue(StoryYaml.roundTripProblems(yaml).isEmpty(),
                () -> "problèmes: " + StoryYaml.roundTripProblems(yaml) + "\n---\n" + yaml);

        StoryYaml.ReadResult r = StoryYaml.read(yaml);
        assertEquals("main_story_v2", r.draft().id);
        assertEquals("Histoire principale", r.draft().name);
        assertEquals(3, r.draft().questIds.size());
        assertEquals("first_steps", r.draft().questIds.get(1));
    }

    @Test
    void validatorFlagsMissingTitleAndUnknownRefs() {
        QuestDraft q = QuestDraft.blank();
        q.id = "x";
        // titre / description manquants
        RefData ref = new RefData(java.util.List.of("first_steps"), java.util.List.of("guard"),
                java.util.List.of("world"), true, true, true);
        q.prerequisites.add("does_not_exist");
        var diags = QuestValidator.validate(q, ref);
        assertTrue(Diagnostic.hasError(diags));
        assertTrue(diags.stream().anyMatch(d -> d.field().equals("title") && d.level() == Diagnostic.Level.ERROR));
        assertTrue(diags.stream().anyMatch(d -> d.message().contains("does_not_exist")));
    }

    // ---- Récompense monétaire (issue #16) -----------------------------------------------------

    @Test
    void aMoneyRewardSurvivesTheWriteReadRoundTrip() {
        QuestDraft q = QuestDraft.blank();
        q.id = "tc250_money";
        q.title = "Quête payée";
        q.description = "Description.";
        q.steps.get(0).objectives.get(0).put("entity", "ZOMBIE");
        q.steps.get(0).objectives.get(0).put("amount", "1");
        q.rewards.add(obj("MONEY", "amount", "250"));

        String yaml = QuestYaml.write(q);
        // Le YAML écrit doit être celui que le MOTEUR lit : « type: MONEY » + « amount ».
        assertTrue(yaml.contains("type: MONEY"), () -> yaml);
        assertTrue(yaml.contains("amount: 250"), () -> yaml);
        assertTrue(QuestYaml.roundTripProblems(yaml).isEmpty(),
                () -> "problèmes: " + QuestYaml.roundTripProblems(yaml) + "\n---\n" + yaml);

        QuestYaml.ReadResult r = QuestYaml.read(yaml);
        assertEquals(1, r.draft().rewards.size());
        assertEquals("MONEY", r.draft().rewards.get(0).get("kind"));
        assertEquals("250", r.draft().rewards.get(0).get("amount"));
    }

    @Test
    void addingAMoneyRewardPreservesTheRewardsAlreadyThere() {
        // La crainte réelle en éditant : perdre les récompenses existantes. L'ordre compte aussi,
        // puisqu'il décide de l'ordre d'affichage côté joueur.
        QuestDraft q = QuestDraft.blank();
        q.id = "tc250_mixed";
        q.title = "Quête mixte";
        q.description = "Description.";
        q.steps.get(0).objectives.get(0).put("entity", "ZOMBIE");
        q.steps.get(0).objectives.get(0).put("amount", "1");
        q.rewards.add(obj("EXPERIENCE", "amount", "100"));
        q.rewards.add(obj("MONEY", "amount", "25"));
        q.rewards.add(obj("ITEM", "material", "IRON_INGOT", "amount", "2"));

        QuestYaml.ReadResult r = QuestYaml.read(QuestYaml.write(q));

        assertEquals(java.util.List.of("EXPERIENCE", "MONEY", "ITEM"),
                r.draft().rewards.stream().map(m -> m.get("kind")).toList());
        assertEquals("2", r.draft().rewards.get(2).get("amount"));
        assertEquals("IRON_INGOT", r.draft().rewards.get(2).get("material"));
    }

    @Test
    void aMoneyRewardIsValidatedAsAStrictlyPositiveIntegerByTheBackend() {
        RefData ref = new RefData(java.util.List.of(), java.util.List.of(), java.util.List.of("world"),
                true, true, true);
        for (String amount : new String[] {"0", "-5", "abc", "1.5"}) {
            QuestDraft q = validQuestWithMoney(amount);
            assertTrue(QuestValidator.validate(q, ref).stream()
                            .anyMatch(d -> d.level() == Diagnostic.Level.ERROR),
                    "montant « " + amount + " » doit produire une erreur de validation");
        }

        QuestDraft ok = validQuestWithMoney("250");
        assertTrue(QuestValidator.validate(ok, ref).stream()
                        .noneMatch(d -> d.level() == Diagnostic.Level.ERROR),
                () -> "un montant valide ne doit produire aucune erreur : " + QuestValidator.validate(ok, ref));
    }

    @Test
    void anUnusuallyLargeMoneyRewardIsWarnedAboutButNeverRefused() {
        RefData ref = new RefData(java.util.List.of(), java.util.List.of(), java.util.List.of("world"),
                true, true, true);
        QuestDraft q = validQuestWithMoney(Long.toString(QuestValidator.MONEY_REWARD_WARNING_THRESHOLD + 1));

        var diags = QuestValidator.validate(q, ref);

        // AVERTISSEMENT et non erreur : l'équilibrage appartient à l'auteur du contenu, pas au
        // panel. Refuser reviendrait à décider du gain maximum à sa place.
        assertTrue(diags.stream().anyMatch(d -> d.level() == Diagnostic.Level.WARNING
                && d.message().contains("inhabituellement élevé")), () -> diags.toString());
        assertTrue(diags.stream().noneMatch(d -> d.level() == Diagnostic.Level.ERROR), () -> diags.toString());

        // Juste au seuil : rien à signaler.
        QuestDraft atThreshold = validQuestWithMoney(Long.toString(QuestValidator.MONEY_REWARD_WARNING_THRESHOLD));
        assertTrue(QuestValidator.validate(atThreshold, ref).stream()
                .noneMatch(d -> d.level() == Diagnostic.Level.WARNING
                        && d.message().contains("inhabituellement élevé")));
    }

    private static QuestDraft validQuestWithMoney(String amount) {
        QuestDraft q = QuestDraft.blank();
        q.id = "tc250_money";
        q.title = "Quête payée";
        q.description = "Description.";
        q.steps.get(0).objectives.get(0).put("entity", "ZOMBIE");
        q.steps.get(0).objectives.get(0).put("amount", "1");
        q.rewards.add(obj("MONEY", "amount", amount));
        return q;
    }
}
